package dev.hamster.rvm.models

import android.content.Context
import android.util.Log
import dev.hamster.rvm.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** What the models page renders for one [ModelSpec]. */
sealed interface ModelDownloadState {
    data object NotInstalled : ModelDownloadState
    data object Queued : ModelDownloadState
    data class Downloading(val bytesRead: Long, val total: Long) : ModelDownloadState {
        val fraction: Float get() = if (total > 0) bytesRead.toFloat() / total else 0f
    }
    data object Installed : ModelDownloadState
    data class Failed(val message: String) : ModelDownloadState
}

/**
 * Owns every model download and the state the UI renders from, as a process-wide singleton.
 *
 * Deliberately **not** a ViewModel: a 104 MB transfer has to survive navigating off the models
 * page, so its coroutine cannot live in a `viewModelScope`. Screens collect [states] directly.
 *
 * Downloads run **one at a time** behind [downloadMutex]. Two concurrent 104 MB streams would
 * only halve each other's bandwidth and double peak disk usage; waiting entries report
 * [ModelDownloadState.Queued] so the UI never has to render a queued download as a stalled 0%.
 *
 * *Known limitation:* a download still dies with the process and cannot resume — there is no
 * `WorkManager`/HTTP-`Range` machinery here. A killed transfer restarts from zero.
 */
class ModelRepository private constructor(context: Context) {

    companion object {
        private const val TAG = "ModelRepository"

        @Volatile
        private var instance: ModelRepository? = null

        fun get(context: Context): ModelRepository =
            instance ?: synchronized(this) {
                instance ?: ModelRepository(context.applicationContext).also { instance = it }
            }
    }

    private val appContext = context.applicationContext
    private val store = ModelStore(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloadMutex = Mutex()
    private val jobs = mutableMapOf<String, Job>()

    private val _states = MutableStateFlow(
        ModelManifest.ALL.associate { spec ->
            spec.fileName to if (store.isInstalled(spec.fileName)) {
                ModelDownloadState.Installed
            } else {
                ModelDownloadState.NotInstalled
            }
        }
    )
    val states: StateFlow<Map<String, ModelDownloadState>> = _states.asStateFlow()

    fun stateOf(spec: ModelSpec): ModelDownloadState =
        _states.value[spec.fileName] ?: ModelDownloadState.NotInstalled

    /** No-op unless the model is [ModelDownloadState.NotInstalled] or [ModelDownloadState.Failed]. */
    fun download(spec: ModelSpec) {
        synchronized(jobs) {
            if (jobs.containsKey(spec.fileName)) return
            when (_states.value[spec.fileName]) {
                is ModelDownloadState.Installed,
                is ModelDownloadState.Queued,
                is ModelDownloadState.Downloading -> return
                else -> Unit
            }
            setState(spec, ModelDownloadState.Queued)
            jobs[spec.fileName] = scope.launch {
                try {
                    downloadMutex.withLock {
                        setState(spec, ModelDownloadState.Downloading(0L, spec.sizeBytes))
                        ModelDownloader.download(spec, store) { bytesRead, total ->
                            setState(spec, ModelDownloadState.Downloading(bytesRead, total))
                        }
                    }
                    setState(spec, ModelDownloadState.Installed)
                    Log.d(TAG, "download: ${spec.fileName} installed")
                } catch (e: Throwable) {
                    // Full detail goes to logcat; only the short form reaches the UI.
                    Log.e(TAG, "download: ${spec.fileName} failed", e)
                    setState(spec, ModelDownloadState.Failed(messageFor(e)))
                } finally {
                    synchronized(jobs) { jobs.remove(spec.fileName) }
                }
            }
        }
    }

    fun retry(spec: ModelSpec) {
        if (_states.value[spec.fileName] is ModelDownloadState.Failed) {
            setState(spec, ModelDownloadState.NotInstalled)
        }
        download(spec)
    }

    /**
     * Downloads the bootstrap models if they aren't already present. Idempotent and non-blocking —
     * safe to call from `MainActivity.onCreate` on every launch.
     */
    fun ensureBootstrapModels() {
        ModelManifest.BOOTSTRAP.forEach { spec ->
            if (!store.isInstalled(spec.fileName)) {
                Log.d(TAG, "ensureBootstrapModels: enqueueing ${spec.fileName}")
                download(spec)
            }
        }
    }

    private fun setState(spec: ModelSpec, state: ModelDownloadState) {
        _states.update { it + (spec.fileName to state) }
    }

    private fun messageFor(e: Throwable): String = when (e) {
        is UnknownHostException -> appContext.getString(R.string.error_no_internet)
        is SocketTimeoutException -> appContext.getString(R.string.error_timeout)
        is InsufficientStorageException -> appContext.getString(R.string.error_no_storage)
        is ModelSizeMismatchException -> appContext.getString(R.string.error_size_mismatch)
        is IOException -> appContext.getString(R.string.error_no_internet)
        else -> appContext.getString(R.string.error_download_generic, e.message ?: e.javaClass.simpleName)
    }
}
