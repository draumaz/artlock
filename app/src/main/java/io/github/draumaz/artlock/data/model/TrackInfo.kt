package io.github.draumaz.artlock.data.model

import android.graphics.Bitmap

data class TrackInfo(
    val title: String,
    val artist: String,
    val album: String? = null,
    val packageName: String,
    val isPlaying: Boolean = true,
    val timestamp: Long = System.currentTimeMillis(),
    val staticArtworkBitmap: Bitmap? = null
)