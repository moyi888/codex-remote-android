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
    fun networkLossDoesNotHoldSupervisorLockWhileSessionCloses() {
        val callbackFinished = java.util.concurrent.CountDownLatch(1)
        lateinit var callbacks: ConnectionSessionCallbacks
        val session = object : ConnectionSession {
            override fun connect() = Unit

            override fun close() {
                Thread {
                    callbacks.onDisconnected()
                    callbackFinished.countDown()
                }.start()
                assertTrue(callbackFinished.await(1, java.util.concurrent.TimeUnit.SECONDS))
            }

            override fun dispose() = Unit
        }
        val supervisor = ConnectionSupervisor(
            sessionFactory = ConnectionSessionFactory { createdCallbacks ->
                callbacks = createdCallbacks
                session
            },
            scheduler = FakeScheduler(),
            statusListener = ConnectionStatusListener { },
        )
        supervisor.start(networkAvailable = true)

        supervisor.onNetworkUnavailable()

        assertTrue(callbackFinished.await(1, java.util.concurrent.TimeUnit.SECONDS))
        supervisor.onNetworkAvailable()
        assertTrue(supervisor.isRunning())
    }

    @Test
    fun networkRecoveryWaitsUntilOldSessionCleanupCompletes() {
        val closeEntered = java.util.concurrent.CountDownLatch(1)
        val releaseClose = java.util.concurrent.CountDownLatch(1)
        val sessions = mutableListOf<ConnectionSession>()
        val supervisor = ConnectionSupervisor(
            sessionFactory = ConnectionSessionFactory {
                object : ConnectionSession {
                    override fun connect() = Unit

                    override fun close() {
                        closeEntered.countDown()
                        releaseClose.await(2, java.util.concurrent.TimeUnit.SECONDS)
                    }

                    override fun dispose() = Unit
                }.also(sessions::add)
            },
            scheduler = FakeScheduler(),
            statusListener = ConnectionStatusListener { },
        )
        supervisor.start(networkAvailable = true)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val unavailable = executor.submit { supervisor.onNetworkUnavailable() }
            assertTrue(closeEntered.await(1, java.util.concurrent.TimeUnit.SECONDS))

            supervisor.onNetworkAvailable()

            assertEquals(1, sessions.size)
            releaseClose.countDown()
            unavailable.get(2, java.util.concurrent.TimeUnit.SECONDS)
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(1)
            while (sessions.size != 2 && System.nanoTime() < deadline) Thread.yield()
            assertEquals(2, sessions.size)
        } finally {
            releaseClose.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun staleStatusCannotOverwriteNewerConnectedStatus() {
        val waitingStatusEntered = java.util.concurrent.CountDownLatch(1)
        val releaseWaitingStatus = java.util.concurrent.CountDownLatch(1)
        val statuses = mutableListOf<ConnectionStatus>()
        lateinit var callbacks: ConnectionSessionCallbacks
        val supervisor = ConnectionSupervisor(
            sessionFactory = ConnectionSessionFactory { createdCallbacks ->
                callbacks = createdCallbacks
                FakeSession(createdCallbacks)
            },
            scheduler = FakeScheduler(),
            statusListener = ConnectionStatusListener { status ->
                if (status == ConnectionStatus.WAITING_FOR_NETWORK) {
                    waitingStatusEntered.countDown()
                    releaseWaitingStatus.await(2, java.util.concurrent.TimeUnit.SECONDS)
                }
                synchronized(statuses) { statuses += status }
            },
        )
        supervisor.start(networkAvailable = true)
        callbacks.onConnected()
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val unavailable = executor.submit { supervisor.onNetworkUnavailable() }
            assertTrue(waitingStatusEntered.await(1, java.util.concurrent.TimeUnit.SECONDS))
            val available = executor.submit { supervisor.onNetworkAvailable() }
            releaseWaitingStatus.countDown()
            available.get(2, java.util.concurrent.TimeUnit.SECONDS)
            callbacks.onConnected()
            unavailable.get(2, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(ConnectionStatus.CONNECTED, synchronized(statuses) { statuses.last() })
        } finally {
            releaseWaitingStatus.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun schedulerFailureStopsInsteadOfLeavingGhostRetry() {
        val statuses = mutableListOf<ConnectionStatus>()
        lateinit var callbacks: ConnectionSessionCallbacks
        val supervisor = ConnectionSupervisor(
            sessionFactory = ConnectionSessionFactory { createdCallbacks ->
                callbacks = createdCallbacks
                FakeSession(createdCallbacks)
            },
            scheduler = RetryScheduler { _, _ -> throw IllegalStateException("scheduler stopped") },
            statusListener = ConnectionStatusListener { statuses += it },
        )
        supervisor.start(networkAvailable = true)

        callbacks.onFailure()

        assertFalse(supervisor.isRunning())
        assertEquals(ConnectionStatus.STOPPED, statuses.last())
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
