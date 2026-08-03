package io.github.draumaz.artlock.data.remote

import io.github.draumaz.artlock.data.model.ArtworkResponseDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

interface ArtworkApi {

    @GET("api/v1/artwork/search")
    suspend fun searchArtwork(
        @Query("artist") artist: String,
        @Query("album") album: String,
        @Query("title") title: String? = null
    ): Response<ArtworkResponseDto>
}