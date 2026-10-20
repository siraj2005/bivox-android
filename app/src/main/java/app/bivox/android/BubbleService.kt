package app.bivox.android

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
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
    private val cCardT = Color.argb(0xB0, 0x1C, 0x1C, 0x24)
    private val cPrimaryT = Color.argb(0xCC, 0x5B, 0x8D, 0xEF)
    private val cAccentT = Color.argb(0xCC, 0x3F, 0xA3, 0x7A)
    private val cCardBorder = Color.parseColor("#2B2B36")
    private val cField = Color.argb(0xB0, 0x23, 0x23, 0x2D)
    private val cFieldBorder = Color.parseColor("#34343F")
    private val cBtn = Color.argb(0x99, 0x2A, 0x2A, 0x36)
    private val cBtnBorder = Color.parseColor("#3A3A47")
    private val cBtnPressed = Color.argb(0xCC, 0x34, 0x34, 0x4A)
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
    // مستمعا النطق المشترك / listeners for the shared speech
    private val voiceReady: () -> Unit = { main.post { refreshStatus() } }
    private val voiceFail: (String) -> Unit = { _ -> main.post { if (panel != null) setStatus("speak_fail") } }
    private var screenReceiver: BroadcastReceiver? = null
    private lateinit var env: Env
    private lateinit var hub: TranslateHub
    private lateinit var result: ResultOverlay
    private lateinit var pick: PickOverlay
    private lateinit var lens: LensOverlay
    private var resultToken = 0

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
    private var panelLp: WindowManager.LayoutParams? = null
    private var panelWidth = 0
    private var input: EditText? = null
    private var statusLbl: TextView? = null
    private var meBtn: Button? = null
    private var theyBtn: Button? = null
    private var autoBtn: Button? = null
    private var myIdx = 0
    private var theirIdx = 1
    private var lastOrig = ""
    private var lastTrans = ""
    private var lastDst = "en"
    private var draft = ""
    private var busy = false
    private var statusKey = "ready"

    // الميكروفون / the microphone
    private var micSession: MicSession? = null
    private var micMine = true            // الاستماع بلغتي (true) أم بلغة الطرف الآخر / listening in my language (true) or the other's
    private var micMyBtn: Button? = null
    private var micTheirBtn: Button? = null
    private var fgNotif: Notification? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        env = Env(this)
        hub = TranslateHub(translator, main)
        result = ResultOverlay(env)
        pick = PickOverlay(env)
        lens = LensOverlay(env, hub)
        // أحداث الشاشة من خدمة الوصول: التمرير يمسح الترجمات القديمة، والعدسة تعيد القراءة
        // Screen events from the accessibility service: scrolling clears old translations, the lens re-reads
        BivoxAccessibilityService.listener = { type ->
            if ((type == AccessibilityEvent.TYPE_VIEW_SCROLLED || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) &&
                result.showing && SystemClock.uptimeMillis() - result.shownAt > 800) {
                result.close()
            }
            lens.onScreenEvent(type)
        }
        Speech.acquire()
        Speech.listen(voiceReady, voiceFail)
        registerScreenReceiver()
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
        if (intent?.action == ACTION_RELOAD) {
            // تغيّر الحجم/الشفافية في الإعدادات: نعيد بناء الفقاعة / size/opacity changed in settings: rebuild the bubble
            closePanel()
            removeBubble()
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
        pick.close(); result.close(); lens.close()
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
        stopMic(true)
        BivoxAccessibilityService.listener = null
        lens.close(); pick.close(); result.close()
        hub.shutdown()
        env.shutdown()
        closePanel()
        removeBubble()
        Speech.unlisten(voiceReady, voiceFail)
        Speech.release()
        screenReceiver?.let { try { unregisterReceiver(it) } catch (e: Exception) { } }
        screenReceiver = null
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
        fgNotif = nb.build()
        applyFgType(false)
    }

    // نوع الخدمة الأمامية: «استخدام خاص» دائمًا، ونضيف «ميكروفون» أثناء الاستماع فقط
    // Foreground type: "special use" always, plus "microphone" only while listening
    private fun applyFgType(mic: Boolean) {
        val n = fgNotif ?: return
        if (Build.VERSION.SDK_INT >= 34) {
            var ty = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (mic) ty = ty or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(NOTIF_ID, n, ty)
        } else if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIF_ID, n, if (mic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
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
        val f = when (Prefs.bubbleScale(this)) { 0 -> 11f; 2 -> 17f; else -> 14f }
        bubbleSize = (u * f).toInt()
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
        // فقاعة دائرية: الشعار مكبّر ليملأ الدائرة كلها ويُقصّ على شكلها، وحولها إطار رفيع
        // Round bubble: the logo is enlarged to fill the whole circle and clipped to its shape, with a thin ring around it
        val ring = max(1, (u * 0.45f).toInt())
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(cCard)
            setStroke(ring, cPrimary)
        }
        view.background = bg
        view.alpha = when (Prefs.bubbleAlpha(this)) { 1 -> 0.75f; 2 -> 0.5f; else -> 0.95f }
        val img = ImageView(this)
        img.setImageResource(R.drawable.bivox_logo)
        img.contentDescription = "BiVox"
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        img.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setOval(0, 0, v.width, v.height)
            }
        }
        img.clipToOutline = true
        view.addView(img, FrameLayout.LayoutParams(bubbleSize - 2 * ring, bubbleSize - 2 * ring).apply {
            gravity = Gravity.CENTER
        })

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
                        if (moved) snapToEdge(v, lp) else onBubbleTap()
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
            setColor(Color.argb(0xC0, 0x13, 0x13, 0x18))
            cornerRadius = u * 3f
            setStroke(max(1, (u * 0.35f).toInt()), Color.argb(0xAA, 0x5B, 0x8D, 0xEF))
        }
        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        root.addView(scroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        val m = px(2.4f)
        body.setPadding(m, m, m, m)
        scroll.addView(body, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panelBody = body

        val width = min((s.first * 0.88f).toInt(), px(100f))
        // نافذة قابلة للتركيز كي تعمل لوحة المفاتيح؛ لا FLAG_ALT_FOCUSABLE_IM
        // Focusable window so the keyboard works; no FLAG_ALT_FOCUSABLE_IM
        val lp = WindowManager.LayoutParams(
            width, ViewGroup.LayoutParams.WRAP_CONTENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT)
        // موضع مطلق قابل للسحب (محفوظ بين الفتحات) / absolute draggable position (saved between openings)
        lp.gravity = Gravity.TOP or Gravity.LEFT
        panelWidth = width
        val spx = Prefs.panelX(this)
        val spy = Prefs.panelY(this)
        lp.x = if (spx < 0) (s.first - width) / 2 else spx.coerceIn(0, max(0, s.first - width))
        lp.y = if (spy < 0) px(4f) else spy.coerceIn(0, max(0, s.second - px(16f)))
        panelLp = lp
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
        // نهيّئ النطق أثناء كتابة المستخدم ليكون جاهزًا / warm up speech while the user types so it is ready
        if (Prefs.autoSpeak(this)) Speech.get(this)
    }

    private fun closePanel() {
        stopMic(true)
        hideKeyboard()
        val p = panel
        if (p != null) {
            try { wm.removeView(p) } catch (e: Exception) { }
        }
        panel = null
        panelBody = null
        panelLp = null
        input = null
        statusLbl = null
        meBtn = null
        theyBtn = null
        autoBtn = null
        micMyBtn = null
        micTheirBtn = null
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
        // «≡» مقبض السحب: اسحب الرأس لتحريك اللوحة / "≡" is the drag handle: drag the header to move the panel
        val handle = label("≡", 6f, cMuted)
        val hLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        hLp.marginEnd = gap
        head.addView(handle, hLp)
        head.setOnTouchListener(dragPanelListener())
        val title = label("BiVox", 4.8f, cText, true)
        title.textDirection = View.TEXT_DIRECTION_LTR
        title.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        add(head, title, weight = 1f, width = 0)
        head.addView(smallButton("✕") { closePanel() }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        add(body, head)

        // صف الإجراءات: اختيار نص / الصفحة كلها / عدسة (تحتاج خدمة الوصول)
        // Actions row: select text / whole page / lens (needs the accessibility service)
        val acts = LinearLayout(this)
        acts.orientation = LinearLayout.HORIZONTAL
        val actNames = listOf("act_pick", "act_page", "act_lens")
        val actCalls = listOf<() -> Unit>({ startPick() }, { translatePage() }, { toggleLens() })
        for (i in actNames.indices) {
            val b = smallButton(t.getValue(actNames[i])) { actCalls[i]() }
            val alp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (i > 0) alp.marginStart = gap / 2
            acts.addView(b, alp)
        }
        add(body, acts, gap)

        // اللغتان + التبديل / the two languages + swap
        val myPicker = picker({ myIdx }, { myIdx = it })
        val theirPicker = picker({ theirIdx }, { theirIdx = it })
        val swap = smallButton(t.getValue("swap")) {
            val a = myIdx; myIdx = theirIdx; theirIdx = a
            Prefs.setLangs(this, myIdx, theirIdx)
            myPicker.text = Langs.NAMES.getValue(Langs.CODES[myIdx]) + " ▾"
            theirPicker.text = Langs.NAMES.getValue(Langs.CODES[theirIdx]) + " ▾"
            updateMicButtons()
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
            sizeU(this, 3.8f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(false); maxLines = 3
            minHeight = px(8.5f)
            setPadding(px(2.2f), px(1.4f), px(2.2f), px(1.4f))
            background = rounded(cField, cFieldBorder)
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            imeOptions = EditorInfo.IME_ACTION_SEND
            setText(draft)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) { goAuto(); true } else false
            }
        }
        input = et
        val inRow = LinearLayout(this)
        inRow.orientation = LinearLayout.HORIZONTAL
        inRow.gravity = Gravity.CENTER_VERTICAL
        add(inRow, et, weight = 1f, width = 0)
        val clrLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        clrLp.marginStart = gap / 2
        inRow.addView(smallButton("⌫") { input?.setText(""); draft = "" }, clrLp)
        // زرا الميكروفون: 🎤 بلغتي و🎤 بلغة الطرف الآخر (يُخفيان إن لم يوجد محرك تعرف)
        // Two mic buttons: 🎤 in my language and 🎤 in the other person's (hidden if no recognition engine)
        micMyBtn = null; micTheirBtn = null
        if (Mic.available(this)) {
            val mb1 = smallButton("") { startMic(true) }
            val mb2 = smallButton("") { startMic(false) }
            micMyBtn = mb1; micTheirBtn = mb2
            val l1 = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            l1.marginStart = gap / 2
            inRow.addView(mb1, l1)
            val l2 = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            l2.marginStart = gap / 2
            inRow.addView(mb2, l2)
            updateMicButtons()
        }
        add(body, inRow, gap)

        // الزران الكبيران / the two big buttons
        val me = bigButton(t.getValue("btn_me"), cPrimaryT, cPrimaryPressed) { go(true) }
        val they = bigButton(t.getValue("btn_they"), cAccentT, cAccentPressed) { go(false) }
        val auto = bigButton(t.getValue("btn_auto"), cBtn, cBtnPressed) { goAuto() }
        meBtn = me; theyBtn = they; autoBtn = auto
        me.isEnabled = !busy; they.isEnabled = !busy; auto.isEnabled = !busy
        val btns = LinearLayout(this)
        btns.orientation = LinearLayout.HORIZONTAL
        add(btns, me, weight = 1f, width = 0)
        val theyLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        theyLp.marginStart = gap / 2
        btns.addView(they, theyLp)
        val autoLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        autoLp.marginStart = gap / 2
        btns.addView(auto, autoLp)
        add(body, btns, gap)

        // النتيجة / result
        if (lastTrans.isNotEmpty()) {
            val card = LinearLayout(this)
            card.orientation = LinearLayout.VERTICAL
            val cp = px(2.5f)
            card.setPadding(cp, cp, cp, cp)
            card.background = rounded(cCardT, cCardBorder)
            val o = label(lastOrig, 3.8f, cOrig)
            val tr = label(lastTrans, 5.4f, cText, true)
            for (v in listOf(o, tr)) {
                v.setTextIsSelectable(true)
                v.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                v.textAlignment = View.TEXT_ALIGNMENT_TEXT_START
            }
            add(card, o)
            add(card, tr, px(1.5f))
            val actRow = LinearLayout(this)
            actRow.orientation = LinearLayout.HORIZONTAL
            actRow.addView(smallButton(t.getValue("speak")) {
                Speech.say(this, lastTrans, lastDst, main) { r ->
                    if (r == SpeakResult.NO_VOICE || r == SpeakResult.NO_ENGINE) setStatus("no_voice")
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val cpLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            cpLp.marginStart = gap
            actRow.addView(smallButton(t.getValue("copy")) {
                try {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("BiVox", lastTrans))
                    setStatus("copied")
                } catch (e: Exception) { }
            }, cpLp)
            add(card, actRow, px(1.5f), width = ViewGroup.LayoutParams.WRAP_CONTENT)
            add(body, card, gap)
        }

        val st = label("", 3.2f, cMuted)
        statusLbl = st
        add(body, st, gap)
        refreshStatus()
        focusInput()
    }

    // يفتح لوحة المفاتيح تلقائيًا ليكتب المستخدم فورًا / opens the keyboard automatically so the user can type at once
    private fun focusInput() {
        main.postDelayed({
            val v = input ?: return@postDelayed
            if (panel == null) return@postDelayed
            try {
                v.requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
            } catch (e: Exception) { }
        }, 250)
    }

    // سحب اللوحة من رأسها وحفظ موضعها / drags the panel by its header and saves its position
    private fun dragPanelListener(): View.OnTouchListener = object : View.OnTouchListener {
        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val lp = panelLp ?: return false
            val p = panel ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    startX = lp.x; startY = lp.y
                    downX = e.rawX; downY = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    val sc = screen()
                    lp.x = (startX + e.rawX - downX).toInt().coerceIn(0, max(0, sc.first - panelWidth))
                    lp.y = (startY + e.rawY - downY).toInt().coerceIn(0, max(0, sc.second - px(16f)))
                    try { wm.updateViewLayout(p, lp) } catch (ex: Exception) { }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    Prefs.setPanelPos(this@BubbleService, lp.x, lp.y)
            }
            return true
        }
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
            sizeU(this, 3.8f)
            gravity = Gravity.CENTER_VERTICAL
            minHeight = px(8.5f)
            setPadding(px(2.2f), px(1.4f), px(2.2f), px(1.4f))
            background = rounded(cField, cFieldBorder)
            isClickable = true
            setOnClickListener { buildList(getIdx(), setIdx) }
        }

    // ── الحالة والترجمة / Status and translation ──
    private fun refreshStatus() {
        val s = statusLbl ?: return
        var txt = t.getValue(statusKey)
        if (statusKey == "ready" && Speech.peek()?.available == false) txt += "  " + t.getValue("speak_off")
        s.text = txt
    }

    private fun setStatus(key: String) {
        statusKey = key
        refreshStatus()
    }

    // ── الميكروفون / Microphone ──
    private fun setInputText(s: String) {
        val e = input ?: return
        e.setText(s)
        e.setSelection(e.text.length)
    }

    private fun updateMicButtons() {
        val a = micMyBtn ?: return
        val b = micTheirBtn ?: return
        val on = micSession != null
        a.text = if (on && micMine) "⏹" else "🎤 " + Langs.CODES[myIdx].uppercase()
        b.text = if (on && !micMine) "⏹" else "🎤 " + Langs.CODES[theirIdx].uppercase()
        a.isEnabled = !on || micMine
        b.isEnabled = !on || !micMine
        a.alpha = if (a.isEnabled) 1f else 0.4f
        b.alpha = if (b.isEnabled) 1f else 0.4f
    }

    // الضغط يبدأ الاستماع بتلك اللغة، والضغط ثانية ينهيه ويترجم ما سُمع
    // Tapping starts listening in that language; tapping again ends it and translates what was heard
    private fun startMic(mine: Boolean) {
        val cur = micSession
        if (cur != null) { cur.stop(); return }
        if (busy) return
        if (myIdx == theirIdx) { setStatus("same"); return }
        if (!Mic.granted(this)) {
            // الإذن يُطلب عبر نشاط شفاف ثم نكمل تلقائيًا / the permission is asked via a transparent activity, then we continue
            val opened = MicPermissionActivity.request(this) { g ->
                if (panel != null) {
                    if (g) startMic(mine) else setStatus("mic_denied")
                }
            }
            if (!opened) setStatus("mic_denied")
            return
        }
        micMine = mine
        val code = Langs.CODES[if (mine) myIdx else theirIdx]
        hideKeyboard()
        // نضيف نوع «ميكروفون» للخدمة الأمامية؛ إن رفضه النظام نكمل بدونه
        // Add the "microphone" type to the foreground service; if the system refuses, carry on without it
        try { applyFgType(true) } catch (e: Exception) { }
        val sess = MicSession(this,
            { p -> if (micSession != null) setInputText(p) },
            { text ->
                micDone()
                setInputText(text)
                go(mine)
            },
            { key ->
                micDone()
                if (panel != null) setStatus(key)
            })
        micSession = sess
        if (!sess.start(code)) {
            micDone()
            setStatus("mic_err")
            return
        }
        setStatus("mic_listening")
        updateMicButtons()
    }

    // انتهت الجلسة (بنتيجة أو خطأ) / the session ended (with a result or an error)
    private fun micDone() {
        micSession = null
        if (running) { try { applyFgType(false) } catch (e: Exception) { } }
        updateMicButtons()
    }

    // إلغاء الاستماع بلا نتيجة (إغلاق اللوحة أو إيقاف الخدمة) / cancel listening with no result (panel closed or service stopped)
    private fun stopMic(cancel: Boolean) {
        val s = micSession ?: return
        if (cancel) s.cancel() else s.stop()
        micDone()
    }

    // اتجاه يدوي: أنا ➜ هو أو هو ➜ أنا / manual direction: me ➜ them or them ➜ me
    private fun go(meToThem: Boolean) = translateWith { text, my, their ->
        if (meToThem) Pair(translator.translate(text, my, their), their)
        else Pair(translator.translate(text, their, my), my)
    }

    // تلقائي: يكتشف لغة النص ويترجمه إلى اللغة الأخرى / auto: detects the text's language and translates to the other one
    private fun goAuto() = translateWith { text, my, their -> translator.translateSmart(text, my, their) }

    private fun setBusyButtons(b: Boolean) {
        meBtn?.isEnabled = !b; theyBtn?.isEnabled = !b; autoBtn?.isEnabled = !b
    }

    private fun translateWith(job: (String, String, String) -> Pair<String, String>) {
        if (busy) return
        val text = input?.text?.toString()?.trim() ?: return
        if (text.isEmpty()) { setStatus("empty"); return }
        if (myIdx == theirIdx) { setStatus("same"); return }
        if (!Net.online(this)) { setStatus("offline"); return }
        val my = Langs.CODES[myIdx]
        val their = Langs.CODES[theirIdx]
        busy = true
        setBusyButtons(true)
        setStatus("working")
        io.execute {
            val res: Pair<String, String>? = try {
                job(text, my, their)
            } catch (e: Exception) {
                null
            }
            main.post { onDone(text, res?.first, res?.second ?: their) }
        }
    }

    private fun onDone(orig: String, out: String?, dst: String) {
        busy = false
        setBusyButtons(false)
        if (panel == null) return
        if (out == null) { setStatus("fail"); return }
        lastOrig = orig; lastTrans = out; lastDst = dst
        draft = ""
        statusKey = "ready"
        if (Prefs.autoSpeak(this)) {
            Speech.say(this, out, dst, main) { r -> if (r == SpeakResult.NO_VOICE) setStatus("no_voice") }
        }
        buildMain()
    }

    // انطفاء الشاشة: نوقف قراءة العدسة ونمسح الترجمات لتوفير البطارية
    // Screen off: pause the lens reading and clear translations to save battery
    private fun registerScreenReceiver() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                when (i?.action) {
                    Intent.ACTION_SCREEN_OFF -> { lens.pause(); result.close() }
                    Intent.ACTION_SCREEN_ON -> lens.resume()
                }
            }
        }
        try {
            val f = IntentFilter()
            f.addAction(Intent.ACTION_SCREEN_OFF)
            f.addAction(Intent.ACTION_SCREEN_ON)
            registerReceiver(r, f)
            screenReceiver = r
        } catch (e: Exception) { }
    }

    // ── الترجمة على الشاشة (تحتاج خدمة الوصول) / On-screen translation (needs the accessibility service) ──
    private fun onBubbleTap() {
        // الضغط والترجمات ظاهرة: يمسحها أولًا / tapping while translations are shown clears them first
        if (result.showing) { result.close(); return }
        openPanel()
    }

    private fun toast(msg: String) {
        try { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() } catch (e: Exception) { }
    }

    // إن لم تُفعَّل الخدمة نشرح ونفتح إعدادات إمكانية الوصول / if the service is off, explain and open accessibility settings
    private fun needReader(): Boolean {
        if (BivoxAccessibilityService.instance != null) return false
        toast(t.getValue("a11y_need"))
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { }
        closePanel()
        return true
    }

    // نغلق اللوحة (تأخذ التركيز) وننتظر لحظة ليعود التركيز للتطبيق تحتها قبل قراءة الشاشة
    // Close the panel (it holds focus) and wait a moment so focus returns to the app below before reading the screen
    private fun readScreen(onBoxes: (List<TextBox>) -> Unit) {
        closePanel()
        main.postDelayed({
            val reader = BivoxAccessibilityService.instance
            if (reader == null) return@postDelayed
            env.readPool.execute {
                val boxes: List<TextBox> = try { reader.collect() } catch (e: Exception) { emptyList() }
                main.post {
                    if (boxes.isEmpty()) toast(t.getValue("pick_none")) else onBoxes(boxes)
                }
            }
        }, 300)
    }

    private fun startPick() {
        if (needReader()) return
        result.close()
        readScreen { boxes ->
            pick.show(boxes, t.getValue("pick_hint"), { sel ->
                pick.close()
                showResults(sel.map { Item(it.text, it.rect) }, false)
            }, { pick.close() })
        }
    }

    private fun translatePage() {
        if (needReader()) return
        result.close()
        readScreen { boxes ->
            showResults(boxes.take(80).map { Item(it.text, it.rect) }, true)
        }
    }

    private fun toggleLens() {
        if (lens.showing) { lens.close(); closePanel(); return }
        if (needReader()) return
        closePanel()
        lens.show()
    }

    // يعرض الترجمات فوق النصوص ويحدّثها تباعًا عند وصولها / shows translations over the texts and updates them as they arrive
    private fun showResults(items: List<Item>, page: Boolean) {
        if (items.isEmpty()) return
        val dst = Langs.CODES[Prefs.my(this)]
        val fallback = Langs.CODES[Prefs.their(this)]
        val token = ++resultToken
        val label = t.getValue("page_working")
        var left = items.size
        var failed = 0
        result.show(items, if (page) "$label 0/${items.size}" else label)
        for (it in items) {
            hub.request(it.orig, dst, fallback) { r ->
                if (token == resultToken) {
                    if (r != null) it.trans = r else failed++
                    left--
                    if (left <= 0) {
                        if (failed == items.size) {
                            result.close()
                            toast(t.getValue("fail"))
                        } else {
                            result.update(null)
                        }
                    } else {
                        result.update("$label ${items.size - left}/${items.size}")
                    }
                }
            }
        }
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
            setPadding(px(2.6f), px(1.5f), px(2.6f), px(1.5f))
            setTextColor(cText)
            sizeU(this, 3.6f)
            background = stateBg(cBtn, cBtnPressed, cBtnBorder)
            setOnClickListener { onClick() }
        }

    private fun bigButton(text: String, color: Int, pressed: Int, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
            setPadding(px(2f), px(3.2f), px(2f), px(3.2f))
            setTypeface(typeface, Typeface.BOLD)
            sizeU(this, 3.8f)
            setTextColor(ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(cDisabledText, Color.WHITE)))
            background = stateBg(color, pressed, null)
            setOnClickListener { onClick() }
        }

    companion object {
        const val ACTION_STOP = "app.bivox.android.STOP_BUBBLE"
        const val ACTION_RELOAD = "app.bivox.android.RELOAD_BUBBLE"
        private const val CHANNEL = "bubble"
        private const val NOTIF_ID = 1

        // هل الخدمة تعمل الآن؟ / is the service running now?
        @Volatile
        var running = false

        // هل يسمح النظام بالعرض فوق التطبيقات؟ / does the system allow drawing over other apps?
        fun canOverlay(c: Context): Boolean =
            try { Settings.canDrawOverlays(c) } catch (e: Exception) { false }

        fun start(c: Context) = launch(c, null)

        // إعادة بناء الفقاعة بعد تغيير شكلها في الإعدادات / rebuild the bubble after its look changed in settings
        fun reload(c: Context) {
            if (running) launch(c, ACTION_RELOAD)
        }

        private fun launch(c: Context, action: String?) {
            val i = Intent(c, BubbleService::class.java)
            if (action != null) i.action = action
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, BubbleService::class.java))
        }
    }
}
