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
            require(invitation.rawAuthority == "pair") { "invalid pairing invitation authority" }
            require(invitation.host == "pair") { "invalid pairing invitation host" }
            require(invitation.userInfo == null) { "pairing invitation must not contain user info" }
            require(invitation.port == -1) { "pairing invitation must not contain a port" }
            require(invitation.path.isEmpty()) { "pairing invitation must not contain a path" }
            require(invitation.fragment == null) { "pairing invitation must not contain a fragment" }

            val parameters = parseQuery(invitation.rawQuery)
            val baseUrl = parameters.getValue("baseUrl")
            val token = parameters.getValue("token")
            require(token.isNotBlank()) { "pairing invitation token is required" }

            val bridge = parseUri(baseUrl, "invalid pairing invitation baseUrl")
            require(bridge.scheme == "http" || bridge.scheme == "https") {
                "pairing invitation baseUrl must use http or https"
            }
            require(!bridge.host.isNullOrEmpty()) { "pairing invitation baseUrl host is required" }
            require(bridge.userInfo == null) { "pairing invitation baseUrl must not contain user info" }
            require(bridge.port == -1 || bridge.port in 1..65535) {
                "pairing invitation baseUrl port is invalid"
            }
            require(!bridge.rawAuthority.orEmpty().endsWith(':')) {
                "pairing invitation baseUrl port is invalid"
            }
            require(bridge.rawPath.isEmpty() || bridge.rawPath == "/") {
                "pairing invitation baseUrl must be an origin"
            }
            require(bridge.query == null) { "pairing invitation baseUrl must not contain a query" }
            require(bridge.fragment == null) { "pairing invitation baseUrl must not contain a fragment" }

            return PairingInvitation(baseUrl.removeSuffix("/"), token)
        }

        private fun parseUri(raw: String, message: String): URI =
            try {
                URI(raw)
            } catch (_: Exception) {
                throw IllegalArgumentException(message)
            }

        private fun parseQuery(rawQuery: String?): Map<String, String> {
            val query = requireNotNull(rawQuery) { "pairing invitation query is required" }
            require(query.isNotEmpty()) { "pairing invitation query is required" }
            require(!query.startsWith('&') && !query.endsWith('&') && !query.contains("&&")) {
                "pairing invitation query contains an empty segment"
            }
            val parameters = mutableMapOf<String, String>()
            query.split('&').forEach { parameter ->
                val separator = parameter.indexOf('=')
                val rawName = if (separator >= 0) parameter.substring(0, separator) else parameter
                val rawValue = if (separator >= 0) parameter.substring(separator + 1) else ""
                val name = decode(rawName)
                require(name == "baseUrl" || name == "token") {
                    "pairing invitation query contains an unknown parameter"
                }
                require(parameters.put(name, decode(rawValue)) == null) {
                    "pairing invitation query contains a duplicate parameter"
                }
            }
            require(parameters.keys == setOf("baseUrl", "token")) {
                "pairing invitation query must contain baseUrl and token"
            }
            return parameters
        }

        private fun decode(value: String): String =
            try {
                URLDecoder.decode(value, StandardCharsets.UTF_8.name())
            } catch (_: Exception) {
                throw IllegalArgumentException("invalid pairing invitation query")
            }
    }
}
