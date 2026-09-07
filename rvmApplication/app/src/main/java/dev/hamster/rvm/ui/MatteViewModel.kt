package dev.hamster.rvm.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hamster.rvm.Controller
import dev.hamster.rvm.R
import dev.hamster.rvm.matte.MatteConfig
import dev.hamster.rvm.models.ModelRepository
import dev.hamster.rvm.models.ModelSource
import dev.hamster.rvm.models.ModelSpec
import dev.hamster.rvm.models.ModelStore
import dev.hamster.rvm.modelRunner.RuntimeConfig
import dev.hamster.rvm.utils.MediaStoreSaver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything the matting screen needs to render, as one observable snapshot. [config] and
 * [stage] are what let the config sheet (feature 6) and the progress UI (feature 7) stay in
 * sync with the rest of the screen without any extra plumbing. [elapsedMs] is populated once a
 * run reaches [Stage.DONE], for the completion stats (elapsed time / avg ms per frame).
 */
data class MatteUiState(
    val selectedVideoUri: Uri? = null,
    val outputMatteVideoUri: Uri? = null,
    val outputFgrVideoUri: Uri? = null,
    val outputCompositeVideoUri: Uri? = null,
    val outputSelection: OutputKind = OutputKind.MATTE,
    val config: MatteConfig = MatteConfig(),
    val stage: Stage = Stage.IDLE,
    val processedFrames: Int = 0,
    val totalFrames: Int = 0,
    val errorMessage: String? = null,
    val elapsedMs: Long? = null,
    val isSaving: Boolean = false,
    val transientMessage: String? = null,
    val isConfiguring: Boolean = false,
    /** No model that [config] could name is installed yet, so nothing can be configured or run. */
    val modelMissing: Boolean = false
) {
    enum class Stage { IDLE, RUNNING, DONE, ERROR }
    enum class OutputKind { MATTE, FOREGROUND, BOTH }

    val activeOutputUri: Uri?
        get() = when (outputSelection) {
            OutputKind.MATTE -> outputMatteVideoUri
            OutputKind.FOREGROUND -> outputFgrVideoUri
            OutputKind.BOTH -> outputCompositeVideoUri
        }
}

