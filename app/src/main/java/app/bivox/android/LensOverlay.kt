package app.bivox.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextPaint
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// العدسة: إطار على الشاشة يترجم أي نص يمرّ تحته أثناء التمرير.
// يمكن تحريكه وتغيير كل ضلع وحجمه، وزر «تثبيت» يقفل كل ذلك ويترك اللمس يمرّ للتطبيق تحته.
// The lens: a frame on screen that translates any text passing under it while scrolling.
// It can be moved and each side/size changed; a "pin" button locks all that and lets touches pass through to the app below.
class LensOverlay(private val env: Env, private val hub: TranslateHub) {
    private var view: LensView? = null
    private var bar: LinearLayout? = null
    private var pinBtn: TextView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var barLp: WindowManager.LayoutParams? = null

    // مستطيل العدسة بإحداثيات الشاشة (بلا هوامش المقابض) / lens rectangle in screen coordinates (without the handle margins)
    private val rect = Rect()
    private var pinned = false
    private var m = 0                 // هامش المقابض حول الإطار / handle margin around the frame
    private var gen = 0               // رقم الجيل: يُلغي نتائج قديمة / generation: discards stale results
    private val refreshRun = Runnable { refresh() }

    val showing: Boolean get() = view != null
    private var paused = false

    // الشاشة انطفأت: لا قراءة ولا ترجمة / screen went off: no reading, no translating
    fun pause() {
        paused = true
        env.main.removeCallbacks(refreshRun)
        gen++
    }

    fun resume() {
        paused = false
        if (view != null) scheduleRefresh(300)
    }

    fun show() {
        if (view != null) return
        env.measure()
        val u = env.u
        m = max(2, (u * 3.5f).toInt())
        val s = env.screen()
        val saved = Prefs.lens(env.ctx)
        if (saved != null && saved[2] > saved[0] && saved[3] > saved[1]) {
            rect.set(saved[0], saved[1], saved[2], saved[3])
        } else {
            val w = (s.first * 0.88f).toInt()
            val h = (u * 32f).toInt()
            val x = (s.first - w) / 2
            val y = (s.second * 0.4f).toInt()
            rect.set(x, y, x + w, y + h)
        }
        clampRect(rect)
        pinned = Prefs.lensPinned(env.ctx)

        val v = LensView(env.ctx)
        val flp = WindowManager.LayoutParams(
            rect.width() + 2 * m, rect.height() + 2 * m, env.type(), frameFlags(), PixelFormat.TRANSLUCENT)
        flp.gravity = Gravity.TOP or Gravity.LEFT
        env.applyCutout(flp)
        flp.x = rect.left - m
        flp.y = rect.top - m
        try {
            env.wm.addView(v, flp)
        } catch (e: Exception) {
            return
        }
        view = v
        lp = flp

        buildBar()
        scheduleRefresh(150)
    }

    fun close() {
        env.main.removeCallbacks(refreshRun)
        gen++
        val v = view
        if (v != null) {
            try { env.wm.removeView(v) } catch (e: Exception) { }
        }
        val b = bar
        if (b != null) {
            try { env.wm.removeView(b) } catch (e: Exception) { }
        }
        view = null; bar = null; pinBtn = null; lp = null; barLp = null
    }

