package momoi.mod.qqpro.hook.call

import android.content.Context
import com.tencent.av.camera.CameraUtils
import com.tencent.av.camera.FrameBufMgr
import momoi.anno.mixin.Mixin

/**
 * Stop the video-call preview from flickering while [ScreenShare] is active.
 *
 * [CameraUtils] is the camera's `CameraPreviewCallback`: for every hardware preview frame,
 * [AndroidCamera]'s `onPreviewFrame` calls [CameraUtils.a], which pushes the frame straight into the
 * encoder via `GraphicRenderMgr.sendCameraFrame`. Screen share injects its own frames through the
 * *same* sink ([ScreenShare.feedFrame]). We close the camera when a share starts, but the native AV
 * stack — which still sees `setLocalHasVideo` = true — reopens the front camera a few seconds later
 * (seen in the log: "CameraOpenFix: opened front camera" right after "capturing"). From then on the
 * live camera frames and the screen frames both reach the encoder, so the remote video alternates
 * between the two → flicker.
 *
 * Fix: while [ScreenShare.sharing], drop the native camera frame here (only screen frames get
 * through). The buffer is still recycled so the preview buffer pool doesn't starve. This holds no
 * matter how often the native stack reopens the camera.
 */
@Mixin
class CameraFrameGate(context: Context) : CameraUtils(context) {
    override fun a(bArr: ByteArray, i: Int, i2: Int, i3: Int, i4: Int, j: Long, z: Boolean) {
        if (ScreenShare.sharing) {
            runCatching { FrameBufMgr.b().d(bArr, 0) }
            return
        }
        super.a(bArr, i, i2, i3, i4, j, z)
    }
}
