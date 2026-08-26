package dev.hamster.rvm.ui

import android.app.Activity
import android.net.Uri
import android.view.WindowManager
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.hamster.rvm.R
import dev.hamster.rvm.matte.MatteConfig

/**
 * The app's single screen: a title bar, the input/output video previews, and the primary
 * action buttons. Configuration UI and progress/run behaviour are layered on in later phases;
 * this only lays out the structure and wires video selection through to [viewModel].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatteScreen(viewModel: MatteViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showConfigSheet by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val isRunning = uiState.stage == MatteUiState.Stage.RUNNING

    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            viewModel.onVideoSelected(uri)
        } else {
            Toast.makeText(context, R.string.error_selection_cancelled, Toast.LENGTH_SHORT).show()
        }
    }

    DisposableEffect(isRunning) {
        val activity = context as? Activity
        if (isRunning) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    LaunchedEffect(uiState.errorMessage) {
        val message = uiState.errorMessage
        if (uiState.stage == MatteUiState.Stage.ERROR && message != null) {
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.app_name)) })
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(stringResource(R.string.input_video_label), style = MaterialTheme.typography.titleMedium)
            VideoPlayer(
                uri = uiState.selectedVideoUri,
                contentDescription = stringResource(R.string.input_video_label),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
            )

            Text(stringResource(R.string.output_video_label), style = MaterialTheme.typography.titleMedium)
            VideoPlayer(
                uri = uiState.outputVideoUri,
                contentDescription = stringResource(R.string.output_video_label),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
            )
            if (uiState.stage == MatteUiState.Stage.DONE && uiState.elapsedMs != null && uiState.totalFrames > 0) {
                Text(
                    text = stringResource(
                        R.string.completion_stats,
                        uiState.elapsedMs!! / 1000f,
                        uiState.elapsedMs!! / uiState.totalFrames
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { pickVideo.launch("video/*") },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.select_video))
                }
                if (isRunning) {
                    Button(
                        onClick = { viewModel.cancel() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                } else {
                    Button(
                        onClick = { viewModel.runMatting() },
                        enabled = uiState.selectedVideoUri != null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.relight))
                    }
                }
                OutlinedButton(
                    onClick = { if (isRunning) showResetConfirm = true else viewModel.reset() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.reset))
                }
            }

            if (isRunning) {
                val total = uiState.totalFrames
                val processed = uiState.processedFrames
                val progress = if (total > 0) processed.toFloat() / total.toFloat() else 0f
                val percent = if (total > 0) processed * 100 / total else 0

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = stringResource(R.string.progress_caption, processed, total, percent),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            ConfigCard(config = uiState.config, modifier = Modifier.fillMaxWidth())

            OutlinedButton(
                onClick = { showConfigSheet = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.configure))
            }
        }
    }

    ConfigSheet(
        visible = showConfigSheet,
        config = uiState.config,
        isRunning = isRunning,
        onApply = { viewModel.updateConfig(it) },
        onDismiss = { showConfigSheet = false }
    )

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(stringResource(R.string.reset_confirm_title)) },
            text = { Text(stringResource(R.string.reset_confirm_message)) },
            confirmButton = {
                Button(onClick = {
                    viewModel.reset()
                    showResetConfirm = false
                }) {
                    Text(stringResource(R.string.reset))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

/**
 * A quiet summary card of the currently active [config] — everything the config sheet edits,
 * plus the resolved model filename that actually gets loaded. Recomposes automatically whenever
 * [MatteViewModel.updateConfig] pushes a new config into [MatteUiState.config], so it always
 * reflects what was last applied without any extra plumbing.
 */
@Composable
fun ConfigCard(config: MatteConfig, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ConfigRow(stringResource(R.string.compute_device_label), config.runtimeConfig.device.name)
            ConfigRow(stringResource(R.string.resolution_label), "${config.height} × ${config.width}")
            ConfigRow(stringResource(R.string.backbone_label), config.variant.backbone)
            ConfigRow(
                stringResource(R.string.downsample_ratio_label),
                downsampleDisplay(
                    config,
                    autoFormat = stringResource(R.string.downsample_auto_ratio_format),
                    percentFormat = stringResource(R.string.downsample_percent_format)
                )
            )
            ConfigRow(stringResource(R.string.dtype_label), config.dtype.name)
            ConfigRow(stringResource(R.string.threads_value_label), config.runtimeConfig.numThreads.toString())

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Text(
                text = stringResource(R.string.model_file_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = config.runtimeConfig.modelFileName,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun ConfigRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * [MatteConfig.init] resolves the `-1.0F` "auto" sentinel into a concrete ratio immediately, so
 * by the time a [MatteConfig] instance exists there's no field left distinguishing "auto" from a
 * fixed ratio that happens to match. The model filename is the one place that distinction
 * survives (`buildModelFileName` runs before the sentinel is resolved), so it's what this reads.
 */
private fun downsampleDisplay(config: MatteConfig, autoFormat: String, percentFormat: String): String {
    return if (config.runtimeConfig.modelFileName.contains("_ds_auto")) {
        autoFormat.format(config.downsampleRatio)
    } else {
        percentFormat.format((config.downsampleRatio * 100).toInt())
    }
}

/**
 * Plays [uri] in a [VideoView] wrapped for Compose, or shows a placeholder surface when there's
 * nothing selected yet. Preserves two behaviours from the original XML screen: `setZOrderOnTop`
 * (a `VideoView` is backed by a `SurfaceView`, which otherwise composites behind the window and
 * renders as black), and fitting the video's aspect ratio inside the allotted box rather than
 * stretching it.
 */
@Composable
fun VideoPlayer(uri: Uri?, contentDescription: String, modifier: Modifier = Modifier) {
    if (uri == null) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .semantics { this.contentDescription = contentDescription },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.no_video_selected),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    AndroidView(
        modifier = modifier.semantics { this.contentDescription = contentDescription },
        factory = { context ->
            VideoView(context).apply {
                setZOrderOnTop(true)
            }
        },
        update = { videoView ->
            videoView.setVideoURI(uri)
            videoView.setOnPreparedListener { mediaPlayer ->
                val videoProportion = mediaPlayer.videoWidth.toFloat() / mediaPlayer.videoHeight.toFloat()

                val parentWidth = videoView.width
                val parentHeight = videoView.height
                val screenProportion = parentWidth.toFloat() / parentHeight.toFloat()

                val layoutParams = videoView.layoutParams
                if (videoProportion > screenProportion) {
                    // Video is wider than the view
                    layoutParams.width = parentWidth
                    layoutParams.height = (parentWidth / videoProportion).toInt()
                } else {
                    // Video is taller than the view
                    layoutParams.width = (videoProportion * parentHeight).toInt()
                    layoutParams.height = parentHeight
                }
                videoView.layoutParams = layoutParams

                mediaPlayer.isLooping = true
                videoView.start()
            }
        }
    )
}
