package com.quickpdf.reader.ui.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
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
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.zIndex
import com.quickpdf.reader.R
import com.quickpdf.reader.data.Library
import com.quickpdf.reader.data.RecentDoc
import com.quickpdf.reader.data.Updates
import com.quickpdf.reader.ui.update.UpdateBanner
import com.quickpdf.reader.ui.update.UpdateDialog
import kotlinx.coroutines.launch
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val drag = remember(listState) { PinnedDrag(listState, library::reorderPinned) }

    fun remove(doc: RecentDoc) {
        val index = recents.indexOfFirst { it.uri == doc.uri }
        val removed = library.remove(doc.uri) ?: return
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(
                context.getString(R.string.removed_from_recent, removed.name),
                actionLabel = context.getString(R.string.undo),
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) library.restore(removed, index)
        }
    }

    fun move(doc: RecentDoc, by: Int) {
        val order = recents.filter { it.pinned }.map { it.uri }.toMutableList()
        val from = order.indexOf(doc.uri)
        val to = (from + by).coerceIn(0, order.lastIndex)
        if (from < 0 || from == to) return
        order.add(to, order.removeAt(from))
        library.reorderPinned(order)
    }

    @Composable
    fun DocCard(doc: RecentDoc, modifier: Modifier = Modifier, dragHandle: Modifier? = null) {
        val pinned = recents.filter { it.pinned }
        RecentCard(
            doc = doc,
            onClick = { onOpen(Uri.parse(doc.uri)) },
            actions = DocActions(
                onPin = { library.setPinned(doc.uri, !doc.pinned) },
                onRemove = { remove(doc) },
                onMoveUp = if (doc.pinned && pinned.firstOrNull() != doc) ({ move(doc, -1) }) else null,
                onMoveDown = if (doc.pinned && pinned.lastOrNull() != doc) ({ move(doc, +1) }) else null,
            ),
            dragHandle = dragHandle,
            modifier = modifier,
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbar) },
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
            val last = recents.maxByOrNull { it.openedAt }
            val matching = remember(recents, filter) { recents.filter { it.name.contains(filter.trim(), ignoreCase = true) } }
            // While dragging, pinned documents show in the order being arranged.
            val pinned = recents.filter { it.pinned }.let { list ->
                drag.order?.let { order -> list.sortedBy { order.indexOf(it.uri) } } ?: list
            }
            val unpinned = recents.filter { !it.pinned && it.uri != last?.uri }
            LazyColumn(
                state = listState,
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
                    if (filtering) {
                        items(matching, key = { it.uri }) { doc -> DocCard(doc, Modifier.animateItem()) }
                    } else {
                        if (last != null) {
                            item(key = "continue") {
                                ContinueCard(
                                    doc = last,
                                    onClick = { onOpen(Uri.parse(last.uri)) },
                                    actions = DocActions(onPin = { library.setPinned(last.uri, !last.pinned) }, onRemove = { remove(last) }),
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                        if (pinned.isNotEmpty()) {
                            item(key = "pinned-title") { SectionTitle(stringResource(R.string.pinned), Modifier.animateItem()) }
                        }
                        items(pinned, key = { it.uri }) { doc ->
                            val dragging = drag.dragged == doc.uri
                            DocCard(
                                doc,
                                modifier = if (dragging) Modifier.zIndex(1f).graphicsLayer { translationY = drag.shift() } else Modifier.animateItem(),
                                dragHandle = Modifier.pointerInput(doc.uri) {
                                    detectDragGestures(
                                        onDragStart = { drag.start(doc.uri, recents.filter { it.pinned }.map { it.uri }) },
                                        onDragEnd = drag::end,
                                        onDragCancel = drag::end,
                                    ) { change, amount ->
                                        change.consume()
                                        drag.drag(amount.y)
                                    }
                                },
                            )
                        }
                        if (unpinned.isNotEmpty()) {
                            item(key = "recent-title") { SectionTitle(stringResource(R.string.recent), Modifier.animateItem()) }
                        }
                        items(unpinned, key = { it.uri }) { doc -> DocCard(doc, Modifier.animateItem()) }
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

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 8.dp, start = 4.dp),
    )
}

/** Menu actions for a document card; move actions are null where they don't apply. */
private class DocActions(
    val onPin: () -> Unit,
    val onRemove: () -> Unit,
    val onMoveUp: (() -> Unit)? = null,
    val onMoveDown: (() -> Unit)? = null,
)

@Composable
private fun DocMenu(doc: RecentDoc, actions: DocActions, tint: Color = LocalContentColor.current) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.more), tint = tint) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(if (doc.pinned) R.string.unpin else R.string.pin)) },
                leadingIcon = { Icon(Icons.Outlined.PushPin, null) },
                onClick = { menu = false; actions.onPin() },
            )
            actions.onMoveUp?.let { onMoveUp ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.move_up)) },
                    leadingIcon = { Icon(Icons.Outlined.ArrowUpward, null) },
                    onClick = { menu = false; onMoveUp() },
                )
            }
            actions.onMoveDown?.let { onMoveDown ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.move_down)) },
                    leadingIcon = { Icon(Icons.Outlined.ArrowDownward, null) },
                    onClick = { menu = false; onMoveDown() },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.remove_from_recent)) },
                leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                onClick = { menu = false; actions.onRemove() },
            )
        }
    }
}

