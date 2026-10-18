package app.bivox.android

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// خدمة أمامية تعرض فقاعة عائمة فوق كل التطبيقات؛ الضغط عليها يفتح لوحة ترجمة مصغّرة
// Foreground service showing a floating bubble over all apps; tapping it opens a mini translator panel
class BubbleService : Service() {

    // ── الألوان / Colors ──
    private val cBg = Color.parseColor("#131318")
    private val cText = Color.parseColor("#ECECF1")
    private val cMuted = Color.parseColor("#9A9AA8")
    private val cOrig = Color.parseColor("#B5B5C2")
    private val cCard = Color.parseColor("#1C1C24")
    private val cCardBorder = Color.parseColor("#2B2B36")
    private val cField = Color.parseColor("#23232D")
    private val cFieldBorder = Color.parseColor("#34343F")
    private val cBtn = Color.parseColor("#2A2A36")
    private val cBtnBorder = Color.parseColor("#3A3A47")
    private val cBtnPressed = Color.parseColor("#34344A")
    private val cPrimary = Color.parseColor("#5B8DEF")
    private val cPrimaryPressed = Color.parseColor("#4A78D0")
    private val cAccent = Color.parseColor("#3FA37A")
    private val cAccentPressed = Color.parseColor("#34896A")
    private val cDisabled = Color.parseColor("#22222B")
    private val cDisabledText = Color.parseColor("#666672")

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val translator = Translator()
    private var speaker: Speaker? = null

    private var u = 4f
    private var uiLang = "ar"
    private lateinit var t: Map<String, String>

    // الفقاعة / the bubble
    private var bubble: View? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var bubbleSize = 0
    private var snapAnim: ValueAnimator? = null

