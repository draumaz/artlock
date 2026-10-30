package io.github.draumaz.artlock

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.Formatter
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.draumaz.artlock.data.model.ArtworkSource
import io.github.draumaz.artlock.data.model.WallpaperContent
import io.github.draumaz.artlock.ui.AppSelectionScreen
import io.github.draumaz.artlock.ui.TransitionCorners
import io.github.draumaz.artlock.ui.navEnter
import io.github.draumaz.artlock.ui.navExit
import io.github.draumaz.artlock.ui.navPopEnter
import io.github.draumaz.artlock.ui.navPopExit
import io.github.draumaz.artlock.ui.theme.ArtlockTheme
import io.github.draumaz.artlock.wallpaper.AnimatedWallpaperService
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            lifecycleScope.launch {
                val savedPath = withContext(Dispatchers.IO) {
                    val file = File(filesDir, "default_wallpaper_${System.currentTimeMillis()}.jpg")
                    try {
                        val input = contentResolver.openInputStream(uri) ?: return@withContext null
                        input.use { source -> FileOutputStream(file).use { source.copyTo(it) } }
                        // Only remove the previous background once the new one is safely copied
                        filesDir.listFiles { f -> f.name.startsWith("default_wallpaper_") && f != file }?.forEach { it.delete() }
                        file.absolutePath
                    } catch (e: Exception) {
                        Log.w("MainActivity", "Failed to copy background image", e)
                        file.delete()
                        null
                    }
                }

                if (savedPath != null) {
                    (application as App).wallpaperStateStore.saveDefaultWallpaper(savedPath)
                    Toast.makeText(this@MainActivity, R.string.toast_wallpaper_saved, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, R.string.toast_wallpaper_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as App

        setContent {
            ArtlockTheme {
                val navController = rememberNavController()

                NavHost(
                    navController = navController,
                    startDestination = "main",
                    enterTransition = navEnter,
                    exitTransition = navExit,
                    popEnterTransition = navPopEnter,
                    popExitTransition = navPopExit
                ) {
                    composable("main") {
                        TransitionCorners {
                            MainScreen(
                                onNavigateToAppSelection = { navController.navigate("app_selection") },
                                onPickImage = { pickImageLauncher.launch("image/*") }
                            )
                        }
                    }
                    composable("app_selection") {
                        TransitionCorners {
                            // Null until loaded, so a toggle can't be applied to an empty set and wipe the saved selection
                            val supportedPackages by app.wallpaperStateStore.supportedPackagesFlow.collectAsStateWithLifecycle(initialValue = null)
                            AppSelectionScreen(
                                selectedPackages = supportedPackages,
                                onPackagesChanged = { newSet ->
                                    lifecycleScope.launch {
                                        app.wallpaperStateStore.saveSupportedPackages(newSet)
                                    }
                                },
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    fun MainScreen(onNavigateToAppSelection: () -> Unit, onPickImage: () -> Unit) {
        val app = application as App
        var isNotificationEnabled by remember { mutableStateOf(checkNotificationAccess()) }
        var isWallpaperActive by remember { mutableStateOf(checkWallpaperActive()) }
        var showWallpaperHint by remember { mutableStateOf(false) }
        var wasWallpaperPickerLaunched by remember { mutableStateOf(false) }

        val wallpaperContent by app.wallpaperStateStore.contentFlow.collectAsStateWithLifecycle(initialValue = null)
        val dimLevel by app.wallpaperStateStore.dimLevelFlow.collectAsStateWithLifecycle(initialValue = 30)
        val blurLevel by app.wallpaperStateStore.blurLevelFlow.collectAsStateWithLifecycle(initialValue = 0)
        val defaultWallpaperPath by app.wallpaperStateStore.defaultWallpaperFlow.collectAsStateWithLifecycle(initialValue = null)
        val onlineLookupEnabled by app.wallpaperStateStore.onlineLookupEnabledFlow.collectAsStateWithLifecycle(initialValue = true)

        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    isNotificationEnabled = checkNotificationAccess()
                    isWallpaperActive = checkWallpaperActive()
                    if (wasWallpaperPickerLaunched) {
                        // Only offer troubleshooting if the wallpaper was actually set but nothing is
                        // being picked up as playing
                        showWallpaperHint = isWallpaperActive && wallpaperContent?.trackTitle == null
                        wasWallpaperPickerLaunched = false
                    }
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }

        if (showWallpaperHint) {
            AlertDialog(
                onDismissRequest = { showWallpaperHint = false },
                title = { Text(stringResource(R.string.wallpaper_hint_title)) },
                text = { Text(stringResource(R.string.wallpaper_hint_message)) },
                confirmButton = {
                    TextButton(onClick = { showWallpaperHint = false }) {
                        Text(stringResource(R.string.ok))
                    }
                }
            )
        }

        Scaffold(
            // Segmented list items use a surface container, so the page sits one tone lower
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp)
            ) {
                // The title scrolls with the content rather than sitting in an app bar, so there's
                // no header area for the list to be clipped against
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 4.dp, top = 24.dp, bottom = 8.dp)
                )
                SectionHeader(stringResource(R.string.permissions_section_title))
                NotificationAccessItem(
                    enabled = isNotificationEnabled,
                    onClick = {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }
                )

                SectionHeader(stringResource(R.string.live_status_header))
                NowPlayingItem(wallpaperContent)

                SectionHeader(stringResource(R.string.actions_section_title))
                val showingTrackArt = wallpaperContent?.trackTitle != null && wallpaperContent?.staticImagePath != null
                ActionsGroup(
                    backgroundImagePath = defaultWallpaperPath,
                    // What the wallpaper is showing: the playing track's art, else the background
                    wallpaperImagePath = wallpaperContent?.staticImagePath ?: defaultWallpaperPath,
                    isWallpaperActive = isWallpaperActive,
                    // Only track art is previewed with the effects; the background is shown as-is
                    blurLevel = if (showingTrackArt) blurLevel else 0,
                    dimLevel = if (showingTrackArt) dimLevel else 0,
                    onPickImage = onPickImage,
                    onSetWallpaper = {
                        wasWallpaperPickerLaunched = true
                        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                            putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(this@MainActivity, AnimatedWallpaperService::class.java))
                        }
                        startActivity(intent)
                    },
                    onNavigateToAppSelection = onNavigateToAppSelection
                )

                SectionHeader(stringResource(R.string.artwork_section_title))
                OnlineLookupItem(
                    enabled = onlineLookupEnabled,
                    onEnabledChange = { enabled ->
                        lifecycleScope.launch {
                            app.wallpaperStateStore.saveOnlineLookupEnabled(enabled)
                        }
                    }
                )

                SectionHeader(stringResource(R.string.dimming_section_title))
                AppearanceGroup(
                    dimLevel = dimLevel,
                    onDimChange = { newValue ->
                        lifecycleScope.launch {
                            app.wallpaperStateStore.saveDimLevel(newValue.toInt())
                        }
                    },
                    blurLevel = blurLevel,
                    onBlurChange = { newValue ->
                        lifecycleScope.launch {
                            app.wallpaperStateStore.saveBlurLevel(newValue.toInt())
                        }
                    }
                )

                VersionFooter()
            }
        }
    }

    /** Whether Artlock's live wallpaper is currently set on the home or (Android 14+) lock screen. */
    private fun checkWallpaperActive(): Boolean {
        val component = ComponentName(this, AnimatedWallpaperService::class.java)
        val wallpaperManager = WallpaperManager.getInstance(this)
        val onHome = wallpaperManager.wallpaperInfo?.component == component
        val onLock = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            wallpaperManager.getWallpaperInfo(WallpaperManager.FLAG_LOCK)?.component == component
        return onHome || onLock
    }

    private fun checkNotificationAccess(): Boolean {
        return packageName in NotificationManagerCompat.getEnabledListenerPackages(this)
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp)
    )
}

