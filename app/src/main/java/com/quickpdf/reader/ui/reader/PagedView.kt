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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.quickpdf.reader.data.PageTint
import com.quickpdf.reader.pdf.PdfPageRenderer
import kotlinx.coroutines.launch

/**
 * Pages grouped into what one screen shows. With [twoUp], the first page (usually the cover) stands alone and the
 * rest pair up as in a printed book: [0], [1, 2], [3, 4], …
 */
fun spreadsFor(pageCount: Int, twoUp: Boolean): List<IntRange> =
    if (!twoUp) List(pageCount) { it..it }
    else buildList {
        if (pageCount > 0) add(0..0)
        var p = 1
        while (p < pageCount) {
            add(p..minOf(p + 1, pageCount - 1))
            p += 2
        }
    }

/** Index of the spread that shows [page]. */
fun spreadIndexOf(page: Int, twoUp: Boolean): Int = if (twoUp) (page + 1) / 2 else page

class PagerNavigator(
    private val pagerState: PagerState,
    private val spreads: List<IntRange>,
    private val twoUp: Boolean,
) : PageNavigator {
    override val currentPage: Int get() = spreads.getOrNull(pagerState.currentPage)?.first ?: 0
    override val isMoving: Boolean get() = pagerState.isScrollInProgress

    override suspend fun goTo(page: Int, fx: Float?, fy: Float?) {
        pagerState.scrollToPage(spreadIndexOf(page, twoUp).coerceIn(0, (spreads.size - 1).coerceAtLeast(0)))
    }
}

/** Zoom of the spread on screen: screen = offset + scale * local. Reset when the page changes. */
@Stable
class PagedZoom {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    var settledScale by mutableFloatStateOf(1f)
        private set

    var width = 1f
    var height = 1f

    fun transform(factor: Float, focus: Offset, pan: Offset) {
        val old = scale
        val new = (old * factor).coerceIn(ZoomState.MIN, ZoomState.MAX)
        val local = (focus - offset) / old
        scale = new
        offset = clamp(focus - local * new + pan)
    }

    fun pan(delta: Offset) {
        offset = clamp(offset + delta)
    }

    fun settle() {
        settledScale = scale
    }

    fun reset() {
        scale = 1f
        offset = Offset.Zero
        settledScale = 1f
    }

    private fun clamp(o: Offset) = Offset(
        o.x.coerceIn(width - scale * width, 0f),
        o.y.coerceIn(height - scale * height, 0f),
    )
}

/** Page by page, swiping sideways; on wide screens two pages side by side. */
@Composable
fun PagedView(
    renderer: PdfPageRenderer,
    pagerState: PagerState,
    spreads: List<IntRange>,
    zoom: PagedZoom,
    tint: PageTint,
    /** Space kept free for the reader's bars, so a whole page is always visible. */
    contentPadding: PaddingValues,
    onTap: () -> Unit,
    pageContent: PageContent,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    LaunchedEffect(pagerState.settledPage) { zoom.reset() }

    BoxWithConstraints(
        modifier
            .clipToBounds()
            .background(tint.canvas())
            .pointerInput(Unit) {
                // Pinch in the Initial pass so the pager never sees it; one-finger pan when zoomed in the
                // Main pass, after the page had its chance to use the drag for text selection.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var pinched = false
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            pinched = true
                            zoom.transform(event.calculateZoom(), event.calculateCentroid(useCurrent = true), event.calculatePan())
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    if (pinched) zoom.settle()
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (zoom.scale > 1.01f && event.changes.none { it.isConsumed }) {
                            zoom.pan(event.calculatePan())
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { focus ->
                        scope.launch {
                            val target = if (zoom.scale > 1.05f) 1f else 2.5f
                            Animatable(zoom.scale).animateTo(target, tween(220)) {
                                zoom.transform(value / zoom.scale, focus, Offset.Zero)
                            }
                            zoom.settle()
                        }
                    },
                )
            }
    ) {
        val w = constraints.maxWidth.toFloat()
        zoom.width = w
        zoom.height = constraints.maxHeight.toFloat()
        val gap = with(density) { 6.dp.toPx() }
        val h = constraints.maxHeight - with(density) {
            (contentPadding.calculateTopPadding() + contentPadding.calculateBottomPadding()).toPx()
        }

        HorizontalPager(
            state = pagerState,
            userScrollEnabled = zoom.scale <= 1.01f,
            beyondViewportPageCount = 1,
            key = { spreads[it].first },
            modifier = Modifier.fillMaxSize(),
        ) { index ->
            val pages = spreads[index]
            val ratios = pages.map { renderer.aspectRatios[it] }
            // Common page height that fits the whole spread on screen.
            val pageHeight = minOf(h, (w - gap * (ratios.size - 1)) / ratios.sumOf { 1.0 / it }.toFloat())
            val zoomed = index == pagerState.currentPage
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        if (zoomed) {
                            scaleX = zoom.scale
                            scaleY = zoom.scale
                            translationX = zoom.offset.x
                            translationY = zoom.offset.y
                            transformOrigin = TransformOrigin(0f, 0f)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Row(Modifier.padding(contentPadding), horizontalArrangement = Arrangement.spacedBy(with(density) { gap.toDp() })) {
                    for ((k, page) in pages.withIndex()) {
                        val pageWidth = pageHeight / ratios[k]
                        val scale = if (zoomed) zoom.settledScale else 1f
                        with(density) {
                            pageContent(page, renderWidthFor(pageWidth, scale), Modifier.size(pageWidth.toDp(), pageHeight.toDp()))
                        }
                    }
                }
            }
        }
    }
}
