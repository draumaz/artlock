package io.github.draumaz.artlock.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.draumaz.artlock.data.model.WallpaperContent
import io.github.draumaz.artlock.data.model.WallpaperContentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "wallpaper_state")

class WallpaperStateStore(
    private val context: Context
) {
    private object Keys {
        val TRACK_TITLE = stringPreferencesKey("track_title")
        val TRACK_ARTIST = stringPreferencesKey("track_artist")
        val TRACK_ALBUM = stringPreferencesKey("track_album")
        val SOURCE_PACKAGE = stringPreferencesKey("source_package")
        val CONTENT_TYPE = stringPreferencesKey("content_type")
        val ANIMATED_URL = stringPreferencesKey("animated_url")
        val STATIC_IMAGE_PATH = stringPreferencesKey("static_image_path")
        val UPDATED_AT = longPreferencesKey("updated_at")

        // CHAVE PARA A FOTO PADRÃO
        val DEFAULT_WALLPAPER_PATH = stringPreferencesKey("default_wallpaper_path")

        // CHAVE NOVA PARA O NÍVEL DE ESCURECIMENTO (0 a 100)
        val DIM_LEVEL = intPreferencesKey("dim_level")

        // CHAVE PARA O NÍVEL DE BLUR (0 a 100)
        val BLUR_LEVEL = intPreferencesKey("blur_level")

        val SUPPORTED_PACKAGES = androidx.datastore.preferences.core.stringSetPreferencesKey("supported_packages")
    }

    val contentFlow: Flow<WallpaperContent> = context.dataStore.data.map { prefs ->
        val typeName = prefs[Keys.CONTENT_TYPE] ?: WallpaperContentType.NONE.name

        WallpaperContent(
            trackTitle = prefs[Keys.TRACK_TITLE].orEmpty().ifBlank { null },
            trackArtist = prefs[Keys.TRACK_ARTIST].orEmpty().ifBlank { null },
            trackAlbum = prefs[Keys.TRACK_ALBUM].orEmpty().ifBlank { null },
            sourcePackage = prefs[Keys.SOURCE_PACKAGE].orEmpty().ifBlank { null },
            contentType = runCatching { WallpaperContentType.valueOf(typeName) }
                .getOrDefault(WallpaperContentType.NONE),
            animatedUrl = prefs[Keys.ANIMATED_URL].orEmpty().ifBlank { null },
            staticImagePath = prefs[Keys.STATIC_IMAGE_PATH].orEmpty().ifBlank { null },
            updatedAt = prefs[Keys.UPDATED_AT] ?: 0L
        )
    }

    // Fluxo separado só para ler a foto padrão
    val defaultWallpaperFlow: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[Keys.DEFAULT_WALLPAPER_PATH]
    }

    // Fluxo novo para ler o nível de escurecimento (Padrão: 30%)
    val dimLevelFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.DIM_LEVEL] ?: 30
    }

    // Fluxo novo para ler o nível de blur (Padrão: 0%)
    val blurLevelFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.BLUR_LEVEL] ?: 0
    }

    val supportedPackagesFlow: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[Keys.SUPPORTED_PACKAGES] ?: io.github.draumaz.artlock.notification.SupportedMusicApps.packages
    }

    suspend fun save(content: WallpaperContent) {
        context.dataStore.edit { prefs ->
            prefs[Keys.TRACK_TITLE] = content.trackTitle.orEmpty()
            prefs[Keys.TRACK_ARTIST] = content.trackArtist.orEmpty()
            prefs[Keys.TRACK_ALBUM] = content.trackAlbum.orEmpty()
            prefs[Keys.SOURCE_PACKAGE] = content.sourcePackage.orEmpty()
            prefs[Keys.CONTENT_TYPE] = content.contentType.name
            prefs[Keys.ANIMATED_URL] = content.animatedUrl.orEmpty()
            prefs[Keys.STATIC_IMAGE_PATH] = content.staticImagePath.orEmpty()
            prefs[Keys.UPDATED_AT] = content.updatedAt
        }
    }

    // Função para salvar a foto que você escolher na galeria
    suspend fun saveDefaultWallpaper(path: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DEFAULT_WALLPAPER_PATH] = path
            
            // Se não houver nada tocando (ou se já estivermos no modo estático sem track), atualizamos o estado atual
            val currentType = prefs[Keys.CONTENT_TYPE] ?: WallpaperContentType.NONE.name
            val currentTrack = prefs[Keys.TRACK_TITLE]
            
            if (currentType == WallpaperContentType.NONE.name || (currentType == WallpaperContentType.STATIC.name && currentTrack.isNullOrBlank())) {
                prefs[Keys.CONTENT_TYPE] = WallpaperContentType.STATIC.name
                prefs[Keys.STATIC_IMAGE_PATH] = path
                prefs[Keys.UPDATED_AT] = System.currentTimeMillis()
                prefs[Keys.TRACK_TITLE] = ""
                prefs[Keys.TRACK_ARTIST] = ""
            }
        }
    }

    // Função nova para salvar o nível de escurecimento do Slider
    suspend fun saveDimLevel(level: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DIM_LEVEL] = level
        }
    }

    // Função nova para salvar o nível de blur do Slider
    suspend fun saveBlurLevel(level: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.BLUR_LEVEL] = level
        }
    }

    suspend fun saveSupportedPackages(packages: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SUPPORTED_PACKAGES] = packages
        }
    }
}