private val OptionPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)

/** Vertically stacked segmented list items, separated by the M3 segmented gap. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SegmentedGroup(content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        content()
    }
}

@Composable
fun LeadingIcon(
    imageVector: ImageVector,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    shape: Shape = CircleShape,
    size: Dp = 40.dp
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(containerColor),
        contentAlignment = Alignment.Center
    ) {
        Icon(imageVector, contentDescription = null, tint = contentColor, modifier = Modifier.size(24.dp))
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NotificationAccessItem(enabled: Boolean, onClick: () -> Unit) {
    val title = stringResource(R.string.notification_access_title)
    if (enabled) {
        SegmentedListItem(
            onClick = onClick,
            shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1),
            contentPadding = OptionPadding,
            leadingContent = { LeadingIcon(Icons.Default.NotificationsActive) },
            supportingContent = { Text(stringResource(R.string.notification_access_granted_summary)) },
            trailingContent = { Icon(Icons.Default.CheckCircle, contentDescription = null, tint = successColor()) }
        ) {
            Text(title)
        }
    } else {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(OptionPadding)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LeadingIcon(
                        imageVector = Icons.Default.NotificationsOff,
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.notification_access_required_summary),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onClick,
                    modifier = Modifier.align(Alignment.End),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text(stringResource(R.string.btn_notification_access))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NowPlayingItem(content: WallpaperContent?) {
    val artwork by produceState<ImageBitmap?>(initialValue = null, content?.staticImagePath) {
        value = content?.staticImagePath?.let { path -> withContext(Dispatchers.IO) { decodeThumbnail(path) } }
    }
    val title = content?.trackTitle

    SegmentedListItem(
        shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1),
        contentPadding = OptionPadding,
        leadingContent = { NowPlayingArt(art = artwork.takeIf { title != null }, spinning = title != null) },
        trailingContent = if (title != null) {
            { ArtworkDetails(content) }
        } else {
            null
        },
        supportingContent = {
            Text(
                if (title != null) content.trackArtist ?: "-"
                else stringResource(R.string.nothing_playing_summary)
            )
        }
    ) {
        Text(title ?: stringResource(R.string.nothing_playing))
    }
}

private data class ArtworkFileInfo(val width: Int, val height: Int, val bytes: Long)

/** Technical details about the current artwork: dimensions, file size, and where it came from. */
@Composable
private fun ArtworkDetails(content: WallpaperContent) {
    val context = LocalContext.current
    val fileInfo by produceState<ArtworkFileInfo?>(initialValue = null, content.staticImagePath) {
        value = content.staticImagePath?.let { path ->
            withContext(Dispatchers.IO) {
                val file = File(path)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                if (file.exists() && bounds.outWidth > 0) ArtworkFileInfo(bounds.outWidth, bounds.outHeight, file.length()) else null
            }
        }
    }
    val appName = remember(content.sourcePackage) {
        content.sourcePackage?.let { pkg ->
            runCatching {
                context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)
        }
    } ?: "?"
    val systemArtwork = when {
        content.systemArtworkPlaceholder -> stringResource(R.string.artwork_placeholder)
        content.systemArtworkSize != null -> content.systemArtworkSize
        else -> stringResource(R.string.artwork_none)
    }

    // The source actually in use is highlighted; one that was considered but not used is dimmed
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val active = MaterialTheme.colorScheme.primary
    val inactive = neutral.copy(alpha = 0.6f)
    val lines = buildList {
        fileInfo?.let { add(DetailLine("${it.width}×${it.height} · ${Formatter.formatShortFileSize(context, it.bytes)}", neutral)) }
        when (content.artworkSource) {
            ArtworkSource.SYSTEM -> add(DetailLine(stringResource(R.string.artwork_source_system, appName), active, emphasized = true))
            ArtworkSource.ITUNES -> {
                add(DetailLine(stringResource(R.string.artwork_source_itunes), active, emphasized = true))
                add(DetailLine("$appName: $systemArtwork", inactive))
            }
            // No artwork recorded at all; only say the app supplied none if there's genuinely no
            // image (content saved by older versions has an image but no recorded source)
            null -> if (content.staticImagePath == null) add(DetailLine("$appName: $systemArtwork", inactive))
        }
    }
    Column(horizontalAlignment = Alignment.End) {
        lines.forEach { line ->
            Text(
                line.text,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (line.emphasized) FontWeight.Bold else null,
                color = line.color
            )
        }
    }
}

