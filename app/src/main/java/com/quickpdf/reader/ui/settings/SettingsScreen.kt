package com.quickpdf.reader.ui.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.quickpdf.reader.R
import com.quickpdf.reader.data.AppLanguage
import com.quickpdf.reader.data.AppTheme
import com.quickpdf.reader.data.Backup
import com.quickpdf.reader.data.Library
import com.quickpdf.reader.data.PageTint
import com.quickpdf.reader.data.Settings
import com.quickpdf.reader.data.Updates
import com.quickpdf.reader.ui.reader.paper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Reading options, shared by the settings screen and the reader's quick settings sheet. */
@Composable
fun ReadingSettings(settings: Settings, onChange: ((Settings) -> Settings) -> Unit) {
    Column {
        SectionTitle(stringResource(R.string.settings_layout))
        RadioRow(stringResource(R.string.layout_continuous), !settings.paged) { onChange { it.copy(paged = false) } }
        RadioRow(stringResource(R.string.layout_paged), settings.paged) { onChange { it.copy(paged = true) } }
        if (settings.paged) {
            SwitchRow(
                stringResource(R.string.two_pages),
                stringResource(R.string.two_pages_hint),
                settings.spreads,
            ) { v -> onChange { it.copy(spreads = v) } }
        } else {
            SectionTitle(stringResource(R.string.settings_fit))
            RadioRow(stringResource(R.string.fit_width), !settings.fitPage) { onChange { it.copy(fitPage = false) } }
            RadioRow(stringResource(R.string.fit_page), settings.fitPage) { onChange { it.copy(fitPage = true) } }
        }

        SectionTitle(stringResource(R.string.settings_page_color))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            for (tint in PageTint.entries) {
                TintSwatch(tint, selected = settings.tint == tint) { onChange { it.copy(tint = tint) } }
            }
        }
        Spacer(Modifier.height(8.dp))
        SwitchRow(stringResource(R.string.keep_screen_on), null, settings.keepScreenOn) { v -> onChange { it.copy(keepScreenOn = v) } }
    }
}

@Composable
private fun TintSwatch(tint: PageTint, selected: Boolean, onClick: () -> Unit) {
    val label = stringResource(
        when (tint) {
            PageTint.ORIGINAL -> R.string.tint_original
            PageTint.SEPIA -> R.string.tint_sepia
            PageTint.NIGHT -> R.string.tint_night
            PageTint.NIGHT_SEPIA -> R.string.tint_night_sepia
        }
    )
    val ink = when (tint) {
        PageTint.ORIGINAL -> androidx.compose.ui.graphics.Color(0xFF202124)
        PageTint.SEPIA -> androidx.compose.ui.graphics.Color(0xFF5B4636)
        PageTint.NIGHT -> androidx.compose.ui.graphics.Color(0xFFDFDFDF)
        PageTint.NIGHT_SEPIA -> androidx.compose.ui.graphics.Color(0xFFD8C9AE)
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(MaterialTheme.shapes.medium)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(6.dp),
    ) {
        Box(
            Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(tint.paper)
                .border(
                    if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary)
                    else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text("Aa", color = ink, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(library: Library, updates: Updates, onBack: () -> Unit) {
    val settings by library.settings.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val language = remember { AppLanguage.get(context) }
    var checkStatus by remember { mutableStateOf<String?>(null) }
    val upToDate = stringResource(R.string.update_up_to_date)
    val checkFailed = stringResource(R.string.update_check_failed)
    val availableFormat = stringResource(R.string.update_available_short)
    var backupStatus by remember { mutableStateOf<String?>(null) }
    var restoreStatus by remember { mutableStateOf<String?>(null) }
    val backupSaved = stringResource(R.string.backup_saved)
    val backupFailed = stringResource(R.string.backup_failed)
    val restoreFailed = stringResource(R.string.restore_failed)
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            backupStatus = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { Backup.write(context, library, it) }
                }
            }.fold({ backupSaved }, { backupFailed })
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            restoreStatus = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)!!.use { Backup.read(context, library, it) }
                }
            }.fold({ n -> context.resources.getQuantityString(R.plurals.restore_done, n, n) }, { restoreFailed })
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp)
            ) {
                ReadingSettings(settings, library::updateSettings)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                SectionTitle(stringResource(R.string.settings_theme))
                for (theme in AppTheme.entries) {
                    val label = stringResource(
                        when (theme) {
                            AppTheme.SYSTEM -> R.string.theme_system
                            AppTheme.LIGHT -> R.string.theme_light
                            AppTheme.DARK -> R.string.theme_dark
                        }
                    )
                    RadioRow(label, settings.theme == theme) { library.updateSettings { it.copy(theme = theme) } }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                SectionTitle(stringResource(R.string.settings_language))
                for (tag in AppLanguage.options) {
                    val label = when (tag) {
                        "" -> stringResource(R.string.language_system)
                        "en" -> "English"
                        else -> "Русский"
                    }
                    RadioRow(label, tag == language) {
                        if (tag != language) (context as? Activity)?.let { AppLanguage.set(it, tag) }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                SectionTitle(stringResource(R.string.settings_updates))
                SwitchRow(
                    stringResource(R.string.check_updates),
                    stringResource(R.string.check_updates_hint),
                    settings.checkUpdates,
                ) { v -> library.updateSettings { it.copy(checkUpdates = v) } }
                ClickRow(stringResource(R.string.check_now), checkStatus) {
                    checkStatus = null
                    scope.launch {
                        checkStatus = try {
                            updates.check(force = true)?.let { availableFormat.format(it.version) } ?: upToDate
                        } catch (e: Exception) {
                            checkFailed
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                SectionTitle(stringResource(R.string.settings_backup))
                ClickRow(stringResource(R.string.backup_create), backupStatus ?: stringResource(R.string.backup_create_hint)) {
                    backupStatus = null
                    backupLauncher.launch("QuickPDF-backup-${LocalDate.now()}.json")
                }
                ClickRow(stringResource(R.string.backup_restore), restoreStatus ?: stringResource(R.string.backup_restore_hint)) {
                    restoreStatus = null
                    restoreLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/plain"))
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                SectionTitle(stringResource(R.string.settings_about))
                InfoRow(stringResource(R.string.version), updates.currentVersion)
                ClickRow(stringResource(R.string.source_code), "github.com/RGB-Outl4w/quickpdf-android") {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/RGB-Outl4w/quickpdf-android")))
                }
                InfoRow(stringResource(R.string.license), "Apache License 2.0")

                ElevatedCard(
                    onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://boosty.to/rgboutlaw"))) } },
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.FavoriteBorder, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.support_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                            Text(
                                stringResource(R.string.support_subtitle),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SwitchRow(label: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun ClickRow(label: String, detail: String?, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
