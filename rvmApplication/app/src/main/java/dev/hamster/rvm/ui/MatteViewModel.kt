package dev.hamster.rvm.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hamster.rvm.Controller
import dev.hamster.rvm.matte.MatteConfig
import dev.hamster.rvm.modelRunner.RuntimeConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Everything the matting screen needs to render, as one observable snapshot. [config] and
 * [stage] are what let the config sheet (feature 6) and the progress UI (feature 7) stay in
 * sync with the rest of the screen without any extra plumbing. [elapsedMs] is populated once a
 * run reaches [Stage.DONE], for the completion stats (elapsed time / avg ms per frame).
 */
data class MatteUiState(
    val selectedVideoUri: Uri? = null,
    val outputVideoUri: Uri? = null,
    val config: MatteConfig = MatteConfig(),
    val stage: Stage = Stage.IDLE,
    val processedFrames: Int = 0,
    val totalFrames: Int = 0,
    val errorMessage: String? = null,
    val elapsedMs: Long? = null
) {
    enum class Stage { IDLE, CONFIGURING, RUNNING, DONE, ERROR }
}

private const val KEY_HEIGHT = "config_height"
private const val KEY_WIDTH = "config_width"
private const val KEY_DEVICE = "config_device"
private const val KEY_THREADS = "config_threads"
private const val KEY_DTYPE = "config_dtype"
private const val KEY_VARIANT = "config_variant"
private const val KEY_DOWNSAMPLE = "config_downsample"

/**
 * Owns the [Controller] and exposes [MatteUiState] as a single [StateFlow] for the matting
 * screen to collect.
 *
 * Takes `(Application, SavedStateHandle)` rather than extending `AndroidViewModel` so it can get
 * both without a custom factory: the default `SavedStateViewModelFactory` that
 * `ComponentActivity.viewModels()` uses can resolve either parameter type by reflection on the
 * constructor. [SavedStateHandle] survives process death (unlike a plain in-memory default), so
 * the user's chosen [MatteConfig] — device/resolution/backbone/etc. — is restored after Android
 * kills and recreates the process, not just a config change.
 */
class MatteViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val controller = Controller(application)

    private val _uiState = MutableStateFlow(MatteUiState(config = restoreConfig()))
    val uiState: StateFlow<MatteUiState> = _uiState.asStateFlow()

    private var runningJob: Job? = null

    init {
        controller.configure(_uiState.value.config)
    }

    private fun restoreConfig(): MatteConfig {
        val defaults = MatteConfig()
        val height = savedStateHandle.get<Int>(KEY_HEIGHT) ?: return defaults
        val width = savedStateHandle.get<Int>(KEY_WIDTH) ?: return defaults
        val device = savedStateHandle.get<String>(KEY_DEVICE)
            ?.let { runCatching { RuntimeConfig.ComputeDevice.valueOf(it) }.getOrNull() }
            ?: return defaults
        val dtype = savedStateHandle.get<String>(KEY_DTYPE)
            ?.let { runCatching { MatteConfig.Dtype.valueOf(it) }.getOrNull() }
            ?: return defaults
        val variant = savedStateHandle.get<String>(KEY_VARIANT)
            ?.let { runCatching { MatteConfig.Variant.valueOf(it) }.getOrNull() }
            ?: return defaults

        return MatteConfig(
            height = height,
            width = width,
            runtimeConfig = RuntimeConfig(
                modelFileName = "",
                device = device,
                numThreads = savedStateHandle.get<Int>(KEY_THREADS) ?: defaults.runtimeConfig.numThreads
            ),
            dtype = dtype,
            variant = variant,
            downsampleRatio = savedStateHandle.get<Float>(KEY_DOWNSAMPLE) ?: defaults.downsampleRatio
        )
    }

    private fun persistConfig(config: MatteConfig) {
        // config.downsampleRatio is already resolved by MatteConfig.init by this point (e.g.
        // -1.0F "auto" becomes 0.4F for a 720x1280 config), so persisting it raw would restore
        // as a frozen fixed ratio instead of "auto" - and that resolved ratio generally doesn't
        // name a model file that exists (only specific fixed tags and "auto" ship as assets).
        // The filename is where the auto/fixed distinction survives, so re-derive the sentinel
        // from it, same as ConfigCard's downsampleDisplay does.
        val downsampleRatio = if (config.runtimeConfig.modelFileName.contains("_ds_auto")) {
            -1.0F
        } else {
            config.downsampleRatio
        }

        savedStateHandle[KEY_HEIGHT] = config.height
        savedStateHandle[KEY_WIDTH] = config.width
        savedStateHandle[KEY_DEVICE] = config.runtimeConfig.device.name
        savedStateHandle[KEY_THREADS] = config.runtimeConfig.numThreads
        savedStateHandle[KEY_DTYPE] = config.dtype.name
        savedStateHandle[KEY_VARIANT] = config.variant.name
        savedStateHandle[KEY_DOWNSAMPLE] = downsampleRatio
    }

    fun onVideoSelected(uri: Uri) {
        runningJob?.cancel()
        runningJob = null
        controller.loadInputVideo(uri)
        _uiState.update {
            it.copy(
                selectedVideoUri = uri,
                outputVideoUri = null,
                stage = MatteUiState.Stage.IDLE,
                processedFrames = 0,
                totalFrames = 0,
                errorMessage = null,
                elapsedMs = null
            )
        }
    }

    fun updateConfig(config: MatteConfig) {
        controller.configure(config)
        persistConfig(config)
        _uiState.update { it.copy(config = config) }
    }

    fun runMatting() {
        if (_uiState.value.stage == MatteUiState.Stage.RUNNING) return
        if (_uiState.value.selectedVideoUri == null) return

        _uiState.update {
            it.copy(
                stage = MatteUiState.Stage.RUNNING,
                outputVideoUri = null,
                processedFrames = 0,
                totalFrames = 0,
                errorMessage = null,
                elapsedMs = null
            )
        }

        val startTime = System.currentTimeMillis()
        runningJob = viewModelScope.launch {
            try {
                val outputUri = controller.matteVideo { current, total ->
                    _uiState.update { it.copy(processedFrames = current, totalFrames = total) }
                }
                val elapsed = System.currentTimeMillis() - startTime
                _uiState.update {
                    it.copy(stage = MatteUiState.Stage.DONE, outputVideoUri = outputUri, elapsedMs = elapsed)
                }
            } catch (e: CancellationException) {
                _uiState.update { it.copy(stage = MatteUiState.Stage.IDLE) }
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(stage = MatteUiState.Stage.ERROR, errorMessage = e.message ?: "Matting failed")
                }
            }
        }
    }

    fun cancel() {
        runningJob?.cancel()
        runningJob = null
        if (_uiState.value.stage == MatteUiState.Stage.RUNNING) {
            _uiState.update { it.copy(stage = MatteUiState.Stage.IDLE) }
        }
    }

    fun reset() {
        runningJob?.cancel()
        runningJob = null
        controller.reset()
        _uiState.update {
            it.copy(
                selectedVideoUri = null,
                outputVideoUri = null,
                stage = MatteUiState.Stage.IDLE,
                processedFrames = 0,
                totalFrames = 0,
                errorMessage = null,
                elapsedMs = null
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        controller.close()
    }
}
