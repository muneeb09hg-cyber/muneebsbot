package com.muneeb.autotrader

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.animation.ValueAnimator
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class TraderService : AccessibilityService() {

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val sw get() = resources.displayMetrics.widthPixels
    private val sh get() = resources.displayMetrics.heightPixels

    // ओवरले व्यू
    private var fab: TextView? = null
    private var panel: LinearLayout? = null
    private var statusBar: TextView? = null
    private var scanLine: View? = null
    private var scanAnim: ValueAnimator? = null
    private val markerViews = mutableListOf<View>()
    private var rectView: View? = null
    private var rectParams: WindowManager.LayoutParams? = null
    private var calibBar: View? = null

    // निशानों की जगहें: UP, DOWN, TL, BR, AMT
    private val pos = mutableMapOf<String, Point>()

    // सेटिंग
    private var amount = 1
    private var maxTrades = 5
    private var durationSec = 5

    // चलने की स्थिति
    private var running = false
    private var scanning = false
    private var tradesDone = 0
    private var wins = 0
    private var losses = 0
    private var open: OpenTrade? = null

    private class OpenTrade(val dir: Int, val entry: Float, val endAt: Long)

    // ---------- शुरुआत ----------

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences("auto_trader", Context.MODE_PRIVATE)
        amount = prefs.getInt("amount", 1)
        maxTrades = prefs.getInt("maxTrades", 5)
        durationSec = prefs.getInt("durationSec", 5)
        loadPos()
        showFab()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        stopRun()
        closePanel()
        removeCalibViews()
        fab?.let { runCatching { wm.removeView(it) } }
        fab = null
        super.onDestroy()
    }

    private fun lp(w: Int, h: Int, touchable: Boolean = true, x: Int = 0, y: Int = 0) =
        WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }

    private fun oval(color: String) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.parseColor(color))
        setStroke(dp(2), Color.WHITE)
    }

    private fun loadPos() {
        val defaults = mapOf(
            "UP" to Point((sw * 0.75f).toInt(), (sh * 0.85f).toInt()),
            "DOWN" to Point((sw * 0.25f).toInt(), (sh * 0.85f).toInt()),
            "TL" to Point((sw * 0.05f).toInt(), (sh * 0.25f).toInt()),
            "BR" to Point((sw * 0.85f).toInt(), (sh * 0.6f).toInt()),
            "AMT" to Point((sw * 0.5f).toInt(), (sh * 0.78f).toInt())
        )
        for ((k, p) in defaults) {
            pos[k] = Point(prefs.getInt("p_${k}_x", p.x), prefs.getInt("p_${k}_y", p.y))
        }
    }

    private fun rect(): Rect {
        val a = pos["TL"]!!
        val b = pos["BR"]!!
        return Rect(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))
    }

    // ---------- तैरता "AT" बटन ----------

    private fun showFab() {
        if (fab != null) return
        val size = dp(52)
        val p = lp(size, size, true, sw - size - dp(8), (sh * 0.4f).toInt())
        val v = TextView(this).apply {
            text = "AT"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            background = oval("#CC37474F")
        }
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        v.setOnTouchListener { view, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = p.x
                    startY = p.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    if (moved) {
                        p.x = (startX + dx).toInt()
                        p.y = (startY + dy).toInt()
                        wm.updateViewLayout(view, p)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        if (panel != null) closePanel() else showPanel(0)
                    }
                    true
                }
                else -> false
            }
        }
        wm.addView(v, p)
        fab = v
    }

    // ---------- पैनल की मदद करने वाले फ़ंक्शन ----------

    private fun label(t: String, size: Float = 15f, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(Color.WHITE)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun button(t: String, selected: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(Color.parseColor(if (selected) "#2E7D32" else "#37474F"))
        }
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (v in views) {
            addView(
                v,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                }
            )
        }
    }

    private fun closePanel() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
    }

    private fun showPanel(step: Int) {
        closePanel()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#F2212121"))
            }
        }
        when (step) {
            0 -> homeStep(box)
            1 -> amountStep(box)
            2 -> timeStep(box)
            else -> summaryStep(box)
        }
        val p = lp(dp(300), WindowManager.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        }
        wm.addView(box, p)
        panel = box
    }

    private fun homeStep(box: LinearLayout) {
        val calibrated = prefs.getBoolean("calibrated", false)
        box.addView(label("Auto Trader", 20f, true))
        box.addView(label(if (calibrated) "✓ बटन और चार्ट की जगह सेट है" else "⚠ पहले जगह सेट करें"))
        box.addView(
            label(
                "स्क्रीन कैप्चर: " +
                        if (CaptureHolder.running) "चालू ✓" else "बंद ✗ (Auto Trader ऐप में जाकर चालू करें)"
            )
        )
        box.addView(
            label("कुल नतीजे (अनुमानित): जीत ${prefs.getInt("tw", 0)} / हार ${prefs.getInt("tl", 0)}")
        )
        box.addView(button("1) जगह सेट करें") { startCalibration() })
        box.addView(button("2) ट्रेड सेटअप ▶") { showPanel(1) })
        box.addView(button("बंद करें") { closePanel() })
    }

    private fun amountStep(box: LinearLayout) {
        box.addView(label("कितने डॉलर की ट्रेड?", 18f, true))
        val amounts = (1..5).map { v ->
            button("\$$v", amount == v) {
                amount = v
                showPanel(1)
            }
        }
        box.addView(row(*amounts.toTypedArray()))
        box.addView(label("एक साथ कुल कितनी ट्रेड: $maxTrades"))
        box.addView(
            row(
                button("−") {
                    maxTrades = max(1, maxTrades - 1)
                    showPanel(1)
                },
                button("+") {
                    maxTrades = min(50, maxTrades + 1)
                    showPanel(1)
                }
            )
        )
        box.addView(row(button("◀ वापस") { showPanel(0) }, button("अगला ▶") { showPanel(2) }))
    }

    private fun timeStep(box: LinearLayout) {
        box.addView(label("कितने सेकंड की ट्रेड?", 18f, true))
        val secs = listOf(5, 10, 15, 20).map { v ->
            button("${v}s", durationSec == v) {
                durationSec = v
                showPanel(2)
            }
        }
        box.addView(row(*secs.toTypedArray()))
        box.addView(label("या मिनट में"))
        val mins = listOf(1, 2, 3, 5).map { v ->
            button("${v}m", durationSec == v * 60) {
                durationSec = v * 60
                showPanel(2)
            }
        }
        box.addView(row(*mins.toTypedArray()))
        box.addView(label("ध्यान रखें: ट्रेडिंग ऐप में भी यही समय चुना हुआ होना चाहिए।", 13f))
        box.addView(row(button("◀ वापस") { showPanel(1) }, button("अगला ▶") { showPanel(3) }))
    }

    private fun summaryStep(box: LinearLayout) {
        val t = if (durationSec >= 60) "${durationSec / 60} मिनट" else "$durationSec सेकंड"
        box.addView(label("तैयार", 20f, true))
        box.addView(label("रकम: \$$amount\nकुल ट्रेड: $maxTrades\nसमय: $t"))
        if (!prefs.getBoolean("calibrated", false)) box.addView(label("⚠ जगह सेट नहीं है"))
        if (!CaptureHolder.running) box.addView(label("⚠ स्क्रीन कैप्चर चालू नहीं है"))
        box.addView(button("▶ शुरू करें") {
            closePanel()
            startRun()
        })
        box.addView(button("◀ वापस") { showPanel(2) })
    }

    // ---------- जगह सेट करना ----------

    private fun startCalibration() {
        closePanel()
        removeCalibViews()
        loadPos()

        val rv = View(this).apply {
            background = GradientDrawable().apply {
                setStroke(dp(2), Color.parseColor("#1E88E5"))
                setColor(Color.TRANSPARENT)
            }
        }
        val r = rect()
        val rp = lp(max(1, r.width()), max(1, r.height()), false, r.left, r.top)
        wm.addView(rv, rp)
        rectView = rv
        rectParams = rp

        makeMarker("UP", "UP", "#2E7D32")
        makeMarker("DOWN", "DN", "#C62828")
        makeMarker("TL", "TL", "#1E88E5")
        makeMarker("BR", "BR", "#1E88E5")
        makeMarker("AMT", "\$", "#F9A825")

        val bar = button("✓ सेव करें") { saveCalibration() }
        wm.addView(bar, lp(dp(140), WindowManager.LayoutParams.WRAP_CONTENT, true, sw / 2 - dp(70), dp(70)))
        calibBar = bar

        Toast.makeText(
            this,
            "UP/DN को ट्रेड बटनों पर, TL/BR को चार्ट के कोनों पर और \$ को रकम बॉक्स पर रखें",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun makeMarker(key: String, text: String, color: String) {
        val size = dp(44)
        val start = pos[key]!!
        val p = lp(size, size, true, start.x - size / 2, start.y - size / 2)
        val v = TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            background = oval(color)
        }
        v.setOnTouchListener { view, e ->
            if (e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_MOVE) {
                val cx = e.rawX.toInt()
                val cy = e.rawY.toInt()
                p.x = cx - size / 2
                p.y = cy - size / 2
                pos[key] = Point(cx, cy)
                wm.updateViewLayout(view, p)
                updateRectView()
            }
            true
        }
        wm.addView(v, p)
        markerViews.add(v)
    }

    private fun updateRectView() {
        val rv = rectView ?: return
        val rp = rectParams ?: return
        val r = rect()
        rp.x = r.left
        rp.y = r.top
        rp.width = max(1, r.width())
        rp.height = max(1, r.height())
        runCatching { wm.updateViewLayout(rv, rp) }
    }

    private fun saveCalibration() {
        val e = prefs.edit()
        for ((k, p) in pos) {
            e.putInt("p_${k}_x", p.x)
            e.putInt("p_${k}_y", p.y)
        }
        e.putBoolean("calibrated", true).apply()
        removeCalibViews()
        Toast.makeText(this, "जगह सेव हो गई", Toast.LENGTH_SHORT).show()
    }

    private fun removeCalibViews() {
        markerViews.forEach { v -> runCatching { wm.removeView(v) } }
        markerViews.clear()
        rectView?.let { runCatching { wm.removeView(it) } }
        rectView = null
        calibBar?.let { runCatching { wm.removeView(it) } }
        calibBar = null
    }

    // ---------- ट्रेड चलाना ----------

    private fun startRun() {
        if (!prefs.getBoolean("calibrated", false)) {
            Toast.makeText(this, "पहले जगह सेट करें", Toast.LENGTH_LONG).show()
            showPanel(0)
            return
        }
        if (!CaptureHolder.running) {
            Toast.makeText(this, "पहले Auto Trader ऐप में स्क्रीन कैप्चर चालू करें", Toast.LENGTH_LONG).show()
            showPanel(0)
            return
        }
        prefs.edit()
            .putInt("amount", amount)
            .putInt("maxTrades", maxTrades)
            .putInt("durationSec", durationSec)
            .apply()

        running = true
        scanning = false
        tradesDone = 0
        wins = 0
        losses = 0
        open = null
        fab?.visibility = View.GONE
        showStatusBar()
        updateStatus("शुरू")
        handler.postDelayed(tick, 500)
    }

    private fun stopRun() {
        running = false
        scanning = false
        open = null
        handler.removeCallbacksAndMessages(null)
        stopScanAnim()
        statusBar?.let { runCatching { wm.removeView(it) } }
        statusBar = null
        fab?.visibility = View.VISIBLE
    }

    private fun finishRun() {
        running = false
        updateStatus("पूरा हुआ")
        stopScanAnim()
        handler.postDelayed({ stopRun() }, 8000)
    }

    private fun showStatusBar() {
        val tv = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#E6212121"))
            }
            setOnClickListener { stopRun() }
        }
        val p = lp(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(30)
        }
        wm.addView(tv, p)
        statusBar = tv
    }

    private fun updateStatus(note: String) {
        val total = wins + losses
        val pct = if (total > 0) " (${wins * 100 / total}%)" else ""
        statusBar?.text = "▶ $tradesDone/$maxTrades | जीत $wins हार $losses$pct | $note\nरोकने के लिए टैप करें"
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val o = open
            if (o != null) {
                if (System.currentTimeMillis() >= o.endAt) settle(o)
            } else if (tradesDone >= maxTrades) {
                finishRun()
                return
            } else if (!scanning) {
                scanAndTrade()
            }
            handler.postDelayed(this, 500)
        }
    }

    private fun scanAndTrade() {
        scanning = true
        startScanAnim()
        updateStatus("चार्ट स्कैन हो रहा है…")
        handler.postDelayed({
            if (!running) return@postDelayed
            stopScanAnim()
            // स्कैनर लाइन हटने के बाद ताज़ा फ़्रेम आने दें
            handler.postDelayed({
                if (!running) return@postDelayed
                val bmp = CaptureHolder.grab()
                val sig = if (bmp != null) SignalEngine.analyze(bmp, rect())
                else SignalEngine.Signal(0, 0, 0f, "स्क्रीन कैप्चर से फ़्रेम नहीं मिला")
                if (sig.dir != 0) {
                    placeTrade(sig)
                    scanning = false
                } else {
                    updateStatus(sig.note)
                    handler.postDelayed({ scanning = false }, 1000)
                }
            }, 250)
        }, 2200)
    }

    private fun placeTrade(sig: SignalEngine.Signal) {
        setAmount()
        val target = if (sig.dir > 0) pos["UP"]!! else pos["DOWN"]!!
        handler.postDelayed({ tap(target.x, target.y) }, 400)
        tradesDone++
        open = OpenTrade(sig.dir, sig.price, System.currentTimeMillis() + durationSec * 1000L + 1500L)
        updateStatus((if (sig.dir > 0) "UP" else "DOWN") + " लगाई: " + sig.note)
    }

    private fun settle(o: OpenTrade) {
        val bmp = CaptureHolder.grab()
        val price = bmp?.let { SignalEngine.lastClose(it, rect()) }
        var note = "नतीजा पढ़ा नहीं जा सका"
        if (price != null) {
            val win = if (o.dir > 0) price > o.entry else price < o.entry
            val lose = if (o.dir > 0) price < o.entry else price > o.entry
            if (win) {
                wins++
                prefs.edit().putInt("tw", prefs.getInt("tw", 0) + 1).apply()
                note = "पिछली ट्रेड: जीत"
            } else if (lose) {
                losses++
                prefs.edit().putInt("tl", prefs.getInt("tl", 0) + 1).apply()
                note = "पिछली ट्रेड: हार"
            } else {
                note = "पिछली ट्रेड: बराबर"
            }
        }
        open = null
        updateStatus(note)
    }

    // ---------- स्कैनर लाइन ----------

    private fun startScanAnim() {
        stopScanAnim()
        val r = rect()
        val v = View(this).apply { setBackgroundColor(Color.parseColor("#1E88E5")) }
        val p = lp(max(1, r.width()), dp(3), false, r.left, r.top)
        wm.addView(v, p)
        scanLine = v
        scanAnim = ValueAnimator.ofInt(r.top, r.bottom).apply {
            duration = 700
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                p.y = it.animatedValue as Int
                runCatching { wm.updateViewLayout(v, p) }
            }
            start()
        }
    }

    private fun stopScanAnim() {
        scanAnim?.cancel()
        scanAnim = null
        scanLine?.let { runCatching { wm.removeView(it) } }
        scanLine = null
    }

    // ---------- टैप और रकम ----------

    private fun tap(x: Int, y: Int) {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        dispatchGesture(g, null, null)
    }

    private fun setAmount() {
        val a = pos["AMT"] ?: return
        val root = rootInActiveWindow ?: return
        val node = findNode(root, a.x, a.y) { it.isEditable } ?: return
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, amount.toString())
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findNode(
        n: AccessibilityNodeInfo?,
        x: Int,
        y: Int,
        pred: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (n == null) return null
        val r = Rect()
        n.getBoundsInScreen(r)
        var found: AccessibilityNodeInfo? = null
        if (r.contains(x, y) && pred(n)) found = n
        for (i in 0 until n.childCount) {
            findNode(n.getChild(i), x, y, pred)?.let { found = it }
        }
        return found
    }
}
