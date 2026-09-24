package com.aitextassistant.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aitextassistant.generate.Generators

/**
 * Says, on the screen showing the output, that there is no model behind it.
 *
 * With nothing configured the app falls back to a stand-in that strips filler
 * and expands contractions, so a sentence that was already clean comes back
 * three times unchanged. That looks exactly like a broken app, and the only
 * place it said otherwise was a line of small print on a different screen. It
 * is worth interrupting for: every result under it is a placeholder.
 */
@Composable
fun StubWarning(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(
                "No model connected",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "These are placeholders, not rewrites. Put your server address " +
                    "on the home screen and they become real.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** True when nothing is configured and the stand-in is what will answer. */
@Composable
fun usingStub(): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    return androidx.compose.runtime.remember(context) { Generators.usingStub(context) }
}
