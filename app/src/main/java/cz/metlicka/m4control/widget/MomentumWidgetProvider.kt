package cz.metlicka.m4control.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import cz.metlicka.m4control.device.DeviceController

/** One resizable widget; layouts per size are chosen in [WidgetUpdater.build]. Also handles the widget lock. */
class MomentumWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        DeviceController.init(context)
        WidgetUpdater.updateAll(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, mgr: AppWidgetManager, id: Int, options: Bundle) {
        DeviceController.init(context)
        mgr.updateAppWidget(id, WidgetUpdater.build(context, mgr, id))
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        DeviceController.init(context)
        DeviceController.store.clearWidgetLocks(ids)
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        when (intent.action) {
            ACTION_TOGGLE_LOCK -> {
                DeviceController.init(context)
                val store = DeviceController.store
                store.setWidgetLocked(id, !store.isWidgetLocked(id))
                WidgetUpdater.clearLockHint(id)
                hideHint.removeCallbacksAndMessages(id)
                WidgetUpdater.update(context, id)
            }
            ACTION_LOCKED_TAP -> {
                // A tap on the veil changes nothing; it only explains why, for a moment. The receiver
                // returns at once: holding it (goAsync) would queue later taps — including the unlock —
                // behind every hint.
                DeviceController.init(context)
                val showing = WidgetUpdater.isLockHintShowing(id)
                WidgetUpdater.showLockHint(id, HINT_MS)
                if (!showing) WidgetUpdater.update(context, id)
                val app = context.applicationContext
                hideHint.removeCallbacksAndMessages(id)
                hideHint.postAtTime({ WidgetUpdater.update(app, id) }, id, SystemClock.uptimeMillis() + HINT_MS + 100)
            }
            else -> super.onReceive(context, intent)
        }
    }

    companion object {
        const val ACTION_TOGGLE_LOCK = "cz.metlicka.m4control.TOGGLE_LOCK"
        const val ACTION_LOCKED_TAP = "cz.metlicka.m4control.LOCKED_TAP"
        private const val HINT_MS = 2500L
        /** Hides the "Locked" hint; one pending hide per widget id (the id is the token). */
        private val hideHint = Handler(Looper.getMainLooper())
    }
}
