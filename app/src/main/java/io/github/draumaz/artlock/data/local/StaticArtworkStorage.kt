package io.github.draumaz.artlock.data.local

import android.content.Context
import android.graphics.Bitmap
import io.github.draumaz.artlock.App
import io.github.draumaz.artlock.data.model.TrackInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import okio.buffer
import okio.sink
import java.io.File
import java.io.FileOutputStream

class StaticArtworkStorage(
    private val context: Context
) {
    // Salva a foto ruim do Android (Plano C)
    suspend fun save(bitmap: Bitmap, trackInfo: TrackInfo): String = withContext(Dispatchers.IO) {
        val safeArtist = sanitize(trackInfo.artist)
        val safeTitle = sanitize(trackInfo.title)
        val finalFile = File(context.cacheDir, "art_${safeArtist}_${safeTitle}.jpg")
        val tempFile = File(context.cacheDir, "art_${safeArtist}_${safeTitle}.jpg.tmp")

        try {
            FileOutputStream(tempFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                out.flush()
            }
            
            ensureActive()
            
            if (tempFile.exists()) {
                if (finalFile.exists()) finalFile.delete()
                tempFile.renameTo(finalFile)
            }
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
        finalFile.absolutePath
    }

    // BAIXA A FOTO 4K DA INTERNET (Plano B)
    suspend fun downloadAndSave(imageUrl: String, trackInfo: TrackInfo): String? = withContext(Dispatchers.IO) {
        val safeArtist = sanitize(trackInfo.artist)
        val safeTitle = sanitize(trackInfo.title)
        val finalFile = File(context.cacheDir, "highres_${safeArtist}_${safeTitle}.jpg")
        val tempFile = File(context.cacheDir, "highres_${safeArtist}_${safeTitle}.jpg.tmp")

        try {
            val app = context.applicationContext as App
            val client = app.okHttpClient
            
            val request = Request.Builder().url(imageUrl).build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                
                tempFile.sink().buffer().use { sink ->
                    response.body?.source()?.let { source ->
                        sink.writeAll(source)
                    }
                    sink.flush()
                }
            }
            
            ensureActive()
            
            if (tempFile.exists()) {
                if (finalFile.exists()) finalFile.delete()
                if (tempFile.renameTo(finalFile)) {
                    finalFile.absolutePath
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            null // Se der erro de internet ou cancelamento, devolve nulo
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    private fun sanitize(value: String): String {
        return value.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }
}