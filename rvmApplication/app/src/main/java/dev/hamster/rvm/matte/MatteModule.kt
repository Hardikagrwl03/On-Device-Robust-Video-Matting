package dev.hamster.rvm.matte

import android.content.Context
import android.util.Log
import dev.hamster.rvm.modelRunner.TFLiteModelRunner
import dev.hamster.rvm.interfaces.HiddenStatesInterface
import dev.hamster.rvm.interfaces.ModuleInterface
import java.nio.ByteBuffer

/** Matte-specific implementation of [ModuleInterface]: runs the RVM matting model and carries its hidden states between frames. */
class MatteModule(
    context: Context
) : ModuleInterface<MatteConfig, MatteIO> {
    companion object {
        const val TAG = "MatteModule"
    }
    private val matteModel = TFLiteModelRunner(context)
    private lateinit var hiddenStates: HiddenStatesInterface
    private lateinit var config: MatteConfig
    private val rvmInput = arrayOfNulls<ByteBuffer>(5)
    private val rvmOutput = mutableMapOf<Int, ByteBuffer>()

    override fun configure(newConfig: MatteConfig){
        if(!::config.isInitialized){
            config = newConfig
            matteModel.configure(config.runtimeConfig)
            initializeHiddenStates()
            matteModel.logSignature()
            Log.d(TAG, "configure: Matte Module configured with:\nResolution: ${config.height}x${config.width}\nVariant: ${config.variant}\nDownsampleRatio: ${config.downsampleRatio}")
            return
        }
        val oldConfig = config
        val needsNewHiddenStates =
            oldConfig.height != newConfig.height ||
            oldConfig.width != newConfig.width ||
                    oldConfig.variant != newConfig.variant ||
            oldConfig.downsampleRatio != newConfig.downsampleRatio

        reset()
        config = newConfig
        if(needsNewHiddenStates){
            hiddenStates.close()
            initializeHiddenStates()
        }
        matteModel.configure(config.runtimeConfig)
        matteModel.logSignature()
        Log.d(TAG, "configure: Matte Module configured with:\nResolution: ${config.height}x${config.width}\nVariant: ${config.variant}\nDownsampleRatio: ${config.downsampleRatio}")
    }

    private fun initializeHiddenStates(){
        val downsampledHeight = (config.height * config.downsampleRatio).toInt()
        val downsampledWidth = (config.width * config.downsampleRatio).toInt()
        hiddenStates = MatteHiddenStates(
            height = downsampledHeight,
            width = downsampledWidth,
            nBytes = config.dtype.nBytes,
            channels = config.variant.channels
        )
        hiddenStates.reset()
        Log.d(TAG, "initializeHiddenStates: Matte Module Hidden States Initialized")
    }

    private fun runRVM(inputImage: ByteBuffer, outputForeground:ByteBuffer, outputAlphaMatte: ByteBuffer){
        val startTime = System.currentTimeMillis()
        val tmpHiddenStates = MatteHiddenStates(
            height = hiddenStates.height,
            width = hiddenStates.width,
            nBytes = config.dtype.nBytes,
            channels = config.variant.channels
        )
        rvmInput[0] = inputImage
        rvmOutput[0] = outputForeground
        rvmOutput[1] = outputAlphaMatte
        for(i in 0 until 4){
            rvmInput[i+1] = hiddenStates.state[i]!!.buffer
            rvmOutput[i+2] = tmpHiddenStates.state[i]!!.buffer
        }
        matteModel.run(rvmInput as Array<ByteBuffer>, rvmOutput)
        hiddenStates.rewind()
        tmpHiddenStates.rewind()
        hiddenStates.put(tmpHiddenStates)
        tmpHiddenStates.close()
        Log.d(TAG, "runRVM: RVM executed and Hidden states passed in ${System.currentTimeMillis() - startTime} ms")
    }

    override fun run(io: MatteIO, count: Int){
        val inputImage = io.inputImage
        val outputForeground = io.outputForeground
        val outputAlphaMatte = io.outputAlphaMatte

        if(count==1){
            runRVM(inputImage, outputForeground, outputAlphaMatte)
        }else{
            for(i in 0 until count){
                inputImage.position(i*config.height*config.width*3*config.dtype.nBytes)
                inputImage.limit((i+1)*config.height*config.width*3*config.dtype.nBytes)
                outputForeground.position(i*config.height*config.width*3*config.dtype.nBytes)
                outputForeground.limit((i+1)*config.height*config.width*3*config.dtype.nBytes)
                outputAlphaMatte.position(i*config.height*config.width*config.dtype.nBytes)
                outputAlphaMatte.limit((i+1)*config.height*config.width*config.dtype.nBytes)
                val partialInput = inputImage.slice().order(inputImage.order())
                val partialFgr = outputForeground.slice().order(outputForeground.order())
                val partialAlpha = outputAlphaMatte.slice().order(outputAlphaMatte.order())
                Log.d(TAG, "run: Input $i: $partialInput, $partialFgr, $partialAlpha")
                runRVM(partialInput, partialFgr, partialAlpha)
            }
        }
        Log.d(TAG, "run: Matting for $count frames executed")
    }

    override fun reset(){
        hiddenStates.reset()
        hiddenStates.rewind()
        Log.d(TAG, "reset: Matte Module reset")
    }

    override fun close(){
        matteModel.close()
        hiddenStates.close()
        Log.d(TAG, "close: Matte Module closed and cleared")
    }

}