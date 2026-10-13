package com.quickpdf.reader.ui.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.quickpdf.reader.data.PageTint
import com.quickpdf.reader.pdf.PdfPageRenderer
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Content slot for one page: page index, bitmap width to render at, and the modifier that sizes it. */
typealias PageContent = @Composable (page: Int, renderWidth: Int, modifier: Modifier) -> Unit

/** An exact place to come back to: the [page] shown, and the list item and scroll offset in continuous mode. */
data class ReadingSpot(val page: Int, val item: Int, val offset: Int) {
    companion object {
        val Saver = listSaver<ReadingSpot?, Int>(
            save = { if (it == null) emptyList() else listOf(it.page, it.item, it.offset) },
            restore = { if (it.size == 3) ReadingSpot(it[0], it[1], it[2]) else null },
        )
    }
}

/** Where the reader is and how to move it, independent of the page layout. */
@Stable
interface PageNavigator {
    /** First page on screen (for spreads, the left page). */
    val currentPage: Int
    val isMoving: Boolean

    /** Shows [page]; with a page-relative point ([fx], [fy]), puts it about a third down the screen. */
    suspend fun goTo(page: Int, fx: Float? = null, fy: Float? = null)

    fun spot(): ReadingSpot = ReadingSpot(currentPage, currentPage, 0)

    suspend fun restore(spot: ReadingSpot) = goTo(spot.page)
}

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

/** Render width for a page [widthPx] wide at [scale], rounded so the bitmap cache sees few distinct widths. */
fun renderWidthFor(widthPx: Float, scale: Float): Int {
    val bucket = renderBuckets.firstOrNull { it >= scale - 0.01f } ?: renderBuckets.last()
    return ((widthPx * bucket / 64f).roundToInt() * 64).coerceAtLeast(64)
}

class ListNavigator(
    private val listState: LazyListState,
    private val zoom: ZoomState,
    private val renderer: PdfPageRenderer,
) : PageNavigator {
    override val currentPage by derivedStateOf {
        val info = listState.layoutInfo
        val items = info.visibleItemsInfo
        if (items.isEmpty()) listState.firstVisibleItemIndex
        else {
            val probe = info.viewportStartOffset + (info.viewportEndOffset - info.viewportStartOffset) / 3
            (items.firstOrNull { it.offset <= probe && it.offset + it.size > probe } ?: items.first()).index
        }
    }

    override val isMoving: Boolean get() = listState.isScrollInProgress

    override fun spot() = ReadingSpot(currentPage, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)

    override suspend fun restore(spot: ReadingSpot) = listState.scrollToItem(spot.item, spot.offset)

    override suspend fun goTo(page: Int, fx: Float?, fy: Float?) {
        val p = page.coerceIn(0, (renderer.pageCount - 1).coerceAtLeast(0))
        listState.scrollToItem(p)
        if (fy == null) return
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == p } ?: return
        val visible = listState.layoutInfo.viewportSize.height
        listState.scrollToItem(p, (fy * item.size - visible / 3f).roundToInt().coerceAtLeast(0))
        if (fx != null && zoom.scale > 1.01f) {
            val pageWidth = item.size / renderer.aspectRatios[p]
            zoom.centerOn((zoom.viewportWidth - pageWidth) / 2 + fx * pageWidth)
        }
    }
}

/** Continuous vertical scrolling, pages fit to width or to the whole page, with pinch and double-tap zoom. */
@Composable
fun PageList(
    renderer: PdfPageRenderer,
    listState: LazyListState,
    zoom: ZoomState,
    contentPadding: PaddingValues,
    tint: PageTint,
    fitPage: Boolean,
    onTap: () -> Unit,
    pageContent: PageContent,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier
            .clipToBounds()
            .background(tint.canvas())
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
        val widthPx = constraints.maxWidth.toFloat()
        // Fit page: leave a small margin so a whole page and a hint of the next one show.
        val fitHeightPx = constraints.maxHeight * 0.94f
        zoom.viewportWidth = widthPx

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
                val ratio = renderer.aspectRatios[page]
                val pageWidthPx = if (fitPage) minOf(widthPx, fitHeightPx / ratio) else widthPx
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    val w = with(density) { pageWidthPx.toDp() }
                    pageContent(page, renderWidthFor(pageWidthPx, zoom.settledScale), Modifier.size(w, w * ratio))
                }
            }
        }
    }
}
