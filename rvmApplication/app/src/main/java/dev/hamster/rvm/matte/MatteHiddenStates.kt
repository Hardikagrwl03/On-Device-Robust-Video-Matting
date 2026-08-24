package dev.hamster.rvm.matte

import dev.hamster.rvm.utils.SharedBuffer
import dev.hamster.rvm.interfaces.HiddenStatesInterface
import java.nio.ByteBuffer

class MatteHiddenStates(
    override val height: Int,
    override val width: Int,
    nBytes: Int,
    channels: IntArray
): HiddenStatesInterface{
    override val state = mutableMapOf<Int, SharedBuffer>()

    init {
        var scale = 1
        for(i in 0 until 4){
            scale *= 2
            state[i] = SharedBuffer(channels[i] * (height / scale) * (width / scale) * nBytes)
        }
        rewind()
    }

    override fun put(src: HiddenStatesInterface){
        rewind()
        src.rewind()
        for(i in 0 until 4){
            state[i]!!.buffer.put(src.state[i]!!.buffer)
        }
        rewind()
        src.rewind()
    }

    override fun reset() {
        for(i in 0 until 4){
            zero(state[i]!!.buffer)
        }
        rewind()
    }

    override fun rewind() {
        for(i in 0 until 4){
            state[i]!!.buffer.rewind()
        }
    }

    override fun close(){
        for(i in 0 until 4){
            state[i]!!.clear()
        }
    }

    private fun zero(byteBuffer: ByteBuffer) {
        byteBuffer.clear()
        val zeroArray = ByteArray(byteBuffer.capacity())
        byteBuffer.put(zeroArray)
        byteBuffer.clear()
    }
}