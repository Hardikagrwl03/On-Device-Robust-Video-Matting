package dev.hamster.rvm

import android.content.Context
import android.net.Uri
import android.util.Log
import dev.hamster.rvm.matte.MatteIO
import dev.hamster.rvm.matte.MatteModule
import dev.hamster.rvm.matte.MatteModuleConfig
import dev.hamster.rvm.utils.SharedBuffer
import dev.hamster.rvm.video.VideoHandlerModule
import java.io.File
import java.nio.ByteOrder



class Controller(val context: Context) {

    val TAG = "RelightController"
    var inputVideoUri: Uri? = null
    var inputVideoBuffer: SharedBuffer? = null
    var outputFgrBuffer: SharedBuffer? = null
    var alphaMatteBuffer: SharedBuffer? = null
    var outputVideoUri: Uri? = null
    var height: Int? = null
    var width: Int? = null
    var frames: Int? = null
    var fps: Int? = null
    val mattingModule = MatteModule(context)
    val videoHandler = VideoHandlerModule(context)


    fun loadModels(){
        mattingModule.configure(MatteModuleConfig())
    }

    fun loadInputVideo(uri: Uri){
        inputVideoUri = uri
        decodeVideo()
        Log.d(TAG, "loadInputVideo: height = $height, width = $width")
    }

    private fun decodeVideo(){
        videoHandler.startVideoDecoder(inputVideoUri!!)
        height = videoHandler.getHeight()
        width = videoHandler.getWidth()
        frames = videoHandler.getFrameCount()
    }

    fun testVideoHandler(count: Int = 1){
        val dummyFrameBuffer: SharedBuffer = SharedBuffer(count*height!!* width!!*3*4)
        val buf = dummyFrameBuffer.buffer
        videoHandler.getNextFrames(buf, count)
        buf.rewind()
        buf.limit(buf.capacity())
        videoHandler.putNextFrames(buf, count)
        buf.rewind()
    }

    fun testVideoHandler(): Uri{
        val videoFile = File(context.getExternalFilesDir(null), "test.mp4")
        videoHandler.startVideoEncoder(videoFile)
        val dummyFrameBuffer: SharedBuffer = SharedBuffer(height!!* width!!*3*4)
        val buf = dummyFrameBuffer.buffer
        for(i in 0 until frames!!){
            videoHandler.getNextFrame(buf)
            buf.rewind()
            videoHandler.putNextFrame(buf)
            buf.rewind()
        }
        val outputFile = videoHandler.saveVideo()
        outputVideoUri = Uri.fromFile(videoFile)
        return outputVideoUri!!
    }

    fun matteVideo(): Uri{
        val matteFile = File(context.getExternalFilesDir(null), "alphamatte.mp4")
        videoHandler.startVideoEncoder(matteFile)
        val frameBuffer: SharedBuffer = SharedBuffer(height!!* width!!*3*4)
        val inputFrameBuffer = frameBuffer.buffer
        val fgrBuffer: SharedBuffer = SharedBuffer(height!!* width!!*3*4)
        val outputFgrBuffer = fgrBuffer.buffer.apply {
            order(ByteOrder.nativeOrder())
        }
        val matteBuffer: SharedBuffer = SharedBuffer(height!!* width!!*4)
        val outputMatteBuffer = matteBuffer.buffer.apply {
            order(ByteOrder.nativeOrder())
        }
        for(i in 0 until frames!!){
            val startTime = System.currentTimeMillis()
            videoHandler.getNextFrame(inputFrameBuffer)
            inputFrameBuffer.rewind()

            mattingModule.run(MatteIO(inputFrameBuffer, outputFgrBuffer, outputMatteBuffer), count = 1)
            outputMatteBuffer.rewind()

            videoHandler.putNextFrame(outputMatteBuffer, channels = 1, scale = 255.0f)
            inputFrameBuffer.rewind()
            outputFgrBuffer.rewind()
            outputMatteBuffer.rewind()

            Log.d(TAG, "matteVideo: Frame $i Matte Estimation in ${System.currentTimeMillis() - startTime} ms")
        }

        mattingModule.close()
        inputFrameBuffer.clear()
        outputMatteBuffer.clear()
        outputFgrBuffer.clear()

        val outputFile = videoHandler.saveVideo()
        val matteUri = Uri.fromFile(matteFile)
        return matteUri
    }

    fun fgrVideo(): Uri{
        val matteFile = File(context.getExternalFilesDir(null), "fgr.mp4")
        videoHandler.startVideoEncoder(matteFile)
        val frameBuffer: SharedBuffer = SharedBuffer(height!!* width!!*3*4)
        val inputFrameBuffer = frameBuffer.buffer
        val fgrBuffer: SharedBuffer = SharedBuffer(height!!* width!!*3*4)
        val outputFgrBuffer = fgrBuffer.buffer.apply {
            order(ByteOrder.nativeOrder())
        }
        val matteBuffer: SharedBuffer = SharedBuffer(height!!* width!!*4)
        val outputMatteBuffer = matteBuffer.buffer.apply {
            order(ByteOrder.nativeOrder())
        }
        for(i in 0 until frames!!){
            val startTime = System.currentTimeMillis()
            videoHandler.getNextFrame(inputFrameBuffer)
            inputFrameBuffer.rewind()

            mattingModule.run(MatteIO(inputFrameBuffer, outputFgrBuffer, outputMatteBuffer), count = 1)
            outputMatteBuffer.rewind()

            videoHandler.putNextFrame(outputFgrBuffer, channels = 3)
            inputFrameBuffer.rewind()
            outputFgrBuffer.rewind()
            outputMatteBuffer.rewind()

            Log.d(TAG, "fgrVideo: Frame $i Foreground Estimation in ${System.currentTimeMillis() - startTime} ms")
        }

        mattingModule.close()
        inputFrameBuffer.clear()
        outputMatteBuffer.clear()
        outputFgrBuffer.clear()

        val outputFile = videoHandler.saveVideo()
        val matteUri = Uri.fromFile(matteFile)
        return matteUri
    }

}