package io.github.draumaz.artlock.data.repository

import io.github.draumaz.artlock.data.model.LookupResult
import io.github.draumaz.artlock.data.model.TrackInfo

interface ArtworkRepository {
    suspend fun resolveArtwork(trackInfo: TrackInfo): LookupResult
}