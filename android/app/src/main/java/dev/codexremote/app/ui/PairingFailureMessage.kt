package dev.codexremote.app.ui

import dev.codexremote.app.bridge.BridgeApiException
import java.io.IOException

enum class PairingFailureKind { EXPIRED, UNREACHABLE, REVOKED, UNKNOWN }

enum class PairingOperation { PAIR, RESUME }

data class PairingFailureMessage private constructor(
    val kind: PairingFailureKind,
    val text: String,
) {
    companion object {
        fun from(error: Throwable, operation: PairingOperation): PairingFailureMessage {
            val causes = generateSequence(error as Throwable?) { it.cause }.toList()
            val apiFailure = causes.filterIsInstance<BridgeApiException>().firstOrNull()
            val kind = when {
                apiFailure?.statusCode == 401 && operation == PairingOperation.PAIR ->
                    PairingFailureKind.EXPIRED
                apiFailure?.statusCode == 401 && operation == PairingOperation.RESUME ->
                    PairingFailureKind.REVOKED
                causes.any { it is IOException } -> PairingFailureKind.UNREACHABLE
                else -> PairingFailureKind.UNKNOWN
            }
            return forKind(kind)
        }

        fun forKind(kind: PairingFailureKind): PairingFailureMessage = PairingFailureMessage(
            kind = kind,
            text = when (kind) {
                PairingFailureKind.EXPIRED -> "二维码已过期，请在电脑端刷新后重新扫描。"
                PairingFailureKind.UNREACHABLE -> "无法连接家中电脑，请确认两端 Tailscale 已连接。"
                PairingFailureKind.REVOKED -> "此手机的配对授权已撤销，请重新扫描电脑二维码。"
                PairingFailureKind.UNKNOWN -> "配对失败，请重新扫描或检查 Tailscale。"
            },
        )
    }
}
