package io.github.draumaz.artlock.data.model

data class WallpaperContent(
    val trackTitle: String? = null,
    val trackArtist: String? = null,
    val trackAlbum: String? = null,
    val sourcePackage: String? = null,
    val contentType: WallpaperContentType = WallpaperContentType.NONE,
    val animatedUrl: String? = null,
    val staticImagePath: String? = null,
    val updatedAt: Long = 0L
)