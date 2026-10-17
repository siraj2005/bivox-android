package app.bivox.android

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

// معلومات إصدار منشور على GitHub / info about a release published on GitHub
data class UpdateInfo(
    val code: Int,
    val name: String,
    val notes: String,
    val apkUrl: String,
    val size: Long,
)

// المحدّث: يسأل GitHub Releases عن آخر إصدار، ينزّله، ثم يسلّمه لمثبّت النظام
// Updater: asks GitHub Releases for the latest build, downloads it, hands it to the system installer
object Updater {

    // وسم كل إصدار منشور هو b + رقم البناء (مثل b12) ويطابق versionCode
    // Every published release is tagged b + build number (e.g. b12) and matches versionCode
    private const val LATEST = "https://api.github.com/repos/siraj2005/bivox-android/releases/latest"
    private val TAG = Regex("^b(\\d+)$")

    // يجب استدعاؤها من خيط خلفي / call from a background thread
    // يعيد null إن لم يوجد إصدار أحدث / returns null when nothing newer exists
    fun check(currentCode: Int): UpdateInfo? {
        val c = URL(LATEST).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.setRequestProperty("User-Agent", "BiVox-Android")
            c.setRequestProperty("Accept", "application/vnd.github+json")
            if (c.responseCode == 404) return null          // لا إصدارات بعد / no releases yet
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            val j = JSONObject(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            val tag = j.getString("tag_name")
            val code = TAG.find(tag)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            if (code <= currentCode) return null
            val assets = j.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) {
                    return UpdateInfo(
                        code = code,
                        name = j.optString("name", tag),
                        notes = j.optString("body", ""),
                        apkUrl = a.getString("browser_download_url"),
                        size = a.optLong("size", -1L),
                    )
                }
            }
            return null
        } finally {
            c.disconnect()
        }
    }

    // ينزّل الملف إلى ذاكرة التطبيق المؤقتة ويبلغ عن النسبة
    // Downloads to the app's cache and reports percent progress
    fun download(ctx: Context, info: UpdateInfo, onProgress: (Int) -> Unit): File {
        val out = File(ctx.cacheDir, "update.apk")
        if (out.exists()) out.delete()
        val c = URL(info.apkUrl).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "BiVox-Android")
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            val total = if (c.contentLengthLong > 0) c.contentLengthLong else info.size
            c.inputStream.use { ins ->
                out.outputStream().use { os ->
                    val buf = ByteArray(32768)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        os.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val p = (done * 100 / total).toInt()
                            if (p != last) { last = p; onProgress(p) }
                        }
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        return out
    }

    // يسلّم الملف لمثبّت النظام؛ النتيجة تصل إلى InstallReceiver
    // Hands the file to the system installer; the outcome arrives at InstallReceiver
    fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setSize(apk.length())
        if (Build.VERSION.SDK_INT >= 31) {
            // أندرويد 12+: قد يُنفَّذ التحديث دون سؤال إن سمح النظام / Android 12+: may update without a prompt if the system allows
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { ins ->
                session.openWrite("bivox.apk", 0, apk.length()).use { os ->
                    ins.copyTo(os)
                    session.fsync(os)
                }
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(
                ctx, id, Intent(ctx, InstallReceiver::class.java), flags)
            session.commit(pending.intentSender)
        }
    }
}
