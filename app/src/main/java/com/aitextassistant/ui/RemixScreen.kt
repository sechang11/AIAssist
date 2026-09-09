package com.aitextassistant.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aitextassistant.RemixUiState
import com.aitextassistant.remix.Remix
import com.aitextassistant.remix.Slot

private const val SLOT_TAG = "slot"

/**
 * The message is the interface. Tone chips at the top swap the whole draft; the
 * highlighted phrases inside the message open one beat's alternatives in the
 * panel below. There is no separate preview to read twice - what you see in the
 * card is exactly what the primary button hands back.
 */
@Composable
fun RemixScreen(
    state: RemixUiState,
    original: String,
    primaryActionLabel: String,
    onChoose: (Slot, Int) -> Unit,
    onToggleOpen: (Slot) -> Unit,
    onDrop: (Slot) -> Unit,
    onRestore: (Slot) -> Unit,
    onApplyTone: (Int) -> Unit,
    onPrimaryAction: (String) -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (String) -> Unit = {},
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        when (state) {
            is RemixUiState.Idle, is RemixUiState.Loading -> Centered {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("Rewriting", style = MaterialTheme.typography.bodyMedium)
            }

            is RemixUiState.Failed -> Centered {
                Text(state.message, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(20.dp))
                Row {
                    OutlinedButton(onClick = onCancel) { Text("Close") }
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = onRetry) { Text("Try again") }
                }
            }

            is RemixUiState.Ready -> Ready(
                remix = state.remix,
                original = original,
                primaryActionLabel = primaryActionLabel,
                secondaryActionLabel = secondaryActionLabel,
                onChoose = onChoose,
                onToggleOpen = onToggleOpen,
                onDrop = onDrop,
                onRestore = onRestore,
                onApplyTone = onApplyTone,
                onPrimaryAction = onPrimaryAction,
                onSecondaryAction = onSecondaryAction,
            )
        }
    }
}

@Composable
private fun Ready(
    remix: Remix,
    original: String,
    primaryActionLabel: String,
    secondaryActionLabel: String?,
    onChoose: (Slot, Int) -> Unit,
    onToggleOpen: (Slot) -> Unit,
    onDrop: (Slot) -> Unit,
    onRestore: (Slot) -> Unit,
    onApplyTone: (Int) -> Unit,
    onPrimaryAction: (String) -> Unit,
    onSecondaryAction: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {

        Text(
            "Remix",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 20.dp, top = 16.dp, end = 20.dp),
        )

        Column(Modifier.padding(start = 20.dp, top = 10.dp, end = 20.dp, bottom = 14.dp)) {
            Text(
                "YOU WROTE",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                original,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        TonePresets(remix, onApplyTone)

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        ) {
            RemixMessage(
                remix = remix,
                onTap = onToggleOpen,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            )
        }

        if (remix.droppedSlots.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 20.dp, top = 12.dp, end = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Left out",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                remix.droppedSlots.forEach { slot ->
                    AssistChip(
                        onClick = { onRestore(slot) },
                        label = { Text(slot.label.ifBlank { "part" }) },
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, top = 14.dp, end = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            val open = remix.openSlot
            if (open == null) {
                Text(
                    "Tap any highlighted part to change it",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 40.dp),
                )
            } else {
                PickerCard(remix, open, onChoose, onDrop)
            }
        }

        Surface(tonalElevation = 3.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 26.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (secondaryActionLabel != null) {
                    OutlinedButton(
                        onClick = { onSecondaryAction(remix.assemble()) },
                        modifier = Modifier.heightIn(min = 52.dp),
                    ) { Text(secondaryActionLabel) }
                }
                Button(
                    onClick = { onPrimaryAction(remix.assemble()) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 52.dp),
                ) { Text(primaryActionLabel) }
            }
        }
    }
}

@Composable
private fun TonePresets(remix: Remix, onApplyTone: (Int) -> Unit) {
    val uniform = remix.uniformTone()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        remix.draft.tones.forEachIndexed { index, tone ->
            FilterChip(
                selected = uniform == index,
                onClick = { onApplyTone(index) },
                label = { Text(tone, maxLines = 1) },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
            )
        }
        // Not a control. It reports that the draft is no longer any single tone.
        if (uniform == null) {
            Text(
                "Mixed",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The draft as flowing prose with each beat highlighted and tappable. Compose has
 * no per-span padding, so the highlight hugs the text and the generous line
 * height does the breathing - which also gives each phrase a tap target taller
 * than the glyphs suggest.
 */
@Composable
private fun RemixMessage(
    remix: Remix,
    onTap: (Slot) -> Unit,
    modifier: Modifier = Modifier,
) {
    val soft = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    val strong = MaterialTheme.colorScheme.primary.copy(alpha = 0.26f)

    val text: AnnotatedString = buildAnnotatedString {
        remix.kept.forEachIndexed { i, slot ->
            if (i > 0) append(" ")
            pushStringAnnotation(SLOT_TAG, slot.id)
            withStyle(SpanStyle(background = if (remix.isOpen(slot)) strong else soft)) {
                append(remix.textOf(slot))
            }
            pop()
        }
    }

    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 34.sp),
        onTextLayout = { layout = it },
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(text) {
                detectTapGestures { position ->
                    val result = layout ?: return@detectTapGestures
                    val offset = result.getOffsetForPosition(position)
                    val id = text.getStringAnnotations(SLOT_TAG, offset, offset)
                        .firstOrNull()?.item
                        ?: return@detectTapGestures
                    remix.kept.firstOrNull { it.id == id }?.let(onTap)
                }
            },
    )
}

@Composable
private fun PickerCard(
    remix: Remix,
    slot: Slot,
    onChoose: (Slot, Int) -> Unit,
    onDrop: (Slot) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 10.dp)) {
            Text(
                slot.label.ifBlank { "part" }.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
            )

            slot.alternatives.forEachIndexed { index, alternative ->
                val selected = remix.indexOf(slot) == index
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .selectable(selected = selected, onClick = { onChoose(slot, index) })
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RadioButton(selected = selected, onClick = null)
                    Column {
                        Text(
                            remix.draft.tones.getOrElse(index) { "Version ${index + 1}" }.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Text(
                            alternative,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }

            if (slot.optional) {
                TextButton(
                    onClick = { onDrop(slot) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Leave this out") }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}
