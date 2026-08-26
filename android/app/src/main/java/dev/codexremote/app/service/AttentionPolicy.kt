package dev.codexremote.app.service

import dev.codexremote.app.protocol.EventEnvelope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

internal data class AttentionNotice(
    val title: String,
    val body: String,
)

internal object AttentionPolicy {
    fun fromEvent(envelope: EventEnvelope<JsonObject>): AttentionNotice? {
        if (envelope.type !in SUPPORTED_EVENT_TYPES) return null
        val attention = when (envelope.type) {
            ATTENTION_REQUIRED_EVENT -> envelope.payload[ATTENTION_FIELD] as? JsonObject ?: envelope.payload
            else -> envelope.payload[ATTENTION_FIELD] as? JsonObject
        } ?: return null
        val category = (attention[CATEGORY_FIELD] as? JsonPrimitive)?.content ?: return null
        if (category !in BROWSER_ATTENTION_CATEGORIES) return null
        val confidence = (attention[CONFIDENCE_FIELD] as? JsonPrimitive)?.doubleOrNull ?: return null
        if (confidence < MINIMUM_CONFIDENCE) return null
        val site = ((attention[SITE_FIELD] as? JsonPrimitive)?.content).orEmpty().safeSite()
        val target = if (site == null) "第三方页面" else site
        return AttentionNotice(
            title = "需要在电脑上完成授权",
            body = "$target 需要登录、授权或验证码。请打开向日葵完成操作，然后回到 App 重试任务。",
        )
    }

    private fun String.safeSite(): String? {
        if (isBlank() || length > MAX_SITE_LENGTH) return null
        val labels = split('.')
        if (labels.any { label ->
                label.isEmpty() ||
                    label.length > MAX_LABEL_LENGTH ||
                    label.first() == '-' ||
                    label.last() == '-' ||
                    label.any { character ->
                        character !in 'a'..'z' &&
                            character !in 'A'..'Z' &&
                            character !in '0'..'9' &&
                            character != '-'
                    }
            }
        ) {
            return null
        }
        return lowercase()
    }

    private const val ATTENTION_REQUIRED_EVENT = "attention.required"
    private const val ATTENTION_FIELD = "attention"
    private const val CATEGORY_FIELD = "category"
    private const val CONFIDENCE_FIELD = "confidence"
    private const val SITE_FIELD = "site"
    private const val MINIMUM_CONFIDENCE = 0.7
    private const val MAX_SITE_LENGTH = 253
    private const val MAX_LABEL_LENGTH = 63
    private val SUPPORTED_EVENT_TYPES = setOf(
        ATTENTION_REQUIRED_EVENT,
        "thread.created",
        "thread.updated",
        "thread.snapshot",
    )
    private val BROWSER_ATTENTION_CATEGORIES = setOf(
        "browser_authorization",
        "oauth",
        "captcha",
        "third_party_login",
    )
}
