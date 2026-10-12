package com.quickpdf.reader.ui.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import com.quickpdf.reader.R
import com.quickpdf.reader.data.Library
import com.quickpdf.reader.data.RecentDoc
import com.quickpdf.reader.data.Updates
import com.quickpdf.reader.ui.update.UpdateBanner
import com.quickpdf.reader.ui.update.UpdateDialog
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    library: Library,
    updates: Updates,
    onOpen: (Uri) -> Unit,
    onSearchAll: () -> Unit,
    onSettings: () -> Unit,
) {
    val recents by library.recents.collectAsState()
    val release by updates.available.collectAsState()
    var dismissedVersion by remember { mutableStateOf(updates.dismissedVersion) }
    var showUpdate by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onOpen)
    }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.SemiBold) },
                actions = {
                    if (recents.isNotEmpty()) {
                        IconButton(onClick = onSearchAll) { Icon(Icons.Outlined.Search, stringResource(R.string.search_all)) }
                    }
                    IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, stringResource(R.string.settings)) }
                },
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { picker.launch(arrayOf("application/pdf")) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.open_pdf)) },
            )
        },
    ) { padding ->
        val pending = release?.takeIf { it.version != dismissedVersion }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Keep content at a readable width on tablets; the list itself still scrolls edge to edge.
            val side = max(16.dp, (maxWidth - MAX_CONTENT_WIDTH) / 2)
            val filtering = filter.isNotBlank()
            val shown = remember(recents, filter) {
                if (filtering) recents.filter { it.name.contains(filter.trim(), ignoreCase = true) } else recents.drop(1)
            }
            LazyColumn(
                contentPadding = PaddingValues(
                    start = side, end = side,
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding() + 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (pending != null) {
                    item(key = "update") {
                        UpdateBanner(
                            pending,
                            onUpdate = { showUpdate = true },
                            onDismiss = { updates.dismissedVersion = pending.version; dismissedVersion = pending.version },
                        )
                    }
                }
                if (recents.isEmpty()) {
                    item(key = "empty") { EmptyState(Modifier.padding(top = 32.dp)) }
                } else {
                    if (recents.size > 4) {
                        item(key = "filter") {
                            OutlinedTextField(
                                value = filter,
                                onValueChange = { filter = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text(stringResource(R.string.filter_recent)) },
                                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                                trailingIcon = {
                                    if (filter.isNotEmpty()) IconButton(onClick = { filter = "" }) { Icon(Icons.Outlined.Close, stringResource(R.string.clear)) }
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(28.dp),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            )
                        }
                    }
                    if (!filtering) {
                        val last = recents.first()
                        item(key = "continue") { ContinueCard(last, onClick = { onOpen(Uri.parse(last.uri)) }) }
                    }
                    if (shown.isNotEmpty()) {
                        item(key = "recent-title") {
                            Text(
                                stringResource(R.string.recent),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                            )
                        }
                    }
                    items(shown, key = { it.uri }) { doc ->
                        RecentCard(
                            doc = doc,
                            onClick = { onOpen(Uri.parse(doc.uri)) },
                            onRemove = { library.remove(doc.uri) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
        if (showUpdate && pending != null) {
            UpdateDialog(pending, updates, onDismiss = { showUpdate = false })
        }
    }
}

private val MAX_CONTENT_WIDTH = 720.dp

private fun RecentDoc.progress(): Float = if (pageCount > 0) (lastPage + 1f) / pageCount else 0f

@Composable
private fun ContinueCard(doc: RecentDoc, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                stringResource(R.string.continue_reading),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                doc.name,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(14.dp))
            if (doc.pageCount > 0) {
                LinearProgressIndicator(
                    progress = { doc.progress() },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    drawStopIndicator = {},
                )
                Spacer(Modifier.height(10.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (doc.pageCount > 0) stringResource(R.string.page_of_percent, doc.lastPage + 1, doc.pageCount, (doc.progress() * 100).toInt())
                    else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = onClick) { Text(stringResource(R.string.continue_button)) }
            }
        }
    }
}

@Composable
private fun RecentCard(doc: RecentDoc, onClick: () -> Unit, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    ElevatedCard(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Description, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(doc.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                val parts = mutableListOf<String>()
                if (doc.pageCount > 0) parts += stringResource(R.string.page_of, doc.lastPage + 1, doc.pageCount)
                parts += openedWhen(doc.openedAt)
                if (doc.bookmarks.isNotEmpty()) parts += pluralStringResource(R.plurals.bookmarks, doc.bookmarks.size, doc.bookmarks.size)
                Text(
                    parts.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (doc.pageCount > 1) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { doc.progress() },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        drawStopIndicator = {},
                    )
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.remove_from_recent)) }, onClick = { menu = false; onRemove() })
                }
            }
        }
    }
}

/** "Today", "Yesterday" or a date, in the app's language. */
@Composable
private fun openedWhen(time: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    val then = Calendar.getInstance().apply { timeInMillis = time }
    val now = Calendar.getInstance()
    val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
    val days = now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR)
    return when {
        sameYear && days == 0 -> stringResource(R.string.today)
        sameYear && days == 1 -> stringResource(R.string.yesterday)
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(time))
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(28.dp), modifier = Modifier.size(96.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.AutoMirrored.Outlined.MenuBook, null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Tip(Icons.Outlined.TravelExplore, stringResource(R.string.tip_search))
        Tip(Icons.Outlined.ImageSearch, stringResource(R.string.tip_ocr))
        Tip(Icons.Outlined.Bookmark, stringResource(R.string.tip_navigation))
    }
}

@Composable
private fun Tip(icon: ImageVector, text: String) {
    Row(Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
