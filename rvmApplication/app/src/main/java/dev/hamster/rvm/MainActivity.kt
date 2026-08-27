package dev.hamster.rvm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.hamster.rvm.ui.HomeScreen
import dev.hamster.rvm.ui.MatteScreen
import dev.hamster.rvm.ui.MatteViewModel
import dev.hamster.rvm.ui.theme.RvmTheme

private enum class RvmDestination { HOME, MATTE }

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
        enableEdgeToEdge()
        setContent {
            RvmTheme {
                var destination by rememberSaveable { mutableStateOf(RvmDestination.HOME) }
                BackHandler(enabled = destination != RvmDestination.HOME) {
                    destination = RvmDestination.HOME
                }
                when (destination) {
                    RvmDestination.HOME -> HomeScreen(onOpenVideoMatte = { destination = RvmDestination.MATTE })
                    RvmDestination.MATTE -> MatteScreen(viewModel, onNavigateBack = { destination = RvmDestination.HOME })
                }
            }
        }
    }
}