/**
 * Drag-to-reorder for the pinned cards: the dragged card follows the finger and trades places with a card once its
 * middle passes over that card.
 */
@Stable
private class PinnedDrag(private val list: LazyListState, private val onDrop: (List<String>) -> Unit) {
    /** Pinned URIs in the order being arranged; null when not dragging. */
    var order by mutableStateOf<List<String>?>(null)
        private set
    var dragged by mutableStateOf<String?>(null)
        private set
    private var startOffset = 0
    private var delta by mutableFloatStateOf(0f)

    fun start(uri: String, pinned: List<String>) {
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == uri } ?: return
        order = pinned
        dragged = uri
        startOffset = item.offset
        delta = 0f
    }

    /** How far the dragged card is drawn from where the list places it. */
    fun shift(): Float {
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == dragged } ?: return 0f
        return startOffset + delta - item.offset
    }

    // ponytail: no auto-scroll at the list's edges; add it if people pin more documents than fit on screen.
    fun drag(dy: Float) {
        val uri = dragged ?: return
        val current = order ?: return
        delta += dy
        val items = list.layoutInfo.visibleItemsInfo
        // Wait for the list to show the last swap before deciding on the next one.
        if (items.map { it.key }.filter { it in current } != current.filter { k -> items.any { it.key == k } }) return
        val item = items.firstOrNull { it.key == uri } ?: return
        val middle = startOffset + delta + item.size / 2f
        val target = items.firstOrNull { it.key != uri && it.key in current && middle > it.offset && middle < it.offset + it.size } ?: return
        order = current.toMutableList().apply { add(current.indexOf(target.key), removeAt(current.indexOf(uri))) }
    }

    fun end() {
        order?.let(onDrop)
        order = null
        dragged = null
        delta = 0f
    }
}

private fun RecentDoc.progress(): Float = if (pageCount > 0) (lastPage + 1f) / pageCount else 0f

@Composable
private fun ContinueCard(doc: RecentDoc, onClick: () -> Unit, actions: DocActions, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp, top = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.continue_reading),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    modifier = Modifier.weight(1f),
                )
                Box(Modifier.offset(x = 12.dp)) { DocMenu(doc, actions, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
            }
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

/** A document in the list; [dragHandle], if given, is the gesture modifier of a handle for reordering. */
@Composable
private fun RecentCard(doc: RecentDoc, onClick: () -> Unit, actions: DocActions, dragHandle: Modifier?, modifier: Modifier = Modifier) {
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
            if (dragHandle != null) {
                Icon(
                    Icons.Outlined.DragIndicator,
                    stringResource(R.string.drag_to_reorder),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = dragHandle.size(48.dp).padding(12.dp),
                )
            }
            DocMenu(doc, actions)
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
