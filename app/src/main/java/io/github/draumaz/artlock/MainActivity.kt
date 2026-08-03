package io.github.draumaz.artlock

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.github.draumaz.artlock.data.model.WallpaperContent
import io.github.draumaz.artlock.notification.MusicNotificationListenerService
import io.github.draumaz.artlock.ui.AppSelectionScreen
import io.github.draumaz.artlock.ui.theme.ArtlockTheme
import io.github.draumaz.artlock.wallpaper.AnimatedWallpaperService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            lifecycleScope.launch {
                val savedPath = withContext(Dispatchers.IO) {
                    try {
                        // Cleanup old default wallpapers
                        filesDir.listFiles { _, name -> name.startsWith("default_wallpaper_") }?.forEach { it.delete() }

                        val inputStream = contentResolver.openInputStream(uri)
                        val timestamp = System.currentTimeMillis()
                        val file = File(filesDir, "default_wallpaper_$timestamp.jpg")
                        val outputStream = FileOutputStream(file)
                        inputStream?.copyTo(outputStream)
                        inputStream?.close()
                        outputStream.close()
                        file.absolutePath
                    } catch (_: Exception) {
                        null
                    }
                }

                if (savedPath != null) {
                    val app = application as App
                    app.wallpaperStateStore.saveDefaultWallpaper(savedPath)
                    Toast.makeText(this@MainActivity, getString(R.string.toast_wallpaper_saved), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as App

        setContent {
            ArtlockTheme {
                val navController = rememberNavController()

                NavHost(navController = navController, startDestination = "main") {
                    composable("main") {
                        MainScreen(
                            onNavigateToAppSelection = { navController.navigate("app_selection") },
                            onPickImage = { pickImageLauncher.launch("image/*") }
                        )
                    }
                    composable("app_selection") {
                        val supportedPackages by app.wallpaperStateStore.supportedPackagesFlow.collectAsStateWithLifecycle(initialValue = emptySet())
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

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainScreen(onNavigateToAppSelection: () -> Unit, onPickImage: () -> Unit) {
        val app = application as App
        var isNotificationEnabled by remember { mutableStateOf(checkNotificationAccess()) }
        val wallpaperContent by app.wallpaperStateStore.contentFlow.collectAsStateWithLifecycle(initialValue = null)
        val dimLevel by app.wallpaperStateStore.dimLevelFlow.collectAsStateWithLifecycle(initialValue = 30)
        val blurLevel by app.wallpaperStateStore.blurLevelFlow.collectAsStateWithLifecycle(initialValue = 0)

        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    isNotificationEnabled = checkNotificationAccess()
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    actions = {
                        IconButton(onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, "https://github.com/draumaz/Artlock".toUri())
                            startActivity(intent)
                        }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                                contentDescription = stringResource(R.string.help_description)
                            )
                        }
                    }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SectionHeader(stringResource(R.string.permissions_section_title))

                // Notification Status Card
                StatusCard(
                    enabled = isNotificationEnabled,
                    onActionClick = {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }
                )

                // Live Status Section
                CollapsibleSection(title = stringResource(R.string.live_status_header)) {
                    LiveStatusCard(wallpaperContent)
                }

                // Actions Section
                CollapsibleSection(title = stringResource(R.string.actions_section_title)) {
                    ActionsCard(
                        onPickImage = onPickImage,
                        onSetWallpaper = {
                            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                                putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(this@MainActivity, AnimatedWallpaperService::class.java))
                            }
                            startActivity(intent)
                        },
                        onNavigateToAppSelection = onNavigateToAppSelection
                    )
                }

                // Appearance Section
                CollapsibleSection(
                    title = stringResource(R.string.dimming_section_title),
                    initialExpanded = true
                ) {
                    AppearanceCard(
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
                }
                
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    private fun checkNotificationAccess(): Boolean {
        val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
        return enabledListeners.contains(ComponentName(this, MusicNotificationListenerService::class.java).flattenToString())
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
fun CollapsibleSection(
    title: String,
    initialExpanded: Boolean = true,
    content: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(initialExpanded) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            content()
        }
    }
}

@Composable
fun StatusCard(enabled: Boolean, onActionClick: () -> Unit) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            //Text(
            //    text = stringResource(if (enabled) R.string.notification_access_allowed else R.string.notification_access_blocked),
            //    style = MaterialTheme.typography.bodyLarge,
            //    fontWeight = FontWeight.Bold
            //)
            if (!enabled) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onActionClick,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text(stringResource(R.string.btn_notification_access).substringAfter(". "))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActionsCard(
    onPickImage: () -> Unit,
    onSetWallpaper: () -> Unit,
    onNavigateToAppSelection: () -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        FlowRow(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AssistChip(
                onClick = onPickImage,
                label = { Text(stringResource(R.string.chip_background)) },
                leadingIcon = { Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp)) }
            )
            AssistChip(
                onClick = onSetWallpaper,
                label = { Text(stringResource(R.string.chip_wallpaper)) },
                leadingIcon = { Icon(Icons.Default.Wallpaper, contentDescription = null, modifier = Modifier.size(18.dp)) }
            )
            AssistChip(
                onClick = onNavigateToAppSelection,
                label = { Text(stringResource(R.string.chip_apps)) },
                leadingIcon = { Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(18.dp)) }
            )
        }
    }
}