    // اللوحة / the panel
    private var panel: View? = null
    private var panelBody: LinearLayout? = null
    private var input: EditText? = null
    private var statusLbl: TextView? = null
    private var meBtn: Button? = null
    private var theyBtn: Button? = null
    private var myIdx = 0
    private var theirIdx = 1
    private var lastOrig = ""
    private var lastTrans = ""
    private var lastDst = "en"
    private var draft = ""
    private var busy = false
    private var statusKey = "ready"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        speaker = Speaker(this,
            onReady = { main.post { refreshStatus() } },
            onFail = { main.post { if (panel != null) setStatus("speak_fail") } })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // أول شيء: إشعار الخدمة الأمامية (يلزم خلال 5 ثوانٍ) / first: the foreground notification (required within 5 s)
        try {
            startAsForeground()
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            Prefs.setBubbleOn(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        // إعادة تشغيل النظام للخدمة بعد قتلها: نحترم رغبة المستخدم والإذن
        // System restart after a kill: respect the user's wish and the permission
        if (intent == null && !Prefs.bubbleOn(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!canOverlay(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (bubble == null) {
            try {
                showBubble()
            } catch (e: Exception) {
                // الجهاز لا يسمح بالعرض / the device refuses overlays
                bubble = null
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // تدوير الشاشة: نغلق اللوحة ونعيد ضبط موضع الفقاعة / rotation: close the panel and re-clamp the bubble
        closePanel()
        measure()
        val lp = bubbleLp
        val b = bubble
        if (lp != null && b != null) {
            val s = screen()
            lp.x = if (lp.x + bubbleSize / 2 < s.first / 2) 0 else s.first - bubbleSize
            lp.y = lp.y.coerceIn(0, max(0, s.second - bubbleSize))
            try { wm.updateViewLayout(b, lp) } catch (e: Exception) { }
        }
    }

    override fun onDestroy() {
        running = false
        snapAnim?.cancel()
        closePanel()
        removeBubble()
        speaker?.shutdown()
        speaker = null
        io.shutdownNow()
        super.onDestroy()
    }

    // ── الإشعار / Notification ──
    private fun startAsForeground() {
        val lang = Prefs.uiLang(this)
        val tx = Ui.TEXTS.getValue(lang)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "BiVox", NotificationManager.IMPORTANCE_LOW))
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val stopIntent = Intent(this, BubbleService::class.java).setAction(ACTION_STOP)
        val stop = PendingIntent.getService(this, 1, stopIntent, flags)
        @Suppress("DEPRECATION")
        val nb = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        nb.setSmallIcon(R.drawable.ic_stat_bivox)
            .setContentTitle("BiVox")
            .setContentText(tx.getValue("notif_text"))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_stat_bivox), tx.getValue("notif_stop"), stop).build())
        val n = nb.build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    // ── القياس / Measuring ──
    private fun screen(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            Pair(b.width(), b.height())
        } else {
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            Pair(dm.widthPixels, dm.heightPixels)
        }
    }

    private fun measure() {
        val s = screen()
        u = max(3f, min(s.first, s.second) / 100f)
        bubbleSize = (u * 13f).toInt()
    }

    private fun px(units: Float): Int = max(1, (u * units).toInt())

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    // ── الفقاعة / The bubble ──
    private fun showBubble() {
        measure()
        val s = screen()
        val view = FrameLayout(this)
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(cCard)
            setStroke(max(1, (u * 0.5f).toInt()), cPrimary)
        }
        view.background = bg
        view.alpha = 0.95f
        val img = ImageView(this)
        img.setImageResource(R.drawable.bivox_logo)
        img.contentDescription = "BiVox"
        val pad = px(1.8f)
        img.setPadding(pad, pad, pad, pad)
        view.addView(img, FrameLayout.LayoutParams(bubbleSize, bubbleSize))

        val lp = WindowManager.LayoutParams(
            bubbleSize, bubbleSize, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.LEFT
        val sx = Prefs.bubbleX(this)
        val sy = Prefs.bubbleY(this)
        lp.x = if (sx < 0) s.first - bubbleSize else sx.coerceIn(0, max(0, s.first - bubbleSize))
        lp.y = if (sy < 0) s.second / 3 else sy.coerceIn(0, max(0, s.second - bubbleSize))

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        view.setOnTouchListener(object : View.OnTouchListener {
            var startX = 0
            var startY = 0
            var downX = 0f
            var downY = 0f
            var moved = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        snapAnim?.cancel()
                        startX = lp.x; startY = lp.y
                        downX = e.rawX; downY = e.rawY
                        moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY
                        if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
                        if (moved) {
                            val sc = screen()
                            lp.x = (startX + dx).toInt().coerceIn(0, max(0, sc.first - bubbleSize))
                            lp.y = (startY + dy).toInt().coerceIn(0, max(0, sc.second - bubbleSize))
                            try { wm.updateViewLayout(v, lp) } catch (ex: Exception) { }
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (moved) snapToEdge(v, lp) else openPanel()
                    }
                }
                return true
            }
        })

