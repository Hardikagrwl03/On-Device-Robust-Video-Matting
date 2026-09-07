package dev.hamster.rvm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.hamster.rvm.R
import dev.hamster.rvm.matte.MatteConfig
import dev.hamster.rvm.models.ModelCatalog
import dev.hamster.rvm.models.ModelSource
import dev.hamster.rvm.modelRunner.RuntimeConfig
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private fun downsampleTagToRatio(tag: String): Float =
    if (tag == "auto") -1.0F else tag.toInt() / 100F

private fun downsampleTagLabel(tag: String, autoLabel: String, percentFormat: String): String =
    if (tag == "auto") autoLabel else percentFormat.format(tag.toInt())

/**
 * Editable draft of the settings [ConfigSheet] exposes, kept separate from [MatteConfig] itself
 * since the sheet talks in terms of [ModelCatalog]'s raw resolution/backbone/tag vocabulary
 * rather than [MatteConfig]'s resolved fields.
 */
private data class ConfigDraft(
    val device: RuntimeConfig.ComputeDevice,
    val numThreads: Int,
    val source: ModelSource,
    val resolution: Pair<Int, Int>,
    val backbone: String,
    val downsampleTag: String
)

/**
 * A [ModalBottomSheet] for editing [MatteConfig], opened/closed by the caller via [visible].
 * Edits happen on a local draft — nothing is applied until [onApply] fires (Apply), and
 * dismissing any other way (Cancel, scrim tap, back gesture) simply discards it, since the
 * draft lives in `remember` state scoped to this composable's lifetime.
 *
 * Every dropdown is populated from [ModelCatalog] (the models actually downloaded into
 * `filesDir/models/`), and each one narrows the ones below it, so no reachable combination of
 * source/resolution/backbone/downsample-ratio can name a model file that doesn't exist.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigSheet(
    visible: Boolean,
    config: MatteConfig,
    isRunning: Boolean,
    isConfiguring: Boolean,
    onApply: (MatteConfig) -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return

    val context = LocalContext.current
    val catalog = remember(context) { ModelCatalog(context) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    fun dismiss() {
        scope.launch {
            sheetState.hide()
        }.invokeOnCompletion {
            if (!sheetState.isVisible) onDismiss()
        }
    }

    // Set true right before onApply fires; once the resulting isConfiguring run finishes, the
    // sheet dismisses itself. Keeping the sheet open with the Apply button showing a spinner
    // (rather than dismissing immediately) is what makes an in-flight interpreter rebuild visible
    // instead of looking like the tap did nothing.
    var isApplying by remember { mutableStateOf(false) }
    LaunchedEffect(isConfiguring) {
        if (isApplying && !isConfiguring) {
            isApplying = false
            dismiss()
        }
    }

    var draft by remember {
        mutableStateOf(
            ConfigDraft(
                device = config.runtimeConfig.device,
                numThreads = config.runtimeConfig.numThreads,
                source = config.source,
                resolution = config.height to config.width,
                backbone = config.variant.backbone,
                downsampleTag = if (config.runtimeConfig.modelFileName.contains("_ds_auto")) {
                    "auto"
                } else {
                    (config.downsampleRatio * 100).toInt().toString()
                }
            )
        )
    }

    val sources = remember { catalog.availableSources() }
    val resolutions = remember(draft.source) { catalog.availableResolutions(draft.source) }
    val backbones = remember(draft.source, draft.resolution) {
        catalog.availableBackbones(draft.source, draft.resolution.first, draft.resolution.second)
    }
    val downsampleTags = remember(draft.source, draft.backbone, draft.resolution) {
        catalog.availableDownsampleTags(
            draft.source,
            draft.backbone,
            draft.resolution.first,
            draft.resolution.second
        )
    }

    // Narrow dependent selections whenever an upstream one changes them out of range. Resolution
    // is in the chain now too: source is the outermost key, so changing it can empty the list
    // below just as changing resolution can empty the backbones.
    LaunchedEffect(resolutions) {
        if (draft.resolution !in resolutions && resolutions.isNotEmpty()) {
            draft = draft.copy(resolution = resolutions.first())
        }
    }
    LaunchedEffect(backbones) {
        if (draft.backbone !in backbones && backbones.isNotEmpty()) {
            draft = draft.copy(backbone = backbones.first())
        }
    }
    LaunchedEffect(downsampleTags) {
        if (draft.downsampleTag !in downsampleTags && downsampleTags.isNotEmpty()) {
            draft = draft.copy(downsampleTag = downsampleTags.first())
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(stringResource(R.string.configure), style = MaterialTheme.typography.titleLarge)

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.compute_device_label), style = MaterialTheme.typography.labelLarge)
                val devices = RuntimeConfig.ComputeDevice.entries
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    devices.forEachIndexed { index, device ->
                        SegmentedButton(
                            selected = draft.device == device,
                            onClick = { draft = draft.copy(device = device) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = devices.size)
                        ) {
                            Text(device.name)
                        }
                    }
                }
            }

            // With nothing installed every dropdown below would render empty with no explanation
            // of why, and Apply would resolve to a file that doesn't exist.
            val hasModels = sources.isNotEmpty()
            if (!hasModels) {
                Text(
                    stringResource(R.string.config_no_models),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (hasModels) {
            LabeledDropdown(
                label = stringResource(R.string.source_label),
                options = sources,
                selected = draft.source,
                optionLabel = { it.tag },
                onSelect = { draft = draft.copy(source = it) }
            )

            LabeledDropdown(
                label = stringResource(R.string.resolution_label),
                options = resolutions,
                selected = draft.resolution,
                optionLabel = { "${it.first} × ${it.second}" },
                onSelect = { draft = draft.copy(resolution = it) }
            )

            LabeledDropdown(
                label = stringResource(R.string.backbone_label),
                options = backbones,
                selected = draft.backbone,
                optionLabel = { it },
                onSelect = { draft = draft.copy(backbone = it) }
            )

            val downsampleAutoLabel = stringResource(R.string.downsample_auto_label)
            val downsamplePercentFormat = stringResource(R.string.downsample_percent_format)
            LabeledDropdown(
                label = stringResource(R.string.downsample_ratio_label),
                options = downsampleTags,
                selected = draft.downsampleTag,
                optionLabel = { downsampleTagLabel(it, downsampleAutoLabel, downsamplePercentFormat) },
                onSelect = { draft = draft.copy(downsampleTag = it) }
            )

            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.threads_label, draft.numThreads),
                    style = MaterialTheme.typography.labelLarge
                )
                Slider(
                    value = draft.numThreads.toFloat(),
                    onValueChange = { draft = draft.copy(numThreads = it.roundToInt()) },
                    valueRange = 1f..8f,
                    steps = 6
                )
            }

            HorizontalDivider()

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.model_file_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    config.runtimeConfig.modelFileName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    onClick = { dismiss() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = {
                        val (height, width) = draft.resolution
                        val variant = MatteConfig.Variant.entries.first { it.backbone == draft.backbone }
                        val newConfig = config.copy(
                            height = height,
                            width = width,
                            variant = variant,
                            source = draft.source,
                            downsampleRatio = downsampleTagToRatio(draft.downsampleTag),
                            runtimeConfig = config.runtimeConfig.copy(
                                device = draft.device,
                                numThreads = draft.numThreads
                            )
                        )
                        isApplying = true
                        onApply(newConfig)
                    },
                    enabled = !isRunning && !isConfiguring && hasModels,
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    modifier = Modifier.weight(1f)
                ) {
                    if (isConfiguring) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(ButtonDefaults.IconSize),
                            strokeWidth = 2.dp,
                            color = LocalContentColor.current
                        )
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    }
                    Text(stringResource(R.string.apply))
                }
            }
        }
    }
}

/** A read-only [ExposedDropdownMenuBox] over a fixed, pre-narrowed list of [options]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> LabeledDropdown(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = optionLabel(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    }
                )
            }
        }
    }
}
