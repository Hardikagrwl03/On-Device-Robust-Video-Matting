package dev.hamster.rvm.models

import android.util.Log
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/** Not enough free space to hold the model plus headroom. */
class InsufficientStorageException(message: String) : IOException(message)

/** The server's length, or the bytes actually written, disagreed with the manifest. */
class ModelSizeMismatchException(message: String) : IOException(message)

/**
 * Streams a [ModelSpec] from its GitHub release URL into [ModelStore].
 *
 * Plain [HttpURLConnection] rather than a networking dependency: eight static URLs don't warrant
 * one, and it follows the release's single https -> https redirect to `objects.githubusercontent.com`
 * by default (only cross-protocol hops are refused).
 *
 * Since the release publishes no checksums, length is the only integrity check available: the
 * server's `Content-Length` and the bytes actually written must both equal [ModelSpec.sizeBytes].
 * The download lands in a `.part` file and is renamed only once that holds, so a truncated
 * transfer can never be mistaken for an installed model.
 */
object ModelDownloader {

    private const val TAG = "ModelDownloader"
    private const val BUFFER_SIZE = 64 * 1024
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    // A 107 MB download reads ~1,700 buffers; emitting progress on each one would recompose the
    // models page into the ground. Time-based throttling caps it at 4 updates/sec regardless of
    // transfer speed, and unlike a percentage gate it still shows liveness on a slow connection.
    private const val PROGRESS_INTERVAL_MS = 250L

    suspend fun download(
        spec: ModelSpec,
        store: ModelStore,
        onProgress: (bytesRead: Long, total: Long) -> Unit
    ) {
        if (!store.hasFreeSpaceFor(spec)) {
            throw InsufficientStorageException(
                "Need ${spec.sizeBytes} bytes for ${spec.fileName}, ${store.dir.usableSpace} available"
            )
        }

        val target = store.fileFor(spec.fileName)
        val part = File(store.dir, "${spec.fileName}.part")
        val startedAt = System.currentTimeMillis()
        Log.d(TAG, "download: ${spec.url} -> ${part.name}, expecting ${spec.sizeBytes} bytes")

        val connection = (URL(spec.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }

        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP ${connection.responseCode} for ${spec.fileName}")
            }
            // Checked before writing so a stale manifest entry costs one round trip, not 100 MB.
            val declared = connection.contentLengthLong
            if (declared != spec.sizeBytes) {
                throw ModelSizeMismatchException(
                    "${spec.fileName}: server declared $declared bytes, manifest says ${spec.sizeBytes}"
                )
            }

            var bytesRead = 0L
            var lastEmit = 0L
            connection.inputStream.use { input ->
                FileOutputStream(part).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        bytesRead += count

                        val now = System.currentTimeMillis()
                        if (now - lastEmit >= PROGRESS_INTERVAL_MS) {
                            lastEmit = now
                            onProgress(bytesRead, spec.sizeBytes)
                        }
                    }
                }
            }

            if (part.length() != spec.sizeBytes) {
                throw ModelSizeMismatchException(
                    "${spec.fileName}: wrote ${part.length()} bytes, expected ${spec.sizeBytes}"
                )
            }
            // Delete first: renameTo does not overwrite on all filesystems.
            target.delete()
            if (!part.renameTo(target)) {
                throw IOException("Could not rename ${part.name} to ${target.name}")
            }
            onProgress(spec.sizeBytes, spec.sizeBytes)

            val elapsed = System.currentTimeMillis() - startedAt
            val mbPerSec = if (elapsed > 0) spec.sizeBytes / 1024.0 / 1024.0 / (elapsed / 1000.0) else 0.0
            Log.d(TAG, "download: ${spec.fileName} complete in $elapsed ms (${"%.1f".format(mbPerSec)} MB/s)")
        } catch (t: Throwable) {
            // Covers cancellation too: a leftover .part would otherwise occupy space forever,
            // since nothing resumes it.
            part.delete()
            Log.e(TAG, "download: ${spec.fileName} failed", t)
            throw t
        } finally {
            connection.disconnect()
        }
    }
}
