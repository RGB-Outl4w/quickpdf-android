package com.quickpdf.reader.ui.reader

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.quickpdf.reader.pdf.PdfPageRenderer
import com.quickpdf.reader.pdf.SearchHit
import com.quickpdf.reader.pdf.highlightRects
import com.quickpdf.reader.ui.theme.HighlightColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Zoom/pan of the whole page column. Screen x = tx + scale * localX, screen y = scale * localY. */
@Stable
class ZoomState {
    var scale by mutableFloatStateOf(1f)
        private set
    var tx by mutableFloatStateOf(0f)
        private set

    /** Scale the pages are rendered for; only updated once a gesture ends to avoid re-rendering mid-pinch. */
    var settledScale by mutableFloatStateOf(1f)
        private set

    var viewportWidth = 1f

    /** Zooms by [factor] around [focus] (screen px) and pans by [pan]; returns the vertical scroll (local px) needed. */
    fun transform(factor: Float, focus: Offset, pan: Offset): Float {
        val old = scale
        val new = (old * factor).coerceIn(MIN, MAX)
        val localX = (focus.x - tx) / old
        scale = new
        tx = clampTx(focus.x - new * localX + pan.x)
        return focus.y / old - focus.y / new - pan.y / new
    }

    fun panX(dx: Float) {
        tx = clampTx(tx + dx)
    }

    /** Horizontally centers local x (px) when zoomed. */
    fun centerOn(localX: Float) {
        tx = clampTx(viewportWidth / 2 - scale * localX)
    }

    fun settle() {
        settledScale = scale
    }

    private fun clampTx(v: Float) = v.coerceIn(viewportWidth - scale * viewportWidth, 0f)

    companion object {
        const val MIN = 1f
        const val MAX = 5f
    }
}

private val renderBuckets = floatArrayOf(1f, 1.5f, 2f, 3f)

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

@Composable
fun PageList(
    renderer: PdfPageRenderer,
    listState: LazyListState,
    zoom: ZoomState,
    contentPadding: PaddingValues,
    night: Boolean,
    search: SearchState,
    charBoxes: suspend (Int) -> FloatArray?,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    BoxWithConstraints(
        modifier
            .clipToBounds()
            .background(if (night) Color(0xFF0B0B0D) else MaterialTheme.colorScheme.surfaceContainerHighest)
            .pointerInput(Unit) {
                // Pinch to zoom (two fingers) and horizontal pan when zoomed (one finger).
                // Runs in the Initial pass so a pinch never reaches the list's own scrolling.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var pinched = false
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = event.changes.count { it.pressed }
                        if (pressed >= 2) {
                            pinched = true
                            val dy = zoom.transform(event.calculateZoom(), event.calculateCentroid(useCurrent = true), event.calculatePan())
                            listState.dispatchRawDelta(dy)
                            event.changes.forEach { it.consume() }
                        } else if (!pinched && zoom.scale > 1.01f) {
                            zoom.panX(event.calculatePan().x)
                        }
                    } while (event.changes.any { it.pressed })
                    if (pinched) zoom.settle()
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { focus ->
                        scope.launch {
                            val target = if (zoom.scale > 1.05f) 1f else 2.5f
                            val anim = Animatable(zoom.scale)
                            anim.animateTo(target, tween(220)) {
                                val dy = zoom.transform(value / zoom.scale, focus, Offset.Zero)
                                listState.dispatchRawDelta(dy)
                            }
                            zoom.settle()
                        }
                    },
                )
            }
    ) {
        val widthPx = constraints.maxWidth
        zoom.viewportWidth = widthPx.toFloat()
        val bucket = renderBuckets.firstOrNull { it >= zoom.settledScale - 0.01f } ?: renderBuckets.last()
        val renderWidth = (widthPx * bucket).roundToInt()

        LazyColumn(
            state = listState,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.layout { measurable, c ->
                // The list is laid out in a viewport 1/scale as tall and then scaled up, so scroll
                // range and visible items always match what's on screen.
                val s = zoom.scale
                val h = (c.maxHeight / s).roundToInt().coerceAtLeast(1)
                val placeable = measurable.measure(Constraints.fixed(c.maxWidth, h))
                layout(c.maxWidth, c.maxHeight) {
                    placeable.placeWithLayer(0, 0) {
                        scaleX = zoom.scale
                        scaleY = zoom.scale
                        translationX = zoom.tx
                        transformOrigin = TransformOrigin(0f, 0f)
                    }
                }
            },
        ) {
            items(renderer.pageCount, key = { it }) { page ->
                PageItem(
                    page = page,
                    renderer = renderer,
                    renderWidth = renderWidth,
                    night = night,
                    hits = search.byPage[page],
                    currentHit = search.currentHit,
                    charBoxes = charBoxes,
                )
            }
        }
    }
}

@Composable
private fun PageItem(
    page: Int,
    renderer: PdfPageRenderer,
    renderWidth: Int,
    night: Boolean,
    hits: List<SearchHit>?,
    currentHit: SearchHit?,
    charBoxes: suspend (Int) -> FloatArray?,
) {
    var bitmap by remember(page) { mutableStateOf<Bitmap?>(renderer.cached(page, renderWidth) ?: renderer.cachedAny(page)) }
    LaunchedEffect(page, renderWidth) {
        bitmap = runCatching { renderer.render(page, renderWidth) }.getOrNull() ?: bitmap
    }

    var highlights by remember(page) { mutableStateOf<List<Pair<SearchHit, List<RectF>>>>(emptyList()) }
    LaunchedEffect(page, hits) {
        highlights = if (hits.isNullOrEmpty()) emptyList() else {
            val boxes = charBoxes(page)
            if (boxes == null) emptyList() else hits.map { it to highlightRects(boxes, it.start, it.end) }
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f / renderer.aspectRatios[page])
            .background(if (night) Color.Black else Color.White)
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Page ${page + 1}",
                contentScale = ContentScale.FillBounds,
                colorFilter = if (night) invertFilter else null,
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
        if (highlights.isNotEmpty()) {
            Canvas(Modifier.matchParentSize()) {
                val pad = 1.5.dp.toPx()
                for ((hit, rects) in highlights) {
                    val isCurrent = hit === currentHit
                    for (r in rects) {
                        val topLeft = Offset(r.left * size.width - pad, r.top * size.height - pad)
                        val sz = Size(r.width() * size.width + 2 * pad, r.height() * size.height + 2 * pad)
                        val radius = CornerRadius(3.dp.toPx())
                        drawRoundRect(if (isCurrent) HighlightColors.current else HighlightColors.match, topLeft, sz, radius)
                        if (isCurrent) drawRoundRect(Color(0xFFE64A19), topLeft, sz, radius, style = Stroke(1.5.dp.toPx()))
                    }
                }
            }
        }
    }
}
