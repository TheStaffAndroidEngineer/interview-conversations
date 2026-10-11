package staff.android.performance.catalog

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RefreshState(val refreshing: Boolean = false, val message: String = "Ready to explore")
/** refresh() is called on the caller's main-thread scope, never concurrently. */
class RefreshOwner(
    private val catalog: SnapshotCatalog,
    private val source: CatalogSource,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val trace: Tracer = NoTrace,
) {
    private val mutableState = MutableStateFlow(RefreshState())
    val state = mutableState.asStateFlow()
    private var request = 0L
    fun refresh(): Job {
        val ticket = catalog.beginRefresh()
        request = ticket
        mutableState.value = RefreshState(true, "Refreshing collection…")
        return scope.launch {
            try {
                val loaded = withContext(io) {
                    trace.span("CG/repaired/load") { source.load() }
                }
                currentCoroutineContext().ensureActive()
                if (request == ticket && catalog.publish(ticket, loaded)) {
                    mutableState.value = RefreshState(false, "Collection refreshed")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (request == ticket) {
                    mutableState.value = RefreshState(false, "Refresh unavailable. Your collection is still here.")
                }
            }
        }
    }
}
