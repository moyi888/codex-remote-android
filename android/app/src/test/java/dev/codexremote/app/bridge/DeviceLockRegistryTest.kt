package dev.codexremote.app.bridge

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceLockRegistryTest {
    @Test
    fun waitingCallerKeepsDeviceEntryUntilEveryCallerLeaves() {
        val registry = DeviceLockRegistry()
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        val thirdEntered = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(3)

        try {
            val first = executor.submit {
                registry.withLock(String("phone-1".toCharArray())) {
                    firstEntered.countDown()
                    releaseFirst.await(2, TimeUnit.SECONDS)
                }
            }
            assertTrue(firstEntered.await(1, TimeUnit.SECONDS))
            val second = executor.submit {
                registry.withLock(String("phone-1".toCharArray())) {
                    secondEntered.countDown()
                    releaseSecond.await(2, TimeUnit.SECONDS)
                }
            }
            awaitReferenceCount(registry, 2)

            releaseFirst.countDown()
            assertTrue(secondEntered.await(1, TimeUnit.SECONDS))
            val third = executor.submit {
                registry.withLock(String("phone-1".toCharArray())) {
                    thirdEntered.countDown()
                }
            }
            awaitReferenceCount(registry, 2)
            assertFalse(thirdEntered.await(200, TimeUnit.MILLISECONDS))

            releaseSecond.countDown()
            first.get(2, TimeUnit.SECONDS)
            second.get(2, TimeUnit.SECONDS)
            third.get(2, TimeUnit.SECONDS)
            assertTrue(thirdEntered.await(1, TimeUnit.SECONDS))
            assertEquals(0, registry.referenceCount("phone-1"))
        } finally {
            releaseFirst.countDown()
            releaseSecond.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun differentDevicesUseIndependentEntries() {
        val registry = DeviceLockRegistry()
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val first = executor.submit {
                registry.withLock("phone-1") {
                    firstEntered.countDown()
                    releaseFirst.await(2, TimeUnit.SECONDS)
                }
            }
            assertTrue(firstEntered.await(1, TimeUnit.SECONDS))
            val second = executor.submit {
                registry.withLock("phone-2") { secondEntered.countDown() }
            }

            assertTrue(secondEntered.await(1, TimeUnit.SECONDS))
            releaseFirst.countDown()
            first.get(2, TimeUnit.SECONDS)
            second.get(2, TimeUnit.SECONDS)
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
        }
    }

    private fun awaitReferenceCount(registry: DeviceLockRegistry, expected: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (registry.referenceCount("phone-1") != expected && System.nanoTime() < deadline) {
            Thread.yield()
        }
        assertEquals(expected, registry.referenceCount("phone-1"))
    }
}
