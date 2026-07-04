package momoi.mod.qqpro.hook.call

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.tencent.av.camera.AndroidCamera
import com.tencent.av.camera.CameraUtils
import com.tencent.av.opengl.GraphicRenderMgr
import com.tencent.qav.thread.ThreadManager
import momoi.mod.qqpro.util.Utils

/**
 * Screen share for a C2C video call. There is no native screen-share path on the watch AV stack
 * (only GPro channels have one), so we substitute the outgoing video source: capture the screen with
 * MediaProjection and inject the frames through the very same sink the camera uses,
 * `GraphicRenderMgr.getInstance().sendCameraFrame(nv21, 17, w, h, …)` — proven to replace the remote
 * video (see the moving-bars prototype).
 *
 * Flow: long-press → [toggle] launches [ScreenSharePermissionActivity] for the MediaProjection
 * consent → [onConsent] starts [ScreenShareService] (a `mediaProjection` foreground service, required
 * on Android 14+) which mirrors the screen into an ImageReader, converts each RGBA frame to NV21 and
 * calls [feedFrame]. While sharing, the camera is closed via [CameraUtils]'s CloseCameraRunnable
 * (`cu.h`) — that only releases the hardware, it does NOT clear `setLocalHasVideo`, so the peer keeps
 * receiving our (now screen) video. Toggling off stops the service and reopens the camera.
 */
object ScreenShare {
    @Volatile var sharing = false
        private set

    /** Encoder-negotiated frame size the camera feeds; we must match it. */
    fun captureWidth(): Int = AndroidCamera.b.takeIf { it > 0 } ?: 640
    fun captureHeight(): Int = AndroidCamera.c.takeIf { it > 0 } ?: 480

    private fun cu(ctx: Context): CameraUtils? = runCatching { CameraUtils.b(ctx) }.getOrNull()

    /** Long-press entry: start consent flow, or stop an active share. */
    fun toggle(activity: Activity) {
        if (sharing) {
            stop(activity)
        } else {
            runCatching {
                activity.startActivity(
                    Intent(activity, ScreenSharePermissionActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION),
                )
            }.onFailure { Utils.log("ScreenShare: launch consent failed: $it") }
        }
    }

    /** Called from the consent activity once the user granted capture. */
    fun onConsent(context: Context, resultCode: Int, data: Intent) {
        runCatching {
            val svc = Intent(context, ScreenShareService::class.java)
                .putExtra(ScreenShareService.EXTRA_CODE, resultCode)
                .putExtra(ScreenShareService.EXTRA_DATA, data)
            context.startForegroundService(svc)
        }.onFailure { Utils.log("ScreenShare: start service failed: $it") }
    }

    private fun stop(activity: Activity) = ensureStopped(activity)

    /**
     * Stop the share service if it is running. Safe to call unconditionally — used both by the
     * manual toggle and by the call activity's teardown, so a share left running when the call ends
     * (peer hung up, or the user just left the call) doesn't orphan the foreground service/notification.
     */
    fun ensureStopped(context: Context) {
        if (!sharing) return
        runCatching { context.stopService(Intent(context, ScreenShareService::class.java)) }
            .onFailure { Utils.log("ScreenShare: stop service failed: $it") }
    }

    // ---- called by the service ----

    /** Stop camera frames without dropping the call's local-video signal. */
    fun onServiceStarted(context: Context) {
        sharing = true
        runCatching { ThreadManager.b.post(cu(context)?.h ?: return) }
            .onFailure { Utils.log("ScreenShare: close camera failed: $it") }
        MaterialCallUi.refreshCameraIcons()
        Utils.toast(context, "屏幕共享已开启")
    }

    /** Resume the camera when the share ends. */
    fun onServiceStopped(context: Context) {
        sharing = false
        runCatching { cu(context)?.d(0L) }.onFailure { Utils.log("ScreenShare: reopen camera failed: $it") }
        MaterialCallUi.refreshCameraIcons()
        Utils.toast(context, "屏幕共享已停止")
    }

    /** Inject one screen frame into the outgoing video, exactly as the camera preview callback does. */
    fun feedFrame(nv21: ByteArray, w: Int, h: Int) {
        runCatching {
            GraphicRenderMgr.getInstance().sendCameraFrame(
                nv21, 17, w, h, 0, 0, System.currentTimeMillis(), false, null, null, 0, 0,
            )
        }.onFailure { Utils.log("ScreenShare: feedFrame failed: $it") }
    }
}