    // أحداث الشاشة: عند التمرير نمسح القديم فورًا ثم نعيد القراءة بعد توقف الحركة
    // Screen events: on scroll clear the old items at once, then re-read after the motion stops
    fun onScreenEvent(type: Int) {
        val v = view ?: return
        if (paused) return
        if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            v.items = emptyList()
            v.invalidate()
        }
        scheduleRefresh(450)
    }

    private fun scheduleRefresh(delayMs: Long) {
        env.main.removeCallbacks(refreshRun)
        env.main.postDelayed(refreshRun, delayMs)
    }

    private fun frameFlags(): Int {
        var f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        if (pinned) f = f or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return f
    }

    // يقرأ نصوص الشاشة داخل الإطار ويترجمها ويرسمها / reads the on-screen texts inside the frame, translates and draws them
    private fun refresh() {
        val v = view ?: return
        if (paused) return
        val reader = BivoxAccessibilityService.instance ?: return
        val myGen = ++gen
        env.readPool.execute {
            val t0 = System.currentTimeMillis()
            val boxes: List<TextBox> = try { reader.collect() } catch (e: Exception) { emptyList() }
            Dbg.logEvery("lens", 1500, "read " + boxes.size + " texts in " + (System.currentTimeMillis() - t0) + "ms")
            env.main.post {
                if (myGen == gen && view === v) applyBoxes(v, boxes, myGen)
            }
        }
    }

    private fun applyBoxes(v: LensView, boxes: List<TextBox>, myGen: Int) {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val inner = Rect(loc[0] + m, loc[1] + m, loc[0] + v.width - m, loc[1] + v.height - m)
        val dst = Langs.CODES[Prefs.my(env.ctx)]
        val fallback = Langs.CODES[Prefs.their(env.ctx)]
        val items = boxes.filter { Rect.intersects(it.rect, inner) }.take(60).map { b ->
            Item(b.text, b.rect).also { it.trans = hub.cached(b.text, dst) }
        }
        v.items = items
        v.invalidate()
        for (it in items) {
            if (it.trans != null) continue
            hub.request(it.orig, dst, fallback) { r ->
                if (r != null && myGen == gen && view === v) {
                    it.trans = r
                    v.invalidate()
                }
            }
        }
    }

    private fun clampRect(r: Rect) {
        val s = env.screen()
        val minW = (env.u * 15f).toInt()
        val minH = (env.u * 8f).toInt()
        val w = r.width().coerceIn(minW, max(minW, s.first))
        val h = r.height().coerceIn(minH, max(minH, s.second))
        val x = r.left.coerceIn(0, max(0, s.first - w))
        val y = r.top.coerceIn(0, max(0, s.second - h))
        r.set(x, y, x + w, y + h)
    }

    // يطبّق المستطيل الجديد على نافذة الإطار وشريط التحكم / applies the new rectangle to the frame window and the control bar
    private fun applyRect() {
        val v = view ?: return
        val l = lp ?: return
        l.x = rect.left - m
        l.y = rect.top - m
        l.width = rect.width() + 2 * m
        l.height = rect.height() + 2 * m
        try { env.wm.updateViewLayout(v, l) } catch (e: Exception) { }
        placeBar()
        v.invalidate()
    }

    // ── شريط التحكم: تثبيت وإغلاق ──
    // ── Control bar: pin and close ──
    private fun buildBar() {
        val u = env.u
        val btn = (u * 9f).toInt()
        val row = LinearLayout(env.ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.background = GradientDrawable().apply {
            setColor(Color.argb(235, 28, 28, 36))
            cornerRadius = u * 2.5f
            setStroke(max(1, (u * 0.3f).toInt()), Color.parseColor("#3A3A47"))
        }
        val pin = TextView(env.ctx)
        pin.gravity = Gravity.CENTER
        pin.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 5f)
        pin.setTextColor(Col.TEXT)
        pin.setOnClickListener { togglePin() }
        pinBtn = pin
        row.addView(pin, LinearLayout.LayoutParams(btn, btn))
        val closeBtn = TextView(env.ctx)
        closeBtn.gravity = Gravity.CENTER
        closeBtn.text = "✕"
        closeBtn.setTypeface(closeBtn.typeface, Typeface.BOLD)
        closeBtn.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 4.6f)
        closeBtn.setTextColor(Col.TEXT)
        closeBtn.setOnClickListener { close() }
        row.addView(closeBtn, LinearLayout.LayoutParams(btn, btn))

        val blp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, env.type(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT)
        blp.gravity = Gravity.TOP or Gravity.LEFT
        env.applyCutout(blp)
        barLp = blp
        bar = row
        updatePinText()
        placeBar()
        try {
            env.wm.addView(row, blp)
        } catch (e: Exception) {
            bar = null
        }
    }

    private fun updatePinText() {
        pinBtn?.text = if (pinned) "📌" else "🔓"
    }

    // فوق الإطار إن اتسع المكان وإلا تحته / above the frame if there is room, otherwise below it
    private fun placeBar() {
        val b = bar ?: return
        val bl = barLp ?: return
        val u = env.u
        val barH = (u * 9f).toInt()
        val s = env.screen()
        bl.x = rect.left
        bl.y = if (rect.top - barH - m >= 0) rect.top - barH - m else min(s.second - barH, rect.bottom + m)
        try { env.wm.updateViewLayout(b, bl) } catch (e: Exception) { }
    }

    private fun togglePin() {
        pinned = !pinned
        Prefs.setLensPinned(env.ctx, pinned)
        val v = view
        val l = lp
        if (v != null && l != null) {
            l.flags = frameFlags()
            try { env.wm.updateViewLayout(v, l) } catch (e: Exception) { }
            v.invalidate()
        }
        updatePinText()
        scheduleRefresh(100)
    }

    // ── إطار العدسة: رسم الحدود والمقابض والترجمات، ومعالجة اللمس ──
    // ── The lens frame: draws the border, handles and translations, and handles touch ──
    private inner class LensView(ctx: Context) : View(ctx) {
        var items: List<Item> = emptyList()
        private val u = env.u
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(2f, u * 0.5f)
        }
        private val fillPaint = Drawing.fill(Color.argb(22, 255, 255, 255))
        private val handleFill = Drawing.fill(Color.WHITE)
        private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Col.PRIMARY
            strokeWidth = max(2f, u * 0.5f)
        }
        private val bg = Drawing.fill(Color.argb(242, 19, 19, 24))
        private val tp: TextPaint = Drawing.textPaint(Col.TEXT)
        private val loc = IntArray(2)

        // أوضاع اللمس: أضلاع بقناع بتات + تحريك / touch modes: sides as a bit mask + move
        private val SIDE_L = 1
        private val SIDE_R = 2
        private val SIDE_T = 4
        private val SIDE_B = 8
        private val MODE_MOVE = 16
        private var mode = 0
        private var sx = 0f
        private var sy = 0f
        private val start = Rect()

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val inner = RectF(m.toFloat(), m.toFloat(), w - m, h - m)
            if (!pinned) canvas.drawRoundRect(inner, u, u, fillPaint)

            // الترجمات مقصوصة داخل الإطار / translations clipped inside the frame
            getLocationOnScreen(loc)
            canvas.save()
            canvas.clipRect(inner)
            for (it in items) {
                val tr = it.trans ?: continue
                if (tr.trim().equals(it.orig.trim(), ignoreCase = true)) continue
                Drawing.translation(canvas, (it.rect.left - loc[0]).toFloat(), (it.rect.top - loc[1]).toFloat(),
                    it.rect.width().toFloat(), it.rect.height().toFloat(), tr, u, bg, tp)
            }
            canvas.restore()

            border.color = if (pinned) Col.ACCENT else Col.PRIMARY
            canvas.drawRoundRect(inner, u, u, border)
            if (!pinned) {
                val r = u * 1.5f
                val cx = (inner.left + inner.right) / 2
                val cy = (inner.top + inner.bottom) / 2
                val xs = floatArrayOf(inner.left, cx, inner.right)
                val ys = floatArrayOf(inner.top, cy, inner.bottom)
                for (i in 0..2) for (j in 0..2) {
                    if (i == 1 && j == 1) continue
                    canvas.drawCircle(xs[i], ys[j], r, handleFill)
                    canvas.drawCircle(xs[i], ys[j], r, handleStroke)
                }
            }
        }

        // أي ضلع/زاوية قريبة من اللمسة؟ وإلا فهو تحريك من الداخل
        // Which side/corner is near the touch? Otherwise it is a move from the inside
        private fun hit(x: Float, y: Float): Int {
            val l = m.toFloat()
            val t = m.toFloat()
            val r = (width - m).toFloat()
            val b = (height - m).toFloat()
            val slop = m.toFloat()
            if (x < l - slop || x > r + slop || y < t - slop || y > b + slop) return 0
            var md = 0
            if (abs(x - l) <= slop) md = md or SIDE_L
            if (abs(x - r) <= slop) md = md or SIDE_R
            if (abs(y - t) <= slop) md = md or SIDE_T
            if (abs(y - b) <= slop) md = md or SIDE_B
            // لا يمكن أن يكون الضلعان المتقابلان معًا / opposite sides can't both be active
            if (md and SIDE_L != 0 && md and SIDE_R != 0) md = md and SIDE_R.inv()
            if (md and SIDE_T != 0 && md and SIDE_B != 0) md = md and SIDE_B.inv()
            return if (md != 0) md else MODE_MOVE
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (pinned) return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    mode = hit(e.x, e.y)
                    sx = e.rawX; sy = e.rawY
                    start.set(rect)
                    return mode != 0
                }
                MotionEvent.ACTION_MOVE -> {
                    if (mode == 0) return false
                    val dx = (e.rawX - sx).toInt()
                    val dy = (e.rawY - sy).toInt()
                    val s = env.screen()
                    val minW = (u * 15f).toInt()
                    val minH = (u * 8f).toInt()
                    val r = Rect(start)
                    if (mode == MODE_MOVE) {
                        val nx = (start.left + dx).coerceIn(0, max(0, s.first - start.width()))
                        val ny = (start.top + dy).coerceIn(0, max(0, s.second - start.height()))
                        r.set(nx, ny, nx + start.width(), ny + start.height())
                    } else {
                        if (mode and SIDE_L != 0) r.left = (start.left + dx).coerceIn(0, max(0, start.right - minW))
                        if (mode and SIDE_R != 0) r.right = (start.right + dx).coerceIn(min(s.first, start.left + minW), s.first)
                        if (mode and SIDE_T != 0) r.top = (start.top + dy).coerceIn(0, max(0, start.bottom - minH))
                        if (mode and SIDE_B != 0) r.bottom = (start.bottom + dy).coerceIn(min(s.second, start.top + minH), s.second)
                    }
                    rect.set(r)
                    applyRect()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (mode != 0) {
                        Prefs.setLens(env.ctx, rect.left, rect.top, rect.right, rect.bottom)
                        scheduleRefresh(350)
                    }
                    mode = 0
                }
            }
            return true
        }
    }
}
