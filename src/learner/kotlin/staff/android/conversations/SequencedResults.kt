package staff.android.conversations

/** Deliberately incomplete: request identity alone is insufficient for a stream. */
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
        if (state.requestId != update.requestId) return@synchronized false
        // TODO: enforce sequence and terminal publication rules before changing state.
        state = StreamState(update.requestId, update.sequence, update.items.toList(), update.terminal)
        true
    }
    fun clear() = synchronized(guard) { state = StreamState() }
    fun snapshot(): StreamState = synchronized(guard) { state.copy(items = state.items.toList()) }
}
