package momoi.mod.qqpro.hook.call

import android.content.ComponentName
import android.os.IBinder
import com.tencent.activitys.QQNTC2CWatchActivity
import com.tencent.activitys.`QQNTC2CWatchActivity$mServiceConnection$1`
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings

/**
 * QQ wires + re-tints the active-call control buttons (camera/mic/hangup) white/red inside the call
 * activity's service-connection callback. We hook it so that, after the native wiring runs, our Material 3
 * control styling ([MaterialCallUi.styleActiveControls]) is applied last and wins. `b` is the outer
 * [QQNTC2CWatchActivity]; the constructor param forwards to the anonymous class's super ctor (like
 * MenuPanelLayout). Compiles thanks to the EnclosingMethod-stripped stub (see build.gradle.kts).
 */
@Mixin
class CallConnHook(p0: QQNTC2CWatchActivity) : `QQNTC2CWatchActivity$mServiceConnection$1`(p0) {
    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        super.onServiceConnected(name, service)
        if (Settings.materializeCall.value) MaterialCallUi.rebuildActive(b)
    }
}
