package io.github.draumaz.artlock.data.model

/** Where the current track's artwork came from. */
enum class ArtworkSource {
    /** Supplied by the music app through the notification / media session */
    SYSTEM,

    /** Fetched from the iTunes Search API */
    ITUNES
}
