package dev.codexremote.app.bridge

import dev.codexremote.app.protocol.CommandEnvelope
import dev.codexremote.app.protocol.CommandResponse
import dev.codexremote.app.protocol.StoredBridgeConnection
import java.io.IOException
import java.util.Collections
import java.util.WeakHashMap

sealed interface CommandOutboxResult {
    val command: QueuedCommand

    data class Sent(
        override val command: QueuedCommand,
        val response: CommandResponse,
    ) : CommandOutboxResult

    data class Queued(
        override val command: QueuedCommand,
        val error: Exception,
    ) : CommandOutboxResult

    data class AuthenticationRequired(
        override val command: QueuedCommand,
        val statusCode: Int,
    ) : CommandOutboxResult

    data class Rejected(
        override val command: QueuedCommand,
        val statusCode: Int,
    ) : CommandOutboxResult
}

data class FlushResult(val outcomes: List<CommandOutboxResult>)

sealed interface SendOrQueueResult {
    val command: QueuedCommand
    val drainOutcomes: List<CommandOutboxResult>

    data class Attempted(
        override val command: QueuedCommand,
        val outcome: CommandOutboxResult,
        override val drainOutcomes: List<CommandOutboxResult>,
    ) : SendOrQueueResult

    data class Blocked(
        override val command: QueuedCommand,
        val blocker: CommandOutboxResult,
        override val drainOutcomes: List<CommandOutboxResult>,
    ) : SendOrQueueResult
}

class CommandOutbox(
    private val queue: PendingCommandQueue,
    private val httpClient: BridgeHttpClient,
    private val connection: StoredBridgeConnection,
    private val clock: () -> String,
) {
    fun sendOrQueue(command: CommandEnvelope): SendOrQueueResult {
        val authenticatedDeviceId = connection.credential.deviceId
        require(command.deviceId == authenticatedDeviceId) {
            "Command device must match the authenticated device"
        }
        return withDeviceLock(authenticatedDeviceId) {
            queue.enqueue(command)
            val outcomes = drain(authenticatedDeviceId)
            val queued = queue.list().firstOrNull { it.matches(command) }
                ?: outcomes.first { it.command.matches(command) }.command
            val target = outcomes.firstOrNull { it.command.matches(command) }
            if (target != null) {
                SendOrQueueResult.Attempted(queued, target, outcomes)
            } else {
                SendOrQueueResult.Blocked(queued, outcomes.last(), outcomes)
            }
        }
    }

    fun flush(): FlushResult = withDeviceLock(connection.credential.deviceId) {
        FlushResult(drain(connection.credential.deviceId))
    }

    private fun drain(deviceId: String): List<CommandOutboxResult> {
        val outcomes = mutableListOf<CommandOutboxResult>()
        while (true) {
            val queued = queue.list().firstOrNull { it.command.deviceId == deviceId }
                ?: break
            queue.markAttempt(
                queued.command.deviceId,
                queued.command.idempotencyKey,
                clock(),
            )
            val attempted = current(queued)
            val outcome = try {
                val response = send(attempted.command)
                queue.removeCompleted(
                    attempted.command.deviceId,
                    attempted.command.idempotencyKey,
                )
                CommandOutboxResult.Sent(attempted, response)
            } catch (error: BridgeApiException) {
                classifyHttpFailure(attempted, error)
            } catch (error: IOException) {
                CommandOutboxResult.Queued(attempted, error)
            }
            outcomes += outcome
            when (outcome) {
                is CommandOutboxResult.Sent -> Unit
                is CommandOutboxResult.Rejected -> {
                    queue.removeCompleted(
                        attempted.command.deviceId,
                        attempted.command.idempotencyKey,
                    )
                }
                is CommandOutboxResult.Queued,
                is CommandOutboxResult.AuthenticationRequired,
                -> break
            }
        }
        return outcomes.toList()
    }

    private fun classifyHttpFailure(
        attempted: QueuedCommand,
        error: BridgeApiException,
    ): CommandOutboxResult = when {
        error.statusCode == 401 || error.statusCode == 403 ->
            CommandOutboxResult.AuthenticationRequired(attempted, error.statusCode)
        error.statusCode in RETRYABLE_STATUS_CODES || error.statusCode in 500..599 ->
            CommandOutboxResult.Queued(attempted, error)
        else -> CommandOutboxResult.Rejected(attempted, error.statusCode)
    }

    private fun send(command: CommandEnvelope): CommandResponse = httpClient.sendCommand(
        connection.baseUrl,
        connection.credential,
        command,
    )

    private fun current(queued: QueuedCommand): QueuedCommand = queue.list().firstOrNull {
        it.command.deviceId == queued.command.deviceId &&
            it.command.idempotencyKey == queued.command.idempotencyKey
    } ?: queued

    private fun QueuedCommand.matches(command: CommandEnvelope): Boolean =
        this.command.deviceId == command.deviceId &&
            this.command.idempotencyKey == command.idempotencyKey

    private inline fun <T> withDeviceLock(deviceId: String, action: () -> T): T {
        val lock = synchronized(DEVICE_LOCKS) {
            DEVICE_LOCKS.getOrPut(deviceId) { Any() }
        }
        return synchronized(lock, action)
    }

    private companion object {
        val RETRYABLE_STATUS_CODES = setOf(408, 409, 425, 429)
        val DEVICE_LOCKS: MutableMap<String, Any> = Collections.synchronizedMap(WeakHashMap())
    }
}
