package com.aiapp.app

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.aiapp.chat.ChatViewModel

/**
 * Receives text, photos and files that other apps send with their Share button
 * and hands them to Chat: files become attachments, text goes in the message box.
 */
object IncomingShare {
    /** Goes up once per share, so the app shell knows to switch to Chat. */
    var count by mutableIntStateOf(0)
        private set

    /** Returns true if [intent] was a share and has been passed to [chat]. */
    fun handle(intent: Intent?, chat: ChatViewModel): Boolean {
        if (intent == null) return false
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(stream(intent))
            Intent.ACTION_SEND_MULTIPLE -> streams(intent)
            else -> return false
        }
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (uris.isEmpty() && text.isEmpty()) return false
        if (uris.isNotEmpty()) chat.attach(uris)
        if (text.isNotEmpty()) chat.sharedText = text
        count++
        return true
    }

    @Suppress("DEPRECATION")
    private fun stream(intent: Intent): Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri

    @Suppress("DEPRECATION")
    private fun streams(intent: Intent): List<Uri> =
        intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
}
