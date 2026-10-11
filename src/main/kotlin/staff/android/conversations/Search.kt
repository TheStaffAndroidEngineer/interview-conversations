package staff.android.conversations

enum class SearchPhase { IDLE, LOADING, READY, FAILED }
data class SearchState(
    val query: String? = null,
    val phase: SearchPhase = SearchPhase.IDLE,
    val items: List<String> = emptyList(),
    val failure: String? = null,
)
sealed interface Reply {
    data class Success(val items: List<String>) : Reply
    data class Failure(val message: String) : Reply
}
/** Idempotent, nonthrowing best effort. May synchronously deliver a callback. */
fun interface CancelHandle { fun cancel() }
interface SearchSource {
    /** May call deliver synchronously, late, repeatedly, or on another thread.
     * May throw RuntimeException. Neither start nor cancel is a publication barrier.
     * A Success list must remain stable during delivery; this consumer copies it.
     */
    fun start(query: String, deliver: (Reply) -> Unit): CancelHandle
}

/** Thread-safe state core. Platform view rendering still belongs to its UI owner. */
class LatestSearch(private val source: SearchSource) {
    private class Request(val query: String) { var handle: CancelHandle? = null }
    private val guard = Any()
    private var active: Request? = null
    private var state = SearchState()

    fun submit(query: String) {
        require(query.isNotBlank())
        val request = Request(query)
        val oldHandle = synchronized(guard) {
            val old = active?.handle
            active = request
            state = SearchState(query, SearchPhase.LOADING)
            old
        }
        oldHandle?.cancel()
        val returned = try {
            source.start(query) { reply -> complete(request, reply) }
        } catch (failure: RuntimeException) {
            complete(request, Reply.Failure(failure.message ?: "Source failed"))
            return
        }
        val obsolete = synchronized(guard) {
            if (active === request) {
                request.handle = returned
                false
            } else true
        }
        if (obsolete) returned.cancel()
    }

    private fun complete(request: Request, reply: Reply) = synchronized(guard) {
        if (active !== request) return@synchronized
        state = when (reply) {
            is Reply.Success -> SearchState(
                request.query, SearchPhase.READY, reply.items.toList(),
            )
            is Reply.Failure -> SearchState(
                request.query, SearchPhase.FAILED, failure = reply.message,
            )
        }
        active = null // First admitted terminal reply wins.
    }

    fun clear() {
        val oldHandle = synchronized(guard) {
            val old = active?.handle
            active = null
            state = SearchState()
            old
        }
        oldHandle?.cancel()
    }

    fun snapshot(): SearchState = synchronized(guard) {
        state.copy(items = state.items.toList())
    }
}

/** Deliberately wrong, single-lane counterexample. Not a thread-safe controller. */
class BrokenSearch(private val source: SearchSource) {
    private var handle: CancelHandle? = null
    private var state = SearchState()
    fun submit(query: String) {
        require(query.isNotBlank())
        handle?.cancel()
        state = SearchState(query, SearchPhase.LOADING)
        handle = source.start(query) { reply ->
            state = when (reply) {
                is Reply.Success -> state.copy(phase = SearchPhase.READY, items = reply.items.toList())
                is Reply.Failure -> state.copy(phase = SearchPhase.FAILED, failure = reply.message)
            }
        }
    }
    fun snapshot(): SearchState = state.copy(items = state.items.toList())
}

/** Manually stepped fixture. Its control methods are confined to the test lane. */
class ControlledSource : SearchSource {
    class Call(val query: String, private val deliver: (Reply) -> Unit) {
        var cancelCalls = 0
            private set
        fun cancel() { cancelCalls += 1 }
        fun reply(value: Reply) = deliver(value) // Cancellation intentionally does not suppress delivery.
    }
    val calls = mutableListOf<Call>()
    override fun start(query: String, deliver: (Reply) -> Unit): CancelHandle {
        val call = Call(query, deliver)
        calls += call
        return CancelHandle { call.cancel() }
    }
}
