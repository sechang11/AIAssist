package com.aitextassistant.bubble

import android.content.ClipboardManager
import android.content.Context

/**
 * Where the bubble gets the text to work on.
 *
 * The bubble cannot see the app behind it. Nothing in the public SDK lets one
 * app read another's screen; the only API that does is `AccessibilityService`,
 * and Play restricts that to apps genuinely serving users with disabilities, so
 * shipping it here would get the listing pulled. A second implementation of this
 * interface backed by an accessibility service is roughly a hundred lines and is
 * fine for a build you sideload onto your own phone. See the README.
 */
interface TextSource {
    fun read(): String?
}

/**
 * The route that needs no permission at all: the user copies, then taps the
 * bubble.
 *
 * Since Android 10 the clipboard is readable only by the app that holds input
 * focus, so [BubbleWindow] makes its window focusable before calling this and
 * waits for focus to actually arrive. Reading it from a background service
 * returns null, silently.
 */
class ClipboardTextSource(private val context: Context) : TextSource {

    override fun read(): String? {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0)
            .coerceToText(context)
            .toString()
            .trim()
            .ifEmpty { null }
    }
}