        wm.addView(view, lp)
        bubble = view
        bubbleLp = lp
    }

    // يلصق الفقاعة بأقرب حافة ويحفظ موضعها / snaps the bubble to the nearest edge and saves its position
    private fun snapToEdge(v: View, lp: WindowManager.LayoutParams) {
        val s = screen()
        val target = if (lp.x + bubbleSize / 2 < s.first / 2) 0 else s.first - bubbleSize
        snapAnim?.cancel()
        val a = ValueAnimator.ofInt(lp.x, target)
        a.duration = 180
        a.addUpdateListener {
            lp.x = it.animatedValue as Int
            try { wm.updateViewLayout(v, lp) } catch (ex: Exception) { }
        }
        a.start()
        snapAnim = a
        Prefs.setBubblePos(this, target, lp.y)
    }

    private fun removeBubble() {
        val b = bubble ?: return
        try { wm.removeView(b) } catch (e: Exception) { }
        bubble = null
        bubbleLp = null
    }

    // ── اللوحة المصغّرة / The mini panel ──
    // جذر اللوحة: زر الرجوع يغلقها / panel root: the Back key closes it
    private inner class PanelRoot(ctx: Context) : FrameLayout(ctx) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) closePanel()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }

    private fun openPanel() {
        if (panel != null) return
        measure()
        uiLang = Prefs.uiLang(this)
        t = Ui.TEXTS.getValue(uiLang)
        myIdx = Prefs.my(this)
        theirIdx = Prefs.their(this)
        val s = screen()

        val root = PanelRoot(this)
        root.layoutDirection = if (uiLang == "ar") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        root.background = GradientDrawable().apply {
            setColor(cBg)
            cornerRadius = u * 3f
            setStroke(max(1, (u * 0.4f).toInt()), cPrimary)
        }
        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        root.addView(scroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        val m = px(3f)
        body.setPadding(m, m, m, m)
        scroll.addView(body, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panelBody = body

        val width = min((s.first * 0.94f).toInt(), px(110f))
        // نافذة قابلة للتركيز كي تعمل لوحة المفاتيح؛ لا FLAG_ALT_FOCUSABLE_IM
        // Focusable window so the keyboard works; no FLAG_ALT_FOCUSABLE_IM
        val lp = WindowManager.LayoutParams(
            width, ViewGroup.LayoutParams.WRAP_CONTENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        lp.y = px(4f)
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE

        buildMain()
        try {
            wm.addView(root, lp)
        } catch (e: Exception) {
            panelBody = null
            return
        }
        panel = root
        bubble?.visibility = View.GONE
    }

    private fun closePanel() {
        hideKeyboard()
        val p = panel
        if (p != null) {
            try { wm.removeView(p) } catch (e: Exception) { }
        }
        panel = null
        panelBody = null
        input = null
        statusLbl = null
        meBtn = null
        theyBtn = null
        bubble?.visibility = View.VISIBLE
    }

    private fun hideKeyboard() {
        val v = input ?: return
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(v.windowToken, 0)
        } catch (e: Exception) { }
    }

    // الصفحة الرئيسية للوحة / the panel's main page
    private fun buildMain() {
        val body = panelBody ?: return
        body.removeAllViews()
        val gap = px(2.5f)

        // الرأس: الشعار + الاسم + زر الإغلاق / header: logo + name + close
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val logo = ImageView(this)
        logo.setImageResource(R.drawable.bivox_logo)
        val logoLp = LinearLayout.LayoutParams(px(8f), px(8f))
        logoLp.marginEnd = gap
        head.addView(logo, logoLp)
        val title = label("BiVox", 4.8f, cText, true)
        title.textDirection = View.TEXT_DIRECTION_LTR
        title.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        add(head, title, weight = 1f, width = 0)
        head.addView(smallButton("✕") { closePanel() }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        add(body, head)

        // اللغتان + التبديل / the two languages + swap
        val myPicker = picker({ myIdx }, { myIdx = it })
        val theirPicker = picker({ theirIdx }, { theirIdx = it })
        val swap = smallButton(t.getValue("swap")) {
            val a = myIdx; myIdx = theirIdx; theirIdx = a
            Prefs.setLangs(this, myIdx, theirIdx)
            myPicker.text = Langs.NAMES.getValue(Langs.CODES[myIdx]) + " ▾"
            theirPicker.text = Langs.NAMES.getValue(Langs.CODES[theirIdx]) + " ▾"
        }
        val langRow = LinearLayout(this)
        langRow.orientation = LinearLayout.HORIZONTAL
        langRow.gravity = Gravity.CENTER_VERTICAL
        add(langRow, myPicker, weight = 1f, width = 0)
        val swapLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        swapLp.marginStart = gap; swapLp.marginEnd = gap
        langRow.addView(swap, swapLp)
        add(langRow, theirPicker, weight = 1f, width = 0)
        add(body, langRow, gap)

        // حقل الإدخال / input field
        val et = EditText(this).apply {
            hint = t.getValue("hint")
            setHintTextColor(cMuted)
            setTextColor(cText)
            sizeU(this, 4.2f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(false); maxLines = 3
            minHeight = px(11f)
            setPadding(px(2.5f), px(2f), px(2.5f), px(2f))
            background = rounded(cField, cFieldBorder)
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            imeOptions = EditorInfo.IME_ACTION_SEND
            setText(draft)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) { go(true); true } else false
            }
        }
        input = et
        add(body, et, gap)

        // الزران الكبيران / the two big buttons
        val me = bigButton(t.getValue("btn_me"), cPrimary, cPrimaryPressed) { go(true) }
        val they = bigButton(t.getValue("btn_they"), cAccent, cAccentPressed) { go(false) }
        meBtn = me; theyBtn = they
        me.isEnabled = !busy; they.isEnabled = !busy
        val btns = LinearLayout(this)
        btns.orientation = LinearLayout.HORIZONTAL
        add(btns, me, weight = 1f, width = 0)
        val theyLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        theyLp.marginStart = gap
        btns.addView(they, theyLp)
        add(body, btns, gap)

        // النتيجة / result
        if (lastTrans.isNotEmpty()) {
            val card = LinearLayout(this)
            card.orientation = LinearLayout.VERTICAL
            val cp = px(2.5f)
            card.setPadding(cp, cp, cp, cp)
            card.background = rounded(cCard, cCardBorder)
            val o = label(lastOrig, 3.8f, cOrig)
            val tr = label(lastTrans, 5.4f, cText, true)
            for (v in listOf(o, tr)) {
                v.setTextIsSelectable(true)
                v.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                v.textAlignment = View.TEXT_ALIGNMENT_TEXT_START
            }
            add(card, o)
            add(card, tr, px(1.5f))
            add(card, smallButton(t.getValue("speak")) {
                val r = speaker?.say(lastTrans, lastDst)
                if (r == SpeakResult.NO_VOICE || r == SpeakResult.NO_ENGINE) setStatus("no_voice")
            }, px(1.5f), width = ViewGroup.LayoutParams.WRAP_CONTENT)
            add(body, card, gap)
        }

        val st = label("", 3.2f, cMuted)
        statusLbl = st
        add(body, st, gap)
        refreshStatus()
    }

    // صفحة اختيار اللغة داخل اللوحة (بدل نافذة منبثقة) / language list page inside the panel (instead of a dialog)
    private fun buildList(current: Int, onPick: (Int) -> Unit) {
        val body = panelBody ?: return
        draft = input?.text?.toString() ?: draft
        hideKeyboard()
        body.removeAllViews()
        for ((i, code) in Langs.CODES.withIndex()) {
            val row = TextView(this)
            row.text = (if (i == current) "✓  " else "") + Langs.NAMES.getValue(code)
            row.setTextColor(if (i == current) cPrimary else cText)
            sizeU(row, 4.6f)
            row.setPadding(px(3f), px(2.8f), px(3f), px(2.8f))
            row.background = rounded(if (i == current) cField else cCard, cCardBorder)
            row.isClickable = true
            row.setOnClickListener {
                onPick(i)
                Prefs.setLangs(this, myIdx, theirIdx)
                buildMain()
            }
            add(body, row, if (i == 0) 0 else px(1f))
        }
    }

    private fun picker(getIdx: () -> Int, setIdx: (Int) -> Unit): TextView =
        TextView(this).apply {
            text = Langs.NAMES.getValue(Langs.CODES[getIdx()]) + " ▾"
            setTextColor(cText)
            sizeU(this, 4.2f)
            gravity = Gravity.CENTER_VERTICAL
            minHeight = px(11f)
            setPadding(px(2.5f), px(2f), px(2.5f), px(2f))
            background = rounded(cField, cFieldBorder)
            isClickable = true
            setOnClickListener { buildList(getIdx(), setIdx) }
        }

    // ── الحالة والترجمة / Status and translation ──
    private fun refreshStatus() {
        val s = statusLbl ?: return
        var txt = t.getValue(statusKey)
        if (statusKey == "ready" && speaker?.available != true) txt += "  " + t.getValue("speak_off")
        s.text = txt
    }

    private fun setStatus(key: String) {
        statusKey = key
        refreshStatus()
    }

    private fun go(meToThem: Boolean) {
        if (busy) return
        val text = input?.text?.toString()?.trim() ?: return
        if (text.isEmpty()) { setStatus("empty"); return }
        if (myIdx == theirIdx) { setStatus("same"); return }
        val my = Langs.CODES[myIdx]
        val their = Langs.CODES[theirIdx]
        val src = if (meToThem) my else their
        val dst = if (meToThem) their else my
        busy = true
        meBtn?.isEnabled = false; theyBtn?.isEnabled = false
        setStatus("working")
        io.execute {
            val out: String? = try {
                translator.translate(text, src, dst)
            } catch (e: Exception) {
                null
            }
            main.post { onDone(text, out, dst) }
        }
    }

    private fun onDone(orig: String, out: String?, dst: String) {
        busy = false
        meBtn?.isEnabled = true; theyBtn?.isEnabled = true
        if (panel == null) return
        if (out == null) { setStatus("fail"); return }
        lastOrig = orig; lastTrans = out; lastDst = dst
        draft = ""
        val r = speaker?.say(out, dst)
        statusKey = if (r == SpeakResult.NO_VOICE) "no_voice" else "ready"
        buildMain()
    }

    // ── أدوات التنسيق / Styling helpers ──
    private fun rounded(fill: Int, stroke: Int?): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = u * 2.5f
        if (stroke != null) setStroke(max(1, (u * 0.3f).toInt()), stroke)
    }

    private fun stateBg(normal: Int, pressed: Int, border: Int?): StateListDrawable =
        StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), rounded(cDisabled, null))
            addState(intArrayOf(android.R.attr.state_pressed), rounded(pressed, null))
            addState(intArrayOf(), rounded(normal, border))
        }

    private fun sizeU(v: TextView, units: Float) {
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * units)
    }

    private fun add(parent: LinearLayout, v: View, top: Int = 0, weight: Float = 0f,
                    width: Int = ViewGroup.LayoutParams.MATCH_PARENT) {
        val lp = LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
        lp.topMargin = top
        parent.addView(v, lp)
    }

    private fun label(text: String, sizeUnits: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(color)
            sizeU(this, sizeUnits)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun smallButton(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
            setPadding(px(3.5f), px(2.2f), px(3.5f), px(2.2f))
            setTextColor(cText)
            sizeU(this, 4.2f)
            background = stateBg(cBtn, cBtnPressed, cBtnBorder)
            setOnClickListener { onClick() }
        }

    private fun bigButton(text: String, color: Int, pressed: Int, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
            setPadding(px(2.5f), px(5f), px(2.5f), px(5f))
            setTypeface(typeface, Typeface.BOLD)
            sizeU(this, 4.2f)
            setTextColor(ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(cDisabledText, Color.WHITE)))
            background = stateBg(color, pressed, null)
            setOnClickListener { onClick() }
        }

    companion object {
        const val ACTION_STOP = "app.bivox.android.STOP_BUBBLE"
        private const val CHANNEL = "bubble"
        private const val NOTIF_ID = 1

        // هل الخدمة تعمل الآن؟ / is the service running now?
        @Volatile
        var running = false

        // هل يسمح النظام بالعرض فوق التطبيقات؟ / does the system allow drawing over other apps?
        fun canOverlay(c: Context): Boolean =
            try { Settings.canDrawOverlays(c) } catch (e: Exception) { false }

        fun start(c: Context) {
            val i = Intent(c, BubbleService::class.java)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, BubbleService::class.java))
        }
    }
}
