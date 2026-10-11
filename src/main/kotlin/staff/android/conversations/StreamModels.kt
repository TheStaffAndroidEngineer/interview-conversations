package staff.android.conversations

data class StreamUpdate(
    val requestId: Long,
    val sequence: Long,
    val items: List<String>,
    val terminal: Boolean,
)
data class StreamState(
    val requestId: Long? = null,
    val lastSequence: Long? = null,
    val items: List<String> = emptyList(),
    val finished: Boolean = false,
)
