package dev.codexremote.app.ui

import dev.codexremote.app.protocol.Capabilities
import dev.codexremote.app.protocol.CommandEnvelope
import dev.codexremote.app.protocol.DeviceCredential
import dev.codexremote.app.protocol.PairingInvitation
import dev.codexremote.app.protocol.Snapshot
import dev.codexremote.app.protocol.StoredBridgeConnection
import dev.codexremote.app.protocol.ThreadHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteAppControllerTest {
    @Test
    fun resumesSavedConnectionLoadsSnapshotAndStartsService() {
        val gateway = FakeGateway(savedConnection = connection())
        val controller = controller(gateway)

        val result = controller.resume()

        assertTrue(result is LoadResult.Ready)
        assertEquals(42L, (result as LoadResult.Ready).state.snapshot?.eventCursor)
        assertEquals(1, gateway.snapshotLoads)
        assertEquals(1, gateway.serviceStarts)
    }

    @Test
    fun resumeWithoutSavedConnectionIsUnpaired() {
        val gateway = FakeGateway()

        val result = controller(gateway).resume()

        assertEquals(LoadResult.Unpaired, result)
        assertEquals(0, gateway.serviceStarts)
    }

    @Test
    fun pairingExchangesSavesLoadsSnapshotAndStartsService() {
        val gateway = FakeGateway(exchangeResult = connection())

        val result = controller(gateway).pair(
            "codex-remote://pair?baseUrl=https%3A%2F%2Fbridge.example&token=secret",
        )

        assertTrue(result is LoadResult.Ready)
        assertEquals("https://bridge.example", gateway.exchangedInvitation?.baseUrl)
        assertEquals("phone-1", gateway.exchangeDevice?.id)
        assertEquals("我的手机", gateway.exchangeDevice?.name)
        assertEquals(connection(), gateway.savedConnection)
        assertEquals(1, gateway.snapshotLoads)
        assertEquals(1, gateway.serviceStarts)
    }

    @Test
    fun pairingFailureReturnsSafeMessageWithoutInvitationOrSecret() {
        val secret = "do-not-leak"
        val gateway = FakeGateway(pairingFailure = IllegalStateException("raw $secret"))

        val result = controller(gateway).pair(
            "codex-remote://pair?baseUrl=https%3A%2F%2Fbridge.example&token=$secret",
        )

        assertTrue(result is LoadResult.Failed)
        val message = (result as LoadResult.Failed).message
        assertFalse(message.contains(secret))
        assertFalse(message.contains("codex-remote://"))
        assertEquals(0, gateway.serviceStarts)
    }

    @Test
    fun commandsUseAuthenticatedConnectionAndOutboxGateway() {
        val gateway = FakeGateway(savedConnection = connection())
        val controller = controller(gateway)
        controller.resume()

        val start = controller.startTask("project-1", "执行任务", "model-a", "high")
        val send = controller.sendTurn("thread-1", "继续执行")

        assertEquals(CommandDelivery.Sent, start)
        assertEquals(CommandDelivery.Sent, send)
        assertEquals(listOf("task.start", "thread.send"), gateway.commands.map { it.type })
        assertTrue(gateway.commandConnections.all { it == connection() })
    }

    @Test
    fun activeTurnCommandsUseAuthenticatedConnection() {
        val gateway = FakeGateway(savedConnection = connection())
        val controller = controller(gateway)
        controller.resume()

        assertEquals(CommandDelivery.Sent, controller.steerTurn("thread-1", "turn-7", "补充说明"))
        assertEquals(CommandDelivery.Sent, controller.interruptTurn("thread-1", "turn-7"))
        assertEquals(listOf("turn.steer", "turn.interrupt"), gateway.commands.map { it.type })
    }

    @Test
    fun sendingBeforePairingRequiresAuthentication() {
        val controller = controller(FakeGateway())

        assertEquals(
            CommandDelivery.AuthenticationRequired,
            controller.sendTurn("thread-1", "继续"),
        )
    }

    private fun controller(gateway: FakeGateway) = RemoteAppController(
        gateway = gateway,
        device = DeviceIdentity("phone-1", "我的手机"),
        newIdentifier = sequence("request-1", "idempotency-1", "request-2", "idempotency-2"),
        now = { "2026-08-26T10:00:00Z" },
    )

    private fun sequence(vararg values: String): () -> String {
        val remaining = ArrayDeque(values.toList())
        return { remaining.removeFirst() }
    }

    private fun connection() = StoredBridgeConnection(
        baseUrl = "https://bridge.example",
        credential = DeviceCredential(1, "phone-1", "credential"),
    )

    private fun snapshot() = Snapshot(
        protocolVersion = 1,
        eventCursor = 42,
        capabilities = Capabilities(true, true, true, false, false),
        projects = emptyList(),
        models = emptyList(),
        threads = emptyList(),
    )

    private inner class FakeGateway(
        var savedConnection: StoredBridgeConnection? = null,
        private val exchangeResult: StoredBridgeConnection = connection(),
        private val pairingFailure: Exception? = null,
    ) : RemoteAppGateway {
        var exchangedInvitation: PairingInvitation? = null
        var exchangeDevice: DeviceIdentity? = null
        var snapshotLoads = 0
        var serviceStarts = 0
        val commands = mutableListOf<CommandEnvelope>()
        val commandConnections = mutableListOf<StoredBridgeConnection>()

        override fun loadConnection(): StoredBridgeConnection? = savedConnection

        override fun exchange(
            invitation: PairingInvitation,
            device: DeviceIdentity,
        ): StoredBridgeConnection {
            pairingFailure?.let { throw it }
            exchangedInvitation = invitation
            exchangeDevice = device
            return exchangeResult
        }

        override fun saveConnection(connection: StoredBridgeConnection) {
            savedConnection = connection
        }

        override fun loadSnapshot(connection: StoredBridgeConnection): Snapshot {
            snapshotLoads += 1
            return snapshot()
        }

        override fun loadThreadHistory(
            connection: StoredBridgeConnection,
            threadId: String,
            cursor: String?,
        ): ThreadHistory = ThreadHistory()

        override fun send(
            connection: StoredBridgeConnection,
            command: CommandEnvelope,
        ): CommandDelivery {
            commandConnections += connection
            commands += command
            return CommandDelivery.Sent
        }

        override fun startService() {
            serviceStarts += 1
        }
    }
}
