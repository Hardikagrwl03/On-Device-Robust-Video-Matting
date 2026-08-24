package dev.hamster.rvm.utils

import android.content.Context
import android.util.Log
import dev.hamster.rvm.matte.MatteModule
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

data class RuntimeConfig(
    val modelFileName: String,
    val device: ComputeDevice = ComputeDevice.GPU,
    val numThreads: Int = 4
){
    enum class ComputeDevice {
        CPU,
        GPU,
        NPU,
        AUTO
    }
}

class TFLiteModelRunner(private val context: Context){
    companion object{
        private const val TAG = "TFLiteModelRunner"
    }
    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var nnApiDelegate: NnApiDelegate? = null
    private lateinit var runtimeConfig: RuntimeConfig

    fun configure(newConfig: RuntimeConfig){
        if(!::runtimeConfig.isInitialized || interpreter == null){
            runtimeConfig = newConfig
            loadModel(newConfig.modelFileName)
            Log.d(MatteModule.TAG, "configure: TFLite Model Runner configured with:\nModel: ${runtimeConfig.modelFileName}\nDevice: ${runtimeConfig.device}\nNum Threads: ${runtimeConfig.numThreads}")
            return
        }
        val oldConfig = runtimeConfig
        val needsNewInterpreter =
            oldConfig.modelFileName != newConfig.modelFileName ||
            oldConfig.device != newConfig.device ||
            oldConfig.numThreads != newConfig.numThreads

        if(needsNewInterpreter){
            close()
            runtimeConfig = newConfig
            loadModel(newConfig.modelFileName)
            Log.d(MatteModule.TAG, "configure: TFLite Model Runner configured with:\nModel: ${runtimeConfig.modelFileName}\nDevice: ${runtimeConfig.device}\nNum Threads: ${runtimeConfig.numThreads}")
        } else {
            runtimeConfig = newConfig
            Log.d(TAG, "configure: no interpreter-affecting change, reusing existing interpreter")
            Log.d(MatteModule.TAG, "configure: TFLite Model Runner configured with:\nModel: ${runtimeConfig.modelFileName}\nDevice: ${runtimeConfig.device}\nNum Threads: ${runtimeConfig.numThreads}")
        }
    }

    private fun loadModel(modelFileName: String){

        val model = loadModelFile(modelFileName)
        val options = Interpreter.Options()
        options.setNumThreads(runtimeConfig.numThreads)

        when(runtimeConfig.device){
            RuntimeConfig.ComputeDevice.CPU ->{
                Log.d(TAG,"loadModel: Using CPU")
            }
            RuntimeConfig.ComputeDevice.GPU ->{
                gpuDelegate = GpuDelegate()
                options.addDelegate(gpuDelegate)
                Log.d(TAG,"loadModel: Using GPU")
            }
            RuntimeConfig.ComputeDevice.NPU ->{
                nnApiDelegate = NnApiDelegate()
                options.addDelegate(nnApiDelegate)
                options.setUseNNAPI(true)
                Log.d(TAG,"loadModel: Using NNAPI")
            }
            RuntimeConfig.ComputeDevice.AUTO ->{
                try{
                    nnApiDelegate = NnApiDelegate()
                    options.addDelegate(nnApiDelegate)
                    options.setUseNNAPI(true)
                    Log.d(TAG,"loadModel: AUTO -> NNAPI")
                }catch(e:Exception){
                    try{
                        gpuDelegate = GpuDelegate()
                        options.addDelegate(gpuDelegate)
                        Log.d(TAG,"loadModel: AUTO -> GPU")
                    }catch(e2:Exception){
                        Log.d(TAG,"loadModel: AUTO -> CPU")
                    }
                }
            }
        }
        interpreter = Interpreter(model, options)
        Log.d(TAG,"loadModel: Interpreter Created")
    }

    fun run(input: Any, output:Any){
        val start = System.currentTimeMillis()
        interpreter!!.run(input, output)
        Log.d(TAG, "run: Model with single i/o executed in ${System.currentTimeMillis() - start} ms")
    }
    fun run(input: Array<ByteBuffer>, output: Map<Int, Any>){
        val start = System.currentTimeMillis()
        interpreter!!.runForMultipleInputsOutputs(input, output)
        Log.d(TAG, "run: Model with multiple i/o executed in ${System.currentTimeMillis() - start} ms")
    }

