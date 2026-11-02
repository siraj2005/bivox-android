package app.bivox.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build

// أنواع إشعارات BiVox: لكل نوع قناته وأيقونته وشكله. لإضافة نوع جديد: أضف قيمة هنا وحالة في build().
// BiVox notification kinds: each has its own channel, icon and look. To add a kind: add a value here and a case in build().
enum class NKind(val channel: String, val nameKey: String, val importance: Int) {
    BUBBLE("bubble", "chan_bubble", NotificationManager.IMPORTANCE_LOW),       // الفقاعة تعمل: الشعار أحادي اللون فقط / bubble running: monochrome logo only
    UPDATE("update", "chan_update", NotificationManager.IMPORTANCE_DEFAULT),   // تحديث متاح: الشعار أحادي اللون + الشعار بألوانه + لون BiVox / update available: monochrome logo + the colour logo + BiVox colour
}

object Notif {
    private val BRAND = Color.parseColor("#5B8DEF")
    const val ID_UPDATE = 2

    fun channels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val tx = Ui.TEXTS.getValue(Prefs.uiLang(ctx))
        for (k in NKind.values()) {
            nm.createNotificationChannel(NotificationChannel(k.channel, tx.getValue(k.nameKey), k.importance))
        }
    }

    // يبني إشعارًا من نوع معيّن / builds a notification of a given kind
    @Suppress("DEPRECATION")
    fun build(ctx: Context, kind: NKind, title: String, text: String, open: PendingIntent?): Notification.Builder {
        channels(ctx)
        val nb = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, kind.channel) else Notification.Builder(ctx)
        // الأيقونة الصغيرة أحادية اللون دائمًا (قيد أندرويد) / the small icon is always monochrome (an Android rule)
        nb.setSmallIcon(R.drawable.ic_stat_bivox)
            .setContentTitle(title)
            .setContentText(text)
        if (open != null) nb.setContentIntent(open)
        when (kind) {
            NKind.BUBBLE -> nb.setOngoing(true)
            NKind.UPDATE -> {
                nb.setColor(BRAND)
                    .setAutoCancel(true)
                try {
                    nb.setLargeIcon(BitmapFactory.decodeResource(ctx.resources, R.drawable.bivox_logo))
                } catch (e: Exception) { }
            }
        }
        return nb
    }

    // إشعار «تحديث متاح» / the "update available" notification
    fun postUpdate(ctx: Context, info: UpdateInfo) {
        try {
            val tx = Ui.TEXTS.getValue(Prefs.uiLang(ctx))
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val open = PendingIntent.getActivity(ctx, 2, Intent(ctx, MainActivity::class.java), flags)
            val n = build(ctx, NKind.UPDATE, tx.getValue("update_title"),
                tx.getValue("update_found").format(info.name, info.code), open).build()
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(ID_UPDATE, n)
            Dbg.log("upd", "notification posted for build " + info.code)
        } catch (e: Exception) {
            Dbg.log("upd", "notification failed: " + e.javaClass.simpleName)
        }
    }

    fun cancelUpdate(ctx: Context) {
        try {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ID_UPDATE)
        } catch (e: Exception) { }
    }
}

// فحص التحديث في الخلفية (تستدعيه خدمة الفقاعة): مرة كل 5 ساعات على الأكثر، وإشعار واحد لكل إصدار
// Background update check (called by the bubble service): at most once every 5 hours, one notification per build
object UpdateWatcher {
    private const val EVERY_MS = 5 * 3600 * 1000L

    fun due(ctx: Context): Boolean = System.currentTimeMillis() - Prefs.lastBgCheck(ctx) >= EVERY_MS

    // يُستدعى من أي خيط؛ يشغّل الفحص على خيط خلفي إن حان الوقت وكان هناك اتصال
    // Callable from any thread; runs the check on a background thread when due and online
    fun checkIfDue(ctx: Context) {
        val app = ctx.applicationContext
        if (!due(app) || !Net.online(app)) return
        Prefs.setLastBgCheck(app, System.currentTimeMillis())
        Thread {
            try {
                val info = Updater.check(BuildConfig.VERSION_CODE)
                Dbg.log("upd", "background check: " + (if (info != null) "build " + info.code + " available" else "up to date"))
                if (info != null && Prefs.notifiedBuild(app) != info.code) {
                    Prefs.setNotifiedBuild(app, info.code)
                    Notif.postUpdate(app, info)
                }
            } catch (e: Exception) {
                Dbg.log("upd", "background check failed: " + e.javaClass.simpleName)
            }
        }.start()
    }
}
