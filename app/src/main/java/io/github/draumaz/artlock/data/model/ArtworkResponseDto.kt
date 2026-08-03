package io.github.draumaz.artlock.data.model

data class ArtworkResponseDto(
    val url_tall: String?, // Lendo a nova versão retangular da API
    val url: String?,      // Mantendo a versão quadrada original como backup
    val artist: String?,
    val album: String?,
    val isCached: Boolean?
)