package momoi.mod.qqpro.hook.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import momoi.mod.qqpro.util.Utils

/**
 * `mediaProjection` foreground service that mirrors the screen into an ImageReader, converts each
 * RGBA frame to NV21 and feeds it to [ScreenShare.feedFrame]. Must be a foreground service of type
 * mediaProjection (Android 14+) started BEFORE `getMediaProjection`, which we do in [onStartCommand].
 */
class ScreenShareService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var nv21: ByteArray? = null
    @Volatile private var busy = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        val code = intent.getIntExtra(EXTRA_CODE, 0)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        startForeground(NOTI_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        runCatching { startCapture(code, data) }.onFailure {
            Utils.log("ScreenShareService: startCapture failed: $it")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startCapture(code: Int, data: Intent) {
        val w = ScreenShare.captureWidth()
        val h = ScreenShare.captureHeight()
        nv21 = ByteArray(w * h * 3 / 2)

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(code, data) ?: run { stopSelf(); return }
        projection = mp

        handlerThread = HandlerThread("qqpro-screenshare").also { it.start() }
        handler = Handler(handlerThread!!.looper)

        // Required on Android 14+: register a callback so the projection can be revoked cleanly.
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { Utils.log("ScreenShareService: projection stopped by system"); stopSelf() }
        }, handler)

        val dpi = resources.displayMetrics.densityDpi.takeIf { it > 0 } ?: DisplayMetrics.DENSITY_DEFAULT
        val ir = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        reader = ir
        ir.setOnImageAvailableListener({ onFrame(it, w, h) }, handler)

        virtualDisplay = mp.createVirtualDisplay(
            "qqpro-screenshare", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            ir.surface, null, handler,
        )
        ScreenShare.onServiceStarted(applicationContext)
        Utils.log("ScreenShareService: capturing ${w}x$h dpi=$dpi")
    }

    private fun onFrame(ir: ImageReader, w: Int, h: Int) {
        if (busy) { runCatching { ir.acquireLatestImage()?.close() }; return }
        busy = true
        runCatching {
            val image = ir.acquireLatestImage() ?: run { busy = false; return }
            image.use { img ->
                val plane = img.planes[0]
                val buf = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                rgbaToNv21(buf, w, h, pixelStride, rowStride, nv21!!)
            }
            ScreenShare.feedFrame(nv21!!, w, h)
        }.onFailure { Utils.log("ScreenShareService: onFrame failed: $it") }
        busy = false
    }

    /** BT.601 video-range RGBA → NV21 (Y plane + interleaved VU), 2x2 chroma subsample. */
    private fun rgbaToNv21(
        buf: java.nio.ByteBuffer, w: Int, h: Int, pixelStride: Int, rowStride: Int, out: ByteArray,
    ) {
        val frameSize = w * h
        var yIndex = 0
        var uvIndex = frameSize
        for (y in 0 until h) {
            val rowStart = y * rowStride
            for (x in 0 until w) {
                val off = rowStart + x * pixelStride
                val r = buf.get(off).toInt() and 0xFF
                val g = buf.get(off + 1).toInt() and 0xFF
                val b = buf.get(off + 2).toInt() and 0xFF
                val yy = (66 * r + 129 * g + 25 * b + 128 shr 8) + 16
                out[yIndex++] = yy.coerceIn(0, 255).toByte()
                if (y % 2 == 0 && x % 2 == 0) {
                    val u = (-38 * r - 74 * g + 112 * b + 128 shr 8) + 128
                    val v = (112 * r - 94 * g - 18 * b + 128 shr 8) + 128
                    out[uvIndex++] = v.coerceIn(0, 255).toByte()
                    out[uvIndex++] = u.coerceIn(0, 255).toByte()
                }
            }
        }
    }

    override fun onDestroy() {
        runCatching { virtualDisplay?.release() }
        runCatching { reader?.close() }
        runCatching { projection?.stop() }
        runCatching { handlerThread?.quitSafely() }
        virtualDisplay = null; reader = null; projection = null; handlerThread = null; handler = null
        ScreenShare.onServiceStopped(applicationContext)
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "屏幕共享", NotificationManager.IMPORTANCE_LOW),
            )
        }
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("屏幕共享进行中")
            .setContentText("正在将屏幕共享到通话")
            .setSmallIcon(applicationInfo.icon)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "qqpro_screenshare"
        private const val NOTI_ID = 0x5C31
    }
}
