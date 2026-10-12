package com.quickpdf.reader.ui.update

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.quickpdf.reader.R
import com.quickpdf.reader.data.Release
import com.quickpdf.reader.data.Updates
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun UpdateBanner(release: Release, onUpdate: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 14.dp, end = 8.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.SystemUpdate, null)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.update_available, release.version), style = MaterialTheme.typography.titleSmall)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.later)) }
                TextButton(onClick = onUpdate) { Text(stringResource(R.string.update)) }
            }
        }
    }
}

private sealed interface Step {
    data object Intro : Step
    data class Downloading(val done: Long, val total: Long) : Step
    class NeedsPermission(val apk: File) : Step
    data object Installing : Step
    class Failed(val reason: String) : Step
}

/**
 * Downloads the update and hands it to the system installer. Whenever that can't work, it explains how to
 * update by hand from the release page.
 */
@Composable
fun UpdateDialog(release: Release, updates: Updates, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf<Step>(Step.Intro) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun install(apk: File) {
        val a = activity ?: return
        if (updates.needsInstallPermission()) {
            step = Step.NeedsPermission(apk)
        } else {
            updates.install(a, apk)
            step = Step.Installing
        }
    }

    // Back from "Install unknown apps": continue if it was allowed.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val s = step
        if (s is Step.NeedsPermission && !updates.needsInstallPermission()) install(s.apk)
    }

    fun openReleasePage() {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.pageUrl)))
    }

    AlertDialog(
        onDismissRequest = { if (step !is Step.Downloading) onDismiss() },
        icon = { Icon(Icons.Outlined.SystemUpdate, null) },
        title = { Text(stringResource(R.string.update_title, release.version)) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                when (val s = step) {
                    Step.Intro -> {
                        if (release.notes.isNotBlank()) {
                            Text(stringResource(R.string.whats_new), style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(4.dp))
                            Text(release.notes.trim(), style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(12.dp))
                        }
                        Text(stringResource(R.string.update_keeps_data), style = MaterialTheme.typography.bodyMedium)
                    }
                    is Step.Downloading -> {
                        Text(
                            stringResource(R.string.update_downloading, s.done / 1_048_576f, s.total / 1_048_576f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { if (s.total > 0) s.done.toFloat() / s.total else 0f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    is Step.NeedsPermission -> Text(stringResource(R.string.update_permission), style = MaterialTheme.typography.bodyMedium)
                    Step.Installing -> Text(stringResource(R.string.update_installing), style = MaterialTheme.typography.bodyMedium)
                    is Step.Failed -> {
                        Text(stringResource(R.string.update_failed, s.reason), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.update_manual), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            when (val s = step) {
                Step.Intro -> TextButton(
                    enabled = release.apkUrl != null && activity != null,
                    onClick = {
                        step = Step.Downloading(0L, release.apkSize)
                        job = scope.launch {
                            step = try {
                                val apk = updates.download(release) { done, total -> step = Step.Downloading(done, total) }
                                install(apk)
                                step
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Step.Failed(e.message ?: e.javaClass.simpleName)
                            }
                        }
                    },
                ) { Text(stringResource(R.string.download_and_install)) }
                is Step.NeedsPermission -> TextButton(onClick = { activity?.let(updates::openInstallPermissionSettings) }) {
                    Text(stringResource(R.string.open_settings))
                }
                is Step.Failed, Step.Installing -> TextButton(onClick = ::openReleasePage) { Text(stringResource(R.string.open_release_page)) }
                is Step.Downloading -> {}
            }
        },
        dismissButton = {
            when (step) {
                is Step.Downloading -> TextButton(onClick = { job?.cancel(); step = Step.Intro }) { Text(stringResource(R.string.cancel)) }
                Step.Intro -> Row {
                    TextButton(onClick = ::openReleasePage) { Text(stringResource(R.string.open_release_page)) }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.later)) }
                }
                else -> TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }
        },
    )
}
