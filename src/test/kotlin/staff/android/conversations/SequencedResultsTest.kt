package staff.android.conversations

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SequencedResultsTest {
    private fun update(id: Long, seq: Long, item: String, terminal: Boolean = false) =
        StreamUpdate(id, seq, listOf(item), terminal)

    @Test fun firstSequenceMayBeZeroAndUpdatesReplaceInsteadOfAppend() {
        val results = SequencedResults(); results.begin(1)
        assertTrue(results.accept(update(1, 0, "cached")))
        assertTrue(results.accept(update(1, 3, "network")))
        assertEquals(StreamState(1, 3, listOf("network"), false), results.snapshot())
    }
    @Test fun lowerSequenceCannotRegressResults() {
        val results = SequencedResults(); results.begin(7)
        results.accept(update(7, 3, "tea"))
        assertFalse(results.accept(update(7, 2, "camera")))
        assertEquals(StreamState(7, 3, listOf("tea"), false), results.snapshot())
    }
    @Test fun duplicateSequenceWithDifferentPayloadIsNotAnotherUpdate() {
        val results = SequencedResults(); results.begin(1)
        results.accept(update(1, 4, "first"))
        assertFalse(results.accept(update(1, 4, "conflicting")))
        assertEquals(listOf("first"), results.snapshot().items)
    }
    @Test fun terminalClosesEvenHigherSequence() {
        val results = SequencedResults(); results.begin(7)
        results.accept(update(7, 5, "final", terminal = true))
        assertFalse(results.accept(update(7, 6, "late")))
        assertEquals(StreamState(7, 5, listOf("final"), true), results.snapshot())
    }
    @Test fun olderTerminalCannotCloseNewerInterim() {
        val results = SequencedResults(); results.begin(1)
        results.accept(update(1, 5, "newer"))
        assertFalse(results.accept(update(1, 2, "old-final", terminal = true)))
        assertTrue(results.accept(update(1, 6, "actual-final", terminal = true)))
    }
    @Test fun duplicateTerminalFlagCannotChangeAcceptedInterimAtSameSequence() {
        val results = SequencedResults(); results.begin(1)
        results.accept(update(1, 3, "interim"))
        assertFalse(results.accept(update(1, 3, "interim", terminal = true)))
        assertFalse(results.snapshot().finished)
        assertTrue(results.accept(update(1, 4, "final", terminal = true)))
    }
    @Test fun newRequestIgnoresOldIdentityAndResetsSequenceAndTerminal() {
        val results = SequencedResults(); results.begin(1)
        results.accept(update(1, 99, "old", terminal = true)); results.begin(2)
        assertEquals(StreamState(requestId = 2), results.snapshot())
        assertFalse(results.accept(update(1, 100, "late")))
        assertTrue(results.accept(update(2, 0, "new")))
    }
    @Test fun clearRetainsHighWaterIdAndRejectsEveryOldCallback() {
        val results = SequencedResults(); results.begin(10); results.clear()
        assertEquals(StreamState(), results.snapshot())
        assertFalse(results.accept(update(10, 1, "old")))
        for (id in listOf(-1L, 0L, 1L, 10L)) {
            assertThrows(IllegalArgumentException::class.java) { results.begin(id) }
        }
        results.begin(11); assertTrue(results.accept(update(11, 0, "new")))
    }
    @Test fun validateBeforeFilteringAndInvalidBeginPreservesExistingState() {
        val results = SequencedResults(); results.begin(5); results.accept(update(5, 0, "tea"))
        val before = results.snapshot()
        assertThrows(IllegalArgumentException::class.java) { results.begin(5) }
        assertThrows(IllegalArgumentException::class.java) { results.accept(update(4, -1, "wrong-id")) }
        assertThrows(IllegalArgumentException::class.java) { results.accept(update(0, 1, "bad-id")) }
        assertEquals(before, results.snapshot())
    }
    @Test fun maximumIdsAndSequencesNeedNoIncrementOverflow() {
        val results = SequencedResults(); results.begin(Long.MAX_VALUE)
        assertTrue(results.accept(update(Long.MAX_VALUE, Long.MAX_VALUE, "last")))
        assertFalse(results.accept(update(Long.MAX_VALUE, Long.MAX_VALUE - 1, "old")))
        results.clear()
        assertThrows(IllegalArgumentException::class.java) { results.begin(Long.MAX_VALUE) }
    }
    @Test fun producerAndSnapshotListsAreDetached() {
        val results = SequencedResults(); results.begin(1)
        val items = mutableListOf("tea", "bag")
        results.accept(StreamUpdate(1, 0, items, false)); items.clear()
        (results.snapshot().items as MutableList<String>).clear()
        assertEquals(listOf("tea", "bag"), results.snapshot().items)
    }
    @Test fun crossThreadLowerUpdateCannotRegressAnAcceptedHigherSequence() {
        val results = SequencedResults(); results.begin(1)
        val barrier = CyclicBarrier(3); val highDone = java.util.concurrent.CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val low = executor.submit {
                barrier.await(5, TimeUnit.SECONDS); check(highDone.await(5, TimeUnit.SECONDS))
                results.accept(update(1, 1, "low"))
            }
            val high = executor.submit {
                barrier.await(5, TimeUnit.SECONDS); results.accept(update(1, 2, "high")); highDone.countDown()
            }
            barrier.await(5, TimeUnit.SECONDS); low.get(5, TimeUnit.SECONDS); high.get(5, TimeUnit.SECONDS)
            assertEquals(StreamState(1, 2, listOf("high"), false), results.snapshot())
        } finally { highDone.countDown(); executor.shutdownNow() }
    }
}
