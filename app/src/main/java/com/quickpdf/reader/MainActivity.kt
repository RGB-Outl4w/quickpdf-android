package com.quickpdf.reader

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.quickpdf.reader.ui.home.HomeScreen
import com.quickpdf.reader.ui.reader.ReaderScreen
import com.quickpdf.reader.ui.theme.QuickPdfTheme

class MainActivity : ComponentActivity() {
    private var incoming by mutableStateOf<Uri?>(null)
    private val readerStore: ReaderStoreHolder by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) incoming = pdfUriFrom(intent)
        val library = (application as QuickPdfApp).library
        setContent {
            QuickPdfTheme {
                var openUri by rememberSaveable { mutableStateOf<Uri?>(null) }
                LaunchedEffect(incoming) {
                    incoming?.let {
                        keepAccess(it)
                        openUri = it
                        incoming = null
                    }
                }
                val uri = openUri
                if (uri == null) {
                    HomeScreen(
                        library = library,
                        onOpen = { picked ->
                            keepAccess(picked)
                            openUri = picked
                        },
                    )
                } else {
                    ReaderScope(readerStore.ownerFor(uri)) {
                        ReaderScreen(
                            uri = uri,
                            onBack = {
                                openUri = null
                                readerStore.release()
                            },
                        )
                    }
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
