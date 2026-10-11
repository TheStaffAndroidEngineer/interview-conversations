package staff.android.conversations

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LatestSearchTest {
    @Test fun lateCanceledReplyCannotReplaceCurrentResult() {
        val source = ControlledSource(); val search = LatestSearch(source)
        search.submit("cam"); search.submit("tea")
        assertEquals(1, source.calls[0].cancelCalls)
        source.calls[1].reply(Reply.Success(listOf("tea")))
        source.calls[0].reply(Reply.Success(listOf("camera")))
        assertEquals(SearchState("tea", SearchPhase.READY, listOf("tea")), search.snapshot())
    }

    @Test fun brokenComparatorActuallyPublishesMismatchedQueryAndResult() {
        val source = ControlledSource(); val search = BrokenSearch(source)
        search.submit("cam"); search.submit("tea")
        source.calls[1].reply(Reply.Success(listOf("tea")))
        source.calls[0].reply(Reply.Success(listOf("camera")))
        assertEquals(SearchState("tea", SearchPhase.READY, listOf("camera")), search.snapshot())
    }

    @Test fun sameQueryAfterAnotherQueryHasANewIdentity() {
        val source = ControlledSource(); val search = LatestSearch(source)
        search.submit("cam"); search.submit("tea"); search.submit("cam")
        source.calls[0].reply(Reply.Success(listOf("old-camera")))
        assertEquals(SearchState("cam", SearchPhase.LOADING), search.snapshot())
        source.calls[2].reply(Reply.Success(listOf("current-camera")))
        assertEquals(listOf("current-camera"), search.snapshot().items)
    }

    @Test fun clearInvalidatesInFlightAndLaterSameTextDoesNotRestoreAuthority() {
        val source = ControlledSource(); val search = LatestSearch(source)
        search.submit("tea"); search.clear()
        assertEquals(SearchState(), search.snapshot())
        assertEquals(1, source.calls[0].cancelCalls)
        search.submit("tea")
        source.calls[0].reply(Reply.Success(listOf("old-account")))
        assertEquals(SearchPhase.LOADING, search.snapshot().phase)
        source.calls[1].reply(Reply.Success(listOf("new-account")))
        search.clear(); source.calls[1].reply(Reply.Success(listOf("duplicate")))
        assertEquals(SearchState(), search.snapshot())
    }

    @Test fun firstSuccessWinsOverDuplicateSuccessAndFailure() {
        val source = ControlledSource(); val search = LatestSearch(source)
        search.submit("tea")
        source.calls.single().reply(Reply.Success(listOf("tea")))
        source.calls.single().reply(Reply.Success(listOf("wrong")))
        source.calls.single().reply(Reply.Failure("late failure"))
        assertEquals(SearchState("tea", SearchPhase.READY, listOf("tea")), search.snapshot())
    }

    @Test fun firstFailureWinsAndNextSubmitResetsFailure() {
        val source = ControlledSource(); val search = LatestSearch(source)
        search.submit("tea"); source.calls[0].reply(Reply.Failure("offline"))
        source.calls[0].reply(Reply.Success(listOf("late")))
        assertEquals(SearchState("tea", SearchPhase.FAILED, failure = "offline"), search.snapshot())
        search.submit("cam")
        assertEquals(SearchState("cam", SearchPhase.LOADING), search.snapshot())
    }

    @Test fun synchronousSuccessIsAdmittedAndReturnedHandleIsCanceled() {
        val cancellations = AtomicInteger()
        val search = LatestSearch(object : SearchSource {
            override fun start(query: String, deliver: (Reply) -> Unit): CancelHandle {
                deliver(Reply.Success(listOf("tea")))
                return CancelHandle { cancellations.incrementAndGet(); deliver(Reply.Failure("cancel callback")) }
            }
        })
        search.submit("tea")
        assertEquals(SearchState("tea", SearchPhase.READY, listOf("tea")), search.snapshot())
        assertEquals(1, cancellations.get())
    }

    @Test fun startFailureIsCurrentTerminalOutcomeWithFallbackMessage() {
        val search = LatestSearch(object : SearchSource {
            override fun start(query: String, deliver: (Reply) -> Unit): CancelHandle = throw RuntimeException()
        })
        search.submit("tea")
        assertEquals(SearchState("tea", SearchPhase.FAILED, failure = "Source failed"), search.snapshot())
    }

    @Test fun startThrowAfterSynchronousReplyCannotReplaceSuccess() {
        val search = LatestSearch(object : SearchSource {
            override fun start(query: String, deliver: (Reply) -> Unit): CancelHandle {
                deliver(Reply.Success(listOf("tea"))); throw IllegalStateException("after callback")
            }
        })
        search.submit("tea")
        assertEquals(SearchState("tea", SearchPhase.READY, listOf("tea")), search.snapshot())
    }

    @Test fun cancelMaySynchronouslyDeliverOldSuccessWithoutReplacingLoading() {
        lateinit var callback: (Reply) -> Unit
        val search = LatestSearch(object : SearchSource {
            override fun start(query: String, deliver: (Reply) -> Unit): CancelHandle {
                if (query == "cam") callback = deliver
                return CancelHandle { if (query == "cam") callback(Reply.Success(listOf("camera"))) }
            }
        })
        search.submit("cam"); search.submit("tea")
        assertEquals(SearchState("tea", SearchPhase.LOADING), search.snapshot())
        search.clear()
        assertEquals(SearchState(), search.snapshot())
    }

    @Test fun reentrantCancelMaySupersedeTheOuterSubmit() {
        lateinit var search: LatestSearch
        val canceled = mutableListOf<String>()
        val callbacks = mutableMapOf<String, (Reply) -> Unit>()
        search = LatestSearch(object : SearchSource {
            override fun start(query: String, deliver: (Reply) -> Unit): CancelHandle {
                callbacks[query] = deliver
                return CancelHandle {
                    canceled += query
                    if (query == "cam") search.submit("bag")
                }
            }
        })
        search.submit("cam"); search.submit("tea")
        assertEquals(listOf("cam", "tea"), canceled)
        callbacks.getValue("tea")(Reply.Success(listOf("obsolete")))
        callbacks.getValue("bag")(Reply.Success(listOf("bag")))
        assertEquals(SearchState("bag", SearchPhase.READY, listOf("bag")), search.snapshot())
    }

    @Test fun reentrantStartMayCompleteNewerRequestBeforeOldHandleReturns() {
        lateinit var search: LatestSearch
        val canceled = mutableListOf<String>()
        search = LatestSearch(object : SearchSource {
            override fun start(query: String, deliver: (Reply) -> Unit): CancelHandle {
                if (query == "cam") {
                    search.submit("tea"); deliver(Reply.Success(listOf("obsolete")))
                } else deliver(Reply.Success(listOf("tea")))
                return CancelHandle { canceled += query }
            }
        })
        search.submit("cam")
        assertEquals(SearchState("tea", SearchPhase.READY, listOf("tea")), search.snapshot())
        assertEquals(listOf("tea", "cam"), canceled)
    }

    @Test fun clearDuringStartCancelsLateReturnedHandleAndRejectsItsCallback() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val canceled = AtomicInteger(); val executor = Executors.newSingleThreadExecutor()
        val search = LatestSearch(object : SearchSource {
            override fun start(query: String, deliver: (Reply) -> Unit): CancelHandle {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                return CancelHandle { canceled.incrementAndGet(); deliver(Reply.Success(listOf("late"))) }
            }
        })
        try {
            val pending = executor.submit { search.submit("tea") }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            search.clear(); assertEquals(SearchState(), search.snapshot())
            release.countDown(); pending.get(5, TimeUnit.SECONDS)
            assertEquals(1, canceled.get()); assertEquals(SearchState(), search.snapshot())
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun startAndCancelRunOutsideGuardSoOtherThreadCanSnapshot() {
        val executor = Executors.newSingleThreadExecutor(); lateinit var search: LatestSearch
        fun otherThreadSnapshot() = executor.submit<SearchState> { search.snapshot() }.get(5, TimeUnit.SECONDS)
        search = LatestSearch(object : SearchSource {
            override fun start(query: String, deliver: (Reply) -> Unit): CancelHandle {
                assertEquals(SearchPhase.LOADING, otherThreadSnapshot().phase)
                return CancelHandle { assertEquals(SearchPhase.IDLE, otherThreadSnapshot().phase) }
            }
        })
        try { search.submit("tea"); search.clear() } finally { executor.shutdownNow() }
    }

    @Test fun simultaneousTerminalCallbacksHaveOneStableWinner() {
        val executor = Executors.newFixedThreadPool(2); val barrier = java.util.concurrent.CyclicBarrier(3)
        lateinit var deliver: (Reply) -> Unit
        val search = LatestSearch(object : SearchSource {
            override fun start(query: String, callback: (Reply) -> Unit): CancelHandle {
                deliver = callback; return CancelHandle { }
            }
        })
        search.submit("tea")
        try {
            val first = executor.submit { barrier.await(5, TimeUnit.SECONDS); deliver(Reply.Success(listOf("a"))) }
            val second = executor.submit { barrier.await(5, TimeUnit.SECONDS); deliver(Reply.Success(listOf("b"))) }
            barrier.await(5, TimeUnit.SECONDS); first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS)
            val accepted = search.snapshot()
            assertTrue(accepted.items == listOf("a") || accepted.items == listOf("b"))
            deliver(Reply.Failure("third")); assertEquals(accepted, search.snapshot())
        } finally { executor.shutdownNow() }
    }

    @Test fun invalidQueryDoesNotCancelOrChangeCurrentStateAndTextIsExact() {
        val source = ControlledSource(); val search = LatestSearch(source)
        search.submit(" tea ")
        assertThrows(IllegalArgumentException::class.java) { search.submit(" \t") }
        assertEquals(0, source.calls[0].cancelCalls); assertEquals(1, source.calls.size)
        source.calls[0].reply(Reply.Success(emptyList()))
        assertEquals(SearchState(" tea ", SearchPhase.READY), search.snapshot())
    }

    @Test fun inputAndSnapshotListsDoNotAliasInternalState() {
        val source = ControlledSource(); val search = LatestSearch(source)
        val input = mutableListOf("tea", "bag")
        search.submit("tea"); source.calls.single().reply(Reply.Success(input)); input.clear()
        val snapshot = search.snapshot()
        (snapshot.items as MutableList<String>).clear()
        assertEquals(listOf("tea", "bag"), search.snapshot().items)
    }
}
