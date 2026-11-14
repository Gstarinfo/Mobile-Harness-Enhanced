package com.jarves.mh.daemon

import kotlinx.coroutines.runBlocking
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
            activePort = 8765,
            activeDeviceId = "72717fa3b694b4a6",
            installedPackage = "com.termux",
            lastCheckedMillis = 1000L,
            isChecking = false,
        )
        assertTrue(available.isAvailable)
        assertEquals("72717fa3b694b4a6", available.activeDeviceId)

        val availablePort1234 = DaemonDiscoveryState(
            status = DaemonStatus.AVAILABLE,
            activePort = 1234,
        )
        assertTrue(availablePort1234.isAvailable)

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
        // 8765 & 8767 (Termux Terminal Bridge) take priority over 1234 & 1236 (IDE Bridge)
        assertEquals(listOf(8765, 8767, 1234, 1236), AntiGemDaemonClient.CANDIDATE_PORTS)
        assertEquals(600, AntiGemDaemonClient.HEALTH_TIMEOUT_MS)
        assertTrue(AntiGemDaemonClient.KNOWN_PACKAGES.contains("com.antigem"))
        assertTrue(AntiGemDaemonClient.KNOWN_PACKAGES.contains("com.termux"))
    }

    @Test
    fun cachedPortUpdatesAvailability() {
        AntiGemDaemonClient.setCachedPort(8765, deviceId = "test-device-id")
        assertEquals(8765, AntiGemDaemonClient.cachedActivePort)
        assertEquals("test-device-id", AntiGemDaemonClient.cachedDeviceId)
        assertEquals(DaemonStatus.AVAILABLE, AntiGemDaemonClient.cachedStatus)
        assertTrue(AntiGemDaemonClient.isDaemonCachedAvailable())

        AntiGemDaemonClient.setCachedPort(1234)
        assertEquals(1234, AntiGemDaemonClient.cachedActivePort)
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

    @Test
    fun customHostPortAndDeviceIdConfiguration() {
        AntiGemDaemonClient.configure("192.168.1.50", 8765, "custom-id-123")
        assertEquals("192.168.1.50", AntiGemDaemonClient.customHost)
        assertEquals(8765, AntiGemDaemonClient.customPort)
        assertEquals("custom-id-123", AntiGemDaemonClient.customDeviceId)
        assertEquals("http://192.168.1.50:8765/execute", AntiGemDaemonClient.getEndpoint("/execute"))
        assertEquals("http://192.168.1.50:8765/cancel", AntiGemDaemonClient.getEndpoint("cancel"))

        // Reset to default
        AntiGemDaemonClient.configure(null, null, null)
        assertEquals("127.0.0.1", AntiGemDaemonClient.customHost)
        assertNull(AntiGemDaemonClient.customPort)
        assertNull(AntiGemDaemonClient.customDeviceId)
        assertEquals("http://127.0.0.1:8765/health", AntiGemDaemonClient.getEndpoint("/health", 8765))
    }

    @Test
    fun cachedPortWithCustomHost() {
        AntiGemDaemonClient.setCachedPort(9000, "10.0.2.2", "dev-456")
        assertEquals(9000, AntiGemDaemonClient.cachedActivePort)
        assertEquals("10.0.2.2", AntiGemDaemonClient.cachedActiveHost)
        assertEquals("dev-456", AntiGemDaemonClient.cachedDeviceId)
        assertEquals(DaemonStatus.AVAILABLE, AntiGemDaemonClient.cachedStatus)
        assertTrue(AntiGemDaemonClient.isDaemonCachedAvailable())
        assertEquals("http://10.0.2.2:9000/session", AntiGemDaemonClient.getEndpoint("/session"))

        // Clean up
        AntiGemDaemonClient.setCachedPort(null)
    }

    @Test
    fun terminalEndpointsAndFallbacks() {
        assertEquals("http://127.0.0.1:8765/api/terminal/send", AntiGemDaemonClient.getEndpoint("/api/terminal/send", 8765))
        assertEquals("http://127.0.0.1:8765/api/terminal/read", AntiGemDaemonClient.getEndpoint("/api/terminal/read", 8765))
        assertEquals("http://127.0.0.1:1234/api/health", AntiGemDaemonClient.getEndpoint("/api/health", 1234))
        assertTrue(AntiGemDaemonClient.CANDIDATE_PORTS.contains(8765))
        assertTrue(AntiGemDaemonClient.CANDIDATE_PORTS.contains(8767))
    }

    @Test
    fun agyHubPortBlockedFromExecute() = runBlocking {
        // Port 1235 is AGY Hub Web UI, not a terminal daemon
        val result1235 = AntiGemDaemonClient.executeCommand(
            command = "ls",
            cwd = "/data/data/com.termux/files/home",
            portOverride = 1235,
        )
        assertEquals(1, result1235.exitCode)
        assertTrue(result1235.output.contains("Antigravity Hub Web Server"))
        assertTrue(result1235.output.contains("8765"))

        // Port 1237 is also an AGY Hub UI port
        val result1237 = AntiGemDaemonClient.executeCommand(
            command = "ls",
            cwd = "/data/data/com.termux/files/home",
            portOverride = 1237,
        )
        assertEquals(1, result1237.exitCode)
        assertTrue(result1237.output.contains("Antigravity Hub Web Server"))
    }
}
