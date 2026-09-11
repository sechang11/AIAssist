package com.aitextassistant.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aitextassistant.RepliesUiState
import com.aitextassistant.generate.Reply

/**
 * Three ways to answer a message you were sent, with a button for three more.
 *
 * The replies differ in stance rather than tone. Three polite phrasings of the
 * same yes is not a choice, and tone is what the remix screen is for: tapping
 * a reply hands it there.
 */
@Composable
fun RepliesScreen(
    state: RepliesUiState,
    incoming: String,
    onPick: (Reply) -> Unit,
    onRemix: (Reply) -> Unit,
    onMore: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "Replies",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                ),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "THEY SENT",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(incoming, style = MaterialTheme.typography.bodyLarge)
                }
            }

            when (state) {
                is RepliesUiState.Idle -> Unit

                is RepliesUiState.Loading -> Row(
                    modifier = Modifier.padding(vertical = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        if (state.replacing) "Finding three different ones" else "Thinking of replies",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is RepliesUiState.Failed -> Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(state.message, style = MaterialTheme.typography.bodyLarge)
                    Row {
                        OutlinedButton(onClick = onCancel) { Text("Close") }
                        Spacer(Modifier.width(12.dp))
                        Button(onClick = onMore) { Text("Try again") }
                    }
                }

                is RepliesUiState.Ready -> Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    state.replies.forEach { reply ->
                        ReplyCard(reply, onPick = { onPick(reply) }, onRemix = { onRemix(reply) })
                    }
                }
            }

            if (state is RepliesUiState.Ready) {
                OutlinedButton(
                    onClick = onMore,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp),
                ) { Text("Show me three more") }
            }

            TextButton(
                onClick = onCancel,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) { Text("Write my own instead") }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ReplyCard(reply: Reply, onPick: () -> Unit, onRemix: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        ),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 13.dp, end = 10.dp, bottom = 6.dp)) {
            Text(
                reply.intent.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            Text(reply.text, style = MaterialTheme.typography.bodyLarge)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                // The rewriter is the other half of this. Picking a stance and
                // then choosing how it sounds is the whole product.
                TextButton(onClick = onRemix) { Text("Reword") }
                TextButton(onClick = onPick) { Text("Copy") }
            }
        }
    }
}
