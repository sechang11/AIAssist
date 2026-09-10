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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

sealed interface RemixUiState {
    data object Idle : RemixUiState
    data object Loading : RemixUiState

    /** [streaming] is true while later beats are still arriving. */
    data class Ready(val remix: Remix, val streaming: Boolean = false) : RemixUiState
    data class Failed(val message: String) : RemixUiState
}

class RemixViewModel(private val generator: VariantGenerator) : ViewModel() {

    var original: String by mutableStateOf("")
        private set

    var state: RemixUiState by mutableStateOf(RemixUiState.Idle)
        private set

    private var work: Job? = null

    /** Ignores a repeat call for text already loaded, so rotation does not re-bill. */
    fun load(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            state = RemixUiState.Failed("Nothing to work with.")
            return
        }
        if (trimmed == original && state !is RemixUiState.Idle && state !is RemixUiState.Failed) return

        work?.cancel()
        original = trimmed
        state = RemixUiState.Loading

        work = viewModelScope.launch {
            var carried: Remix? = null
            try {
                generator.generate(trimmed).collect { draft ->
                    val validated = draft.validated() ?: return@collect
                    // Beats arrive one at a time. Rebase so a pick the reader
                    // already made survives the next redraw.
                    val next = carried?.rebasedOn(validated) ?: Remix(validated)
                    carried = next
                    state = RemixUiState.Ready(next, streaming = true)
                }
                val finished = carried
                state = if (finished == null) {
                    RemixUiState.Failed("Nothing usable came back.")
                } else {
                    RemixUiState.Ready(finished, streaming = false)
                }
            } catch (e: GenerationException) {
                // Whatever arrived before the failure is still worth showing.
                val partial = carried
                state = if (partial != null) {
                    RemixUiState.Ready(partial, streaming = false)
                } else {
                    RemixUiState.Failed(e.message ?: "Something went wrong.")
                }
            } catch (e: Exception) {
                state = RemixUiState.Failed(e.message ?: "Something went wrong.")
            }
        }
    }

    fun retry() {
        val text = original
        original = ""
        load(text)
    }

    fun reset() {
        work?.cancel()
        original = ""
        state = RemixUiState.Idle
    }

    private fun edit(block: (Remix) -> Remix) {
        val current = state as? RemixUiState.Ready ?: return
        state = current.copy(remix = block(current.remix))
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
