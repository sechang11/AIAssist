package com.aitextassistant.bubble

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aitextassistant.RemixUiState
import com.aitextassistant.RemixViewModel
import com.aitextassistant.ui.RemixScreen
import com.aitextassistant.ui.RemixTheme

/** The resting state: a small circle that sits over whatever app you are in. */
@Composable
fun CollapsedBubble() {
    RemixTheme {
        Surface(
            modifier = Modifier.size(56.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            shadowElevation = 6.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    "R",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

/**
 * The put-it-away target, which appears at the bottom the moment a drag starts
 * and swells when the bubble is close enough to be caught.
 */
@Composable
fun DismissTarget(armed: Boolean) {
    RemixTheme {
        val scale by animateFloatAsState(targetValue = if (armed) 1.22f else 1f, label = "target")
        val background = if (armed) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
        val tint = if (armed) {
            MaterialTheme.colorScheme.onError
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

        Box(modifier = Modifier.size(96.dp), contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier
                    .size(64.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    },
                shape = CircleShape,
                color = background,
                shadowElevation = 8.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(22.dp)) {
                        val edge = size.minDimension
                        val stroke = 2.6.dp.toPx()
                        drawLine(tint, Offset(0f, 0f), Offset(edge, edge), stroke, StrokeCap.Round)
                        drawLine(tint, Offset(edge, 0f), Offset(0f, edge), stroke, StrokeCap.Round)
                    }
                }
            }
        }
    }
}

/**
 * The opened bubble. It reuses the same remix screen as the rest of the app, so
 * the interaction is identical wherever you reach it from. The one difference is
 * the primary action: the bubble cannot type into the app behind it, so the
 * finished message goes to the clipboard for you to paste.
 */
@Composable
fun ExpandedPanel(
    vm: RemixViewModel,
    onCollapse: () -> Unit,
    onCopy: (String) -> Unit,
    onStop: () -> Unit,
) {
    RemixTheme {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 12.dp,
        ) {
            if (vm.state is RemixUiState.Idle) {
                PasteFallback(onRemix = vm::load, onCollapse = onCollapse, onStop = onStop)
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
                    onPrimaryAction = { text -> onCopy(text); onCollapse() },
                    onCancel = onCollapse,
                    onRetry = vm::retry,
                )
            }
        }
    }
}

/**
 * Shown when the clipboard is empty, which is also what you get if a device
 * refuses the focused-window clipboard read. Typing or pasting here always
 * works, so the bubble is never a dead end.
 */
@Composable
private fun PasteFallback(
    onRemix: (String) -> Unit,
    onCollapse: () -> Unit,
    onStop: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "Nothing copied yet",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Copy the message you want to rework, then tap the bubble again. " +
                "Or paste it here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            label = { Text("Your message") },
        )

        Button(
            onClick = { onRemix(draft) },
            enabled = draft.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
        ) { Text("Remix") }

        Spacer(Modifier.height(2.dp))

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
