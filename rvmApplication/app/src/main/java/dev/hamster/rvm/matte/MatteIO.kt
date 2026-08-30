package dev.hamster.rvm.matte

import java.nio.ByteBuffer

/** MatteModule's [dev.hamster.rvm.interfaces.ModuleInterface] IO: named buffers instead of positional ones so call sites read clearly. */
data class MatteIO(
    val inputImage: ByteBuffer,
    val outputForeground: ByteBuffer,
    val outputAlphaMatte: ByteBuffer
)
