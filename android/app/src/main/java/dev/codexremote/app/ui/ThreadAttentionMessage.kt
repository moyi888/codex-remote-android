package dev.codexremote.app.ui

import dev.codexremote.app.protocol.Attention
import dev.codexremote.app.service.BrowserAttentionText

object ThreadAttentionMessage {
    fun from(attention: Attention): String? = BrowserAttentionText.fromAttention(attention)
}
