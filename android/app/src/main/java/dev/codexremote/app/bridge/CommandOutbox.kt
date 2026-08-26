package dev.codexremote.app.bridge

import dev.codexremote.app.protocol.CommandEnvelope
import dev.codexremote.app.protocol.CommandResponse
import dev.codexremote.app.protocol.StoredBridgeConnection
import java.io.IOException

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

    private fun <T> withDeviceLock(deviceId: String, action: () -> T): T =
        DEVICE_LOCKS.withLock(deviceId, action)

    private companion object {
        val RETRYABLE_STATUS_CODES = setOf(408, 409, 425, 429)
        val DEVICE_LOCKS = DeviceLockRegistry()
    }
}

internal class DeviceLockRegistry {
    private data class Entry(
        val lock: Any = Any(),
        var references: Int = 0,
    )

    private val entries = mutableMapOf<String, Entry>()

    fun <T> withLock(deviceId: String, action: () -> T): T {
        val entry = acquire(deviceId)
        return try {
            synchronized(entry.lock, action)
        } finally {
            release(deviceId, entry)
        }
    }

    internal fun referenceCount(deviceId: String): Int = synchronized(entries) {
        entries[deviceId]?.references ?: 0
    }

    private fun acquire(deviceId: String): Entry = synchronized(entries) {
        entries.getOrPut(deviceId) { Entry() }.also { it.references += 1 }
    }

    private fun release(deviceId: String, entry: Entry) = synchronized(entries) {
        check(entry.references > 0) { "Device lock reference count underflow" }
        entry.references -= 1
        if (entry.references == 0) {
            entries.remove(deviceId, entry)
        }
    }
}
