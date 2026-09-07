package dev.hamster.rvm.models

/**
 * Which copy of the RVM PyTorch source a `.tflite` was traced from — a **build-time** property
 * baked into the file by the converter, not a runtime choice.
 *
 * [GPU] models come from `RobustVideoMatting/model_gpu/`, where four ops that the TFLite GPU
 * delegate cannot handle are rewritten (`AdaptiveAvgPool2d` -> `mean`, the `ceil_mode`
 * `AvgPool2d`, torchvision's `SqueezeExcitation.avgpool`, and `clamp(0,1)` -> a ReLU identity).
 * Each rewrite is exactly numerically equivalent to the original, so a [GPU] model is never
 * *worse* than an [ORIGINAL] one — it just also delegates completely instead of falling back to
 * CPU for those ops. [ORIGINAL] is the unmodified upstream graph, published so the converter repo
 * can benchmark the rewrites against it.
 *
 * Not to be confused with [dev.hamster.rvm.modelRunner.RuntimeConfig.ComputeDevice], which picks
 * the *delegate* at interpreter-build time. The two are independent: any source runs on any
 * device.
 */
enum class ModelSource(val tag: String) {
    GPU("gpu"),
    ORIGINAL("original")
}

/**
 * One downloadable `.tflite`, identified by the exact filename the `models-v1` GitHub release
 * publishes it under. That same string is the on-disk name in [ModelStore] and the value of
 * [dev.hamster.rvm.modelRunner.RuntimeConfig.modelFileName], so there is no mapping layer
 * anywhere that could drift out of sync.
 */
data class ModelSpec(
    val source: ModelSource,
    // A String rather than MatteConfig.Variant so this package stays independent of `matte`:
    // MatteConfig imports ModelSource from here, and a Variant import back the other way would
    // make that a dependency cycle.
    val backbone: String,
    val height: Int,
    val width: Int,
    val downsampleTag: String,
    val sizeBytes: Long
) {
    val fileName: String
        get() = "rvm_${source.tag}_${backbone}_${height}x${width}_ds_$downsampleTag.tflite"

    val url: String
        get() = "${ModelManifest.RELEASE_BASE_URL}/$fileName"
}

/**
 * The static list of every model the app can install, and the single source of truth for what a
 * valid model is. A `.tflite` side-loaded into the store that isn't listed here is invisible to
 * the app by design.
 *
 * [ModelSpec.sizeBytes] values are the release assets' current sizes and are what
 * [ModelDownloader] checks a download against, since the release publishes no checksums. **If a
 * `models-v1` asset is ever re-uploaded, this table must be updated in the same commit** or every
 * install will reject the new file as a size mismatch.
 */
object ModelManifest {
    const val RELEASE_BASE_URL =
        "https://github.com/Hardikagrwl03/On-Device-Robust-Video-Matting/releases/download/models-v1"

    val ALL: List<ModelSpec> = listOf(
        ModelSpec(ModelSource.GPU, "mobilenetv3", 720, 1280, "auto", 15_219_308L),
        ModelSpec(ModelSource.GPU, "mobilenetv3", 720, 1280, "100", 15_200_516L),
        ModelSpec(ModelSource.GPU, "resnet50", 720, 1280, "auto", 107_683_140L),
        ModelSpec(ModelSource.GPU, "resnet50", 720, 1280, "100", 107_663_768L),
        ModelSpec(ModelSource.ORIGINAL, "mobilenetv3", 720, 1280, "auto", 15_419_312L),
        ModelSpec(ModelSource.ORIGINAL, "mobilenetv3", 720, 1280, "100", 16_368_288L),
        ModelSpec(ModelSource.ORIGINAL, "resnet50", 720, 1280, "auto", 107_874_080L),
        ModelSpec(ModelSource.ORIGINAL, "resnet50", 720, 1280, "100", 108_822_888L)
    )

    /**
     * Downloaded automatically on first launch. mobilenetv3 is deliberately first: downloads run
     * one at a time, and at 15 MB it makes the app usable minutes before resnet50's 104 MB lands.
     */
    val BOOTSTRAP: List<ModelSpec> = listOf(
        ALL.first { it.source == ModelSource.GPU && it.backbone == "mobilenetv3" && it.downsampleTag == "auto" },
        ALL.first { it.source == ModelSource.GPU && it.backbone == "resnet50" && it.downsampleTag == "auto" }
    )

    private val byFileName: Map<String, ModelSpec> = ALL.associateBy { it.fileName }

    fun byFileName(fileName: String): ModelSpec? = byFileName[fileName]
}
