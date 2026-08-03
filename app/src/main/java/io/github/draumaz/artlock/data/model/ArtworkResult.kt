package io.github.draumaz.artlock.data.model

data class ArtworkResult(
    val hlsUrl: String,
    val artist: String,
    val album: String,
    val isCached: Boolean
)