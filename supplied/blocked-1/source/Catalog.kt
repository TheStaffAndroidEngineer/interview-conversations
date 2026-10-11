package staff.android.performance.catalog

/** Immutable values; callers never receive the catalog's mutable container. */
data class Product(val id: String, val name: String, val priceMinor: Long)
data class CatalogSnapshot(val revision: Long, val products: List<Product>)
fun interface CatalogSource { fun load(): List<Product> }
interface Tracer {
    fun begin(name: String)
    fun end()
}
object NoTrace : Tracer {
    override fun begin(name: String) = Unit
    override fun end() = Unit
}
inline fun <T> Tracer.span(name: String, block: () -> T): T {
    begin(name)
    return try { block() } finally { end() }
}
fun checkedProducts(products: List<Product>): List<Product> {
    require(products.all { it.id.isNotBlank() && it.name.isNotBlank() && it.priceMinor >= 0 })
    require(products.map { it.id }.distinct().size == products.size)
    return java.util.Collections.unmodifiableList(products.toList())
}

/** Deliberately broken: source latency becomes every reader's lock latency. */
class BroadLockCatalog(
    seed: List<Product>,
    private val source: CatalogSource,
    private val trace: Tracer = NoTrace,
) {
    private val guard = Any()
    private var accepted = CatalogSnapshot(0, checkedProducts(seed))
    fun refresh(onLocked: () -> Unit = {}) = synchronized(guard) {
        trace.span("CG/blocked/hold") {
            onLocked()
            val loaded = checkedProducts(source.load())
            accepted = CatalogSnapshot(Math.addExact(accepted.revision, 1), loaded)
        }
    }
    fun snapshot(): CatalogSnapshot = trace.span("CG/blocked/snapshot") {
        synchronized(guard) { accepted }
    }
}

/** Loading is caller-owned and outside this monitor. */
class SnapshotCatalog(seed: List<Product>, private val trace: Tracer = NoTrace) {
    private val guard = Any()
    private var latestTicket = 0L
    private var accepted = CatalogSnapshot(0, checkedProducts(seed))
    fun beginRefresh(): Long = synchronized(guard) {
        latestTicket = Math.addExact(latestTicket, 1)
        latestTicket
    }
    fun publish(ticket: Long, products: List<Product>): Boolean {
        val frozen = checkedProducts(products)
        return trace.span("CG/repaired/publish") {
            synchronized(guard) {
                if (ticket != latestTicket) false else {
                    accepted = CatalogSnapshot(Math.addExact(accepted.revision, 1), frozen)
                    true
                }
            }
        }
    }
    fun snapshot(): CatalogSnapshot = trace.span("CG/repaired/snapshot") {
        synchronized(guard) { accepted }
    }
}

object CpuWork {
    @JvmStatic fun checksum(rounds: Int): Long {
        require(rounds >= 0)
        var value = 1L
        repeat(rounds) { index -> value = (value xor index.toLong()) * 1_664_525L + 1_013_904_223L }
        return value
    }
}
