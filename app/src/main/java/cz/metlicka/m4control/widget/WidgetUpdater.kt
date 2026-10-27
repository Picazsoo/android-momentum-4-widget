package cz.metlicka.m4control.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.ColorRes
import androidx.annotation.LayoutRes
import cz.metlicka.m4control.R
import cz.metlicka.m4control.device.DeviceController
import cz.metlicka.m4control.device.EqModel
import cz.metlicka.m4control.device.NoiseControl
import cz.metlicka.m4control.device.NoiseMode
import cz.metlicka.m4control.gaia.Codec
import cz.metlicka.m4control.service.CommandService
import cz.metlicka.m4control.state.AppState
import cz.metlicka.m4control.state.LinkState
import cz.metlicka.m4control.ui.MainActivity
import cz.metlicka.m4control.ui.agoText

/** Renders DeviceController state into the one resizable widget (plan: Widget design). */
object WidgetUpdater {
    private val CELLS = intArrayOf(R.id.cell_0, R.id.cell_1, R.id.cell_2, R.id.cell_3, R.id.cell_4, R.id.cell_5, R.id.cell_6)
    private val SEG_L = intArrayOf(R.id.seg_l_0, R.id.seg_l_1, R.id.seg_l_2, R.id.seg_l_3, R.id.seg_l_4, R.id.seg_l_5, R.id.seg_l_6)
    private val SEG_R = intArrayOf(R.id.seg_r_0, R.id.seg_r_1, R.id.seg_r_2, R.id.seg_r_3, R.id.seg_r_4, R.id.seg_r_5, R.id.seg_r_6)
    private val THUMBS = intArrayOf(R.id.thumb_0, R.id.thumb_1, R.id.thumb_2, R.id.thumb_3, R.id.thumb_4, R.id.thumb_5, R.id.thumb_6)
    private val FAVS = intArrayOf(R.id.fav_0, R.id.fav_1, R.id.fav_2, R.id.fav_3)
    private val FAV_DIVIDERS = intArrayOf(R.id.fav_div_1, R.id.fav_div_2, R.id.fav_div_3)

    private const val CENTER = 3

    fun updateAll(context: Context) {
        val mgr = AppWidgetManager.getInstance(context)
        val ids = mgr.getAppWidgetIds(ComponentName(context, MomentumWidgetProvider::class.java))
        for (id in ids) mgr.updateAppWidget(id, build(context, mgr, id))
    }

    fun update(context: Context, id: Int) {
        val mgr = AppWidgetManager.getInstance(context)
        mgr.updateAppWidget(id, build(context, mgr, id))
    }

    /** Widget ids currently showing the "Locked" hint, with the time it disappears. */
    private val hintUntil = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    fun showLockHint(id: Int, ms: Long) {
        hintUntil[id] = System.currentTimeMillis() + ms
    }

    fun isLockHintShowing(id: Int) = (hintUntil[id] ?: 0) > System.currentTimeMillis()

    fun clearLockHint(id: Int) {
        hintUntil.remove(id)
    }

    /** Debug builds: inflate every layout once so RemoteViews errors show up in the frame log. */
    fun selfTest(context: Context) {
        for ((name, layout) in listOf("compact" to R.layout.widget_compact, "medium" to R.layout.widget_medium,
                "tall" to R.layout.widget_tall, "large" to R.layout.widget_large)) {
            try {
                RemoteViews(context.packageName, layout).apply(context, android.widget.FrameLayout(context))
                views(context, layout, Size.LARGE, AppWidgetManager.INVALID_APPWIDGET_ID).apply(context, android.widget.FrameLayout(context))
                DeviceController.log("-- widget $name inflates")
            } catch (t: Throwable) {
                DeviceController.log("!! widget $name: $t ${t.cause ?: ""}")
            }
        }
    }

