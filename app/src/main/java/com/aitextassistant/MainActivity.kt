package com.aitextassistant

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aitextassistant.bubble.BubbleService
import com.aitextassistant.generate.Generators
import com.aitextassistant.ui.RemixScreen
import com.aitextassistant.ui.RemixTheme

/**
 * Setup plus a playground, so the remix screen can be exercised without bouncing
 * out to another app. The real entry points are [ProcessTextActivity] and the
 * bubble.
 */
class MainActivity : ComponentActivity() {

    /** Refreshed on resume, because the user grants this in a settings screen we cannot observe. */
    private var canDrawOverlays by mutableStateOf(false)

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { startBubble() }

    override fun onResume() {
        super.onResume()
        canDrawOverlays = BubbleService.canDrawOverlays(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RemixTheme {
                Surface(Modifier.fillMaxSize()) {
                    val vm: RemixViewModel = viewModel(
                        factory = RemixViewModel.factory(Generators.default(this)),
                    )
                    if (vm.state is RemixUiState.Idle) {
                        Home(
                            canDrawOverlays = canDrawOverlays,
                            bubbleRunning = BubbleService.isRunning,
                            onGrantOverlay = ::openOverlaySettings,
                            onStartBubble = ::askThenStartBubble,
                            onStopBubble = { BubbleService.stop(this) },
                            onRemix = vm::load,
                        )
                    } else {
                        RemixScreen(
                            state = vm.state,
                            original = vm.original,
                            primaryActionLabel = "Copy",
                            onChoose = vm::choose,
                            onToggleOpen = vm::toggleOpen,
                            onDrop = vm::drop,
                            onRestore = vm::restore,
                            onApplyTone = vm::applyTone,
                            onPrimaryAction = ::copyToClipboard,
                            onCancel = vm::reset,
                            onRetry = vm::retry,
                        )
                    }
                }
            }
        }
    }

    private fun openOverlaySettings() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    private fun askThenStartBubble() {
        val needsNotificationConsent = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsNotificationConsent) {
            // The bubble runs either way; without this the required notification
            // is simply invisible, which is worse for the user, not better.
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startBubble()
        }
    }

    private fun startBubble() {
        BubbleService.start(this)
    }

    private fun copyToClipboard(text: String) {
        getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun Home(
    canDrawOverlays: Boolean,
    bubbleRunning: Boolean,
    onGrantOverlay: () -> Unit,
    onStartBubble: () -> Unit,
    onStopBubble: () -> Unit,
    onRemix: (String) -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    val context = LocalContext.current
    val settings = remember { com.aitextassistant.generate.Settings(context) }
    var host by remember { mutableStateOf(settings.ollamaHost) }
    var model by remember { mutableStateOf(settings.ollamaModel) }
    var saved by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Remix", style = MaterialTheme.typography.headlineMedium)

        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Where the rewrites come from", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Now using " + Generators.describe(context) + ".",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Point this at a machine on your wifi running ollama serve and you " +
                        "get real rewrites with no key and no cost. Leave it blank and " +
                        "the app falls back to a stand-in that barely changes anything.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it; saved = false },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Server") },
                    placeholder = { Text("http://192.168.0.45:11434") },
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it; saved = false },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Model") },
                )
                Button(
                    onClick = {
                        settings.ollamaHost = host
                        settings.ollamaModel = model
                        saved = true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp),
                ) { Text(if (saved) "Saved. Reopen the app to apply." else "Save") }
            }
        }

        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("The bubble", style = MaterialTheme.typography.titleSmall)
                Text(
                    "A circle that floats over every app. Copy the message you want " +
                        "to rework, tap the bubble, pick your wording, and it goes " +
                        "back on the clipboard to paste.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "It cannot read the app behind it. Nothing in Android lets one " +
                        "app read another's screen without the accessibility service, " +
                        "which is meant for assistive tools. So the clipboard is the " +
                        "handoff.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(2.dp))

                when {
                    !canDrawOverlays -> Button(
                        onClick = onGrantOverlay,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp),
                    ) { Text("Allow drawing over other apps") }

                    bubbleRunning -> OutlinedButton(
                        onClick = onStopBubble,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp),
                    ) { Text("Turn the bubble off") }

                    else -> Button(
                        onClick = onStartBubble,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp),
                    ) { Text("Turn the bubble on") }
                }
            }
        }

        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("From the selection toolbar", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Write your message anywhere, select it, then tap Remix in the " +
                        "selection toolbar. You may have to tap the overflow arrow to " +
                        "find it. This one replaces the text in place, no pasting.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Text("Try it here", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
            label = { Text("Something you already wrote") },
        )

        Button(
            onClick = { onRemix(draft) },
            enabled = draft.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
        ) { Text("Remix") }

        Spacer(Modifier.height(24.dp))
    }
}
