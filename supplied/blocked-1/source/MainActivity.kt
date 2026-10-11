package staff.android.performance

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import android.view.View
import android.widget.*
import android.graphics.drawable.GradientDrawable
import kotlinx.coroutines.*
import staff.android.performance.catalog.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object AndroidTrace : Tracer {
    override fun begin(name: String) = Trace.beginSection(name)
    override fun end() = Trace.endSection()
}
class MainActivity : Activity() {
    private val scope = MainScope()
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "cg-loader") }
    private val seed = listOf(Product("camera", "Camera", 1299900), Product("tea", "Tea", 45000))
    private val catalog = SnapshotCatalog(seed, AndroidTrace)
    private val failNext = AtomicBoolean(false)
    private val source = CatalogSource {
        AndroidTrace.span("CG/source/load") {
            val fail = failNext.getAndSet(false)
            // Controlled local delay, not a measured retail backend.
            Thread.sleep(800)
            if (fail) error("Controlled source unavailable")
            seed
        }
    }
    private lateinit var owner: RefreshOwner
    private lateinit var body: LinearLayout
    private var diagnostic = false
    private var labMessage = "Choose a controlled comparison"
    private var saved = false
    private var active = false
    private var destroyed = false
    private var currentScenario = "repaired"
    private var checkIns = 0
    private var pulseLabel: TextView? = null
    private var operationStart = 0L
    private val pulse = object : Runnable {
        override fun run() {
            if (active) AndroidTrace.span("CG/main/check-in") {
                checkIns += 1
                pulseLabel?.text = "Main thread check-ins: $checkIns"
            }
            main.postDelayed(this, 100)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        owner = RefreshOwner(catalog, source, scope, Dispatchers.IO, AndroidTrace)
        body = column().apply { setPadding(dp(20), dp(12), dp(20), dp(24)) }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(body) }
        val host = FrameLayout(this).apply {
            clipToPadding = true
            setBackgroundColor(getColor(R.color.paper))
            setOnApplyWindowInsetsListener { view, insets ->
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                insets
            }
            addView(scroll, FrameLayout.LayoutParams(-1, -1))
        }
        setContentView(host);host.requestApplyInsets()
        scope.launch {
            owner.state.collect { state ->
                if (!state.refreshing && active && operationStart != 0L) finish(currentScenario)
                render()
            }
        }
        main.post(pulse)
        render()
        intent.getStringExtra("scenario")?.let { scenario ->
            diagnostic = true
            main.postDelayed({ runScenario(scenario) }, 900)
        }
    }
    private fun begin() {
        checkIns = 0; active = true
        operationStart = SystemClock.elapsedRealtimeNanos()
        labMessage = "Investigation running"
        render()
    }
    private fun finish(scenario: String, cpuNanos: Long? = null, value: Long? = null) {
        val elapsed = SystemClock.elapsedRealtimeNanos() - operationStart
        labMessage = "$scenario complete · check-ins $checkIns"
        Log.i("CG_DIAG", "scenario=$scenario elapsed_ns=$elapsed main_cpu_ns=$cpuNanos value=$value revision=${catalog.snapshot().revision} check_ins=$checkIns")
        active = false;operationStart = 0L
        render()
    }
    private fun runScenario(scenario: String) {
        if (active || destroyed || owner.state.value.refreshing) return
        currentScenario = scenario
        diagnostic = true
        when (scenario) {
            "blocked" -> {
                begin()
                val broad = BroadLockCatalog(seed, source, AndroidTrace)
                worker.execute {
                    try { broad.refresh {
                        // Post only after the worker has acquired the catalog monitor.
                        main.post {
                            if (destroyed) return@post
                            val wallStart = SystemClock.elapsedRealtimeNanos()
                            val cpuStart = android.os.Debug.threadCpuTimeNanos()
                            val accepted = broad.snapshot()
                            val cpu = android.os.Debug.threadCpuTimeNanos() - cpuStart
                            val wall = SystemClock.elapsedRealtimeNanos() - wallStart
                            Log.i("CG_DIAG", "snapshot_wall_ns=$wall snapshot_cpu_ns=$cpu accepted_revision=${accepted.revision}")
                            finish("blocked", cpu, accepted.revision)
                        }
                    } } catch (_: InterruptedException) {
                        // Destroying this diagnostic screen cancels its owned worker.
                        Thread.currentThread().interrupt()
                    }
                }
            }
            "busy" -> {
                begin()
                main.post {
                    val cpuStart = android.os.Debug.threadCpuTimeNanos()
                    val checksum = AndroidTrace.span("CG/busy/checksum") { CpuWork.checksum(90_000_000) }
                    finish("busy", android.os.Debug.threadCpuTimeNanos() - cpuStart, checksum)
                }
            }
            "repaired", "failure" -> {
                if (scenario == "failure") failNext.set(true)
                begin();owner.refresh()
            }
            else -> { labMessage = "Unknown scenario";render() }
        }
    }
    private fun render() {
        body.removeAllViews();pulseLabel = null
        val brand = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        brand.addView(ImageView(this).apply { setImageResource(R.drawable.ic_common_goods); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(30), dp(30)))
        brand.addView(label("Common Goods", 21f, true).apply { setPadding(dp(12), dp(10), 0, dp(10)) })
        body.addView(brand)
        body.addView(label("Shop", 14f))
        body.addView(label("The daily edit", 30f, true))
        body.addView(label("Considered essentials for everyday living.", 17f))
        val state = owner.state.value
        body.addView(label(state.message, 16f).apply {
            contentDescription = "Collection status: ${state.message}"
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        })
        body.addView(action(if (state.refreshing) "Refreshing collection…" else "Refresh collection", true) { owner.refresh() }.apply { isEnabled = !state.refreshing && !active })
        val products = catalog.snapshot().products
        val wide = resources.configuration.screenWidthDp >= 600
        val cards = LinearLayout(this).apply { orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        products.forEach { product ->
            val card = column().apply { setPadding(dp(16), dp(12), dp(16), dp(16)); background = rounded(R.color.panel) }
            card.addView(ImageView(this).apply { setImageResource(if (product.id == "tea") R.drawable.product_tea else R.drawable.product_camera); scaleType = ImageView.ScaleType.FIT_CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(-1, dp(138)))
            card.addView(label(product.name, 22f, true))
            card.addView(label(if (product.id == "tea") "A little everyday ritual · ₹450" else "Keep the moments that matter · ₹12,999", 16f))
            if (product.id == "camera") card.addView(action(if (saved) "Saved Camera" else "Save Camera", false) { saved = !saved;render() })
            cards.addView(card, LinearLayout.LayoutParams(if (wide) 0 else -1, -2, if (wide) 1f else 0f).apply { bottomMargin = dp(12); if (wide) marginEnd = dp(10) })
        }
        body.addView(cards)
        body.addView(action(if (diagnostic) "Close lab controls" else "Lab controls", false) { diagnostic = !diagnostic;render() })
        if (diagnostic) {
            body.addView(label("Controlled investigation", 22f, true))
            body.addView(label("Local two-product fixture · 800 ms source delay. Saved state lasts for this screen. Reopening resets the catalog.", 15f))
            body.addView(label("Accepted revision: ${catalog.snapshot().revision}", 16f))
            body.addView(label(labMessage, 16f))
            pulseLabel = label("Main thread check-ins: $checkIns", 16f).also { body.addView(it) }
            for ((title, scenario) in listOf("Run lock wait" to "blocked", "Run CPU work" to "busy", "Run repaired refresh" to "repaired", "Run failed refresh" to "failure")) {
                body.addView(action(title, false) { runScenario(scenario) }.apply { isEnabled = !active && !state.refreshing })
            }
        }
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun rounded(color: Int) = GradientDrawable().apply { setColor(getColor(color));cornerRadius = dp(16).toFloat() }
    private fun label(value: String, size: Float, heading: Boolean = false) = TextView(this).apply {
        text = value;textSize = size;setTextColor(getColor(R.color.ink))
        typeface = resources.getFont(if (heading) R.font.sora_bold else R.font.google_sans_regular)
        setPadding(0, dp(8), 0, dp(8))
        if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = heading
    }
    private fun action(title: String, primary: Boolean, callback: () -> Unit) = Button(this).apply {
        text = title;isAllCaps = false;textSize = 16f;minHeight = dp(52)
        typeface = resources.getFont(R.font.google_sans_bold)
        setTextColor(getColor(if (primary) R.color.paper else R.color.accent))
        background = rounded(if (primary) R.color.accent else R.color.panel)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6);bottomMargin = dp(10) }
        setOnClickListener { callback() }
    }
    override fun onDestroy() { destroyed = true;main.removeCallbacksAndMessages(null);scope.cancel();worker.shutdownNow();super.onDestroy() }
}
