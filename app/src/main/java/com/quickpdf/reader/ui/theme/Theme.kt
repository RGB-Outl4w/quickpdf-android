package com.quickpdf.reader.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Light = lightColorScheme(
    primary = Color(0xFF3D5AFE),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE1FF),
    onPrimaryContainer = Color(0xFF001257),
    secondary = Color(0xFF5B5D72),
    secondaryContainer = Color(0xFFE0E0F9),
    tertiary = Color(0xFF00897B),
    background = Color(0xFFF8F9FC),
    surface = Color(0xFFF8F9FC),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFB9C3FF),
    onPrimary = Color(0xFF00218B),
    primaryContainer = Color(0xFF1F3BD6),
    onPrimaryContainer = Color(0xFFDDE1FF),
    secondary = Color(0xFFC4C5DD),
    secondaryContainer = Color(0xFF434659),
    tertiary = Color(0xFF80CBC4),
    background = Color(0xFF111318),
    surface = Color(0xFF111318),
)

@Composable
fun QuickPdfTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Highlight colors for search matches on pages. */
object HighlightColors {
    val match = Color(0x66FFD54F)
    val current = Color(0x99FF7043)
}

/** Colors users can give their highlights; an annotation stores the index. */
object AnnotationColors {
    val all = listOf(Color(0xFFFFD54F), Color(0xFF81C784), Color(0xFF64B5F6), Color(0xFFF06292))

    operator fun get(index: Int): Color = all[index.coerceIn(0, all.lastIndex)]

    /** Text selection on pages. */
    val selection = Color(0x553D5AFE)
    val handle = Color(0xFF3D5AFE)
}
