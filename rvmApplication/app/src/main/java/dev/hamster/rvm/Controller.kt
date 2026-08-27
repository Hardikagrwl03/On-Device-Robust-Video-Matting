package dev.hamster.rvm

import android.content.Context
import android.net.Uri
import android.util.Log
import dev.hamster.rvm.matte.MatteIO
import dev.hamster.rvm.matte.MatteModule
import dev.hamster.rvm.matte.MatteConfig
import dev.hamster.rvm.utils.SharedBuffer
import dev.hamster.rvm.video.VideoFrameDecoder
import dev.hamster.rvm.video.VideoFrameEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

class Controller(val context: Context) {

    companion object {
        const val TAG = "Controller"
    }
    var inputVideoUri: Uri? = null
    var outputFgrVideoUri: Uri? = null
    var outputMatteFile: File? = null
        private set
    var outputFgrFile: File? = null
        private set
    var height: Int? = null
    var width: Int? = null
    var frames: Int? = null
    private lateinit var config: MatteConfig
    val mattingModule = MatteModule(context)
    private val videoDecoder = VideoFrameDecoder(context)
    private val matteEncoder = VideoFrameEncoder()
    private val fgrEncoder = VideoFrameEncoder()


    fun configure(config: MatteConfig){
        this.config = config
        mattingModule.configure(config)
        if(inputVideoUri != null){
            videoDecoder.startVideoDecoder(inputVideoUri!!, 0)
        }
    }

    fun loadInputVideo(uri: Uri){
        inputVideoUri = uri
        decodeVideo()
        Log.d(TAG, "loadInputVideo: height = $height, width = $width")
    }

    private fun decodeVideo(){
        videoDecoder.startVideoDecoder(inputVideoUri!!)
        height = videoDecoder.getHeight()
        width = videoDecoder.getWidth()
        frames = videoDecoder.getFrameCount()
    }

    /**
     * Runs matting over the loaded video in a single decode/inference pass, writing the alpha
     * matte and the foreground into two separate videos at once (rather than decoding and
     * running inference twice, once per output, the way separate matte/fgr passes used to).
     * Returns the alpha matte video's URI; the foreground video's URI is available afterward via
     * [outputFgrVideoUri].
     */
    suspend fun matteVideo(onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }): Uri = withContext(Dispatchers.Default) {
        mattingModule.reset()

        val runDir = prepareRunDir()
        val stamp = System.currentTimeMillis()
        val matteFile = File(runDir, "matte_$stamp.mp4")
        val fgrFile = File(runDir, "fgr_$stamp.mp4")
        matteEncoder.startVideoEncoder(matteFile, config.width, config.height, videoDecoder.getFps(), videoDecoder.getBitrate())
        fgrEncoder.startVideoEncoder(fgrFile, config.width, config.height, videoDecoder.getFps(), videoDecoder.getBitrate())

        val frameBuffer: SharedBuffer = SharedBuffer(config.height* config.width*3*4)
        val inputFrameBuffer = frameBuffer.buffer
        val fgrBuffer: SharedBuffer = SharedBuffer(config.height* config.width*3*4)
        val outputFgrBuffer = fgrBuffer.buffer.apply {
            order(ByteOrder.nativeOrder())
        }
        val matteBuffer: SharedBuffer = SharedBuffer(config.height* config.width*4)
        val outputMatteBuffer = matteBuffer.buffer.apply {
            order(ByteOrder.nativeOrder())
        }
        for(i in 0 until frames!!){
            coroutineContext.ensureActive()
            val startTime = System.currentTimeMillis()
            videoDecoder.getNextFrame(inputFrameBuffer)
            inputFrameBuffer.rewind()

            mattingModule.run(MatteIO(inputFrameBuffer, outputFgrBuffer, outputMatteBuffer), count = 1)
            outputFgrBuffer.rewind()
            outputMatteBuffer.rewind()

            matteEncoder.putNextFrame(outputMatteBuffer, channels = 1, scale = 255.0f)
            fgrEncoder.putNextFrame(outputFgrBuffer, channels = 3)

            inputFrameBuffer.rewind()
            outputFgrBuffer.rewind()
            outputMatteBuffer.rewind()

            Log.d(TAG, "matteVideo: Frame $i Matte + Foreground Estimation in ${System.currentTimeMillis() - startTime} ms")
            onProgress(i + 1, frames!!)
        }

        inputFrameBuffer.clear()
        outputMatteBuffer.clear()
        outputFgrBuffer.clear()

        matteEncoder.saveVideo()
        fgrEncoder.saveVideo()

        outputMatteFile = matteFile
        outputFgrFile = fgrFile
        outputFgrVideoUri = Uri.fromFile(fgrFile)
        Uri.fromFile(matteFile)
    }

    private fun prepareRunDir(): File {
        val dir = File(context.cacheDir, "rvm_runs")
        if (dir.exists()) {
            dir.listFiles()?.forEach { it.delete() }
        } else {
            dir.mkdirs()
        }
        return dir
    }

    fun reset(){
        mattingModule.reset()
        File(context.cacheDir, "rvm_runs").listFiles()?.forEach { it.delete() }
        outputMatteFile = null
        outputFgrFile = null
        outputFgrVideoUri = null
    }

    fun close(){
        mattingModule.close()
    }

}
