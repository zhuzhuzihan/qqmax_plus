package momoi.mod.qqpro.hook.call

import android.content.ComponentName
import android.os.IBinder
import com.tencent.activitys.BeInvitedActivity
import com.tencent.activitys.`BeInvitedActivity$mServiceConnection$1`
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings

/**
 * Restyle the incoming-call answer/reject buttons as M3 (green answer + error reject) after QQ wires them
 * in the service-connection callback. `b` is the outer [BeInvitedActivity]; the constructor param forwards
 * to the anonymous class's super ctor. Compiles thanks to the EnclosingMethod-stripped stub.
 */
@Mixin
class CallIncomingConnHook(p0: BeInvitedActivity) : `BeInvitedActivity$mServiceConnection$1`(p0) {
    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        super.onServiceConnected(name, service)
        if (Settings.materializeCall.value) MaterialCallUi.rebuildIncoming(b)
    }
}
