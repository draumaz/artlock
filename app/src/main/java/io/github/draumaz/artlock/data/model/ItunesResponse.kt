package io.github.draumaz.artlock.data.model

data class ItunesSearchResponse(val results: List<ItunesTrack>)
data class ItunesTrack(val artworkUrl100: String?)