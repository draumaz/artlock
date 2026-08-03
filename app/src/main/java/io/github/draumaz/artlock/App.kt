package io.github.draumaz.artlock

import android.app.Application
import io.github.draumaz.artlock.data.local.WallpaperStateStore
import io.github.draumaz.artlock.data.model.WallpaperContent
import io.github.draumaz.artlock.data.remote.ItunesApi
import io.github.draumaz.artlock.data.repository.ArtworkRepository
import io.github.draumaz.artlock.data.repository.ArtworkRepositoryImpl
import kotlinx.coroutines.flow.MutableSharedFlow
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class App : Application() {

    lateinit var artworkRepository: ArtworkRepository
        private set

    lateinit var wallpaperStateStore: WallpaperStateStore
        private set

    // Real-time updates bypassing DataStore disk latency
    val liveWallpaperFlow = MutableSharedFlow<WallpaperContent>(extraBufferCapacity = 8)

    lateinit var okHttpClient: OkHttpClient
        private set

    override fun onCreate() {
        super.onCreate()

        val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
        okHttpClient = OkHttpClient.Builder().addInterceptor(logging).build()

        // Conexão com o iTunes (Fotos 4K)
        val retrofitItunes = Retrofit.Builder()
            .baseUrl("https://itunes.apple.com/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        val itunesApi = retrofitItunes.create(ItunesApi::class.java)

        wallpaperStateStore = WallpaperStateStore(this)

        // Entregamos a conexão pro nosso Repositório trabalhar!
        artworkRepository = ArtworkRepositoryImpl(itunesApi)
    }
}