package io.github.draumaz.artlock.notification

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import io.github.draumaz.artlock.data.model.TrackInfo

class NotificationMediaParser(private val context: Context) {

    fun parse(notification: Notification, packageName: String): TrackInfo? {
        val extras = notification.extras ?: return null

        var title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        var artist = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        var album = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty().ifBlank { null }

        // Agora a extração não causa mais crashes de ClassCastException!
        var bitmap = extractBitmapFromExtras(extras)

        var isPlaying = false

        try {
            val token = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
            } else {
                @Suppress("DEPRECATION")
                extras.getParcelable(Notification.EXTRA_MEDIA_SESSION) as? MediaSession.Token
            }

            if (token != null) {
                val mediaController = MediaController(context, token)
                val metadata = mediaController.metadata
                val playbackState = mediaController.playbackState

                if (playbackState != null) {
                    isPlaying = (playbackState.state == PlaybackState.STATE_PLAYING || playbackState.state == PlaybackState.STATE_BUFFERING)
                }

                if (metadata != null) {
                    if (album == null) album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)?.trim()?.ifBlank { null }
                    // Session artwork is usually higher resolution than the notification icon; keep the largest
                    bitmap = listOfNotNull(
                        metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART),
                        metadata.getBitmap(MediaMetadata.METADATA_KEY_ART),
                        bitmap
                    ).maxByOrNull { it.width * it.height }
                    if (title.isBlank()) title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
                    if (artist.isBlank()) artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim().orEmpty()
                }
            }
        } catch (_: Exception) {
            // Ignoramos silenciosamente se o MediaController falhar
        }

        if (title.isBlank() || artist.isBlank()) return null

        return TrackInfo(
            title = title,
            artist = artist,
            album = album,
            packageName = packageName,
            isPlaying = isPlaying,
            staticArtworkBitmap = bitmap
        )
    }

    @Suppress("DEPRECATION")
    private fun extractBitmapFromExtras(extras: Bundle): Bitmap? {
        return try {
            // Lemos o objeto como algo genérico, sem forçar que seja um Bitmap
            val obj = extras.get(Notification.EXTRA_LARGE_ICON_BIG) ?: extras.get(Notification.EXTRA_LARGE_ICON)

            // Verificamos o tipo com segurança
            when (obj) {
                is Bitmap -> obj
                else -> null // Se for Icon ou null, retornamos null e o MediaMetadata assume o trabalho
            }
        } catch (_: Exception) {
            null
        }
    }
}