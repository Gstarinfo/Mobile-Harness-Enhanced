package com.jarves.mh.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AntiGemDaemonTest {

    @Test
    fun runtimeModeSerialization() {
        assertEquals(RuntimeMode.AUTO_DETECT, RuntimeMode.fromStored("auto_detect"))
        assertEquals(RuntimeMode.SHARED_DAEMON, RuntimeMode.fromStored("shared_daemon"))
        assertEquals(RuntimeMode.STANDALONE_PROOT, RuntimeMode.fromStored("standalone_proot"))

        // Case-insensitivity and fallback
        assertEquals(RuntimeMode.AUTO_DETECT, RuntimeMode.fromStored("AUTO_DETECT"))
        assertEquals(RuntimeMode.SHARED_DAEMON, RuntimeMode.fromStored("SHARED_DAEMON"))
        assertEquals(RuntimeMode.AUTO_DETECT, RuntimeMode.fromStored("unknown_value"))
        assertEquals(RuntimeMode.AUTO_DETECT, RuntimeMode.fromStored(null))
        assertEquals(RuntimeMode.AUTO_DETECT, RuntimeMode.fromStored(""))
    }

    @Test
    fun daemonDiscoveryStateAvailability() {
        val available = DaemonDiscoveryState(
            status = DaemonStatus.AVAILABLE,
            activePort = 1234,
            installedPackage = "com.antigem",
            lastCheckedMillis = 1000L,
            isChecking = false,
        )
        assertTrue(available.isAvailable)

        val availablePort1236 = DaemonDiscoveryState(
            status = DaemonStatus.AVAILABLE,
            activePort = 1236,
        )
        assertTrue(availablePort1236.isAvailable)

        val unavailable = DaemonDiscoveryState(
            status = DaemonStatus.UNAVAILABLE,
            activePort = null,
            installedPackage = "com.antigem",
        )
        assertFalse(unavailable.isAvailable)

        val notInstalled = DaemonDiscoveryState(
            status = DaemonStatus.NOT_INSTALLED,
            activePort = null,
            installedPackage = null,
        )
        assertFalse(notInstalled.isAvailable)
    }

    @Test
    fun candidatePortsAndConstants() {
        assertEquals(listOf(1234, 1236), AntiGemDaemonClient.CANDIDATE_PORTS)
        assertEquals(500, AntiGemDaemonClient.HEALTH_TIMEOUT_MS)
        assertTrue(AntiGemDaemonClient.KNOWN_PACKAGES.contains("com.antigem"))
        assertTrue(AntiGemDaemonClient.KNOWN_PACKAGES.contains("com.termux"))
    }

    @Test
    fun cachedPortUpdatesAvailability() {
        AntiGemDaemonClient.setCachedPort(1234)
        assertEquals(1234, AntiGemDaemonClient.cachedActivePort)
        assertEquals(DaemonStatus.AVAILABLE, AntiGemDaemonClient.cachedStatus)
        assertTrue(AntiGemDaemonClient.isDaemonCachedAvailable())

        AntiGemDaemonClient.setCachedPort(1236)
        assertEquals(1236, AntiGemDaemonClient.cachedActivePort)
        assertEquals(DaemonStatus.AVAILABLE, AntiGemDaemonClient.cachedStatus)
        assertTrue(AntiGemDaemonClient.isDaemonCachedAvailable())

        AntiGemDaemonClient.setCachedPort(null)
        assertNull(AntiGemDaemonClient.cachedActivePort)
        assertEquals(DaemonStatus.UNAVAILABLE, AntiGemDaemonClient.cachedStatus)
        assertFalse(AntiGemDaemonClient.isDaemonCachedAvailable())
    }

    @Test
    fun daemonCommandResultConstruction() {
        val result = DaemonCommandResult(
            output = "Hello from daemon",
            exitCode = 0,
            cwd = "/workspace/project1",
        )
        assertEquals("Hello from daemon", result.output)
        assertEquals(0, result.exitCode)
        assertEquals("/workspace/project1", result.cwd)
    }
}
