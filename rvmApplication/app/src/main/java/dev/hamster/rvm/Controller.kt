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
    var outputVideoUri: Uri? = null
    var outputFgrVideoUri: Uri? = null
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

    fun testVideoHandler(count: Int = 1){
        val dummyFrameBuffer: SharedBuffer = SharedBuffer(count*height!!* width!!*3*4)
        val buf = dummyFrameBuffer.buffer
        videoDecoder.getNextFrames(buf, count)
        buf.rewind()
        buf.limit(buf.capacity())
        matteEncoder.putNextFrames(buf, count)
        buf.rewind()
    }

    fun testVideoHandler(): Uri{
        val videoFile = File(context.getExternalFilesDir(null), "test.mp4")
        matteEncoder.startVideoEncoder(videoFile, width!!, height!!, videoDecoder.getFps(), videoDecoder.getBitrate())
        val dummyFrameBuffer: SharedBuffer = SharedBuffer(height!!* width!!*3*4)
        val buf = dummyFrameBuffer.buffer
        for(i in 0 until frames!!){
            videoDecoder.getNextFrame(buf)
            buf.rewind()
            matteEncoder.putNextFrame(buf)
            buf.rewind()
        }
        matteEncoder.saveVideo()
        outputVideoUri = Uri.fromFile(videoFile)
        return outputVideoUri!!
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

        val matteFile = File(context.getExternalFilesDir(null), "alphamatte.mp4")
        val fgrFile = File(context.getExternalFilesDir(null), "fgr.mp4")
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

        outputFgrVideoUri = Uri.fromFile(fgrFile)
        Uri.fromFile(matteFile)
    }

    fun reset(){
        mattingModule.reset()
    }

    fun close(){
        mattingModule.close()
    }

}
