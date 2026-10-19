package app.bivox.android

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

// زر في القائمة السريعة (لوحة الإشعارات) يشغّل الفقاعة ويوقفها بضغطة، يدويًا
// A Quick Settings tile (notification shade) that starts/stops the bubble with one tap, manually
class BubbleTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh(BubbleService.running)
    }

    override fun onClick() {
        super.onClick()
        if (BubbleService.running) {
            Prefs.setBubbleOn(this, false)
            BubbleService.stop(this)
            refresh(false)
            return
        }
        // دون إذن «العرض فوق التطبيقات» نفتح التطبيق ليمنحه المستخدم / without the overlay permission, open the app so the user can grant it
        if (!BubbleService.canOverlay(this)) {
            openApp()
            return
        }
        try {
            Prefs.setBubbleOn(this, true)
            BubbleService.start(this)
            refresh(true)
        } catch (e: Exception) {
            Prefs.setBubbleOn(this, false)
            openApp()
        }
    }

    private fun refresh(on: Boolean) {
        val t = qsTile ?: return
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.updateTile()
    }

    @Suppress("DEPRECATION")
    private fun openApp() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
        } else {
            startActivityAndCollapse(i)
        }
    }
}
