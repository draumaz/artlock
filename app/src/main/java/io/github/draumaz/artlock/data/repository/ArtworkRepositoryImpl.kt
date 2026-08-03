package io.github.draumaz.artlock.data.repository

import android.util.LruCache
import io.github.draumaz.artlock.data.model.LookupResult
import io.github.draumaz.artlock.data.model.TrackInfo
import io.github.draumaz.artlock.data.remote.ItunesApi

class ArtworkRepositoryImpl(
    private val itunesApi: ItunesApi
) : ArtworkRepository {

    private val cache = LruCache<String, LookupResult>(100)

    private fun cleanText(text: String): String {
        return text.replace("🅴", "")
            .replace(Regex("(?i)\\s*\\(Explicit\\)"), "")
            .replace(Regex("(?i)\\s*\\[Explicit]"), "")
            .replace(Regex("(?i)\\s*-\\s*EP$"), "")
            .replace(Regex("(?i)\\s*-\\s*Single$"), "")
            .replace(Regex("(?i)\\s*\\(feat\\..*?\\)"), "")
            .trim()
    }

    override suspend fun resolveArtwork(trackInfo: TrackInfo): LookupResult {
        val cleanArtist = cleanText(trackInfo.artist)
        val cleanTitle = cleanText(trackInfo.title)
        val cacheKey = "$cleanArtist-$cleanTitle"

        cache.get(cacheKey)?.let { return it }

        if (cleanArtist.isBlank()) {
            return LookupResult.Error("Metadados insuficientes: artista obrigatório.")
        }

        return try {
            val term = "$cleanArtist $cleanTitle"
            val itunesResponse = itunesApi.searchTrack(term = term)

            if (itunesResponse.isSuccessful) {
                val result = itunesResponse.body()?.results?.firstOrNull()
                val thumbnailUrl = result?.artworkUrl100

                if (thumbnailUrl != null) {
                    val highResUrl = thumbnailUrl.replace("100x100bb", "1000x1000bb")
                    val lookupResult = LookupResult.StaticHighRes(highResUrl)
                    cache.put(cacheKey, lookupResult)
                    lookupResult
                } else {
                    cache.put(cacheKey, LookupResult.NotFound)
                    LookupResult.NotFound
                }
            } else {
                val lookupResult = LookupResult.NotFound
                cache.put(cacheKey, lookupResult)
                lookupResult
            }
        } catch (e: Exception) {
            LookupResult.Error(e.message ?: "Erro no iTunes")
        }
    }
}
