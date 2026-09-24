package com.aitextassistant

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aitextassistant.generate.Settings
import com.aitextassistant.update.Available
import com.aitextassistant.update.Updates
import kotlinx.coroutines.launch

sealed interface UpdateUiState {
    /** Nothing to say. The banner is not drawn at all in this state. */
    data object Quiet : UpdateUiState
    data object Checking : UpdateUiState
    data class Ready(val build: Available) : UpdateUiState
    data object Downloading : UpdateUiState
    data class UpToDate(val version: String) : UpdateUiState
    data class Trouble(val message: String) : UpdateUiState
}

/**
 * The update banner's state.
 *
 * Silent unless there is something to install. A check that fails on launch is
 * not worth a red banner: the phone is often not on the same wifi as the build
 * machine, and that is not an error the reader can do anything about. Failures
 * only become visible once they have asked for a check themselves.
 */
class UpdateViewModel(app: Application) : AndroidViewModel(app) {

    var state: UpdateUiState by mutableStateOf(UpdateUiState.Quiet)
        private set

    private val settings = Settings(app)

    val configuredHost: String get() = settings.updateHost

    /** On launch. Says nothing unless there is a newer build. */
    fun checkQuietly() {
        if (state is UpdateUiState.Downloading || state is UpdateUiState.Ready) return
        viewModelScope.launch {
            when (val outcome = Updates.check(settings.updateHost)) {
                is Updates.Outcome.Newer -> state = UpdateUiState.Ready(outcome.build)
                else -> Unit
            }
        }
    }

    /** From the button, where silence would read as the button being broken. */
    fun checkNow() {
        state = UpdateUiState.Checking
        viewModelScope.launch {
            state = when (val outcome = Updates.check(settings.updateHost)) {
                is Updates.Outcome.Newer -> UpdateUiState.Ready(outcome.build)
                is Updates.Outcome.UpToDate -> UpdateUiState.UpToDate(Updates.runningName)
                is Updates.Outcome.NotConfigured -> UpdateUiState.Trouble(
                    "Set the server address first and this will follow it.",
                )
                is Updates.Outcome.Unreachable -> UpdateUiState.Trouble(
                    "No answer from ${settings.updateHost}. ${outcome.why}",
                )
            }
        }
    }

    fun downloading() {
        state = UpdateUiState.Downloading
    }

    fun failed(message: String) {
        state = UpdateUiState.Trouble(message)
    }

    fun dismiss() {
        state = UpdateUiState.Quiet
    }

    companion object {
        fun factory(app: Application) = viewModelFactory {
            initializer { UpdateViewModel(app) }
        }
    }
}
