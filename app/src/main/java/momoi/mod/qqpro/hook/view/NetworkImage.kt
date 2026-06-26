import android.net.Uri
import android.widget.ImageView
import com.tencent.qqnt.kernel.nativeinterface.PicElement
import momoi.mod.qqpro.util.Utils
import momoi.mod.qqpro.child
import momoi.mod.qqpro.safeCacheDir
import momoi.mod.qqpro.lib.bitmapDecodeFile
import momoi.mod.qqpro.msg.getImageUrl
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import androidx.core.net.toUri

// Bounded pool for image downloads. Each download holds a socket FD for up to the
// connect timeout; spawning an unbounded thread per image (e.g. a chat list or member
// picker loading dozens at once) exhausted the process FD limit → "Too many open files".
// Capping concurrency caps the number of simultaneously open sockets.
val downloadExecutor = Executors.newFixedThreadPool(4) { r ->
    Thread(r, "qqpro-img-dl").apply { isDaemon = true }
}

// 抽取的加载图片 URL 的函数
// onDone(success) is invoked on the UI thread once loading finishes (so callers can hide a
// loading indicator). It is NOT called for null/empty urls (nothing was started).
fun ImageView.loadPicUrl(
    url: String?,
    cacheFileName: String = "${System.currentTimeMillis() / 1000}${url.hashCode()}",
    onDone: ((Boolean) -> Unit)? = null,
    onProgress: ((Float) -> Unit)? = null,
) = apply {
    if (url.isNullOrEmpty()) {
        Utils.log("loadPicUrl: empty url, skip")
        onDone?.let { cb -> post { cb(false) } }
        return@apply
    }
    require(maxHeight != 0)
    val cacheDir = context.safeCacheDir
    if (cacheDir == null) {
        Utils.log("loadPicUrl: no cache dir available, skip $url")
        onDone?.let { cb -> post { cb(false) } }
        return@apply
    }
    val cacheFile = cacheDir.child("$cacheFileName.jpg")
    cacheFile.parentFile?.mkdirs()
    val finish = { ok: Boolean -> onDone?.let { cb -> post { cb(ok) } } }
    if (cacheFile.exists()) {
        Utils.log("Load Image from disk ${cacheFile.path}")
        // finish (→ onDone) fires only once the drawable is actually applied — the GIF path defers the
        // setImageDrawable to doOnLayout, so calling onDone before that left a gap (spinner hidden but
        // image not yet shown).
        bitmapDecodeFile(cacheFile, onApplied = { finish(true) })
    } else {
        Utils.log("Loading image (downloading): $url -> ${cacheFile.path}")
        download(
            url, cacheFile,
            onProgress = { p -> onProgress?.let { cb -> post { cb(p) } } },
        ) { succeed ->
            // The download callback runs on the downloadExecutor (a BACKGROUND thread). Decoding touches
            // the view (setImageDrawable / doOnLayout / ImageDecoder), which MUST happen on the UI thread —
            // the GIF/animated path otherwise throws "Only the original thread that created a view hierarchy
            // can touch its views" and the image never shows. Marshal back to the view's UI thread.
            if (succeed) {
                Utils.log("Downloaded image, decoding ${cacheFile.path}")
                post { bitmapDecodeFile(cacheFile, onApplied = { finish(true) }) }
            } else {
                Utils.log("Download Image Failed (callback): $url")
                post { loadErrorImage() }
                finish(false)
            }
        }
    }
}

fun ImageView.loadPicElement(
    pic: PicElement,
    onDone: ((Boolean) -> Unit)? = null,
    onProgress: ((Float) -> Unit)? = null,
) = apply {
    // 调用抽取的函数
    loadPicUrl(pic.getImageUrl(), pic.md5HexStr, onDone, onProgress)
}

/** Show the fallback "broken image" placeholder. */
fun ImageView.loadErrorImage() {
    val error = (context.safeCacheDir ?: return).child("error.jpg")
    if (error.exists()) {
        bitmapDecodeFile(error)
    } else {
        download("https://i0.hdslb.com/bfs/new_dyn/e8907352f1c8be0ea696c1447723f6091769278028.png", error) {
            if (it) bitmapDecodeFile(error)
        }
    }
}

inline fun download(
    rawUrl: String,
    file: File,
    crossinline onProgress: (Float) -> Unit = {},
    crossinline callback: (Boolean) -> Unit,
) {
    downloadExecutor.execute {
        var connection: HttpURLConnection? = null
        val uri = rawUrl.toUri()
        val url = if (uri.scheme.isNullOrEmpty()) {
            URL("https://$rawUrl")
        } else URL(rawUrl.replace("http://", "https://"))
        try {
            connection = url.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 60_000 // 60秒超时
            connection.readTimeout = 10_000
            connection.requestMethod = "GET"
            connection.doInput = true
            Utils.log("Download Image From: $url")
            connection.connect()
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                if (!file.exists()) {
                    file.createNewFile()
                }
                // Copy in a loop so we can report download progress (Content-Length) for a
                // determinate indicator; total <= 0 (chunked/unknown) leaves it indeterminate.
                val total = connection.contentLengthLong
                connection.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(16 * 1024)
                        var read: Int
                        var done = 0L
                        while (input.read(buf).also { read = it } >= 0) {
                            out.write(buf, 0, read)
                            done += read
                            if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
                callback(true)
            } else {
                callback(false)
                Utils.log("Download Image Failed! code=${connection.responseCode} url=${connection.url}")
            }
        } catch (e: Exception) {
            callback(false)
            Utils.log("Download Image Exception for $url: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
        } finally {
            connection?.disconnect()
        }
    }
}
