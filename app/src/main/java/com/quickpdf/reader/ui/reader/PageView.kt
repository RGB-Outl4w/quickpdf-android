package com.quickpdf.reader.ui.reader

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.quickpdf.reader.R
import com.quickpdf.reader.data.Annotation
import com.quickpdf.reader.data.PageTint
import com.quickpdf.reader.pdf.PageLink
import com.quickpdf.reader.pdf.PdfPageRenderer
import com.quickpdf.reader.pdf.SearchHit
import com.quickpdf.reader.pdf.charIndexAt
import com.quickpdf.reader.pdf.highlightRects
import com.quickpdf.reader.pdf.wordRangeAt
import com.quickpdf.reader.ui.theme.AnnotationColors
import com.quickpdf.reader.ui.theme.HighlightColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// ---- Page colors --------------------------------------------------------------------------------

private val invertFilter = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        )
    )
)

/** Maps page brightness onto a paper → ink gradient: white becomes [paper], black becomes [ink]. */
private fun paperInkFilter(paper: Color, ink: Color): ColorFilter {
    fun row(p: Float, i: Float) = floatArrayOf((p - i) * 0.299f, (p - i) * 0.587f, (p - i) * 0.114f, 0f, i * 255f)
    return ColorFilter.colorMatrix(
        ColorMatrix(row(paper.red, ink.red) + row(paper.green, ink.green) + row(paper.blue, ink.blue) + floatArrayOf(0f, 0f, 0f, 1f, 0f))
    )
}

private val sepiaPaper = Color(0xFFF4ECD8)
private val nightSepiaPaper = Color(0xFF231D15)
private val sepiaFilter = paperInkFilter(sepiaPaper, Color(0xFF5B4636))
private val nightSepiaFilter = paperInkFilter(nightSepiaPaper, Color(0xFFD8C9AE))

val PageTint.filter: ColorFilter?
    get() = when (this) {
        PageTint.ORIGINAL -> null
        PageTint.SEPIA -> sepiaFilter
        PageTint.NIGHT -> invertFilter
        PageTint.NIGHT_SEPIA -> nightSepiaFilter
    }

/** Color of an empty page while it renders. */
val PageTint.paper: Color
    get() = when (this) {
        PageTint.ORIGINAL -> Color.White
        PageTint.SEPIA -> sepiaPaper
        PageTint.NIGHT -> Color.Black
        PageTint.NIGHT_SEPIA -> nightSepiaPaper
    }

/** Background around the pages. */
@Composable
fun PageTint.canvas(): Color = when (this) {
    PageTint.ORIGINAL -> MaterialTheme.colorScheme.surfaceContainerHighest
    PageTint.SEPIA -> Color(0xFFE3D8C1)
    PageTint.NIGHT -> Color(0xFF0B0B0D)
    PageTint.NIGHT_SEPIA -> Color(0xFF15110C)
}

// ---- Page ---------------------------------------------------------------------------------------

/**
 * One rendered page with search matches, highlights and text selection. Long-press selects a word, dragging
 * extends it word by word, and the handles adjust it afterwards. A tap on a highlight opens it; a tap while
 * text is selected clears the selection; a tap on a link follows it. Other taps are left to the reader (bars, zoom).
 */
