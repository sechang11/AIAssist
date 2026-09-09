package com.aitextassistant

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aitextassistant.generate.GenerationException
import com.aitextassistant.generate.VariantGenerator
import com.aitextassistant.remix.Remix
import com.aitextassistant.remix.Slot
import kotlinx.coroutines.launch

sealed interface RemixUiState {
    data object Idle : RemixUiState
    data object Loading : RemixUiState
    data class Ready(val remix: Remix) : RemixUiState
    data class Failed(val message: String) : RemixUiState
}

class RemixViewModel(private val generator: VariantGenerator) : ViewModel() {

    var original: String by mutableStateOf("")
        private set

    var state: RemixUiState by mutableStateOf(RemixUiState.Idle)
        private set

    /** Ignores a repeat call for text already loaded, so rotation does not re-bill. */
    fun load(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            state = RemixUiState.Failed("Nothing to work with.")
            return
        }
        if (trimmed == original && state !is RemixUiState.Idle && state !is RemixUiState.Failed) return

        original = trimmed
        state = RemixUiState.Loading
        viewModelScope.launch {
            state = try {
                RemixUiState.Ready(Remix(generator.generate(trimmed)))
            } catch (e: GenerationException) {
                RemixUiState.Failed(e.message ?: "Something went wrong.")
            } catch (e: Exception) {
                RemixUiState.Failed(e.message ?: "Something went wrong.")
            }
        }
    }

    fun retry() {
        val text = original
        original = ""
        load(text)
    }

    fun reset() {
        original = ""
        state = RemixUiState.Idle
    }

    private fun edit(block: (Remix) -> Remix) {
        val current = state as? RemixUiState.Ready ?: return
        state = RemixUiState.Ready(block(current.remix))
    }

    fun choose(slot: Slot, index: Int) = edit { it.choose(slot, index) }
    fun toggleOpen(slot: Slot) = edit { it.toggleOpen(slot) }
    fun closePicker() = edit { it.close() }
    fun drop(slot: Slot) = edit { it.drop(slot) }
    fun restore(slot: Slot) = edit { it.restore(slot) }
    fun applyTone(index: Int) = edit { it.applyTone(index) }

    companion object {
        fun factory(generator: VariantGenerator) = viewModelFactory {
            initializer { RemixViewModel(generator) }
        }
    }
}