    fun testDummyInputs(){
        val inputCount = interpreter!!.inputTensorCount
        val input : Array<ByteBuffer?> = arrayOfNulls<ByteBuffer>(inputCount)
        Log.d("TFLiteSignature", "=== MODEL INPUTS ===")
        var totalInputBytes = 0
        for (i in 0 until inputCount) {
            val tensor = interpreter!!.getInputTensor(i)
            val shape = tensor.shape().joinToString(prefix = "[", postfix = "]")
            val dataType = tensor.dataType()
            val numBytes = tensor.numBytes()
            Log.d("TFLiteSignature", "Input $i: Shape $shape , Type $dataType, Bytes $numBytes")
            totalInputBytes += numBytes

            val shm = SharedBuffer(numBytes)
            val buffer = shm.buffer.apply {
                order(ByteOrder.nativeOrder())
            }
            input[i] = buffer
        }
        Log.d(TAG, "TFLiteSignature: Input Total Size: ${totalInputBytes/(1024*1024)} MB")
        val output = mutableMapOf<Int, Any>()
        Log.d("TFLiteSignature", "=== MODEL OUTPUTS ===")
        val outputCount = interpreter!!.outputTensorCount
        var totalOutputBytes = 0
        for (i in 0 until outputCount) {
            val tensor = interpreter!!.getOutputTensor(i)
            val shape = tensor.shape().joinToString(prefix = "[", postfix = "]")
            val dataType = tensor.dataType()
            val numBytes = tensor.numBytes()

            Log.d("TFLiteSignature", "Output $i: Shape $shape , Type $dataType, Bytes $numBytes")
            totalOutputBytes += numBytes
            // Allocate direct buffer for the output and set the byte order to native
            val shm = SharedBuffer(numBytes)
            output[i] = shm.buffer.apply {
                order(ByteOrder.nativeOrder())
            }
        }
        Log.d(TAG, "TFLiteSignature: Output Total Size: ${totalOutputBytes/(1024*1024)} MB")
        try {
            val startTime = System.currentTimeMillis()
            // Run the model with the dummy input buffers
            interpreter!!.runForMultipleInputsOutputs(input, output)
            Log.d("TFLiteSignature", "Model execution successful! in ${System.currentTimeMillis() - startTime} ms")
        } catch (e: Exception) {
            Log.e("TFLiteSignature", "Model execution failed:  ")
            e.printStackTrace()
        }

    }

    fun close(){
        interpreter?.close()
        interpreter = null

        gpuDelegate?.close()
        gpuDelegate=null

        nnApiDelegate?.close()
        nnApiDelegate=null
        Log.d(TAG, "close: TFLite Model Runner closed along with delegates")
    }
    private fun loadModelFile(modelFileName: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelFileName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    fun logSignature() {
        Log.d("logSignature", "=== MODEL INPUTS ===")
        val inputCount = interpreter!!.inputTensorCount
        for (i in 0 until inputCount) {
            val tensor = interpreter!!.getInputTensor(i)
            val shape = tensor.shape().joinToString(prefix = "[", postfix = "]")
            val dataType = tensor.dataType()
            // tensor.numBytes() tells you exactly how big your SharedMemory buffer needs to be!
            Log.d("logSignature", "Input $i -> Name: ${tensor.name()}, Shape: $shape, DataType: $dataType, Total Bytes: ${tensor.numBytes()}")
        }

        Log.d("logSignature", "=== MODEL OUTPUTS ===")
        val outputCount = interpreter!!.outputTensorCount
        for (i in 0 until outputCount) {
            val tensor = interpreter!!.getOutputTensor(i)
            val shape = tensor.shape().joinToString(prefix = "[", postfix = "]")
            val dataType = tensor.dataType()
            Log.d("logSignature", "Output $i -> Name: ${tensor.name()}, Shape: $shape, DataType: $dataType, Total Bytes: ${tensor.numBytes()}")
        }
    }

}