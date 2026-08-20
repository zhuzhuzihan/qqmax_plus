package momoi.mod.qqpro.util

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Emergency recovery for a corrupted chat-record database.
 *
 * Background: this app (NWear-QQ / com.tencent.qqlite) stores ALL chat history in
 * `/data/data/com.tencent.qqlite/databases/nt_msg.db` (SQLCipher-encrypted, WAL journal mode).
 * When that file is corrupted (bad shutdown, low storage, ps-key mismatch), message loading
 * silently fails: the kernel returns an empty list, the chat looks "empty", and the in-app
 * 清空聊天记录 only issues a logical DELETE against the same broken DB — it cannot repair the file.
 *
 * The only reliable recovery is to delete the corrupt DB file; the kernel recreates an empty one
 * on next launch and re-syncs recent messages from the server (roam). This helper does that with
 * a mandatory backup first, so the local history is recoverable (e.g. via adb / file export)
 * instead of being gone forever.
 *
 * Deliberately NOT an ApkMixin hook and uses only plain Android APIs, so it can be reused from
 * settings UI / debug UI and kept testable without the QQ kernel.
 */
object ChatDbRecovery {

    /** Subdirectory (under the app's external files dir) where the backup lands. */
    const val BACKUP_DIR_NAME = "qqpro_db_backup"

    /** DB files that hold chat history (message body) — the ones to back up and delete.
     *  `nt_msg.db` is the message store; `-wal` / `-shm` are its WAL journal companions and MUST be
     *  removed together, otherwise the stale WAL replays deleted pages on next open.
     *  Search indexes (msg_fts*) are NOT deleted: they are derived data, cheap to rebuild, and the
     *  kernel regenerates them — deleting them would only add risk. */
    private val TARGET_FILES = listOf("nt_msg.db", "nt_msg.db-wal", "nt_msg.db-shm")

    data class Result(
        val backedUpTo: File?,   // null if backup failed (we then refuse to delete)
        val deleted: List<String>, // file names actually removed
        val failed: List<String> // file names that could not be removed
    )

    /** The app's databases directory (same process, so we can read it directly). */
    fun databaseDir(context: Context): File =
        File(context.applicationInfo.dataDir, "databases")

    /** Backup dir under external files: Android/data/<pkg>/files/qqpro_db_backup/ — no permission
     *  needed, survives app-data clears, and can be pulled off the watch. */
    fun backupDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, BACKUP_DIR_NAME)
    }

    /**
     * Back up every existing target DB file into a timestamped subfolder, then delete the originals.
     * Runs synchronously — call from a background thread. If backup fails, NOTHING is deleted
     * (fail-safe: never destroy the only copy of the user's history).
     *
     * @return per-file outcome; [Result.backedUpTo] is null iff backup failed for any target file.
     */
    fun backupAndDelete(context: Context): Result {
        val dbDir = databaseDir(context)
        // Millisecond precision so repeated invocations within the same second (e.g. a double tap or
        // a retry) get distinct backup folders instead of colliding.
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.US).format(Date())
        val destDir = File(backupDir(context), "chatdb_$stamp")
        // `mkdirs` returns false both when creation FAILS and when the dir already exists; only the
        // former is an error. Treating "already exists" as failure would make a same-timestamp retry
        // falsely report backup failure (found by logic tests). Reuse the existing dir instead.
        if (!destDir.exists() && !destDir.mkdirs()) {
            Utils.log("ChatDbRecovery: cannot create backup dir ${destDir.absolutePath}")
            return Result(null, emptyList(), TARGET_FILES)
        }

        val deleted = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (name in TARGET_FILES) {
            val src = File(dbDir, name)
            if (!src.exists()) continue // nothing to do for a missing file
            try {
                copyFile(src, File(destDir, name))
            } catch (e: IOException) {
                Utils.log("ChatDbRecovery: backup FAILED for $name: $e")
                // Fail-safe: leave the originals untouched — never delete un-backupable data.
                return Result(null, deleted, TARGET_FILES)
            }
            if (src.delete()) {
                Utils.log("ChatDbRecovery: deleted $name")
                deleted.add(name)
            } else {
                Utils.log("ChatDbRecovery: delete FAILED for $name")
                failed.add(name)
            }
        }
        return Result(destDir, deleted, failed)
    }

    private fun copyFile(src: File, dst: File) {
        FileInputStream(src).use { input ->
            FileOutputStream(dst).use { output ->
                input.copyTo(output, bufferSize = 64 * 1024)
            }
        }
    }
}