@Composable
fun PageView(
    page: Int,
    renderer: PdfPageRenderer,
    renderWidth: Int,
    tint: PageTint,
    hits: List<SearchHit>?,
    currentHit: SearchHit?,
    annotations: List<Annotation>,
    selection: TextSelection?,
    pageText: (Int) -> String?,
    charBoxes: suspend (Int) -> FloatArray?,
    links: suspend (Int) -> List<PageLink>,
    onSelect: (TextSelection?) -> Unit,
    onAnnotationTap: (Annotation) -> Unit,
    onLinkTap: (PageLink) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    var bitmap by remember(page) { mutableStateOf<Bitmap?>(renderer.cached(page, renderWidth) ?: renderer.cachedAny(page)) }
    LaunchedEffect(page, renderWidth) {
        bitmap = runCatching { renderer.render(page, renderWidth) }.getOrNull() ?: bitmap
    }

    var boxes by remember(page) { mutableStateOf<FloatArray?>(null) }
    var pageSize by remember { mutableStateOf(IntSize.Zero) }
    val needBoxes = !hits.isNullOrEmpty() || annotations.isNotEmpty() || selection != null
    LaunchedEffect(page, needBoxes) {
        if (needBoxes && boxes == null) boxes = charBoxes(page)
    }

    var pageLinks by remember(page) { mutableStateOf<List<PageLink>>(emptyList()) }
    LaunchedEffect(page) { pageLinks = links(page) }

    val hitRects = remember(boxes, hits) { rectsFor(boxes, hits.orEmpty()) { it.start to it.end } }
    val noteRects = remember(boxes, annotations) { rectsFor(boxes, annotations) { it.start to it.end } }
    val selectionRects = remember(boxes, selection) { rectsFor(boxes, listOfNotNull(selection)) { it.start to it.end } }

    // The gesture coroutine outlives recompositions, so it reads the latest values through these.
    val latestBoxes by rememberUpdatedState(boxes)
    val latestNotes by rememberUpdatedState(noteRects)
    val latestSelection by rememberUpdatedState(selection)
    val latestOnSelect by rememberUpdatedState(onSelect)
    val latestOnAnnotationTap by rememberUpdatedState(onAnnotationTap)
    val latestLinks by rememberUpdatedState(pageLinks)
    val latestOnLinkTap by rememberUpdatedState(onLinkTap)

    Box(
        modifier
            .background(tint.paper)
            .onSizeChanged { pageSize = it }
            .pointerInput(page) {
                fun wordAt(pos: Offset, b: FloatArray? = latestBoxes): IntRange? {
                    b ?: return null
                    val text = pageText(page) ?: return null
                    val i = charIndexAt(b, pos.x / size.width, pos.y / size.height)
                    return if (i < 0) null else wordRangeAt(text, i).takeIf { !it.isEmpty() }
                }

                fun extend(anchor: IntRange, pos: Offset) {
                    val word = wordAt(pos) ?: return
                    latestOnSelect(
                        if (word.first >= anchor.first) TextSelection(page, anchor.first, maxOf(anchor.last, word.last) + 1)
                        else TextSelection(page, word.first, anchor.last + 1)
                    )
                }

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val end = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        awaitPressEnd(down, viewConfiguration.touchSlop)
                    }
                    if (end == null) {
                        // Long press: select the word, then follow the finger until it lifts.
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        var anchor = wordAt(down.position)
                        if (anchor != null) {
                            latestOnSelect(TextSelection(page, anchor.first, anchor.last + 1))
                        } else if (latestBoxes == null) {
                            scope.launch {
                                // The state update reaches latestBoxes only after recomposition, so use the result directly.
                                val loaded = charBoxes(page)
                                boxes = loaded
                                anchor = wordAt(down.position, loaded)?.also { latestOnSelect(TextSelection(page, it.first, it.last + 1)) }
                            }
                        }
                        while (true) {
                            val e = awaitPointerEvent()
                            e.changes.forEach { it.consume() }
                            val c = e.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            val a = anchor
                            if (a != null && c.positionChanged()) extend(a, c.position)
                        }
                    } else {
                        val up = end.up ?: return@awaitEachGesture
                        val x = up.position.x / size.width
                        val y = up.position.y / size.height
                        val tapped = latestNotes.firstOrNull { (_, rects) -> rects.any { it.containsWithSlop(x, y) } }?.first
                        if (tapped != null) {
                            up.consume()
                            latestOnAnnotationTap(tapped)
                        } else if (latestSelection != null) {
                            up.consume()
                            latestOnSelect(null)
                        } else {
                            latestLinks.firstOrNull { it.contains(x, y) }?.let {
                                up.consume()
                                latestOnLinkTap(it)
                            }
                        }
                    }
                }
            }
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = stringResource(R.string.page_n, page + 1),
                contentScale = ContentScale.FillBounds,
                colorFilter = tint.filter,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            Text(
                "${page + 1}",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.Gray.copy(alpha = 0.5f),
                modifier = Modifier.align(Alignment.Center),
            )
        }
        if (hitRects.isNotEmpty() || noteRects.isNotEmpty() || selectionRects.isNotEmpty()) {
            Canvas(Modifier.matchParentSize()) {
                val pad = 1.5.dp.toPx()
                val radius = CornerRadius(3.dp.toPx())
                fun topLeft(r: RectF) = Offset(r.left * size.width - pad, r.top * size.height - pad)
                fun sizeOf(r: RectF) = Size(r.width() * size.width + 2 * pad, r.height() * size.height + 2 * pad)

                for ((a, rects) in noteRects) {
                    val color = AnnotationColors[a.color]
                    for (r in rects) drawRoundRect(color.copy(alpha = 0.42f), topLeft(r), sizeOf(r), radius)
                    if (a.note.isNotEmpty()) {
                        rects.lastOrNull()?.let { r ->
                            drawCircle(color, 3.5.dp.toPx(), Offset(r.right * size.width + pad, r.top * size.height - pad))
                        }
                    }
                }
                for ((_, rects) in selectionRects) {
                    for (r in rects) drawRect(AnnotationColors.selection, topLeft(r), sizeOf(r))
                }
                for ((hit, rects) in hitRects) {
                    val isCurrent = hit === currentHit
                    for (r in rects) {
                        drawRoundRect(if (isCurrent) HighlightColors.current else HighlightColors.match, topLeft(r), sizeOf(r), radius)
                        if (isCurrent) drawRoundRect(Color(0xFFE64A19), topLeft(r), sizeOf(r), radius, style = Stroke(1.5.dp.toPx()))
                    }
                }
            }
        }
        val sel = selection
        val b = boxes
        if (sel != null && b != null) {
            SelectionHandles(sel, b, pageSize, onSelect)
        }
    }
}

