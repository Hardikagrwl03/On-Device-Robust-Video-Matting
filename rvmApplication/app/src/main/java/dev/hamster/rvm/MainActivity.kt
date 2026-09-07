package dev.hamster.rvm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.hamster.rvm.models.ModelRepository
import dev.hamster.rvm.ui.HomeScreen
import dev.hamster.rvm.ui.MatteScreen
import dev.hamster.rvm.ui.MatteViewModel
import dev.hamster.rvm.ui.ModelsScreen
import dev.hamster.rvm.ui.theme.RvmTheme

private enum class RvmDestination { HOME, MATTE, MODELS }

class MainActivity : ComponentActivity() {
    private val viewModel: MatteViewModel by viewModels {
        viewModelFactory {
            initializer {
                MatteViewModel(application, createSavedStateHandle())
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Idempotent and non-blocking: enqueues only the bootstrap models not already on disk,
        // onto the repository's own scope. Safe to call on every launch.
        ModelRepository.get(this).ensureBootstrapModels()
        enableEdgeToEdge()
        setContent {
            RvmTheme {
                var destination by rememberSaveable { mutableStateOf(RvmDestination.HOME) }
                // Collecting uiState here (rather than only inside MatteScreen) forces the lazy
                // `by viewModels` delegate to construct MatteViewModel immediately at launch, so
                // its initial model load starts in the background while the home screen is shown
                // instead of only starting once the user taps into the matting screen.
                val uiState by viewModel.uiState.collectAsState()
                BackHandler(enabled = destination != RvmDestination.HOME) {
                    destination = RvmDestination.HOME
                }
                when (destination) {
                    RvmDestination.HOME -> HomeScreen(
                        isModelReady = !uiState.isConfiguring && !uiState.modelMissing,
                        modelMissing = uiState.modelMissing,
                        onOpenVideoMatte = { destination = RvmDestination.MATTE },
                        onOpenModels = { destination = RvmDestination.MODELS }
                    )
                    RvmDestination.MATTE -> MatteScreen(viewModel, onNavigateBack = { destination = RvmDestination.HOME })
                    RvmDestination.MODELS -> ModelsScreen(onNavigateBack = { destination = RvmDestination.HOME })
                }
            }
        }
    }
}
