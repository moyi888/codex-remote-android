package dev.codexremote.app.protocol

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class PairingInvitation private constructor(
    val baseUrl: String,
    val token: String,
) {
    override fun toString(): String = "PairingInvitation(baseUrl=$baseUrl, token=<redacted>)"

    companion object {
        fun parse(raw: String): PairingInvitation {
            val invitation = parseUri(raw, "invalid pairing invitation")
            require(invitation.scheme == "codex-remote") { "invalid pairing invitation scheme" }
            require(invitation.host == "pair") { "invalid pairing invitation host" }
            require(invitation.fragment == null) { "pairing invitation must not contain a fragment" }

            val parameters = parseQuery(invitation.rawQuery)
            val baseUrl = parameters["baseUrl"]
            val token = parameters["token"]
            require(!baseUrl.isNullOrEmpty()) { "pairing invitation baseUrl is required" }
            require(!token.isNullOrEmpty()) { "pairing invitation token is required" }

            val bridge = parseUri(baseUrl, "invalid pairing invitation baseUrl")
            require(bridge.scheme == "http" || bridge.scheme == "https") {
                "pairing invitation baseUrl must use http or https"
            }
            require(!bridge.host.isNullOrEmpty()) { "pairing invitation baseUrl host is required" }

            return PairingInvitation(baseUrl, token)
        }

        private fun parseUri(raw: String, message: String): URI =
            try {
                URI(raw)
            } catch (error: Exception) {
                throw IllegalArgumentException(message, error)
            }

        private fun parseQuery(rawQuery: String?): Map<String, String> {
            if (rawQuery.isNullOrEmpty()) return emptyMap()
            return rawQuery.split('&').associate { parameter ->
                val separator = parameter.indexOf('=')
                val rawName = if (separator >= 0) parameter.substring(0, separator) else parameter
                val rawValue = if (separator >= 0) parameter.substring(separator + 1) else ""
                decode(rawName) to decode(rawValue)
            }
        }

        private fun decode(value: String): String =
            try {
                URLDecoder.decode(value, StandardCharsets.UTF_8.name())
            } catch (error: Exception) {
                throw IllegalArgumentException("invalid pairing invitation query", error)
            }
    }
}
