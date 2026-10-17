package app.bivox.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast

// يستقبل نتيجة التثبيت من النظام: يفتح شاشة التأكيد، أو يعرض سبب الفشل
// Receives the install result from the system: opens the confirm screen, or shows why it failed
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // النظام يطلب تأكيدك / the system wants your confirmation
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(confirm)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // سيُعاد تشغيل التطبيق بالإصدار الجديد / the app restarts on the new version
            }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "install failed"
                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
            }
        }
    }
}