@Composable
fun AppearanceCard(
    dimLevel: Int,
    onDimChange: (Float) -> Unit,
    blurLevel: Int,
    onBlurChange: (Float) -> Unit
) {
    var selectedOption by remember { mutableIntStateOf(0) } // 0: Dim, 1: Blur

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedOption == 0,
                    onClick = { selectedOption = 0 },
                    label = { Text(stringResource(R.string.chip_dimming)) }
                )
                FilterChip(
                    selected = selectedOption == 1,
                    onClick = { selectedOption = 1 },
                    label = { Text(stringResource(R.string.chip_blur)) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (selectedOption == 0) {
                Text(
                    text = stringResource(R.string.dim_level_label, dimLevel),
                    style = MaterialTheme.typography.bodyLarge
                )
                Slider(
                    value = dimLevel.toFloat(),
                    onValueChange = onDimChange,
                    valueRange = 0f..100f,
                    steps = 100
                )
            } else {
                Text(
                    text = stringResource(R.string.blur_level_label, blurLevel),
                    style = MaterialTheme.typography.bodyLarge
                )
                Slider(
                    value = blurLevel.toFloat(),
                    onValueChange = onBlurChange,
                    valueRange = 0f..100f,
                    steps = 100
                )
            }
        }
    }
}

@Composable
fun LiveStatusCard(content: WallpaperContent?) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (content == null) {
                Text("-", style = MaterialTheme.typography.bodyMedium)
            } else {
                StatusItem(stringResource(R.string.track_label, content.trackTitle ?: "-"))
                StatusItem(stringResource(R.string.artist_label, content.trackArtist ?: "-"))
                //StatusItem(stringResource(R.string.album_label, content.trackAlbum ?: "-"))
                //StatusItem(stringResource(R.string.type_label, content.contentType.name))
                //HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                //StatusItem(stringResource(R.string.animated_url_label, content.animatedUrl ?: "-"))
                //StatusItem(stringResource(R.string.static_path_label, content.staticImagePath ?: "-"))
            }
        }
    }
}

@Composable
fun StatusItem(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(vertical = 2.dp)
    )
}

@Preview(showBackground = true)
@Composable
fun SettingsPreview() {
    ArtlockTheme {
        Surface {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StatusCard(enabled = true, onActionClick = {})
                CollapsibleSection(title = "Ações") {
                    ActionsCard(onPickImage = {}, onSetWallpaper = {}, onNavigateToAppSelection = {})
                }
                CollapsibleSection(title = "Aparência", initialExpanded = false) {
                    AppearanceCard(dimLevel = 30, onDimChange = {}, blurLevel = 10, onBlurChange = {})
                }
                CollapsibleSection(title = "Status") {
                    LiveStatusCard(null)
                }
            }
        }
    }
}
