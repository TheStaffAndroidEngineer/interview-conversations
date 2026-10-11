package staff.android.conversations

/** Each update replaces the whole result list. Sequence numbers belong to one request. */
class SequencedResults {
    private val guard = Any()
    private var highestId = 0L
    private var state = StreamState()

    fun begin(requestId: Long) = synchronized(guard) {
        require(requestId > highestId)
        highestId = requestId
        state = StreamState(requestId = requestId)
    }

    fun accept(update: StreamUpdate): Boolean = synchronized(guard) {
        require(update.requestId > 0 && update.sequence >= 0)
        if (state.requestId != update.requestId || state.finished) return@synchronized false
        val previous = state.lastSequence
        if (previous != null && update.sequence <= previous) return@synchronized false
        state = StreamState(update.requestId, update.sequence, update.items.toList(), update.terminal)
        true
    }

    fun clear() = synchronized(guard) { state = StreamState() }

    fun snapshot(): StreamState = synchronized(guard) { state.copy(items = state.items.toList()) }
}
