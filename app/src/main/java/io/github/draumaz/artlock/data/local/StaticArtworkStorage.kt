package io.github.draumaz.artlock.data.local

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import io.github.draumaz.artlock.App
import io.github.draumaz.artlock.data.model.TrackInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import okio.buffer
import okio.sink
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException

/** Saves track artwork to the cache directory, keeping only the most recent files. */
class StaticArtworkStorage(
    private val context: Context
) {
    /** Saves artwork supplied by the music app. Returns the file path, or null if it couldn't be written. */
    suspend fun save(bitmap: Bitmap, trackInfo: TrackInfo): String? = withContext(Dispatchers.IO) {
        writeAtomically(fileFor("art", trackInfo)) { tempFile ->
            FileOutputStream(tempFile).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out) }
        }
    }

    /** Downloads high-resolution artwork. Returns the file path, or null on any failure. */
    suspend fun downloadAndSave(imageUrl: String, trackInfo: TrackInfo): String? = withContext(Dispatchers.IO) {
        val client = (context.applicationContext as App).okHttpClient
        writeAtomically(fileFor("highres", trackInfo)) { tempFile ->
            client.newCall(Request.Builder().url(imageUrl).build()).execute().use { response ->
                if (!response.isSuccessful) return@writeAtomically false
                tempFile.sink().buffer().use { sink -> sink.writeAll(response.body.source()) }
            }
            true
        }
    }

    /**
     * Writes to a temp file and renames it into place, so a half-written image is never picked up.
     * [write] returns false (or throws) to abort.
     */
    private suspend fun writeAtomically(finalFile: File, write: (File) -> Boolean): String? {
        val tempFile = File(finalFile.path + ".tmp")
        return try {
            if (!write(tempFile)) return null
            currentCoroutineContext().ensureActive()
            if (finalFile.exists()) finalFile.delete()
            if (tempFile.renameTo(finalFile)) {
                prune()
                finalFile.absolutePath
            } else {
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save artwork to ${finalFile.name}", e)
            null
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    /**
     * File names are a hash of artist + title: works for any script (titles in Cyrillic, Japanese,
     * etc. would otherwise collide once non-ASCII characters were stripped) and is never too long.
     */
    private fun fileFor(prefix: String, trackInfo: TrackInfo): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${trackInfo.artist}\u0000${trackInfo.title}".toByteArray())
            .take(12)
            .joinToString("") { "%02x".format(it) }
        return File(context.cacheDir, "${prefix}_$digest.jpg")
    }

    /** Keeps the newest [MAX_CACHED_FILES] artwork files; the current track's is always among them. */
    private fun prune() {
        context.cacheDir.listFiles { file -> file.name.endsWith(".jpg") && (file.name.startsWith("art_") || file.name.startsWith("highres_")) }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_CACHED_FILES)
            ?.forEach { it.delete() }
    }

    private companion object {
        const val TAG = "StaticArtworkStorage"
        const val MAX_CACHED_FILES = 40
    }
}
