package io.github.draumaz.artlock.data.model

data class ItunesSearchResponse(val results: List<ItunesTrack>)
data class ItunesTrack(
    val artistName: String?,
    val trackName: String?,
    val artworkUrl100: String?
)