/**
 * Centered "Artlock, version X" footer with a credits line below. The app name links to the GitHub
 * project and each author's name to their GitHub profile.
 */
@Composable
fun VersionFooter() {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    val linkStyle = TextLinkStyles(
        style = SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 32.dp, bottom = 8.dp)
    ) {
        FooterText(
            R.string.footer_version,
            listOf(stringResource(R.string.app_name) to GITHUB_URL),
            linkStyle,
            version
        )
        FooterText(
            R.string.footer_credits,
            listOf(ORIGINAL_AUTHOR to "https://github.com/$ORIGINAL_AUTHOR", FORK_AUTHOR to "https://github.com/$FORK_AUTHOR"),
            linkStyle
        )
    }
}

/**
 * A localized footer line whose first placeholders are filled with links. Each link is formatted in
 * as a marker and the template split around it, so only the linked words become links.
 */
@Composable
private fun FooterText(template: Int, links: List<Pair<String, String>>, linkStyle: TextLinkStyles, vararg extraArgs: Any) {
    val markers = links.indices.map { "\u0000$it\u0000" }
    val formatted = stringResource(template, *(markers + extraArgs).toTypedArray())
    Text(
        text = buildAnnotatedString {
            var rest = formatted
            links.forEachIndexed { index, (label, url) ->
                val parts = rest.split(markers[index], limit = 2)
                append(parts[0])
                withLink(LinkAnnotation.Url(url, linkStyle)) { append(label) }
                rest = parts.getOrElse(1) { "" }
            }
            append(rest)
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
}

private const val GITHUB_URL = "https://github.com/draumaz/artlock"
private const val ORIGINAL_AUTHOR = "lviniciuss-maker"
private const val FORK_AUTHOR = "draumaz"

private data class DetailLine(val text: String, val color: Color, val emphasized: Boolean = false)

/**
 * Album art masked by a scalloped cookie shape. While something is playing the scallop slowly
 * turns with the art like a record; the placeholder icon is counter-rotated so it stays upright.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NowPlayingArt(art: ImageBitmap?, spinning: Boolean, size: Dp = 56.dp) {
    val shape = MaterialShapes.Cookie9Sided.toShape()
    val rotation = if (spinning) {
        val transition = rememberInfiniteTransition(label = "nowPlayingArt")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 16_000, easing = LinearEasing)),
            label = "rotation"
        )
    } else {
        null
    }

    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer { rotationZ = rotation?.value ?: 0f }
            .clip(shape)
            .background(MaterialTheme.colorScheme.tertiaryContainer),
        contentAlignment = Alignment.Center
    ) {
        if (art != null) {
            Image(
                bitmap = art,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                Icons.Default.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier
                    .graphicsLayer { rotationZ = -(rotation?.value ?: 0f) }
                    .size(24.dp)
            )
        }
    }
}

private fun decodeThumbnail(path: String, targetPx: Int = 168): ImageBitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) sample *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
} catch (_: Exception) {
    null
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ActionsGroup(
    backgroundImagePath: String?,
    wallpaperImagePath: String?,
    isWallpaperActive: Boolean,
    blurLevel: Int,
    dimLevel: Int,
    onPickImage: () -> Unit,
    onSetWallpaper: () -> Unit,
    onNavigateToAppSelection: () -> Unit
) {
    val backgroundImage by produceState<ImageBitmap?>(initialValue = null, backgroundImagePath) {
        value = backgroundImagePath?.let { path -> withContext(Dispatchers.IO) { decodeThumbnail(path, targetPx = 360) } }
    }
    val wallpaperImage by produceState<ImageBitmap?>(initialValue = null, wallpaperImagePath) {
        value = wallpaperImagePath?.let { path -> withContext(Dispatchers.IO) { decodeThumbnail(path, targetPx = 360) } }
    }
    val actions = listOf(
        ActionItem(Icons.Default.Image, R.string.chip_background, R.string.btn_choose_default_photo, step = 1, onPickImage, image = backgroundImage, done = backgroundImagePath != null),
        ActionItem(Icons.Default.Wallpaper, R.string.chip_wallpaper, R.string.btn_choose_wallpaper, step = 2, onSetWallpaper, image = wallpaperImage, imageShowsEffects = true, done = isWallpaperActive, doneSummary = R.string.wallpaper_set_summary),
        ActionItem(Icons.Default.LibraryMusic, R.string.chip_apps, R.string.btn_select_music_apps, step = null, onNavigateToAppSelection, optional = true)
    )
    SegmentedGroup {
        actions.forEachIndexed { index, action ->
            val shapes = ListItemDefaults.segmentedShapes(index = index, count = actions.size)
            val image = action.image
            if (image != null) {
                // Show the chosen image behind the row, under a scrim that keeps the text legible.
                // The shape is held constant so the image's clip always matches the row.
                Box(modifier = Modifier.clip(shapes.shape)) {
                    // The wallpaper preview gets the same blur and dimming as the wallpaper. The
                    // row is about screen-width, so the wallpaper's px radius carries over as-is.
                    val blurRadius = with(LocalDensity.current) { (blurLevel * 2.5f).toDp() }
                    Image(
                        bitmap = image,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .matchParentSize()
                            .then(
                                if (action.imageShowsEffects && blurLevel > 0) {
                                    Modifier.blur(blurRadius, BlurredEdgeTreatment.Unbounded)
                                } else {
                                    Modifier
                                }
                            )
                    )
                    if (action.imageShowsEffects && dimLevel > 0) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .background(Color.Black.copy(alpha = dimLevel.coerceIn(0, 100) / 100f))
                        )
                    }
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(
                                Brush.horizontalGradient(
                                    listOf(Color.Black.copy(alpha = 0.75f), Color.Black.copy(alpha = 0.35f))
                                )
                            )
                    )
                    ActionListItem(
                        action = action,
                        shapes = shapes.copy(
                            pressedShape = shapes.shape,
                            focusedShape = shapes.shape,
                            hoveredShape = shapes.shape
                        ),
                        colors = ListItemDefaults.segmentedColors(
                            containerColor = Color.Transparent,
                            contentColor = Color.White,
                            supportingContentColor = Color.White.copy(alpha = 0.85f),
                            overlineContentColor = Color.White,
                            trailingContentColor = Color.White
                        ),
                        overlineColor = Color.White
                    )
                }
            } else {
                ActionListItem(action = action, shapes = shapes)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ActionListItem(
    action: ActionItem,
    shapes: ListItemShapes,
    colors: ListItemColors = ListItemDefaults.segmentedColors(),
    overlineColor: Color = MaterialTheme.colorScheme.primary
) {
    SegmentedListItem(
        onClick = action.onClick,
        shapes = shapes,
        colors = colors,
        contentPadding = OptionPadding,
        leadingContent = { LeadingIcon(action.icon) },
        overlineContent = when {
            action.step != null -> { { Text(stringResource(R.string.step_label, action.step), color = overlineColor) } }
            action.optional -> { { Text(stringResource(R.string.optional_label), color = overlineColor) } }
            else -> null
        },
        supportingContent = {
            val summary = action.doneSummary?.takeIf { action.done } ?: action.summary
            Text(stringResource(summary))
        },
        trailingContent = {
            if (action.done) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = successColor())
            } else {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            }
        }
    ) {
        Text(stringResource(action.title))
    }
}

private data class ActionItem(
    val icon: ImageVector,
    val title: Int,
    val summary: Int,
    val step: Int?,
    val onClick: () -> Unit,
    val image: ImageBitmap? = null,
    val imageShowsEffects: Boolean = false,
    val done: Boolean = false,
    val doneSummary: Int? = null,
    val optional: Boolean = false
)

/** Material's color scheme has no success role, so use the Material green tones for each theme. */
@Composable
private fun successColor(): Color =
    if (isSystemInDarkTheme()) Color(0xFF6DD58C) else Color(0xFF146C2E)

/** Toggle for fetching artwork from the iTunes API; turning it off asks for confirmation first. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OnlineLookupItem(enabled: Boolean, onEnabledChange: (Boolean) -> Unit) {
    var showDisableWarning by remember { mutableStateOf(false) }

    if (showDisableWarning) {
        AlertDialog(
            onDismissRequest = { showDisableWarning = false },
            title = { Text(stringResource(R.string.online_lookup_warning_title)) },
            text = { Text(stringResource(R.string.online_lookup_warning_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDisableWarning = false
                    onEnabledChange(false)
                }) {
                    Text(stringResource(R.string.online_lookup_warning_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisableWarning = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    val onToggle = { if (enabled) showDisableWarning = true else onEnabledChange(true) }
    SegmentedListItem(
        onClick = onToggle,
        shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1),
        contentPadding = OptionPadding,
        leadingContent = { LeadingIcon(Icons.Default.Public) },
        supportingContent = {
            Text(stringResource(if (enabled) R.string.online_lookup_enabled_summary else R.string.online_lookup_disabled_summary))
        },
        trailingContent = { Switch(checked = enabled, onCheckedChange = { onToggle() }) }
    ) {
        Text(stringResource(R.string.online_lookup_title))
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppearanceGroup(
    dimLevel: Int,
    onDimChange: (Float) -> Unit,
    blurLevel: Int,
    onBlurChange: (Float) -> Unit
) {
    SegmentedGroup {
        SliderItem(
            index = 0,
            count = 2,
            icon = Icons.Default.BlurOn,
            title = stringResource(R.string.chip_blur),
            value = blurLevel,
            onValueChange = onBlurChange
        )
        SliderItem(
            index = 1,
            count = 2,
            icon = Icons.Default.Brightness6,
            title = stringResource(R.string.chip_dimming),
            value = dimLevel,
            onValueChange = onDimChange
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SliderItem(
    index: Int,
    count: Int,
    icon: ImageVector,
    title: String,
    value: Int,
    onValueChange: (Float) -> Unit
) {
    SegmentedListItem(
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        contentPadding = OptionPadding,
        verticalAlignment = Alignment.Top,
        leadingContent = { LeadingIcon(icon) },
        supportingContent = {
            val state = remember { SliderState(value = value.toFloat(), trackRange = 0f..100f) }
            // Follow the saved value (e.g. once it loads from DataStore)
            LaunchedEffect(value) { state.value = value.toFloat() }
            Slider(
                state = state,
                onValueChange = {
                    state.value = it
                    onValueChange(it)
                }
            )
        }
    ) {
        // The value lives in the headline row rather than trailingContent, so its changing width
        // never resizes the slider below it
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f))
            Text(
                stringResource(R.string.percent_value, value),
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun SettingsPreview() {
    ArtlockTheme {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(modifier = Modifier.padding(16.dp)) {
                SectionHeader("Permissions")
                NotificationAccessItem(enabled = false, onClick = {})
                SectionHeader("Now Playing")
                NowPlayingItem(null)
                SectionHeader("Actions")
                ActionsGroup(backgroundImagePath = null, wallpaperImagePath = null, isWallpaperActive = true, blurLevel = 10, dimLevel = 30, onPickImage = {}, onSetWallpaper = {}, onNavigateToAppSelection = {})
                SectionHeader("Artwork")
                OnlineLookupItem(enabled = true, onEnabledChange = {})
                SectionHeader("Effects")
                AppearanceGroup(dimLevel = 30, onDimChange = {}, blurLevel = 10, onBlurChange = {})
            }
        }
    }
}
