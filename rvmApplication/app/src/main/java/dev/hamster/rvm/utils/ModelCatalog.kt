package dev.hamster.rvm.utils

import android.content.Context

/**
 * A single `.tflite` asset parsed from its filename, following the naming convention
 * `rvm_<backbone>_<height>x<width>_ds_<downsampleTag>[_<dtype>].tflite` that
 * [dev.hamster.rvm.matte.MatteConfig.buildModelFileName] produces.
 */
data class ModelEntry(
    val fileName: String,
    val backbone: String,
    val height: Int,
    val width: Int,
    val downsampleTag: String,
    val dtype: String?
)

/**
 * Parses the `.tflite` files actually present in `assets/` so config UI can only ever offer
 * backbone/resolution/downsample-ratio combinations that resolve to a model that exists,
 * instead of letting [dev.hamster.rvm.matte.MatteConfig] name a file that isn't there.
 */
class ModelCatalog(private val context: Context) {
    companion object {
        private val FILE_NAME_REGEX =
            Regex("""rvm_([a-zA-Z0-9]+)_(\d+)x(\d+)_ds_([a-zA-Z0-9]+)(?:_(int8|fp16))?\.tflite""")
    }

    val entries: List<ModelEntry> by lazy {
        (context.assets.list("") ?: emptyArray())
            .mapNotNull { fileName ->
                FILE_NAME_REGEX.matchEntire(fileName)?.let { match ->
                    val (backbone, height, width, downsampleTag, dtype) = match.destructured
                    ModelEntry(
                        fileName = fileName,
                        backbone = backbone,
                        height = height.toInt(),
                        width = width.toInt(),
                        downsampleTag = downsampleTag,
                        dtype = dtype.ifEmpty { null }
                    )
                }
            }
    }

    /** Distinct resolutions available, as height-to-width pairs. */
    fun availableResolutions(): List<Pair<Int, Int>> =
        entries.map { it.height to it.width }.distinct()

    /** Backbones available for the given resolution. */
    fun availableBackbones(height: Int, width: Int): List<String> =
        entries.filter { it.height == height && it.width == width }
            .map { it.backbone }
            .distinct()

    /** Downsample tags available for the given backbone + resolution. */
    fun availableDownsampleTags(backbone: String, height: Int, width: Int): List<String> =
        entries.filter { it.backbone == backbone && it.height == height && it.width == width }
            .map { it.downsampleTag }
            .distinct()
}
