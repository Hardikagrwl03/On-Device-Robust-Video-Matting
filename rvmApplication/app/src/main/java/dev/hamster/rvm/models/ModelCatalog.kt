package dev.hamster.rvm.models

import android.content.Context

/**
 * The models actually **installed** on this device, narrowed the way the config sheet needs them
 * so no reachable selection of source/resolution/backbone/downsample-ratio can name a file that
 * isn't there.
 *
 * Backed by [ModelStore] over [ModelManifest], not by a directory listing: the manifest is the
 * single source of truth for what a valid model is, so a `.tflite` side-loaded into the store
 * that the manifest doesn't list is deliberately invisible.
 *
 * Reads the store on every query rather than caching, since the set of installed models changes
 * whenever a download completes.
 */
class ModelCatalog(context: Context) {

    private val store = ModelStore(context)

    private fun installed(): List<ModelSpec> = store.installedSpecs()

    fun availableSources(): List<ModelSource> =
        installed().map { it.source }.distinct()

    fun availableResolutions(source: ModelSource): List<Pair<Int, Int>> =
        installed().filter { it.source == source }
            .map { it.height to it.width }
            .distinct()

    fun availableBackbones(source: ModelSource, height: Int, width: Int): List<String> =
        installed().filter { it.source == source && it.height == height && it.width == width }
            .map { it.backbone }
            .distinct()

    fun availableDownsampleTags(
        source: ModelSource,
        backbone: String,
        height: Int,
        width: Int
    ): List<String> =
        installed().filter {
            it.source == source && it.backbone == backbone && it.height == height && it.width == width
        }
            .map { it.downsampleTag }
            .distinct()
}
