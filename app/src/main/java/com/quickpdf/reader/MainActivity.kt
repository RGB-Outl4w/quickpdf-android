package com.quickpdf.reader

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.quickpdf.reader.data.AppLanguage
import com.quickpdf.reader.data.AppTheme
import com.quickpdf.reader.ui.home.HomeScreen
import com.quickpdf.reader.ui.reader.ReaderScreen
import com.quickpdf.reader.ui.search.LibrarySearchScreen
import com.quickpdf.reader.ui.settings.SettingsScreen
import com.quickpdf.reader.ui.theme.QuickPdfTheme
import kotlinx.coroutines.launch

private enum class Screen { HOME, SEARCH, SETTINGS }

class MainActivity : ComponentActivity() {
    private var incoming by mutableStateOf<Uri?>(null)
    private val readerStore: ReaderStoreHolder by viewModels()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) incoming = pdfUriFrom(intent)
        val app = application as QuickPdfApp
        val library = app.library
        if (savedInstanceState == null && library.settings.value.checkUpdates) {
            lifecycleScope.launch { app.updates.check(force = false) }
        }
        setContent {
            val settings by library.settings.collectAsState()
            val dark = when (settings.theme) {
                AppTheme.LIGHT -> false
                AppTheme.DARK -> true
                AppTheme.SYSTEM -> isSystemInDarkTheme()
            }
            // Status and navigation bar icons follow the app's theme rather than the system's.
            LaunchedEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(style, style)
            }
            QuickPdfTheme(dark) {
                var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
                var openUri by rememberSaveable { mutableStateOf<Uri?>(null) }
                // Set when a document is opened from library search.
                var openPage by rememberSaveable { mutableStateOf<Int?>(null) }
                var openQuery by rememberSaveable { mutableStateOf<String?>(null) }

                fun open(uri: Uri, page: Int? = null, query: String? = null) {
                    keepAccess(uri)
                    openPage = page
                    openQuery = query
                    openUri = uri
                }

                LaunchedEffect(incoming) {
                    incoming?.let {
                        open(it)
                        incoming = null
                    }
                }
                val uri = openUri
                when {
                    uri != null -> ReaderScope(readerStore.ownerFor(uri)) {
                        ReaderScreen(
                            uri = uri,
                            startPage = openPage,
                            initialQuery = openQuery,
                            onBack = {
                                openUri = null
                                readerStore.release()
                            },
                        )
                    }
                    screen == Screen.SEARCH -> {
                        BackHandler { screen = Screen.HOME }
                        LibrarySearchScreen(
                            onOpen = { u, page, query -> open(u, page, query) },
                            onBack = { screen = Screen.HOME },
                        )
                    }
                    screen == Screen.SETTINGS -> {
                        BackHandler { screen = Screen.HOME }
                        SettingsScreen(library, app.updates, onBack = { screen = Screen.HOME })
                    }
                    else -> HomeScreen(
                        library = library,
                        updates = app.updates,
                        onOpen = { open(it) },
                        onSearchAll = { screen = Screen.SEARCH },
                        onSettings = { screen = Screen.SETTINGS },
                    )
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        (application as QuickPdfApp).library.flush()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pdfUriFrom(intent)?.let { incoming = it }
    }

    private fun keepAccess(uri: Uri) {
        // Lets "Recent" re-open the file after a restart; not every app grants persistable access.
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    private fun pdfUriFrom(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND ->
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        else -> null
    }
}

/** Gives the open document its own ViewModel scope that survives rotation and is cleared when closed. */
class ReaderStoreHolder : ViewModel() {
    private var uri: Uri? = null
    private var owner = newOwner()

    fun ownerFor(uri: Uri): ViewModelStoreOwner {
        if (uri != this.uri) {
            owner.viewModelStore.clear()
            owner = newOwner()
            this.uri = uri
        }
        return owner
    }

    fun release() {
        owner.viewModelStore.clear()
        owner = newOwner()
        uri = null
    }

    override fun onCleared() = owner.viewModelStore.clear()

    private fun newOwner() = object : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }
}

@Composable
private fun ReaderScope(owner: ViewModelStoreOwner, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner, content = content)
}
