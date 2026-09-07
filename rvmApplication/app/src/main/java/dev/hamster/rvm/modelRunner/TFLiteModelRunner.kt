package dev.hamster.rvm.modelRunner

import android.content.Context
import android.util.Log
import dev.hamster.rvm.matte.MatteModule
import dev.hamster.rvm.models.ModelStore
import dev.hamster.rvm.utils.SharedBuffer
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.FileNotFoundException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class TFLiteModelRunner(
    private val context: Context
): ModelRunnerInterface{
    companion object{
        private const val TAG = "TFLiteModelRunner"
    }
    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var nnApiDelegate: NnApiDelegate? = null
    private lateinit var runtimeConfig: RuntimeConfig

    override fun configure(newConfig: RuntimeConfig){
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
        interpreter = try {
            Interpreter(model, options)
        } catch (e: IllegalArgumentException) {
            // A delegate is mandatory once added: if the graph contains an op it doesn't
            // implement, the delegate refuses to prepare and Interpreter's constructor throws
            // rather than silently running those ops on CPU. `original`-source models hit this
            // on the GPU delegate for exactly the ops the converter's model_gpu/ tree rewrites
            // (GATHER_ND, RELU_0_TO_1, STABLEHLO_REDUCE_WINDOW), so every original + GPU pairing
            // would otherwise be a hard crash. Degrade to CPU instead - slower, but numerically
            // identical, and the alternative is killing the app for a legal selection.
            Log.w(TAG, "loadModel: ${runtimeConfig.device} delegate rejected the graph, falling back to CPU", e)
            releaseDelegates()
            Interpreter(model, Interpreter.Options().apply { setNumThreads(runtimeConfig.numThreads) })
        }
        Log.d(TAG,"loadModel: Interpreter Created")
    }

    override fun run(input: Any, output:Any){
        val start = System.currentTimeMillis()
        interpreter!!.run(input, output)
        Log.d(TAG, "run: Model with single i/o executed in ${System.currentTimeMillis() - start} ms")
    }
    override fun run(input: Array<ByteBuffer>, output: Map<Int, Any>){
        val start = System.currentTimeMillis()
        interpreter!!.runForMultipleInputsOutputs(input, output)
        Log.d(TAG, "run: Model with multiple i/o executed in ${System.currentTimeMillis() - start} ms")
    }

    override fun testDummyInputs(){
        val inputCount = interpreter!!.inputTensorCount
        val input : Array<ByteBuffer?> = arrayOfNulls<ByteBuffer>(inputCount)
        Log.d("testDummyInputs", "=== MODEL INPUTS ===")
        var totalInputBytes = 0
        for (i in 0 until inputCount) {
            val tensor = interpreter!!.getInputTensor(i)
            val shape = tensor.shape().joinToString(prefix = "[", postfix = "]")
            val dataType = tensor.dataType()
            val numBytes = tensor.numBytes()
            Log.d("testDummyInputs", "Input $i: Shape $shape , Type $dataType, Bytes $numBytes")
            totalInputBytes += numBytes

            val shm = SharedBuffer(numBytes)
            val buffer = shm.buffer.apply {
                order(ByteOrder.nativeOrder())
            }
            input[i] = buffer
        }
        Log.d(TAG, "testDummyInputs: Input Total Size: ${totalInputBytes/(1024*1024)} MB")
        val output = mutableMapOf<Int, Any>()
        Log.d("testDummyInputs", "=== MODEL OUTPUTS ===")
        val outputCount = interpreter!!.outputTensorCount
        var totalOutputBytes = 0
        for (i in 0 until outputCount) {
            val tensor = interpreter!!.getOutputTensor(i)
            val shape = tensor.shape().joinToString(prefix = "[", postfix = "]")
            val dataType = tensor.dataType()
            val numBytes = tensor.numBytes()

            Log.d("testDummyInputs", "Output $i: Shape $shape , Type $dataType, Bytes $numBytes")
            totalOutputBytes += numBytes
            // Allocate direct buffer for the output and set the byte order to native
            val shm = SharedBuffer(numBytes)
            output[i] = shm.buffer.apply {
                order(ByteOrder.nativeOrder())
            }
        }
        Log.d(TAG, "testDummyInputs: Output Total Size: ${totalOutputBytes/(1024*1024)} MB")
        try {
            val startTime = System.currentTimeMillis()
            // Run the model with the dummy input buffers
            interpreter!!.runForMultipleInputsOutputs(input, output)
            Log.d("testDummyInputs", "Model execution successful! in ${System.currentTimeMillis() - startTime} ms")
        } catch (e: Exception) {
            Log.e("testDummyInputs", "Model execution failed:  ")
            e.printStackTrace()
        }

    }

    override fun close(){
        interpreter?.close()
        interpreter = null
        releaseDelegates()
        Log.d(TAG, "close: TFLite Model Runner closed along with delegates")
    }

    /** Closes whichever delegate is held, without touching the interpreter. */
    private fun releaseDelegates(){
        gpuDelegate?.close()
        gpuDelegate=null

        nnApiDelegate?.close()
        nnApiDelegate=null
    }
    /**
     * Memory-maps a downloaded model out of [ModelStore]. Models are no longer bundled as assets:
     * they are fetched on demand from the `models-v1` release into `filesDir/models/`.
     *
     * This runs on the `rvm-matte` confined thread (see [MatteModule]), which is fine for a
     * memory-map. Nothing here may ever *download* -- that would block the confined thread on
     * network I/O of unbounded duration.
     */
    private fun loadModelFile(modelFileName: String): MappedByteBuffer {
        val file = ModelStore(context).fileFor(modelFileName)
        if (!file.exists()) throw FileNotFoundException("Model not downloaded: $modelFileName")
        // The mapping outlives the channel, so closing it here is safe.
        return RandomAccessFile(file, "r").use { raf ->
            raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
        }
    }

    override fun logSignature() {
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