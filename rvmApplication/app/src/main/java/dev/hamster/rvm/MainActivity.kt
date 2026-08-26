package dev.hamster.rvm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.hamster.rvm.ui.MatteScreen
import dev.hamster.rvm.ui.MatteViewModel
import dev.hamster.rvm.ui.theme.RvmTheme

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
                MatteScreen(viewModel)
            }
        }
    }
}
