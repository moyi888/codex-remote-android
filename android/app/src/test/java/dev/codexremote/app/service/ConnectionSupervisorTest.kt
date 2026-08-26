package dev.codexremote.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionSupervisorTest {
    @Test
    fun startWithNetworkConnectsImmediately() {
        val harness = Harness()

        harness.supervisor.start(networkAvailable = true)

        assertEquals(1, harness.sessions.size)
        assertEquals(1, harness.sessions.single().connectCalls)
        assertEquals(ConnectionStatus.CONNECTING, harness.statuses.last())
    }

    @Test
    fun failuresUseBoundedBackoffAndSuccessResetsIt() {
        val harness = Harness()
        harness.supervisor.start(networkAvailable = true)

        harness.sessions.last().callbacks.onFailure()
        assertEquals(1_000L, harness.scheduler.pendingDelay())
        harness.scheduler.runPending()
        harness.sessions.last().callbacks.onFailure()
        assertEquals(2_000L, harness.scheduler.pendingDelay())
        harness.scheduler.runPending()
        harness.sessions.last().callbacks.onFailure()
        assertEquals(5_000L, harness.scheduler.pendingDelay())
        harness.scheduler.runPending()
        harness.sessions.last().callbacks.onFailure()
        assertEquals(10_000L, harness.scheduler.pendingDelay())
        harness.scheduler.runPending()
        harness.sessions.last().callbacks.onFailure()
        assertEquals(30_000L, harness.scheduler.pendingDelay())
        harness.scheduler.runPending()
        harness.sessions.last().callbacks.onFailure()
        assertEquals(30_000L, harness.scheduler.pendingDelay())

        harness.scheduler.runPending()
        harness.sessions.last().callbacks.onConnected()
        harness.sessions.last().callbacks.onFailure()

        assertEquals(1_000L, harness.scheduler.pendingDelay())
    }

    @Test
    fun networkLossCancelsRetryAndRecoveryConnectsImmediately() {
        val harness = Harness()
        harness.supervisor.start(networkAvailable = true)
        val first = harness.sessions.single()
        first.callbacks.onFailure()
        val retry = harness.scheduler.pending.single()

        harness.supervisor.onNetworkUnavailable()

        assertTrue(retry.cancelled)
        assertEquals(ConnectionStatus.WAITING_FOR_NETWORK, harness.statuses.last())
        harness.supervisor.onNetworkAvailable()
        assertEquals(2, harness.sessions.size)
        assertEquals(1, harness.sessions.last().connectCalls)
    }

    @Test
    fun networkLossClosesActiveSession() {
        val harness = Harness()
        harness.supervisor.start(networkAvailable = true)
        val session = harness.sessions.single()
        session.callbacks.onConnected()

        harness.supervisor.onNetworkUnavailable()

        assertEquals(1, session.closeCalls)
        assertEquals(1, session.disposeCalls)
        assertEquals(ConnectionStatus.WAITING_FOR_NETWORK, harness.statuses.last())
    }

    @Test
    fun stopCancelsWorkAndIgnoresLateCallbacks() {
        val harness = Harness()
        harness.supervisor.start(networkAvailable = true)
        val session = harness.sessions.single()

        harness.supervisor.stop()
        session.callbacks.onFailure()
        session.callbacks.onConnected()

        assertEquals(1, session.closeCalls)
        assertEquals(1, session.disposeCalls)
        assertTrue(harness.scheduler.pending.all { it.cancelled })
        assertEquals(ConnectionStatus.STOPPED, harness.statuses.last())
        assertFalse(harness.supervisor.isRunning())
    }

    private class Harness {
        val sessions = mutableListOf<FakeSession>()
        val scheduler = FakeScheduler()
        val statuses = mutableListOf<ConnectionStatus>()
        val supervisor = ConnectionSupervisor(
            sessionFactory = ConnectionSessionFactory { callbacks ->
                FakeSession(callbacks).also(sessions::add)
            },
            scheduler = scheduler,
            statusListener = ConnectionStatusListener { statuses += it },
        )
    }

    private class FakeSession(
        val callbacks: ConnectionSessionCallbacks,
    ) : ConnectionSession {
        var connectCalls = 0
        var closeCalls = 0
        var disposeCalls = 0

        override fun connect() {
            connectCalls += 1
        }

        override fun close() {
            closeCalls += 1
        }

        override fun dispose() {
            disposeCalls += 1
        }
    }

    private class FakeScheduler : RetryScheduler {
        val pending = mutableListOf<FakeScheduledRetry>()

        override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledRetry =
            FakeScheduledRetry(delayMillis, task).also(pending::add)

        fun pendingDelay(): Long = pending.single { !it.cancelled }.delayMillis

        fun runPending() {
            val retry = pending.single { !it.cancelled }
            pending.remove(retry)
            retry.task()
        }
    }

    private class FakeScheduledRetry(
        val delayMillis: Long,
        val task: () -> Unit,
    ) : ScheduledRetry {
        var cancelled = false

        override fun cancel() {
            cancelled = true
        }
    }
}
