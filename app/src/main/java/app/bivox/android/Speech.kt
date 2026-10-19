package app.bivox.android

import android.content.Context
import android.os.Handler
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.util.concurrent.CopyOnWriteArrayList

// نسخة نطق واحدة مشتركة بين الشاشة والفقاعة، تُنشأ عند أول حاجة لها، وتُغلق حين ينتهي آخر مستعمل
// One speech instance shared by the screen and the bubble, created on first need and closed when the last user is gone
object Speech {
    private var sp: Speaker? = null
    private var users = 0
    private val readyL = CopyOnWriteArrayList<() -> Unit>()
    private val failL = CopyOnWriteArrayList<(String) -> Unit>()

    @Synchronized
    fun acquire() {
        users++
    }

    @Synchronized
    fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) {
            sp?.shutdown()
            sp = null
        }
    }

    // يُنشئ المحركات عند أول طلب / creates the engines on the first request
    @Synchronized
    fun get(ctx: Context): Speaker {
        val cur = sp
        if (cur != null) return cur
        val s = Speaker(ctx.applicationContext,
            onReady = { for (l in readyL) l() },
            onFail = { m -> for (l in failL) l(m) })
        sp = s
        return s
    }

    // ينطق؛ إن كانت المحركات ما زالت تُهيَّأ (أول مرة) ينتظر لحظة ويعيد المحاولة مرة واحدة
    // Speaks; if the engines are still starting (first time) waits a moment and retries once
    fun say(ctx: Context, text: String, lang: String, main: Handler, onResult: (SpeakResult) -> Unit) {
        val r = get(ctx).say(text, lang)
        if (r == SpeakResult.NO_ENGINE) {
            main.postDelayed({ onResult(get(ctx).say(text, lang)) }, 900)
        } else {
            onResult(r)
        }
    }

    // بلا إنشاء: هل جاهز؟ / without creating: is it ready?
    @Synchronized
    fun peek(): Speaker? = sp

    fun listen(onReady: () -> Unit, onFail: (String) -> Unit) {
        readyL.add(onReady)
        failL.add(onFail)
    }

    fun unlisten(onReady: () -> Unit, onFail: (String) -> Unit) {
        readyL.remove(onReady)
        failL.remove(onFail)
    }
}

// فحص سريع لوجود اتصال بالإنترنت / quick check for an internet connection
object Net {
    fun online(c: Context): Boolean = try {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork
        val caps = if (n != null) cm.getNetworkCapabilities(n) else null
        caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (e: Exception) {
        true   // لا نمنع المحاولة إن تعذّر الفحص / don't block the attempt if the check fails
    }
}
