package com.aitextassistant.bubble

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * What the bubble shows first: the copied text, and the two things it can do
 * with it.
 *
 * The panel used to start rewriting the clipboard the moment it opened. That
 * guessed wrong half the time, because the text you have just copied is as
 * likely to be one you were sent as one you wrote, and it spent a model call
 * per beat on the guess before anyone had asked for anything. One tap is
 * cheaper than a wrong answer over a home wifi link.
 */
@Composable
fun BubbleChooser(
    clipboard: String,
    onReword: (String) -> Unit,
    onReply: (String) -> Unit,
    onCollapse: () -> Unit,
    onStop: () -> Unit,
) {
    var typed by remember(clipboard) { mutableStateOf("") }
    val text = clipboard.ifBlank { typed }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (clipboard.isBlank()) {
            Text(
                "Nothing copied yet",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Copy a message, then tap the bubble again. Or type one here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                label = { Text("A message") },
            )
        } else {
            Text(
                "ON YOUR CLIPBOARD",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                ),
            ) {
                Text(
                    clipboard,
                    modifier = Modifier
                        .heightIn(max = 132.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(14.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }

        Spacer(Modifier.height(2.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = { onReword(text) },
                enabled = text.isNotBlank(),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 54.dp),
            ) { Text("I wrote this") }
            OutlinedButton(
                onClick = { onReply(text) },
                enabled = text.isNotBlank(),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 54.dp),
            ) { Text("They sent this") }
        }
        Text(
            "Reword yours into a few versions, or get replies to theirs.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.weight(1f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onStop) { Text("Hide the bubble") }
            TextButton(onClick = onCollapse) { Text("Close") }
        }
    }
}
