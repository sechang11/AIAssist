package com.aitextassistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aitextassistant.UpdateUiState

/**
 * A strip at the top of the home screen saying a newer build exists.
 *
 * Only drawn when it has something to say. The state that matters is
 * [UpdateUiState.Ready]; the rest exist so that a check the reader asked for
 * visibly finishes one way or the other.
 */
@Composable
fun UpdateBanner(
    state: UpdateUiState,
    installAllowed: Boolean,
    onInstall: () -> Unit,
    onAllowInstalls: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        is UpdateUiState.Quiet -> Unit

        is UpdateUiState.Checking -> Row(
            modifier = Modifier.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
            Text("Looking for a newer build", style = MaterialTheme.typography.bodySmall)
        }

        is UpdateUiState.UpToDate -> Text(
            "Up to date, running ${state.version}.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        is UpdateUiState.Trouble -> Text(
            state.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )

        is UpdateUiState.Downloading -> Row(
            modifier = Modifier.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
            Text(
                "Downloading. Android will ask before it installs.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        is UpdateUiState.Ready -> Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "Build ${state.build.versionName} is ready",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                if (state.build.note.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(state.build.note, style = MaterialTheme.typography.bodyMedium)
                }
                if (state.build.built.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Built ${state.build.built}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(12.dp))
                if (installAllowed) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = onInstall) { Text("Install") }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = onDismiss) { Text("Not now") }
                    }
                } else {
                    // Worth saying before the download rather than after it,
                    // because the installer refuses silently from a dialog the
                    // reader did not ask for.
                    Text(
                        "Android needs permission to let Remix install its own " +
                            "updates. This is a one-off.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = onAllowInstalls) { Text("Allow, then install") }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = onDismiss) { Text("Not now") }
                    }
                }
            }
        }
    }
}
