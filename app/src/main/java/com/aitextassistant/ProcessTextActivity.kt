package com.aitextassistant

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aitextassistant.generate.Generators
import com.aitextassistant.ui.RemixScreen
import com.aitextassistant.ui.RemixTheme

/**
 * Entry point from the floating text-selection toolbar in any app.
 *
 * Android hands over the selected text in EXTRA_PROCESS_TEXT. If the host field
 * is editable it also sets EXTRA_PROCESS_TEXT_READONLY to false, which means we
 * may return a replacement in the result intent and the host swaps it in for us.
 * That is the entire trick: no accessibility service, no overlay permission, no
 * clipboard dance, and nothing for Play review to object to.
 */
class ProcessTextActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val selected = intent
            .getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            ?.toString()
            .orEmpty()
        val readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)

        setContent {
            RemixTheme {
                val vm: RemixViewModel = viewModel(
                    factory = RemixViewModel.factory(Generators.default()),
                )
                LaunchedEffect(selected) { vm.load(selected) }

                RemixScreen(
                    state = vm.state,
                    original = vm.original,
                    primaryActionLabel = if (readOnly) "Copy" else "Replace",
                    secondaryActionLabel = if (readOnly) null else "Copy",
                    onChoose = vm::choose,
                    onToggleOpen = vm::toggleOpen,
                    onDrop = vm::drop,
                    onRestore = vm::restore,
                    onApplyTone = vm::applyTone,
                    onPrimaryAction = { text -> if (readOnly) copyAndFinish(text) else replaceAndFinish(text) },
                    onSecondaryAction = ::copyToClipboard,
                    onCancel = { setResult(RESULT_CANCELED); finish() },
                    onRetry = vm::retry,
                )
            }
        }
    }

    private fun replaceAndFinish(text: String) {
        setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, text))
        finish()
    }

    private fun copyToClipboard(text: String) {
        getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    /** Selection came from a field we cannot write to, so the clipboard is the only route back. */
    private fun copyAndFinish(text: String) {
        copyToClipboard(text)
        setResult(RESULT_CANCELED)
        finish()
    }
}
