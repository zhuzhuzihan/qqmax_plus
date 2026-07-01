package momoi.mod.qqpro.hook.call

import android.os.Bundle
import com.tencent.activitys.QQNTC2CWatchActivity
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings

/**
 * Lifecycle hook on the active-call activity. Currently drives 蓝牙耳机路由: on resume (call in the
 * foreground / connected) auto-route audio to a Bluetooth headset if one is connected, and restore
 * normal audio on destroy. Both gate on [Settings.callBluetoothRoute] and are best-effort (the router
 * swallows failures), so with the setting off QQ's native routing is untouched.
 *
 * The in-call output selector (蓝牙/扬声器/听筒) is added by the Material call UI (materializeCall);
 * it calls [CallAudioRouter.route] directly.
 */
@Mixin
class CallActivityHook : QQNTC2CWatchActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Full M3 call UI (opt-in, default off). onCreate only preps the surface to avoid a native flash;
        // the real rebuild happens in CallConnHook once QQ has wired the control buttons (onServiceConnected).
        if (Settings.materializeCall.value) MaterialCallUi.prepActive(this)
    }

    override fun onResume() {
        super.onResume()
        // The active call is up — clear the incoming-ring notification if 来电通知修复 posted one.
        if (Settings.callNotifyFix.value) CallNotification.cancelIncoming(this)
        if (Settings.callBluetoothRoute.value) CallAudioRouter.enter(this)
    }

    override fun onDestroy() {
        if (Settings.callBluetoothRoute.value) CallAudioRouter.release(this)
        super.onDestroy()
    }
}
