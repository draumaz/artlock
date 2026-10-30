package io.github.draumaz.artlock.ui

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import io.github.draumaz.artlock.R
import io.github.draumaz.artlock.SectionHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppInfo(
    val name: String,
    val packageName: String,
    // Converted once while loading, not on every recomposition
    val icon: ImageBitmap
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppSelectionScreen(
    selectedPackages: Set<String>?,
    onPackagesChanged: (Set<String>) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val packageManager = context.packageManager
    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var appsLoaded by remember { mutableStateOf(false) }
    val isLoading = !appsLoaded || selectedPackages == null

    val iconSizePx = with(LocalDensity.current) { 40.dp.roundToPx() }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val mainIntent = Intent(Intent.ACTION_MAIN, null)
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)
            val resolvedInfos = packageManager.queryIntentActivities(mainIntent, 0)

            val appList = resolvedInfos.map { resolved ->
                val packageName = resolved.activityInfo.packageName
                val name = resolved.loadLabel(packageManager).toString()
                val icon = resolved.loadIcon(packageManager).toBitmap(iconSizePx, iconSizePx).asImageBitmap()
                AppInfo(name, packageName, icon)
            }.distinctBy { it.packageName }.sortedBy { it.name.lowercase() }

            withContext(Dispatchers.Main) {
                apps = appList
                appsLoaded = true
            }
        }
    }

    val filteredApps = remember(apps, searchQuery) {
        if (searchQuery.isBlank()) apps
        else apps.filter { it.name.contains(searchQuery, ignoreCase = true) || it.packageName.contains(searchQuery, ignoreCase = true) }
    }

    // Selected apps are grouped at the top; toggling one animates it between the groups
    val selected = selectedPackages.orEmpty()
    val (selectedApps, otherApps) = remember(filteredApps, selected) {
        filteredApps.partition { it.packageName in selected }
    }
    val onToggle = { app: AppInfo, isSelected: Boolean ->
        onPackagesChanged(if (isSelected) selected + app.packageName else selected - app.packageName)
    }

    // Same look as the main screen: no app bar, the title scrolls with the content
    Scaffold(containerColor = MaterialTheme.colorScheme.surfaceContainer) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp
            )
        ) {
            item(key = "header") {
                Column {
                    FilledTonalIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.navigate_up))
                    }
                    Text(
                        stringResource(R.string.app_selection_title),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 16.dp)
                    )
                    SearchField(query = searchQuery, onQueryChange = { searchQuery = it })
                }
            }

            when {
                isLoading -> item(key = "loading") {
                    Box(modifier = Modifier.fillMaxWidth().padding(top = 64.dp), contentAlignment = Alignment.Center) {
                        LoadingIndicator()
                    }
                }
                filteredApps.isEmpty() -> item(key = "empty") {
                    Text(
                        stringResource(R.string.no_apps_found),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 64.dp)
                    )
                }
                else -> {
                    appGroup("selected", R.string.app_section_selected, selectedApps, isSelected = true, onToggle)
                    appGroup("other", R.string.app_section_other, otherApps, isSelected = false, onToggle)
                }
            }
        }
    }
}

/** A section header followed by the apps as one segmented group. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun LazyListScope.appGroup(
    key: String,
    title: Int,
    apps: List<AppInfo>,
    isSelected: Boolean,
    onToggle: (AppInfo, Boolean) -> Unit
) {
    if (apps.isEmpty()) return
    item(key = "header-$key") {
        Box(Modifier.animateItem()) { SectionHeader(stringResource(title)) }
    }
    itemsIndexed(apps, key = { _, app -> app.packageName }) { index, app ->
        AppItem(
            app = app,
            isSelected = isSelected,
            shapes = ListItemDefaults.segmentedShapes(index = index, count = apps.size),
            onSelectedChange = { onToggle(app, it) },
            modifier = Modifier
                .animateItem()
                .padding(bottom = if (index < apps.lastIndex) ListItemDefaults.SegmentedGap else 0.dp)
        )
    }
}

/** Pill-shaped search field in the expressive style, with a clear button once there's a query. */
@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(stringResource(R.string.search_apps_hint)) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear_search))
                }
            }
        } else {
            null
        },
        singleLine = true,
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent
        )
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppItem(
    app: AppInfo,
    isSelected: Boolean,
    shapes: ListItemShapes,
    onSelectedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    SegmentedListItem(
        onClick = { onSelectedChange(!isSelected) },
        shapes = shapes,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        supportingContent = { Text(app.packageName, style = MaterialTheme.typography.bodySmall) },
        leadingContent = {
            Image(
                bitmap = app.icon,
                contentDescription = null,
                modifier = Modifier.size(40.dp)
            )
        },
        trailingContent = {
            Switch(
                checked = isSelected,
                onCheckedChange = onSelectedChange,
                thumbContent = if (isSelected) {
                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
                } else {
                    null
                }
            )
        }
    ) {
        Text(app.name)
    }
}
