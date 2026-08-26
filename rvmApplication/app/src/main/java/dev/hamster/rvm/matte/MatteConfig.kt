package dev.hamster.rvm.matte

import dev.hamster.rvm.modelRunner.RuntimeConfig
import dev.hamster.rvm.interfaces.ConfigInterface

/** Matte-specific implementation of [ConfigInterface]; resolves to a [RuntimeConfig] naming the RVM model to load. */
data class MatteConfig(
    override var height: Int = 720,
    override var width: Int = 1280,
    override var runtimeConfig: RuntimeConfig = RuntimeConfig(""),
    var dtype: Dtype = Dtype.FLOAT32,
    var variant: Variant = Variant.RESNET50,
    var downsampleRatio: Float = 1.0F
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
        return "rvm_${variant.backbone}_${height}x${width}_ds_${if (downsampleRatio==-1.0F) "auto" else (downsampleRatio*100).toInt()}.tflite"
    }
}