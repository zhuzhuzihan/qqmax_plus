package momoi.mod.qqpro.hook.call

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton
import com.tencent.activitys.QQNTC2CWatchActivity
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.MaterialSymbol
import momoi.mod.qqpro.lib.material.MaterialSymbols
import momoi.mod.qqpro.util.Utils

/**
 * 全新通话界面 (materializeCall) — Material 3 treatment for the active call screen.
 *
 * The native `activity_accepted_call` layout is reused in place (its `GLRootView` video surface, avatar,
 * nickname, timer and the camera/mic/hangup [MaterialButton]s stay wired to QQ's call service — we do
 * NOT reconstruct the ViewBinding or touch the lifecycle). We only restyle the chrome into an M3
 * presentation (themed surfaces + typography, a surface-container control pill) and add the in-call
 * audio-output selector (蓝牙/扬声器/听筒) that drives [CallAudioRouter]. A plain object (not a @Mixin
 * body) so the button listeners are safe.
 *
 * Applied after `super.onCreate` (the binding field `t` is populated by then). First cut — layout
 * polish will want on-device tuning; guarded by the default-off materializeCall toggle.
 */
object MaterialCallUi {
    private const val OUT_TAG = "qqpro_call_out"

    // Current index into availableRoutes() for the output selector. Single active call at a time, so
    // a single field suffices; reset when the M3 UI is (re)applied for a new call.
    private var routeIndex = -1

    fun applyActive(activity: QQNTC2CWatchActivity) {
        runCatching {
            val b = activity.t ?: return
            routeIndex = -1
            // Screen + video-fallback background → M3 surface; nickname/timer → M3 typography.
            b.j.setBackgroundColor(M3.surface)
            b.g.setBackgroundColor(M3.surface)
            b.i.setTextColor(M3.onSurface)
            b.m.setTextColor(M3.onSurfaceVariant)

            // Control row → an M3 surface-container pill the circular buttons sit on.
            val bar = b.d
            bar.setPadding(12.dp, 8.dp, 12.dp, 8.dp)
            bar.background = GradientDrawable().apply {
                setColor(M3.surfaceContainerHigh)
                cornerRadius = M3.radiusPill
            }

            // Add the audio-output selector once, matching the native controls (reference = switch_camera).
            if (bar.findViewWithTag<View>(OUT_TAG) == null) {
                bar.addView(makeOutputButton(activity, b.k))
            }
            Utils.log("MaterialCallUi: applied active-call M3 restyle")
        }.onFailure { Utils.log("MaterialCallUi: applyActive failed: $it") }
    }

    /** A circular M3 button that cycles the call audio output, cloned from a native control [ref]. */
    private fun makeOutputButton(activity: QQNTC2CWatchActivity, ref: MaterialButton): MaterialButton {
        val btn = MaterialButton(activity, null)
        btn.tag = OUT_TAG
        // Clone the visual footprint of the native control buttons.
        val refLp = ref.layoutParams as? LinearLayout.LayoutParams
        btn.layoutParams = LinearLayout.LayoutParams(
            refLp?.width ?: 44.dp, refLp?.height ?: 44.dp,
        ).apply {
            val m = refLp?.marginStart ?: 6.dp
            marginStart = m; marginEnd = m
        }
        runCatching {
            btn.cornerRadius = ref.cornerRadius
            btn.iconSize = ref.iconSize
            btn.insetTop = 0; btn.insetBottom = 0
            btn.iconGravity = ref.iconGravity // match how the native controls center their icon
        }
        btn.iconPadding = 0
        btn.iconTint = null
        btn.icon = MaterialSymbol(MaterialSymbols.volume_up, Color.parseColor("#111111"))
        btn.backgroundTintList = ColorStateList.valueOf(Color.WHITE)
        btn.setOnClickListener { cycleRoute(activity) }
        return btn
    }

    /** Cycle through the routes this hardware supports, applying + announcing each. */
    private fun cycleRoute(activity: QQNTC2CWatchActivity) {
        runCatching {
            val routes = CallAudioRouter.availableRoutes(activity)
            if (routes.isEmpty()) return
            routeIndex = (routeIndex + 1) % routes.size
            val route = routes[routeIndex]
            CallAudioRouter.route(activity, route)
            Utils.toast(activity, routeLabel(route))
        }.onFailure { Utils.log("MaterialCallUi: cycleRoute failed: $it") }
    }

    private fun routeLabel(route: CallAudioRouter.Route) = when (route) {
        CallAudioRouter.Route.BLUETOOTH -> "蓝牙耳机"
        CallAudioRouter.Route.SPEAKER -> "扬声器"
        CallAudioRouter.Route.EARPIECE -> "听筒"
    }
}
