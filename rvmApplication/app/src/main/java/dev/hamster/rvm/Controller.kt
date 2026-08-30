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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

class Controller(val context: Context) {

    companion object {
        const val TAG = "Controller"
    }
    var inputVideoUri: Uri? = null
    var outputFgrVideoUri: Uri? = null
    var outputCompositeVideoUri: Uri? = null
    var outputMatteFile: File? = null
        private set
    var outputFgrFile: File? = null
        private set
    var outputCompositeFile: File? = null
        private set
    var height: Int? = null
    var width: Int? = null
    var frames: Int? = null
    private lateinit var config: MatteConfig
    val mattingModule = MatteModule(context)
    private val videoDecoder = VideoFrameDecoder(context)
    private val matteEncoder = VideoFrameEncoder("rvm-encode-matte")
    private val fgrEncoder = VideoFrameEncoder("rvm-encode-fgr")
    private val compositeEncoder = VideoFrameEncoder("rvm-encode-composite")

    // Reused across compositeForegroundWithAlpha calls instead of being reallocated per frame,
    // same reasoning as VideoFrameEncoder's yuvBuffer/floatArray reuse: at 720x1280 these are
    // multi-megabyte arrays.
    private var compositeFgrScratch: FloatArray = FloatArray(0)
    private var compositeAlphaScratch: FloatArray = FloatArray(0)


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
     * matte, the foreground, and their per-pixel composite (foreground * alpha - the subject
     * matted onto black, edges fading out by opacity rather than hard-cut) into three separate
     * videos at once (rather than decoding and running inference three times, once per output).
     * Returns the alpha matte video's URI; the foreground and composite videos' URIs are
     * available afterward via [outputFgrVideoUri] and [outputCompositeVideoUri].
     */
    suspend fun matteVideo(onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }): Uri = withContext(Dispatchers.Default) {
        mattingModule.reset()

        val runDir = prepareRunDir()
        val stamp = System.currentTimeMillis()
        val matteFile = File(runDir, "matte_$stamp.mp4")
        val fgrFile = File(runDir, "fgr_$stamp.mp4")
        val compositeFile = File(runDir, "composite_$stamp.mp4")
        matteEncoder.startVideoEncoder(matteFile, config.width, config.height, videoDecoder.getFps(), videoDecoder.getBitrate())
        fgrEncoder.startVideoEncoder(fgrFile, config.width, config.height, videoDecoder.getFps(), videoDecoder.getBitrate())
        compositeEncoder.startVideoEncoder(compositeFile, config.width, config.height, videoDecoder.getFps(), videoDecoder.getBitrate())

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
        val compositeBuffer: SharedBuffer = SharedBuffer(config.height* config.width*3*4)
        val outputCompositeBuffer = compositeBuffer.buffer.apply {
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

            compositeForegroundWithAlpha(outputFgrBuffer, outputMatteBuffer, outputCompositeBuffer, config.height * config.width)

            matteEncoder.putNextFrame(outputMatteBuffer, channels = 1, scale = 255.0f)
            fgrEncoder.putNextFrame(outputFgrBuffer, channels = 3)
            compositeEncoder.putNextFrame(outputCompositeBuffer, channels = 3)

            inputFrameBuffer.rewind()
            outputFgrBuffer.rewind()
            outputMatteBuffer.rewind()
            outputCompositeBuffer.rewind()

            Log.d(TAG, "matteVideo: Frame $i Matte + Foreground Estimation in ${System.currentTimeMillis() - startTime} ms")
            onProgress(i + 1, frames!!)
        }

        inputFrameBuffer.clear()
        outputMatteBuffer.clear()
        outputFgrBuffer.clear()
        outputCompositeBuffer.clear()

        matteEncoder.saveVideo()
        fgrEncoder.saveVideo()
        compositeEncoder.saveVideo()

        outputMatteFile = matteFile
        outputFgrFile = fgrFile
        outputCompositeFile = compositeFile
        outputFgrVideoUri = Uri.fromFile(fgrFile)
        outputCompositeVideoUri = Uri.fromFile(compositeFile)
        Uri.fromFile(matteFile)
    }

    /**
     * Writes `fgr[pixel] * alpha[pixel]` (per RGB channel, same alpha for all three) into [out] -
     * the subject matted onto black, with partially-transparent edges fading by opacity instead
     * of being either fully kept or fully cut. All three buffers are FLOAT32, row-major,
     * interleaved-channel tensors as produced by [MatteModule]; [fgr] and [out] have 3 channels,
     * [alpha] has 1.
     *
     * Bulk-copies into [compositeFgrScratch]/[compositeAlphaScratch] and back rather than using
     * per-element `FloatBuffer.get(index)`/`put(index, value)`: at 720x1280 that's ~2.76M
     * individual buffer accesses per frame, which measured at ~300ms/frame (more than the
     * inference step itself) on these `SharedMemory`-backed buffers - bulk transfer plus a tight
     * primitive-`FloatArray` loop (the same pattern `VideoFrameEncoder.floatBufferToNV21` already
     * uses) does the identical work in a few ms.
     */
    private fun compositeForegroundWithAlpha(fgr: ByteBuffer, alpha: ByteBuffer, out: ByteBuffer, pixelCount: Int) {
        if (compositeFgrScratch.size != pixelCount * 3) {
            compositeFgrScratch = FloatArray(pixelCount * 3)
        }
        if (compositeAlphaScratch.size != pixelCount) {
            compositeAlphaScratch = FloatArray(pixelCount)
        }

        fgr.asFloatBuffer().get(compositeFgrScratch, 0, pixelCount * 3)
        alpha.asFloatBuffer().get(compositeAlphaScratch, 0, pixelCount)

        for (pixel in 0 until pixelCount) {
            val a = compositeAlphaScratch[pixel]
            val base = pixel * 3
            compositeFgrScratch[base] *= a
            compositeFgrScratch[base + 1] *= a
            compositeFgrScratch[base + 2] *= a
        }

        out.asFloatBuffer().put(compositeFgrScratch, 0, pixelCount * 3)
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
        outputCompositeFile = null
        outputFgrVideoUri = null
        outputCompositeVideoUri = null
    }

    fun close(){
        mattingModule.close()
        videoDecoder.close()
        // Not saveVideo() - that's the per-run finalize step, already done at the end of every
        // successful matteVideo() run. This only shuts down each encoder's dedicated thread, once,
        // when the whole Controller (and thus these reused encoder instances) is being torn down.
        matteEncoder.close()
        fgrEncoder.close()
        compositeEncoder.close()
    }

}
