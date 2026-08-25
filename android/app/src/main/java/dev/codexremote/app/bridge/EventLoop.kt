package dev.codexremote.app.bridge

import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicReference

internal interface EventLoop {
    fun dispatch(task: () -> Unit): Boolean

    fun runSyncIfOpen(task: () -> Unit): Boolean

    fun shutdown()
}

internal class SingleThreadEventLoop : EventLoop {
    private val acceptanceLock = Any()
    private val loopThread = AtomicReference<Thread?>()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, THREAD_NAME).apply {
            isDaemon = true
            loopThread.set(this)
        }
    }

    private var accepting = true

    override fun dispatch(task: () -> Unit): Boolean = synchronized(acceptanceLock) {
        if (!accepting) return@synchronized false
        try {
            executor.execute { task() }
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }

    override fun runSyncIfOpen(task: () -> Unit): Boolean {
        if (Thread.currentThread() === loopThread.get()) {
            val open = synchronized(acceptanceLock) { accepting }
            if (!open) return false
            task()
            return true
        }
        val future = synchronized(acceptanceLock) {
            if (!accepting) return false
            try {
                executor.submit { task() }
            } catch (_: RejectedExecutionException) {
                return false
            }
        }
        try {
            future.get()
            return true
        } catch (error: ExecutionException) {
            val cause = error.cause
            when (cause) {
                is RuntimeException -> throw cause
                is Error -> throw cause
                else -> throw IllegalStateException("Event loop task failed")
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted while waiting for event loop")
        }
    }

    override fun shutdown() {
        synchronized(acceptanceLock) {
            if (!accepting) return
            accepting = false
            executor.shutdown()
        }
    }

    private companion object {
        const val THREAD_NAME = "bridge-event-stream"
    }
}
