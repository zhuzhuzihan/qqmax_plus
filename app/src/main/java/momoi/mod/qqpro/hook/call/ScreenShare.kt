package momoi.mod.qqpro.hook.call

import android.app.Activity
import android.content.Context
import android.content.Intent
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

    // Geometry of the live camera frame, recorded by [CameraFrameGate] before we close the camera.
    // The call negotiates its video encoder from this: a `camW`x`camH` sensor buffer that native
    // rotates by `camOrientation`*90° before encoding. So the actual encoded frame is the sensor
    // size with width/height swapped when the orientation is an odd quarter-turn. Defaults describe
    // this phone (640x480, orientation 3 → 480x640 portrait) for the case the camera never ran.
    @Volatile private var camW = 640
    @Volatile private var camH = 480
    @Volatile private var camOrientation = 3

    fun onCameraGeometry(w: Int, h: Int, orientation: Int) {
        if (w > 0 && h > 0) { camW = w; camH = h; camOrientation = orientation }
    }

    /** The encoder's real frame size = sensor size rotated by the camera orientation. */
    private fun encodeSize(): Pair<Int, Int> {
        val swap = camOrientation % 2 != 0 // 90°/270° quarter-turns swap width and height
        return if (swap) camH to camW else camW to camH
    }

    /**
     * Capture plan for a screen of [screenW]x[screenH]. Native scales whatever buffer we feed to the
     * negotiated encoder size, so to avoid distortion the buffer's aspect ratio MUST equal the
     * encoder's ([encodeSize], e.g. 480x640 = 3:4 portrait here).
     *
     * We capture the WHOLE screen letterboxed into that portrait frame (FIT, not fill): the
     * VirtualDisplay is created at the encoder's portrait size and AUTO_MIRROR scales the screen to
     * fit it, preserving aspect and padding with black bars. Nothing is cropped, so the peer sees the
     * entire screen — and the bars give margin the peer's own center-crop (it zooms received video to
     * fill its viewport) eats into instead of chopping real content. The [ScreenShareService] then
     * rotates this portrait frame 90° into the landscape encoder buffer and tags it with the camera's
     * orientation so the peer rotates it back upright.
     */
    fun capturePlan(screenW: Int, screenH: Int): CapturePlan {
        val (encW, encH) = encodeSize()
        fun even(v: Int) = (v and 1.inv()).coerceAtLeast(2)
        val w = even(encW)
        val h = even(encH)
        return CapturePlan(w, h, w, h, 0, 0)
    }

    /** @see capturePlan */
    data class CapturePlan(
        val captureW: Int,
        val captureH: Int,
        val outW: Int,
        val outH: Int,
        val cropLeft: Int,
        val cropTop: Int,
    )

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

    /** Orientation quarter-turn the peer applies to our frame — same value the camera path uses. */
    fun cameraOrientation(): Int = camOrientation

    /**
     * Inject one screen frame into the outgoing video, exactly as the camera preview callback does.
     * [orientation] MUST match the camera's: the encoder frame stays landscape and the orientation is
     * carried as metadata the peer rotates by. We pre-rotate the screen into that landscape buffer, so
     * `orientation` = the camera's index makes the peer rotate it back upright (see [ScreenShareService]).
     */
    fun feedFrame(nv21: ByteArray, w: Int, h: Int, orientation: Int) {
        runCatching {
            GraphicRenderMgr.getInstance().sendCameraFrame(
                nv21, 17, w, h, orientation, 0, System.currentTimeMillis(), false, null, null, 0, 0,
            )
        }.onFailure { Utils.log("ScreenShare: feedFrame failed: $it") }
    }
}
