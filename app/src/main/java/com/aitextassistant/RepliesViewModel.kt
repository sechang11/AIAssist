package com.aitextassistant

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aitextassistant.generate.GenerationException
import com.aitextassistant.generate.Reply
import com.aitextassistant.generate.ReplySuggester
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

sealed interface RepliesUiState {
    data object Idle : RepliesUiState
    data class Loading(val replacing: Boolean) : RepliesUiState
    data class Ready(val replies: List<Reply>) : RepliesUiState
    data class Failed(val message: String) : RepliesUiState
}

class RepliesViewModel(private val suggester: ReplySuggester) : ViewModel() {

    var incoming: String by mutableStateOf("")
        private set

    var state: RepliesUiState by mutableStateOf(RepliesUiState.Idle)
        private set

    /**
     * Everything offered so far, not just what is on screen. Asking for three
     * more has to mean three it has not already suggested, or the button just
     * reshuffles synonyms.
     */
    private val alreadyOffered = mutableListOf<String>()
    private var work: Job? = null

    fun load(message: String) {
        val trimmed = message.trim()
        if (trimmed.isEmpty()) {
            state = RepliesUiState.Failed("Nothing to reply to.")
            return
        }
        if (trimmed != incoming) alreadyOffered.clear()
        incoming = trimmed
        fetch(replacing = false)
    }

    /** Three more, none of them ones already seen. */
    fun more() {
        if (incoming.isNotBlank()) fetch(replacing = true)
    }

    fun reset() {
        work?.cancel()
        incoming = ""
        alreadyOffered.clear()
        state = RepliesUiState.Idle
    }

    private fun fetch(replacing: Boolean) {
        work?.cancel()
        state = RepliesUiState.Loading(replacing)
        work = viewModelScope.launch {
            try {
                val replies = suggester.suggest(incoming, alreadyOffered.toList())
                state = if (replies.isEmpty()) {
                    // Only an outright failure when there is nothing on screen
                    // already; otherwise keep what the reader is looking at.
                    RepliesUiState.Failed("Nothing usable came back. Try again.")
                } else {
                    alreadyOffered += replies.map { it.text }
                    RepliesUiState.Ready(replies)
                }
            } catch (e: GenerationException) {
                state = RepliesUiState.Failed(e.message ?: "Something went wrong.")
            } catch (e: Exception) {
                state = RepliesUiState.Failed(e.message ?: "Something went wrong.")
            }
        }
    }

    companion object {
        fun factory(suggester: ReplySuggester) = viewModelFactory {
            initializer { RepliesViewModel(suggester) }
        }
    }
}
