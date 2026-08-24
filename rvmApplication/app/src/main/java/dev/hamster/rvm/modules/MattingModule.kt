package dev.hamster.rvm.modules

import android.content.Context
import android.util.Log
import dev.hamster.rvm.SharedBuffer
import dev.hamster.rvm.TfliteModelRunner
import dev.hamster.rvm.interfaces.MattingModuleInterface
import java.nio.ByteBuffer

class MattingModule(
    context: Context,
    val height: Int,
    val width: Int,
    val nBytes: Int = 4
) : MattingModuleInterface {

    val TAG = "MattingModule"
    private val mattingModel = TfliteModelRunner(context)

    private val h1Input = SharedBuffer(16 * (height / 2) * (width / 2) * nBytes)
    private val h2Input = SharedBuffer(32*(height/4)*(width/4)*nBytes)
    private val h3Input = SharedBuffer(64*(height/8)*(width/8)*nBytes)
    private val h4Input = SharedBuffer(128*(height/16)*(width/16)*nBytes)

    private val h1Output = SharedBuffer(16*(height/2)*(width/2)*nBytes)
    private val h2Output = SharedBuffer(32*(height/4)*(width/4)*nBytes)
    private val h3Output = SharedBuffer(64*(height/8)*(width/8)*nBytes)
    private val h4Output = SharedBuffer(128*(height/16)*(width/16)*nBytes)

    init{
        resetHiddenStates()
    }
    private val rvmOutput = mutableMapOf<Int, ByteBuffer>()
    private val rvmInput = arrayOf(null, h1Input.buffer, h2Input.buffer, h3Input.buffer, h4Input.buffer)

    init{
        rvmOutput[2] = h1Output.buffer
        rvmOutput[3] = h2Output.buffer
        rvmOutput[4] = h3Output.buffer
        rvmOutput[5] = h4Output.buffer
    }

    override fun loadModel(modelFileName: String, useGPU: Boolean){
        mattingModel.loadModel(modelFileName, useGPU)
        Log.d(TAG, "loadModel: $modelFileName model loaded  to ${if (useGPU) { "GPU" } else { "CPU" }}")
    }

    private fun runRVM(inputImage: ByteBuffer, outputForeground:ByteBuffer, outputAlphaMatte: ByteBuffer){
        val startTime = System.currentTimeMillis()
        rvmInput[0] = inputImage
        rvmOutput[0] = outputForeground
        rvmOutput[1] = outputAlphaMatte
        mattingModel.runMultipleInference(rvmInput as Array<ByteBuffer>, rvmOutput)
        rewindHiddenStates()
        passHiddenStates()
        rewindHiddenStates()
        Log.d(TAG, "runRVM: RVM executed and Hidden states passed in ${System.currentTimeMillis() - startTime} ms")
    }

    private fun rewindHiddenStates(){
        h1Input.buffer.rewind()
        h2Input.buffer.rewind()
        h3Input.buffer.rewind()
        h4Input.buffer.rewind()
        h1Output.buffer.rewind()
        h2Output.buffer.rewind()
        h3Output.buffer.rewind()
        h4Output.buffer.rewind()
    }

    private fun passHiddenStates(){
        h1Input.buffer.put(h1Output.buffer)
        h2Input.buffer.put(h2Output.buffer)
        h3Input.buffer.put(h3Output.buffer)
        h4Input.buffer.put(h4Output.buffer)
//        Log.d(TAG, "passHiddenStates: Hidden States passed")
    }

    private fun resetHiddenStates(){
        fillZeroInByteBuffer(h1Input.buffer)
        fillZeroInByteBuffer(h2Input.buffer)
        fillZeroInByteBuffer(h3Input.buffer)
        fillZeroInByteBuffer(h4Input.buffer)
//        Log.d(TAG, "resetHiddenStates: Hidden States reset to zero")
    }

    private fun fillZeroInByteBuffer(byteBuffer: ByteBuffer){
        byteBuffer.clear()
        val zeroArray = ByteArray(byteBuffer.capacity())
        byteBuffer.put(zeroArray)
        byteBuffer.clear()
    }

    override fun getMatte(inputImage: ByteBuffer, outputForeground:ByteBuffer, outputAlphaMatte: ByteBuffer, count: Int){
        if(count==1){
            runRVM(inputImage, outputForeground, outputAlphaMatte)
        }else{
            for(i in 0 until count){
                inputImage.position(i*height*width*3*nBytes)
                inputImage.limit((i+1)*height*width*3*4)
                outputForeground.position(i*height*width*3*nBytes)
                outputForeground.limit((i+1)*height*width*3*nBytes)
                outputAlphaMatte.position(i*height*width*nBytes)
                outputAlphaMatte.limit((i+1)*height*width*nBytes)
                val partialInput = inputImage.slice().order(inputImage.order())
                val partialFgr = outputForeground.slice().order(outputForeground.order())
                val partialAlpha = outputAlphaMatte.slice().order(outputAlphaMatte.order())
                Log.d(TAG, "getMatte: Input $i: $partialInput, $partialFgr, $partialAlpha")
                runRVM(partialInput, partialFgr, partialAlpha)
            }
        }
        Log.d(TAG, "getMatte: Matting for $count frames executed")
    }

    override fun resetModule(){
        resetHiddenStates()
        rewindHiddenStates()
    }

    override fun close(){
        mattingModel.close()
        h1Input.clear()
        h2Input.clear()
        h3Input.clear()
        h4Input.clear()
        h1Output.clear()
        h2Output.clear()
        h3Output.clear()
        h4Output.clear()
        Log.d(TAG, "close: Matting Module closed and cleared")
    }

}