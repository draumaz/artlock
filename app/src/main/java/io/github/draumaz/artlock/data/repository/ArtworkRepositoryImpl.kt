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

        // The same song can have different artwork on a single and on an album
        val cacheKey = "$cleanArtist-$cleanTitle-${trackInfo.album.orEmpty()}"
        cache.get(cacheKey)?.let { return it }

        return try {
            val response = itunesApi.searchTrack(term = "$cleanArtist $cleanTitle")
            if (!response.isSuccessful) return LookupResult.Error("iTunes HTTP ${response.code()}")

            val results = response.body()?.results.orEmpty()
            val artworkUrl = pickBestMatch(results, cleanArtist, trackInfo.album, cleanTitle)?.artworkUrl100
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
         * Picks the result whose artist matches the playing track (search can rank another artist's
         * song with the same title first), then the one from the playing album. A song released as a
         * single before its album appears in both, and search often ranks the single first, so when
         * the playing album isn't the single itself, album releases are preferred over singles/EPs.
         * Ties keep iTunes' ranking.
         */
        fun pickBestMatch(
            results: List<ItunesTrack>,
            artist: String,
            album: String? = null,
            title: String? = null
        ): ItunesTrack? {
            val wantedArtist = artist.lowercase()
            val wantedAlbum = album?.let { cleanText(it).lowercase() }?.ifBlank { null }
            // Apps don't always add the " - Single" suffix; a single's album is usually named after the song
            val playingFromAlbum = wantedAlbum != null && !isSingleOrEp(album) &&
                wantedAlbum != title?.let { cleanText(it).lowercase() }

            return results
                .filter { it.artworkUrl100 != null }
                .maxByOrNull { result ->
                    val name = result.artistName?.lowercase()
                    val collection = result.collectionName?.let { cleanText(it).lowercase() }
                    var score = 0
                    if (name != null && (name.contains(wantedArtist) || wantedArtist.contains(name))) score += 4
                    if (wantedAlbum != null && collection == wantedAlbum) score += 2
                    if (playingFromAlbum && result.collectionName?.let(::isSingleOrEp) == false) score += 1
                    score
                }
        }

        private fun isSingleOrEp(collection: String) =
            collection.contains(Regex("(?i)\\s-\\s(Single|EP)$"))
    }
}
