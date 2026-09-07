package dev.hamster.rvm.matte

import dev.hamster.rvm.modelRunner.RuntimeConfig
import dev.hamster.rvm.interfaces.ConfigInterface
import dev.hamster.rvm.models.ModelSource

/** Matte-specific implementation of [ConfigInterface]; resolves to a [RuntimeConfig] naming the RVM model to load. */
data class MatteConfig(
    override var height: Int = 720,
    override var width: Int = 1280,
    override var runtimeConfig: RuntimeConfig = RuntimeConfig(""),
    var dtype: Dtype = Dtype.FLOAT32,
    var variant: Variant = Variant.RESNET50,
    // Defaults to the auto sentinel so a fresh install resolves to a bootstrap model
    // (rvm_gpu_resnet50_720x1280_ds_auto.tflite) rather than to a ds_100 build nothing downloads.
    var downsampleRatio: Float = -1.0F,
    var source: ModelSource = ModelSource.GPU
) : ConfigInterface {
    enum class Variant(id: Int, val backbone: String, val channels: IntArray){
        RESNET50(0, "resnet50", intArrayOf(16, 32, 64, 128)),
        MOBILENETv3(1, "mobilenetv3", intArrayOf(16, 20, 40, 64))
    }

    enum class Dtype(val nBytes: Int, val suffix: String) {
        INT8(1, "int8"),
        FLOAT16(2, "fp16"),
        FLOAT32(4, "fp32")
    }

    /**
     * The downsample ratio *as requested*, i.e. with the `-1.0F` "auto" sentinel intact.
     *
     * [init] resolves that sentinel to a concrete ratio (0.4 at 720x1280), so [downsampleRatio]
     * can no longer tell "auto" apart from a fixed ratio that happens to equal it. Any `copy()`
     * that passes [downsampleRatio] straight back through therefore rebuilds the filename as
     * `_ds_040` instead of `_ds_auto` and names a model that was never published. The filename is
     * where the distinction survives, so re-derive it from there and copy with **this** instead.
     */
    val requestedDownsampleRatio: Float
        get() = if (runtimeConfig.modelFileName.contains("_ds_auto")) -1.0F else downsampleRatio

    init {
        require(width > 0)
        require(height > 0)
        require(width%16 == 0)
        require(height%16 == 0)

        runtimeConfig = runtimeConfig.copy(modelFileName = buildModelFileName())

        if(downsampleRatio == -1.0F){
            downsampleRatio = minOf(512F / maxOf(height, width), 1.0F)
        }
    }

    private fun buildModelFileName(): String {
        val dtypeTag = if (dtype == Dtype.FLOAT32) "" else "_${dtype.suffix}"
        // Zero-padded to three digits to match the converter's convention (0.5 -> "050"), and
        // prefixed with the source so the name is byte-identical to the release asset it came
        // from -- the manifest entry, the URL, the on-disk file and RuntimeConfig.modelFileName
        // are then all the same string, with no mapping layer to drift.
        val dsTag = if (downsampleRatio == -1.0F) "auto" else "%03d".format((downsampleRatio * 100).toInt())
        return "rvm_${source.tag}_${variant.backbone}_${height}x${width}_ds_$dsTag.tflite"
    }
}