package io.github.draumaz.artlock.wallpaper

import android.content.Context
import android.view.SurfaceHolder
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class WallpaperPlayerManager(
    private val context: Context,
    private val onVideoError: () -> Unit // O nosso alarme de emergência
) {
    private var player: ExoPlayer? = null
    private var currentUrl: String? = null

    fun attachSurface(holder: SurfaceHolder) {
        if (player == null) {
            player = ExoPlayer.Builder(context).build().apply {
                volume = 0f
                repeatMode = ExoPlayer.REPEAT_MODE_ALL
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                setVideoSurfaceHolder(holder)

                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        onVideoError() // Se o vídeo engasgar (HDR ou erro de rede), toca o alarme!
                    }
                })

                playWhenReady = true
            }
        } else {
            player?.setVideoSurfaceHolder(holder)
        }
    }

    fun play(url: String) {
        if (url == currentUrl) {
            player?.playWhenReady = true
            return
        }

        currentUrl = url
        val mediaItem = MediaItem.fromUri(url)

        player?.apply {
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
        }
    }

    fun stopAndClearSurface() {
        player?.apply {
            playWhenReady = false
            clearVideoSurface()
        }
    }

    fun pause() {
        player?.playWhenReady = false
    }

    fun resume() {
        player?.playWhenReady = true
    }

    fun detachSurface() {
        player?.clearVideoSurface()
    }

    fun release() {
        player?.release()
        player = null
        currentUrl = null
    }
}