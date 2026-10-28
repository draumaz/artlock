package io.github.draumaz.artlock.data.repository

import android.util.LruCache
import io.github.draumaz.artlock.data.model.ItunesTrack
import io.github.draumaz.artlock.data.model.LookupResult
import io.github.draumaz.artlock.data.model.TrackInfo
import io.github.draumaz.artlock.data.remote.ItunesApi
import kotlin.coroutines.cancellation.CancellationException

class ArtworkRepositoryImpl(
    private val itunesApi: ItunesApi
) : ArtworkRepository {

    // Only definitive answers (found / not found) are cached; network and HTTP errors are retried
    private val cache = LruCache<String, LookupResult>(100)

    override suspend fun resolveArtwork(trackInfo: TrackInfo): LookupResult {
        val cleanArtist = cleanText(trackInfo.artist)
        val cleanTitle = cleanText(trackInfo.title)
        if (cleanArtist.isBlank()) return LookupResult.Error("Missing artist")

        val cacheKey = "$cleanArtist-$cleanTitle"
        cache.get(cacheKey)?.let { return it }

        return try {
            val response = itunesApi.searchTrack(term = "$cleanArtist $cleanTitle")
            if (!response.isSuccessful) return LookupResult.Error("iTunes HTTP ${response.code()}")

            val results = response.body()?.results.orEmpty()
            val artworkUrl = pickBestMatch(results, cleanArtist)?.artworkUrl100
            val result = if (artworkUrl != null) {
                LookupResult.StaticHighRes(artworkUrl.replace("100x100bb", "1000x1000bb"))
            } else {
                LookupResult.NotFound
            }
            cache.put(cacheKey, result)
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LookupResult.Error(e.message ?: "iTunes request failed")
        }
    }

    companion object {
        /** Strips decorations that music apps add to titles but iTunes doesn't use. */
        fun cleanText(text: String): String = text
            .replace("🅴", "")
            .replace(Regex("(?i)\\s*\\(Explicit\\)"), "")
            .replace(Regex("(?i)\\s*\\[Explicit]"), "")
            .replace(Regex("(?i)\\s*-\\s*EP$"), "")
            .replace(Regex("(?i)\\s*-\\s*Single$"), "")
            .replace(Regex("(?i)\\s*\\(feat\\..*?\\)"), "")
            .trim()

        /**
         * Prefers a result whose artist matches the playing track (search can rank another artist's
         * song with the same title first), falling back to the top result.
         */
        fun pickBestMatch(results: List<ItunesTrack>, artist: String): ItunesTrack? {
            val withArt = results.filter { it.artworkUrl100 != null }
            val wanted = artist.lowercase()
            return withArt.firstOrNull { result ->
                val name = result.artistName?.lowercase() ?: return@firstOrNull false
                name.contains(wanted) || wanted.contains(name)
            } ?: withArt.firstOrNull()
        }
    }
}
