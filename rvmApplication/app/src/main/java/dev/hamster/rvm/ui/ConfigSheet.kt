package dev.hamster.rvm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import dev.hamster.rvm.utils.ModelCatalog
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
 * Every dropdown is populated from [ModelCatalog] (the `.tflite` files actually present in
 * `assets/`), and each one narrows the ones below it, so no reachable combination of
 * device/resolution/backbone/downsample-ratio can name a model file that doesn't exist.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigSheet(
    visible: Boolean,
    config: MatteConfig,
    isRunning: Boolean,
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

    var draft by remember {
        mutableStateOf(
            ConfigDraft(
                device = config.runtimeConfig.device,
                numThreads = config.runtimeConfig.numThreads,
                resolution = config.height to config.width,
                backbone = config.variant.backbone,
                downsampleTag = if (config.downsampleRatio == -1.0F) "auto" else (config.downsampleRatio * 100).toInt().toString()
            )
        )
    }

    val resolutions = remember { catalog.availableResolutions() }
    val backbones = remember(draft.resolution) {
        catalog.availableBackbones(draft.resolution.first, draft.resolution.second)
    }
    val downsampleTags = remember(draft.backbone, draft.resolution) {
        catalog.availableDownsampleTags(draft.backbone, draft.resolution.first, draft.resolution.second)
    }

    // Narrow dependent selections whenever an upstream one changes them out of range.
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
                            downsampleRatio = downsampleTagToRatio(draft.downsampleTag),
                            runtimeConfig = config.runtimeConfig.copy(
                                device = draft.device,
                                numThreads = draft.numThreads
                            )
                        )
                        onApply(newConfig)
                        dismiss()
                    },
                    enabled = !isRunning,
                    modifier = Modifier.weight(1f)
                ) {
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
