package com.kira3.ametrine.ui.about

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.LocalListDetailSceneScope
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kira3.ametrine.BuildConfig
import com.kira3.ametrine.R
import com.kira3.ametrine.ui.settings.BackButton
import com.kira3.ametrine.ui.settings.listPadding
import com.kira3.ametrine.ui.settings.segmentedShapes
import com.kira3.ametrine.ui.settings.settingsListColors
import com.kira3.ametrine.ui.settings.settingsTopBarColors

/**
 * The app's version, where its source and the core's source are, and its licenses. GPL-3.0
 * asks an interactive program to show its license and that it comes with no warranty.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun AboutScreen(
    onOpenLicense: (id: String) -> Unit,
    onOpenLicenses: () -> Unit,
    onBack: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    // Beside the settings list there is nothing to go back to.
    val twoPane = LocalListDetailSceneScope.current != null
    val openLink = rememberOpenLink()
    val commit = BuildConfig.CORE_COMMIT

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = { if (!twoPane) BackButton(onBack) },
                colors = settingsTopBarColors(),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        LazyColumn(
            contentPadding = listPadding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "header") { AboutHeader() }
            item(key = "source") {
                LinkRow(
                    title = stringResource(R.string.about_source),
                    summary = SOURCE_URL.removePrefix("https://"),
                    index = 0,
                    onClick = { openLink(SOURCE_URL) },
                )
            }
            item(key = "core") {
                LinkRow(
                    title = stringResource(R.string.about_core),
                    summary = stringResource(R.string.about_core_summary, commit.take(7)),
                    index = 1,
                    // Without a commit there is no tree to link to.
                    onClick = coreSourceUrl(commit)?.takeIf { commit != "unknown" }?.let { url -> { openLink(url) } },
                )
            }
            item(key = "license") {
                LinkRow(
                    title = stringResource(R.string.about_license),
                    summary = stringResource(R.string.about_license_summary),
                    index = 2,
                    onClick = { onOpenLicense(ametrineComponent.id) },
                    chevron = true,
                )
            }
            item(key = "licenses") {
                LinkRow(
                    title = stringResource(R.string.about_licenses),
                    summary = stringResource(R.string.about_licenses_summary),
                    index = 3,
                    onClick = onOpenLicenses,
                    chevron = true,
                )
            }
            item(key = "legal") {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp),
                ) {
                    for (text in listOf(R.string.about_bios_note, R.string.about_legal, R.string.about_trademark)) {
                        Text(
                            stringResource(text),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** The launcher icon, name, version and what the app is. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AboutHeader() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 20.dp),
    ) {
        // The adaptive icon's layers, under the same kind of shape the launcher would use.
        Box(Modifier.size(96.dp).clip(MaterialShapes.Cookie9Sided.toShape())) {
            Image(painterResource(R.drawable.ic_launcher_background), contentDescription = null, modifier = Modifier.fillMaxSize())
            Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.fillMaxSize())
        }
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.about_description),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/** One of About's four rows; [onClick] null shows it as plain text. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LinkRow(title: String, summary: String, index: Int, onClick: (() -> Unit)?, chevron: Boolean = false) {
    SegmentedListItem(
        onClick = onClick ?: {},
        enabled = onClick != null,
        shapes = segmentedShapes(index, 4),
        colors = settingsListColors(),
        trailingContent = if (chevron) ({ Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) }) else null,
        supportingContent = { Text(summary) },
    ) { Text(title) }
}

/** Opens a web link, or says so when nothing on the device can. */
@Composable
internal fun rememberOpenLink(): (String) -> Unit {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val failed = stringResource(R.string.about_no_browser)
    return { url ->
        try {
            uriHandler.openUri(url)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, failed, Toast.LENGTH_SHORT).show()
        } catch (_: IllegalArgumentException) {
            Toast.makeText(context, failed, Toast.LENGTH_SHORT).show()
        }
    }
}