    fun build(context: Context, mgr: AppWidgetManager, id: Int): RemoteViews {
        if (Build.VERSION.SDK_INT >= 31) {
            // The launcher picks the largest layout that fits as the user resizes.
            return RemoteViews(mapOf(
                SizeF(250f, 40f) to views(context, R.layout.widget_compact, Size.COMPACT, id),
                SizeF(250f, 120f) to views(context, R.layout.widget_medium, Size.MEDIUM, id),
                SizeF(250f, 205f) to views(context, R.layout.widget_tall, Size.TALL, id),
                SizeF(250f, 295f) to views(context, R.layout.widget_large, Size.LARGE, id),
            ))
        }
        val h = mgr.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 120)
        return when {
            h < 120 -> views(context, R.layout.widget_compact, Size.COMPACT, id)
            h < 205 -> views(context, R.layout.widget_medium, Size.MEDIUM, id)
            h < 295 -> views(context, R.layout.widget_tall, Size.TALL, id)
            else -> views(context, R.layout.widget_large, Size.LARGE, id)
        }
    }

    /** Rows of a typical launcher: COMPACT 1, MEDIUM 2, TALL 3, LARGE 4+. */
    private enum class Size { COMPACT, MEDIUM, TALL, LARGE;
        val roomy get() = this == TALL || this == LARGE
    }

    private fun views(ctx: Context, @LayoutRes layout: Int, size: Size, widgetId: Int): RemoteViews {
        DeviceController.init(ctx)
        val st = DeviceController.state.value
        val rv = RemoteViews(ctx.packageName, layout)
        bindLock(ctx, rv, widgetId)
        val openApp = PendingIntent.getActivity(ctx, 1, Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        rv.setTextViewText(R.id.name, st.deviceName ?: "MOMENTUM 4")
        rv.setOnClickPendingIntent(R.id.header, openApp)
        rv.setImageViewResource(R.id.dot, when (st.link) {
            LinkState.CONNECTED -> R.drawable.w_dot_ok
            LinkState.UNAVAILABLE -> R.drawable.w_dot_busy
            else -> R.drawable.w_dot_grey
        })
        val s = st.snapshot
        rv.setTextViewText(R.id.battery, (s.battery?.let { "$it%" } ?: "—") + if (s.charging == 1) " ⚡" else "")

        // Setup states: header + one line, the whole widget opens the app.
        if (st.link == LinkState.NO_PERMISSION || st.link == LinkState.NO_DEVICE) {
            rv.setViewVisibility(R.id.controls, View.GONE)
            rv.setViewVisibility(R.id.status, View.VISIBLE)
            rv.setTextViewText(R.id.status, if (st.link == LinkState.NO_PERMISSION) "Tap to set up" else "Tap to choose headphones")
            color(ctx, rv, R.id.status, R.color.w_accent_text)
            rv.setTextViewText(R.id.readout, "")
            rv.setOnClickPendingIntent(R.id.root, openApp)
            return rv
        }

        val statusText = when (st.link) {
            LinkState.DISCONNECTED -> if (!size.roomy) "Not connected · last known" else "Not connected · last known settings"
            LinkState.UNAVAILABLE -> if (!size.roomy) "Control unavailable" else "Control connection unavailable"
            else -> null
        }
        rv.setViewVisibility(R.id.status, if (statusText != null) View.VISIBLE else View.GONE)
        rv.setTextViewText(R.id.status, statusText ?: "")
        color(ctx, rv, R.id.status, if (st.link == LinkState.UNAVAILABLE) R.color.w_busy else R.color.w_fg2)
        rv.setFloat(R.id.controls, "setAlpha", if (st.link == LinkState.CONNECTED) 1f else 0.45f)

        bindNoise(ctx, rv, st, size)
        if (size != Size.COMPACT) bindEq(ctx, rv, st)
        if (size.roomy) {
            val touch = st.shownTouchLock
            rv.setImageViewResource(R.id.sw_touch, if (touch == true) R.drawable.w_sw_on else R.drawable.w_sw_off)
            rv.setFloat(R.id.sw_touch, "setAlpha", if (touch == null || st.touchLockPending) 0.55f else 1f)
            rv.setOnClickPendingIntent(R.id.touch_row, service(ctx, CommandService.ACTION_TOUCH_LOCK, 210))
        }
        if (size == Size.LARGE) {
            rv.setTextViewText(R.id.foot_left, when (st.link) {
                LinkState.CONNECTED -> "Connected" + (s.codec?.let { Codec.CODECS[it] }?.let { " · $it" } ?: "")
                LinkState.DISCONNECTED -> "Disconnected"
                else -> "Control unavailable"
            })
            rv.setTextViewText(R.id.foot_right, if (st.sessionOpen) "live" else "updated ${agoText(st.lastSyncedAt)}")
        }
        return rv
    }

    private fun bindNoise(ctx: Context, rv: RemoteViews, st: AppState, size: Size) {
        val mode = st.shownNoiseMode
        val level = st.shownLevel
        val r = NoiseControl.readout(mode, level)
        rv.setTextViewText(R.id.readout, if (size.roomy) r.text else r.short)
        color(ctx, rv, R.id.readout, when (r.tone) {
            NoiseControl.Tone.ANC -> R.color.w_anc_text
            NoiseControl.Tone.TRANSPARENCY -> R.color.w_trans_text
            NoiseControl.Tone.ADAPTIVE -> R.color.w_accent_text
            NoiseControl.Tone.BALANCED -> R.color.w_fg
            else -> R.color.w_fg3
        })
        val pendingAlpha = if (st.noisePending) 0.55f else 1f
        rv.setFloat(R.id.readout, "setAlpha", pendingAlpha)

        rv.setImageViewResource(R.id.sw_noise, if (mode != null && mode != NoiseMode.OFF) R.drawable.w_sw_on else R.drawable.w_sw_off)
        rv.setOnClickPendingIntent(R.id.sw_noise, service(ctx, CommandService.ACTION_NOISE_TOGGLE, 150))
        val adaptive = mode == NoiseMode.ADAPTIVE
        rv.setViewVisibility(R.id.auto_on, if (adaptive) View.VISIBLE else View.GONE)
        rv.setViewVisibility(R.id.auto_off, if (adaptive) View.GONE else View.VISIBLE)
        rv.setOnClickPendingIntent(R.id.auto, service(ctx, CommandService.ACTION_ADAPTIVE, 160))

        // Slider: thumb at the nearest stop; fill between the center cell and the thumb (Custom only).
        val stop = NoiseControl.nearestStop(level)
        val showThumb = mode == NoiseMode.CUSTOM || mode == NoiseMode.OFF
        val fill = if (mode == NoiseMode.CUSTOM) (if (stop < CENTER) R.drawable.w_seg_anc else R.drawable.w_seg_trans) else R.drawable.w_seg_idle
        val lo = minOf(CENTER, stop)
        val hi = maxOf(CENTER, stop)
        for (i in 0 until 7) {
            rv.setImageViewResource(SEG_L[i], when {
                i == 0 -> R.drawable.w_seg_none
                mode == NoiseMode.CUSTOM && i > lo && i <= hi -> fill
                else -> R.drawable.w_seg_idle
            })
            rv.setImageViewResource(SEG_R[i], when {
                i == 6 -> R.drawable.w_seg_none
                mode == NoiseMode.CUSTOM && i >= lo && i < hi -> fill
                else -> R.drawable.w_seg_idle
            })
            rv.setViewVisibility(THUMBS[i], if (showThumb && i == stop) View.VISIBLE else View.GONE)
            rv.setOnClickPendingIntent(CELLS[i], service(ctx, CommandService.ACTION_STOP, 100 + i) { putExtra(CommandService.EXTRA_INDEX, i) })
        }
        rv.setImageViewResource(THUMBS[stop], when {
            mode == NoiseMode.OFF -> R.drawable.w_thumb_idle
            stop == CENTER -> R.drawable.w_thumb_bal
            stop < CENTER -> R.drawable.w_thumb_anc
            else -> R.drawable.w_thumb_trans
        })
        rv.setFloat(THUMBS[stop], "setAlpha", pendingAlpha)
    }

    private fun bindEq(ctx: Context, rv: RemoteViews, st: AppState) {
        val presets = DeviceController.presets.value
        val label = EqModel.label(st.shownAudioMode, st.shownGains, presets.all)
        rv.setTextViewText(R.id.eq_label, label)
        rv.setFloat(R.id.eq_label, "setAlpha", if (st.eqPending) 0.55f else 1f)
        val active = if (st.shownAudioMode == 1) EqModel.match(st.shownGains, presets.all) else null
        val favs = EqModel.favoritePresets(presets.all, presets.favorites)
        // Same look as the app's toggle group: the selected segment's fill is rounded only on the group's outer edge.
        val shown = favs.size.coerceAtMost(FAVS.size)
        for (i in FAVS.indices) {
            val p = favs.getOrNull(i)
            rv.setViewVisibility(FAVS[i], if (p != null) View.VISIBLE else View.GONE)
            if (i > 0) rv.setViewVisibility(FAV_DIVIDERS[i - 1], if (p != null) View.VISIBLE else View.GONE)
            if (p == null) continue
            val on = p == active
            rv.setTextViewText(FAVS[i], p.name)
            rv.setInt(FAVS[i], "setBackgroundResource", when {
                !on -> R.drawable.w_seg_unsel
                shown == 1 -> R.drawable.w_seg_sel_only
                i == 0 -> R.drawable.w_seg_sel_first
                i == shown - 1 -> R.drawable.w_seg_sel_last
                else -> R.drawable.w_seg_sel_mid
            })
            color(ctx, rv, FAVS[i], if (on) R.color.w_fg else R.color.w_fg2)
            rv.setOnClickPendingIntent(FAVS[i], service(ctx, CommandService.ACTION_FAVORITE, 300 + i) { putExtra(CommandService.EXTRA_PRESET, p.id) })
        }
        // Large: switch in a Bass Boost row. Medium: BASS pill next to the favorites. Missing ids are ignored.
        val bass = st.shownBass == true
        rv.setImageViewResource(R.id.sw_bass, if (bass) R.drawable.w_sw_on else R.drawable.w_sw_off)
        rv.setFloat(R.id.sw_bass, "setAlpha", if (st.bassPending) 0.55f else 1f)
        rv.setViewVisibility(R.id.bass_row_on, if (bass) View.VISIBLE else View.GONE)
        rv.setViewVisibility(R.id.bass_row_off, if (bass) View.GONE else View.VISIBLE)
        rv.setFloat(R.id.bass_row, "setAlpha", if (st.bassPending) 0.55f else 1f)
        rv.setOnClickPendingIntent(R.id.bass_row, service(ctx, CommandService.ACTION_BASS, 200))
    }

    /**
     * Optional lock (Settings → "Lock button on widget"). Locked: a grey veil over the whole card swallows
     * every tap — controls and opening the app — and only the lock icon on top of it stays live.
     */
    private fun bindLock(ctx: Context, rv: RemoteViews, widgetId: Int) {
        val store = DeviceController.store
        val enabled = store.widgetLockEnabled && widgetId != AppWidgetManager.INVALID_APPWIDGET_ID
        val locked = enabled && store.isWidgetLocked(widgetId)
        rv.setViewVisibility(R.id.lock, if (enabled) View.VISIBLE else View.GONE)
        rv.setViewVisibility(R.id.lock_space, if (enabled) View.VISIBLE else View.GONE)
        rv.setViewVisibility(R.id.lock_veil, if (locked) View.VISIBLE else View.GONE)
        if (!enabled) return
        rv.setImageViewResource(R.id.lock, if (locked) R.drawable.w_lock_closed else R.drawable.w_lock_open)
        rv.setInt(R.id.lock, "setBackgroundResource", if (locked) R.drawable.w_lock_circle else 0)
        rv.setContentDescription(R.id.lock, if (locked) "Unlock widget" else "Lock widget")
        rv.setOnClickPendingIntent(R.id.lock, broadcast(ctx, MomentumWidgetProvider.ACTION_TOGGLE_LOCK, widgetId, 0))
        rv.setOnClickPendingIntent(R.id.lock_veil, broadcast(ctx, MomentumWidgetProvider.ACTION_LOCKED_TAP, widgetId, 1))
        val hint = (hintUntil[widgetId] ?: 0) > System.currentTimeMillis()
        rv.setViewVisibility(R.id.lock_hint, if (locked && hint) View.VISIBLE else View.GONE)
    }

    private fun broadcast(ctx: Context, action: String, widgetId: Int, slot: Int): PendingIntent =
        PendingIntent.getBroadcast(ctx, 10_000 + widgetId * 4 + slot,
            Intent(ctx, MomentumWidgetProvider::class.java).setAction(action).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** API 31+: color resource resolved by the launcher, so it follows dark mode without a redraw. */
    private fun color(ctx: Context, rv: RemoteViews, id: Int, @ColorRes res: Int) {
        if (Build.VERSION.SDK_INT >= 31) rv.setColorStateList(id, "setTextColor", res)
        else rv.setTextColor(id, ctx.getColor(res))
    }

    private fun service(ctx: Context, action: String, requestCode: Int, extras: Intent.() -> Unit = {}): PendingIntent =
        PendingIntent.getForegroundService(ctx, requestCode, CommandService.intent(ctx, action).apply(extras),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}