private fun <T> rectsFor(boxes: FloatArray?, items: List<T>, range: (T) -> Pair<Int, Int>): List<Pair<T, List<RectF>>> {
    if (boxes == null || items.isEmpty()) return emptyList()
    return items.map { val (s, e) = range(it); it to highlightRects(boxes, s, e) }
}

private fun RectF.containsWithSlop(x: Float, y: Float, slop: Float = 0.01f) =
    x >= left - slop && x <= right + slop && y >= top - slop && y <= bottom + slop

private class PressEnd(val up: PointerInputChange?)

/** Waits for the press to end: [PressEnd.up] is set for a tap, null if it moved, went multi-touch or was taken by a scroll. */
private suspend fun AwaitPointerEventScope.awaitPressEnd(down: PointerInputChange, slop: Float): PressEnd {
    while (true) {
        val e = awaitPointerEvent()
        val c = e.changes.firstOrNull { it.id == down.id } ?: return PressEnd(null)
        if (e.changes.count { it.pressed } > 1 || c.isConsumed) return PressEnd(null)
        if (!c.pressed) return PressEnd(c)
        if ((c.position - down.position).getDistance() > slop) return PressEnd(null)
    }
}

// ---- Selection handles ----------------------------------------------------------------------------

@Composable
private fun SelectionHandles(selection: TextSelection, boxes: FloatArray, pageSize: IntSize, onSelect: (TextSelection?) -> Unit) {
    if (pageSize.width == 0 || pageSize.height == 0) return
    val first = (selection.start until selection.end).firstOrNull { it * 4 < boxes.size && !boxes[it * 4].isNaN() } ?: return
    val last = (selection.end - 1 downTo selection.start).firstOrNull { it * 4 < boxes.size && !boxes[it * 4].isNaN() } ?: return
    val latest by rememberUpdatedState(selection)
    Handle(start = true, fx = boxes[first * 4], fy = boxes[first * 4 + 3], pageSize) { fx, fy ->
        val i = charIndexAt(boxes, fx, fy)
        if (i >= 0) onSelect(latest.copy(start = minOf(i, latest.end - 1)))
    }
    Handle(start = false, fx = boxes[last * 4 + 2], fy = boxes[last * 4 + 3], pageSize) { fx, fy ->
        val i = charIndexAt(boxes, fx, fy)
        if (i >= 0) onSelect(latest.copy(end = maxOf(i + 1, latest.start + 1)))
    }
}

/**
 * A teardrop handle whose tip sits at page-relative ([fx], [fy]). Dragging reports the page-relative point just
 * above the tip, i.e. on the text line the handle belongs to.
 */
@Composable
private fun Handle(start: Boolean, fx: Float, fy: Float, pageSize: IntSize, onDrag: (Float, Float) -> Unit) {
    val sizeDp = 22.dp
    val sizePx = with(LocalDensity.current) { sizeDp.toPx() }
    val w = pageSize.width.toFloat()
    val h = pageSize.height.toFloat()
    val tip by rememberUpdatedState(Offset(fx * w, fy * h))
    val latestOnDrag by rememberUpdatedState(onDrag)
    Canvas(
        Modifier
            .offset { IntOffset((if (start) tip.x - sizePx else tip.x).roundToInt(), tip.y.roundToInt()) }
            .size(sizeDp)
            .pointerInput(start, pageSize) {
                var probe = Offset.Zero
                detectDragGestures(
                    onDragStart = { probe = tip - Offset(0f, sizePx / 2) },
                ) { change, drag ->
                    change.consume()
                    probe += drag
                    latestOnDrag(probe.x / w, probe.y / h)
                }
            }
    ) {
        val r = size.width / 2
        drawCircle(AnnotationColors.handle, r, Offset(r, r))
        // Square off the corner that touches the text.
        drawRect(AnnotationColors.handle, if (start) Offset(r, 0f) else Offset.Zero, Size(r, r))
    }
}