private const val KEY_HEIGHT = "config_height"
private const val KEY_WIDTH = "config_width"
private const val KEY_DEVICE = "config_device"
private const val KEY_THREADS = "config_threads"
private const val KEY_DTYPE = "config_dtype"
private const val KEY_VARIANT = "config_variant"
private const val KEY_DOWNSAMPLE = "config_downsample"
private const val KEY_SOURCE = "config_source"

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
    private val application: Application,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val controller = Controller(application)

    private val _uiState = MutableStateFlow(MatteUiState(config = restoreConfig()))
    val uiState: StateFlow<MatteUiState> = _uiState.asStateFlow()

    private var runningJob: Job? = null

    init {
        resolveAndConfigure()
        observeModelInstalls()
    }

    /**
     * Picks a model that actually exists on disk and configures it, or records [modelMissing]
     * when nothing is installed at all.
     *
     * Models are downloaded on demand now, so the configured one is not guaranteed to be present:
     * on a fresh install nothing is, and a restored [MatteConfig] can name a model the user never
     * downloaded. Configuring blindly would throw `FileNotFoundException` out of the interpreter.
     *
     * When the configured model is missing but *some other* model is installed, that one is used
     * instead. Bootstrap downloads mobilenetv3 (15 MB) before resnet50 (104 MB) precisely so the
     * app becomes usable early; waiting for the exact default would throw that away.
     */
    private fun resolveAndConfigure() {
        val store = ModelStore(application)
        val config = _uiState.value.config
        if (store.isInstalled(config.runtimeConfig.modelFileName)) {
            // A restored config can name an unsupported pairing too, since it was persisted before
            // this check existed or before the model it names was swapped.
            val effective = coerceUnsupportedDevice(config) ?: config
            _uiState.update { it.copy(config = effective, modelMissing = false) }
            runConfigure(effective)
            return
        }
        val fallback = store.installedSpecs().firstOrNull()
        if (fallback == null) {
            _uiState.update { it.copy(modelMissing = true) }
            return
        }
        val resolved = configFrom(fallback, config).let { coerceUnsupportedDevice(it) ?: it }
        _uiState.update { it.copy(config = resolved, modelMissing = false) }
        runConfigure(resolved)
    }

    /** Configures as soon as a download makes a usable model available. */
    private fun observeModelInstalls() {
        viewModelScope.launch {
            ModelRepository.get(application).states.collect {
                if (_uiState.value.modelMissing) resolveAndConfigure()
            }
        }
    }

    private fun configFrom(spec: ModelSpec, base: MatteConfig): MatteConfig = base.copy(
        height = spec.height,
        width = spec.width,
        variant = MatteConfig.Variant.entries.first { it.backbone == spec.backbone },
        // -1.0F is the "auto" sentinel MatteConfig.init resolves; passing the resolved ratio
        // instead would name a _ds_<n> file that isn't published.
        downsampleRatio = if (spec.downsampleTag == "auto") -1.0F else spec.downsampleTag.toInt() / 100F,
        source = spec.source
    )

    /**
     * (Re)builds the TFLite interpreter/delegate for [config] off the main thread.
     * [Controller.configure] rebuilds the interpreter whenever the model file, compute device, or
     * thread count changed, which can take anywhere from tens of milliseconds to a few seconds
     * (GPU delegate compilation especially) - running it inline on the caller's thread is what
     * made the config sheet's Apply button, and the very first navigation into this screen (which
     * triggers this class's lazy construction), appear to hang with no feedback. [isConfiguring]
     * lets the UI show a spinner instead.
     *
     * [isConfiguring] is raised *synchronously*, before the coroutine is launched, and cleared in
     * a `finally`. Raising it inside the coroutine instead left a window where a caller guarding
     * on it (see [updateConfig]) still read `false` and started a second, overlapping
     * `Controller.configure` - two interpreter/GPU-delegate builds then raced on two different
     * `Dispatchers.Default` workers, and whichever lost leaked its delegate.
     *
     * A failure here is reported, not thrown: [Controller.configure] can fail for reasons outside
     * the app's control (a model file removed from under it, a delegate refusing the graph), and
     * an uncaught throw would propagate out of `viewModelScope` and kill the process.
     */
    private fun runConfigure(config: MatteConfig, onComplete: () -> Unit = {}) {
        _uiState.update { it.copy(isConfiguring = true) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.Default) {
                    controller.configure(config)
                }
                onComplete()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        transientMessage = application.getString(
                            R.string.configure_failed,
                            e.message ?: e.javaClass.simpleName
                        )
                    )
                }
            } finally {
                _uiState.update { it.copy(isConfiguring = false) }
            }
        }
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
        val source = savedStateHandle.get<String>(KEY_SOURCE)
            ?.let { runCatching { ModelSource.valueOf(it) }.getOrNull() }
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
            downsampleRatio = savedStateHandle.get<Float>(KEY_DOWNSAMPLE) ?: defaults.downsampleRatio,
            source = source
        )
    }

    private fun persistConfig(config: MatteConfig) {
        // Persist the *requested* ratio, not the resolved one: storing 0.4F instead of the
        // -1.0F "auto" sentinel would restore as a frozen fixed ratio naming a model that was
        // never published. See MatteConfig.requestedDownsampleRatio.
        val downsampleRatio = config.requestedDownsampleRatio

        savedStateHandle[KEY_HEIGHT] = config.height
        savedStateHandle[KEY_WIDTH] = config.width
        savedStateHandle[KEY_DEVICE] = config.runtimeConfig.device.name
        savedStateHandle[KEY_THREADS] = config.runtimeConfig.numThreads
        savedStateHandle[KEY_DTYPE] = config.dtype.name
        savedStateHandle[KEY_VARIANT] = config.variant.name
        savedStateHandle[KEY_DOWNSAMPLE] = downsampleRatio
        savedStateHandle[KEY_SOURCE] = config.source.name
    }

    fun onVideoSelected(uri: Uri) {
        runningJob?.cancel()
        runningJob = null
        controller.loadInputVideo(uri)
        _uiState.update {
            it.copy(
                selectedVideoUri = uri,
                outputMatteVideoUri = null,
                outputFgrVideoUri = null,
                outputCompositeVideoUri = null,
                outputSelection = MatteUiState.OutputKind.MATTE,
                stage = MatteUiState.Stage.IDLE,
                processedFrames = 0,
                totalFrames = 0,
                errorMessage = null,
                elapsedMs = null
            )
        }
    }

    /**
     * Applies [config], first forcing the compute device to CPU if the selection can't actually
     * run (see [coerceUnsupportedDevice]). [onApplied] reports whether that happened so the caller
     * can say so rather than silently handing back a different device than the user picked.
     */
    fun updateConfig(config: MatteConfig, onApplied: (deviceCoercedToCpu: Boolean) -> Unit = {}) {
        if (_uiState.value.isConfiguring) return
        val coerced = coerceUnsupportedDevice(config)
        val effective = coerced ?: config
        runConfigure(effective) {
            persistConfig(effective)
            _uiState.update { it.copy(config = effective) }
            onApplied(coerced != null)
        }
    }

    /**
     * Returns [config] with the device forced to CPU when the pairing cannot run, or `null` when
     * it is already fine.
     *
     * An `original`-source model is the unmodified upstream graph, so it still contains the ops
     * the converter's `model_gpu/` tree rewrites - `GATHER_ND`, `RELU_0_TO_1`,
     * `STABLEHLO_REDUCE_WINDOW`. A delegate is mandatory once attached: rather than running those
     * ops on CPU and the rest on the accelerator, the GPU/NNAPI delegate refuses the whole graph
     * and `Interpreter`'s constructor throws. Offering the pairing and then failing (or silently
     * degrading deep in [dev.hamster.rvm.modelRunner.TFLiteModelRunner]) is worse than refusing it
     * here, where the UI can explain itself.
     *
     * AUTO is deliberately left alone: falling back through NNAPI -> GPU -> CPU is exactly what it
     * is for.
     */
    private fun coerceUnsupportedDevice(config: MatteConfig): MatteConfig? {
        val device = config.runtimeConfig.device
        val unsupported = config.source == ModelSource.ORIGINAL &&
            (device == RuntimeConfig.ComputeDevice.GPU || device == RuntimeConfig.ComputeDevice.NPU)
        if (!unsupported) return null
        return config.copy(
            // requestedDownsampleRatio, not downsampleRatio: init has already resolved the "auto"
            // sentinel, and copying the resolved value back would rebuild the model filename as
            // _ds_040 rather than _ds_auto -- a file the release doesn't publish.
            downsampleRatio = config.requestedDownsampleRatio,
            runtimeConfig = config.runtimeConfig.copy(device = RuntimeConfig.ComputeDevice.CPU)
        )
    }

    fun runMatting() {
        if (_uiState.value.stage == MatteUiState.Stage.RUNNING) return
        if (_uiState.value.isConfiguring) return
        if (_uiState.value.selectedVideoUri == null) return

        _uiState.update {
            it.copy(
                stage = MatteUiState.Stage.RUNNING,
                outputMatteVideoUri = null,
                outputFgrVideoUri = null,
                outputCompositeVideoUri = null,
                outputSelection = MatteUiState.OutputKind.MATTE,
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
                    it.copy(
                        stage = MatteUiState.Stage.DONE,
                        outputMatteVideoUri = outputUri,
                        outputFgrVideoUri = controller.outputFgrVideoUri,
                        outputCompositeVideoUri = controller.outputCompositeVideoUri,
                        elapsedMs = elapsed
                    )
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
                outputMatteVideoUri = null,
                outputFgrVideoUri = null,
                outputCompositeVideoUri = null,
                outputSelection = MatteUiState.OutputKind.MATTE,
                stage = MatteUiState.Stage.IDLE,
                processedFrames = 0,
                totalFrames = 0,
                errorMessage = null,
                elapsedMs = null
            )
        }
    }

    fun selectOutput(kind: MatteUiState.OutputKind) {
        _uiState.update { it.copy(outputSelection = kind) }
    }

    fun saveOutputs() {
        val matte = controller.outputMatteFile ?: return
        if (_uiState.value.isSaving) return
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            try {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                MediaStoreSaver.saveToMovies(application, matte, "RVM_matte_$stamp.mp4")
                controller.outputFgrFile?.let {
                    MediaStoreSaver.saveToMovies(application, it, "RVM_foreground_$stamp.mp4")
                }
                controller.outputCompositeFile?.let {
                    MediaStoreSaver.saveToMovies(application, it, "RVM_composite_$stamp.mp4")
                }
                _uiState.update {
                    it.copy(isSaving = false, transientMessage = application.getString(R.string.saved_to_gallery))
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        transientMessage = application.getString(R.string.save_failed, e.message ?: "")
                    )
                }
            }
        }
    }

    fun consumeTransientMessage() {
        _uiState.update { it.copy(transientMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        controller.close()
    }
}
