package dev.hamster.rvm.interfaces

import java.nio.ByteBuffer

interface MattingModuleInterface {
    /**
     * Loads the TFLite model into memory and configures the hardware accelerator.
     *
     * @param modelFileName The name of the .tflite file in the assets folder.
     * @param useGPU Whether to use the GPU delegate for acceleration.
     */
    fun loadModel(modelFileName: String, useGPU: Boolean)

    /**
     * Processes one or more frames to extract the foreground and alpha matte.
     *
     * @param inputImage The source buffer containing the input frames.
     * @param outputForeground The destination buffer for the extracted foreground.
     * @param outputAlphaMatte The destination buffer for the alpha matte (mask).
     * @param count The number of frames to process from the buffers.
     */
    fun getMatte(
        inputImage: ByteBuffer,
        outputForeground: ByteBuffer,
        outputAlphaMatte: ByteBuffer,
        count: Int = 1
    )

    /**
     * Reset the hidden states to initial values.
     */
    fun resetModule()

    /**
     * Releases the TFLite interpreter and clears associated memory buffers.
     */
    fun close()
}