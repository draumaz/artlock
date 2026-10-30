package io.github.draumaz.artlock.data.remote

import io.github.draumaz.artlock.data.model.ItunesSearchResponse
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

interface ItunesApi {
    @GET("search")
    suspend fun searchTrack(
        @Query("term") term: String,
        @Query("entity") entity: String = "song",
        // Enough candidates that the album version is usually present alongside the single
        @Query("limit") limit: Int = 15
    ): Response<ItunesSearchResponse>
}
