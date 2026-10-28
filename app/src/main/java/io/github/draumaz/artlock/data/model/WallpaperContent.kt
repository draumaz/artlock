package io.github.draumaz.artlock.data.model

data class WallpaperContent(
    val trackTitle: String? = null,
    val trackArtist: String? = null,
    val trackAlbum: String? = null,
    val sourcePackage: String? = null,
    val contentType: WallpaperContentType = WallpaperContentType.NONE,
    val staticImagePath: String? = null,
    val artworkSource: ArtworkSource? = null,
    // What the music app itself supplied ("WxH"), recorded even when iTunes artwork is used instead
    val systemArtworkSize: String? = null,
    val systemArtworkPlaceholder: Boolean = false,
    val updatedAt: Long = 0L
)