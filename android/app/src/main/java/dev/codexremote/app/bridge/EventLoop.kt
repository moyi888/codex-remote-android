package dev.codexremote.app.bridge

import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicReference

internal interface EventLoop {
    fun dispatch(task: () -> Unit)

    fun <T> runSync(task: () -> T): T

    fun shutdown()
}

internal class SingleThreadEventLoop : EventLoop {
    private val loopThread = AtomicReference<Thread?>()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, THREAD_NAME).apply {
            isDaemon = true
            loopThread.set(this)
        }
    }

    override fun dispatch(task: () -> Unit) {
        try {
            executor.execute { task() }
        } catch (_: RejectedExecutionException) {
            // Callbacks racing with dispose are intentionally discarded.
        }
    }

    override fun <T> runSync(task: () -> T): T {
        if (Thread.currentThread() === loopThread.get()) return task()
        try {
            return executor.submit<T> { task() }.get()
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
        executor.shutdown()
    }

    private companion object {
        const val THREAD_NAME = "bridge-event-stream"
    }
}
