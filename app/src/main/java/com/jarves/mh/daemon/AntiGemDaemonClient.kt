package com.jarves.mh.daemon

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Client for discovering and communicating with the AntiGem daemon on localhost.
 *
 * Supported service ports:
 * - 8765 / 8767: AntiGem LocalTerminalBridge (Termux shell PTY session runner)
 * - 1234 / 1236: Go IDE Bridge (git & filesystem operations)
 * Note: Port 1235 / 1237 is Antigravity Web Hub (UI SPA) - not a shell terminal.
 */
object AntiGemDaemonClient {
    val CANDIDATE_PORTS = listOf(8765, 8767, 1234, 1236)
    const val HEALTH_TIMEOUT_MS = 600

    val KNOWN_PACKAGES = listOf("com.antigem", "com.termux")

    @Volatile
    var customHost: String = "127.0.0.1"
        private set

    @Volatile
    var customPort: Int? = null
        private set

    @Volatile
    var customDeviceId: String? = null
        private set

    @Volatile
    var cachedActiveHost: String = "127.0.0.1"
        private set

    @Volatile
    var cachedActivePort: Int? = null
        private set

    @Volatile
    var cachedDeviceId: String? = null
        private set

    @Volatile
    var cachedStatus: DaemonStatus = DaemonStatus.CHECKING
        private set

    fun configure(host: String?, port: Int?, deviceId: String? = null) {
        customHost = if (!host.isNullOrBlank()) host.trim() else "127.0.0.1"
        customPort = if (port != null && port > 0) port else null
        customDeviceId = if (!deviceId.isNullOrBlank()) deviceId.trim() else null
    }

    fun isDaemonCachedAvailable(): Boolean =
        cachedStatus == DaemonStatus.AVAILABLE && cachedActivePort != null

    fun getEndpoint(path: String, portOverride: Int? = null, hostOverride: String? = null): String {
        val host = (hostOverride ?: cachedActiveHost).ifBlank { customHost.ifBlank { "127.0.0.1" } }
        val port = portOverride ?: cachedActivePort ?: customPort ?: 8765
        val normalizedPath = if (path.startsWith("/")) path else "/$path"
        return "http://$host:$port$normalizedPath"
    }

    /**
     * Resolves the Device ID required for authenticating with AntiGem's LocalTerminalBridge.
     */
    fun resolveDeviceId(context: Context?): String? {
        val configured = customDeviceId?.takeIf { it.isNotBlank() }
        if (configured != null) return configured

        val cached = cachedDeviceId?.takeIf { it.isNotBlank() }
        if (cached != null) return cached

        // 1. Try Settings.Secure.ANDROID_ID from ContentResolver
        val androidId = runCatching {
            context?.contentResolver?.let { resolver ->
                Settings.Secure.getString(resolver, Settings.Secure.ANDROID_ID)
            }
        }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }

        if (androidId != null) {
            cachedDeviceId = androidId
            return androidId
        }

        // 2. Try parsing extension-host.log in Termux / AntiGem home directory
        val logDeviceId = runCatching {
            val possibleFiles = listOf(
                File("/data/user/0/com.termux/files/home/extension-host.log"),
                File("/data/data/com.termux/files/home/extension-host.log"),
                File(System.getenv("HOME") ?: "", "extension-host.log"),
            )
            val regex = Regex("""--device-id\s+([a-zA-Z0-9]+)""")
            for (f in possibleFiles) {
                if (f.exists() && f.canRead()) {
                    val line = f.bufferedReader().use { it.readLine() }
                    val match = line?.let { regex.find(it) }
                    if (match != null) return@runCatching match.groupValues[1].trim()
                }
            }
            null
        }.getOrNull()?.takeIf { it.isNotBlank() }

        if (logDeviceId != null) {
            cachedDeviceId = logDeviceId
            return logDeviceId
        }

        return null
    }

    /**
     * Pings candidate health endpoints (/api/health, /health, /) on the given port.
     */
    suspend fun pingPort(
        port: Int,
        host: String = cachedActiveHost.ifBlank { customHost.ifBlank { "127.0.0.1" } },
        timeoutMs: Int = HEALTH_TIMEOUT_MS,
        deviceId: String? = cachedDeviceId ?: customDeviceId,
    ): Boolean = withContext(Dispatchers.IO) {
        val h = host.ifBlank { "127.0.0.1" }
        val checkPaths = listOf("/api/health", "/health", "/")
        checkPaths.any { path ->
            runCatching {
                val url = URL("http://$h:$port$path")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    instanceFollowRedirects = false
                    if (!deviceId.isNullOrBlank()) {
                        setRequestProperty("X-Device-Id", deviceId)
                    }
                }
                val code = conn.responseCode
                val isAuthRequired = code == 401 && (
                    conn.getHeaderField("Access-Control-Allow-Headers")?.contains("X-Device-Id", ignoreCase = true) == true
                )
                conn.disconnect()
                code in 200..399 || isAuthRequired
            }.getOrDefault(false)
        }
    }

    /**
     * Checks candidate ports and inspects package installation.
     */
    suspend fun discover(
        context: Context,
        hostOverride: String? = null,
        portOverride: Int? = null,
        deviceIdOverride: String? = null,
        timeoutMs: Int = HEALTH_TIMEOUT_MS,
    ): DaemonDiscoveryState = withContext(Dispatchers.IO) {
        val host = (hostOverride ?: customHost).ifBlank { "127.0.0.1" }
        val explicitPort = portOverride ?: customPort
        val explicitDeviceId = deviceIdOverride ?: customDeviceId
        val resolvedDeviceId = explicitDeviceId?.takeIf { it.isNotBlank() } ?: resolveDeviceId(context)
        val installedPkg = getInstalledAntiGemPackage(context)
        var activePort: Int? = null

        val candidatePorts = if (explicitPort != null && explicitPort > 0) {
            listOf(explicitPort)
        } else {
            CANDIDATE_PORTS
        }

        for (port in candidatePorts) {
            if (pingPort(port = port, host = host, timeoutMs = timeoutMs, deviceId = resolvedDeviceId)) {
                activePort = port
                break
            }
        }

        val status = when {
            activePort != null -> DaemonStatus.AVAILABLE
            installedPkg != null -> DaemonStatus.UNAVAILABLE
            else -> DaemonStatus.NOT_INSTALLED
        }

        cachedActiveHost = host
        cachedActivePort = activePort
        cachedDeviceId = resolvedDeviceId
        cachedStatus = status

        DaemonDiscoveryState(
            status = status,
            activePort = activePort,
            activeHost = host,
            activeDeviceId = resolvedDeviceId,
            installedPackage = installedPkg,
            lastCheckedMillis = System.currentTimeMillis(),
            isChecking = false,
            customHost = host,
            customPort = explicitPort?.toString() ?: "",
            customDeviceId = explicitDeviceId ?: "",
        )
    }

    /**
     * Sets cached port directly (used for testing or explicit config).
     */
    fun setCachedPort(port: Int?, host: String = "127.0.0.1", deviceId: String? = null) {
        cachedActivePort = port
        cachedActiveHost = host.ifBlank { "127.0.0.1" }
        if (deviceId != null) cachedDeviceId = deviceId
        cachedStatus = if (port != null) DaemonStatus.AVAILABLE else DaemonStatus.UNAVAILABLE
    }

    /**
     * Checks if AntiGem or Termux is installed on the host device.
     */
    fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0L),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            true
        }.getOrDefault(false)
    }

    fun getInstalledAntiGemPackage(context: Context): String? {
        return KNOWN_PACKAGES.firstOrNull { isPackageInstalled(context, it) }
    }

    fun isAntiGemInstalled(context: Context): Boolean = getInstalledAntiGemPackage(context) != null

    /**
     * Launches the installed AntiGem or Termux application.
     */
    fun launchAntiGem(context: Context): Boolean {
        val packageName = getInstalledAntiGemPackage(context) ?: KNOWN_PACKAGES.first()
        return runCatching {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } else {
                false
            }
        }.getOrDefault(false)
    }

    /**
     * Executes a command via the AntiGem daemon HTTP bridge.
     * Supports both /execute (streaming bridge) and AntiGem LocalTerminalBridge (/api/terminal/send & /api/terminal/read).
     */
    suspend fun executeCommand(
        command: String,
        cwd: String,
        env: Map<String, String> = emptyMap(),
        portOverride: Int? = null,
        hostOverride: String? = null,
        deviceIdOverride: String? = null,
        timeoutMs: Int = 120_000,
        onOutputChunk: ((String) -> Unit)? = null,
    ): DaemonCommandResult = withContext(Dispatchers.IO) {
        val effectivePort = portOverride ?: cachedActivePort ?: customPort ?: 8765
        val effectiveHost = hostOverride ?: cachedActiveHost.ifBlank { customHost.ifBlank { "127.0.0.1" } }
        val effectiveDeviceId = deviceIdOverride ?: customDeviceId ?: cachedDeviceId

        // Guard: If connected to AGY Hub Web Server (port 1235 / 1237)
        if (effectivePort == 1235 || effectivePort == 1237) {
            return@withContext DaemonCommandResult(
                output = "Error: Port $effectivePort is the Antigravity Hub Web Server (UI), not the Termux Terminal Bridge.\n" +
                        "Please configure port to 8765 (or 8767) in AntiGem Daemon Settings to connect to the Termux terminal.",
                exitCode = 1,
                cwd = cwd,
            )
        }

        val endpoint = getEndpoint("/execute", effectivePort, effectiveHost)
        val requestBody = JSONObject()
            .put("command", command)
            .put("cwd", cwd)
            .put("env", JSONObject(env))
            .toString()

        val executeResult = runCatching {
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5_000
                readTimeout = timeoutMs
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json, text/plain")
                if (!effectiveDeviceId.isNullOrBlank()) {
                    setRequestProperty("X-Device-Id", effectiveDeviceId)
                }
                doOutput = true
            }

            connection.outputStream.use { out ->
                out.write(requestBody.toByteArray(Charsets.UTF_8))
                out.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode == 404) {
                connection.disconnect()
                return@runCatching null
            }

            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val responseText = StringBuilder()

            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                var line: String? = reader.readLine()
                while (line != null) {
                    responseText.append(line).append('\n')
                    onOutputChunk?.invoke(line + "\n")
                    line = reader.readLine()
                }
            }
            connection.disconnect()

            val rawOutput = responseText.toString()

            // Guard against Web UI HTML fallbacks
            val isHtml = rawOutput.trimStart().startsWith("<!doctype html", ignoreCase = true) ||
                    rawOutput.trimStart().startsWith("<html", ignoreCase = true) ||
                    rawOutput.contains("antigravity-cli")
            if (isHtml) {
                return@runCatching DaemonCommandResult(
                    output = "Error: Port $effectivePort responded with Antigravity Web UI HTML instead of a shell terminal.\n" +
                            "Please connect to the AntiGem Termux Terminal Bridge on port 8765.",
                    exitCode = 1,
                    cwd = cwd,
                )
            }

            val parsedJson = runCatching { JSONObject(rawOutput.trim()) }.getOrNull()

            if (parsedJson != null && parsedJson.has("exitCode")) {
                DaemonCommandResult(
                    output = parsedJson.optString("output", ""),
                    exitCode = parsedJson.optInt("exitCode", 0),
                    cwd = parsedJson.optString("cwd", cwd),
                )
            } else {
                DaemonCommandResult(
                    output = rawOutput,
                    exitCode = if (responseCode in 200..299) 0 else responseCode,
                    cwd = cwd,
                )
            }
        }.getOrNull()

        if (executeResult != null) return@withContext executeResult

        // Fallback for AntiGem's LocalTerminalBridge (/api/terminal/send & /api/terminal/read)
        val fallbackResult = runCatching {
            val sendUrl = getEndpoint("/api/terminal/send", effectivePort, effectiveHost)
            val sendConn = (URL(sendUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 4_000
                readTimeout = 4_000
                setRequestProperty("Content-Type", "application/json")
                if (!effectiveDeviceId.isNullOrBlank()) {
                    setRequestProperty("X-Device-Id", effectiveDeviceId)
                }
                doOutput = true
            }
            val sendBody = JSONObject()
                .put("command", command)
                .put("append_enter", true)
                .toString()
            sendConn.outputStream.use { it.write(sendBody.toByteArray(Charsets.UTF_8)) }
            val sendCode = sendConn.responseCode
            val sendErrorText = if (sendCode !in 200..299) {
                sendConn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            } else ""
            sendConn.disconnect()

            if (sendCode == 401) {
                return@runCatching DaemonCommandResult(
                    output = "Error: 401 Unauthorized from AntiGem Terminal Bridge at $effectiveHost:$effectivePort.\n" +
                            "Please verify the Device ID in Settings > AntiGem Daemon Connection.\n$sendErrorText".trim(),
                    exitCode = 401,
                    cwd = cwd,
                )
            }

            if (sendCode !in 200..299) return@runCatching null

            // Poll /api/terminal/read for command completion
            val readUrl = getEndpoint("/api/terminal/read", effectivePort, effectiveHost)
            var finished = false
            var finalOutput = ""
            var lastExitCode = 0
            var finalCwd = cwd
            val startTime = System.currentTimeMillis()

            while (!finished && (System.currentTimeMillis() - startTime) < timeoutMs) {
                delay(150)
                val readConn = (URL(readUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 3_000
                    readTimeout = 3_000
                    setRequestProperty("Content-Type", "application/json")
                    if (!effectiveDeviceId.isNullOrBlank()) {
                        setRequestProperty("X-Device-Id", effectiveDeviceId)
                    }
                    doOutput = true
                }
                val readBody = JSONObject()
                    .put("mode", "last_command")
                    .put("lines_count", 200)
                    .toString()
                readConn.outputStream.use { it.write(readBody.toByteArray(Charsets.UTF_8)) }
                if (readConn.responseCode in 200..299) {
                    val resp = readConn.inputStream.bufferedReader().use { it.readText() }
                    readConn.disconnect()
                    val json = runCatching { JSONObject(resp) }.getOrNull()
                    val execution = json?.optJSONObject("execution")
                    if (execution != null) {
                        val isRunning = execution.optBoolean("is_running", true)
                        val out = execution.optString("output", "")
                        finalCwd = execution.optString("working_directory", cwd)
                        if (!execution.isNull("exit_code")) {
                            lastExitCode = execution.optInt("exit_code", 0)
                        }
                        if (out.length > finalOutput.length) {
                            val newChunk = out.substring(finalOutput.length)
                            onOutputChunk?.invoke(newChunk)
                            finalOutput = out
                        }
                        if (!isRunning) {
                            finished = true
                        }
                    }
                } else {
                    readConn.disconnect()
                    break
                }
            }

            DaemonCommandResult(
                output = finalOutput,
                exitCode = lastExitCode,
                cwd = finalCwd,
            )
        }.getOrNull()

        if (fallbackResult != null) return@withContext fallbackResult

        DaemonCommandResult(
            output = "Error: AntiGem daemon at $effectiveHost:$effectivePort did not respond. Ensure AntiGem or termux is running with bridge enabled.",
            exitCode = 1,
            cwd = cwd,
        )
    }

    /**
     * Sends input characters or a line to the running command on the daemon.
     */
    suspend fun sendInput(
        text: String,
        portOverride: Int? = null,
        hostOverride: String? = null,
        deviceIdOverride: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val effectiveDeviceId = deviceIdOverride ?: customDeviceId ?: cachedDeviceId
        val inputOk = runCatching {
            val endpoint = getEndpoint("/input", portOverride, hostOverride)
            val body = JSONObject().put("input", text).toString()
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 2_000
                readTimeout = 2_000
                setRequestProperty("Content-Type", "application/json")
                if (!effectiveDeviceId.isNullOrBlank()) {
                    setRequestProperty("X-Device-Id", effectiveDeviceId)
                }
                doOutput = true
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val ok = conn.responseCode in 200..299
            conn.disconnect()
            ok
        }.getOrDefault(false)

        if (inputOk) return@withContext true

        // Fallback for AntiGem's LocalTerminalBridge (/api/terminal/send)
        runCatching {
            val terminalSendUrl = URL(getEndpoint("/api/terminal/send", portOverride, hostOverride))
            val sendConn = (terminalSendUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 2_000
                readTimeout = 2_000
                setRequestProperty("Content-Type", "application/json")
                if (!effectiveDeviceId.isNullOrBlank()) {
                    setRequestProperty("X-Device-Id", effectiveDeviceId)
                }
                doOutput = true
            }
            sendConn.outputStream.use {
                it.write(JSONObject().put("command", text).put("append_enter", false).toString().toByteArray(Charsets.UTF_8))
            }
            val ok = sendConn.responseCode in 200..299
            sendConn.disconnect()
            ok
        }.getOrDefault(false)
    }

    /**
     * Sends an interrupt or kill signal to the running command on the daemon.
     */
    suspend fun stopExecution(
        signal: Int = 2,
        portOverride: Int? = null,
        hostOverride: String? = null,
        deviceIdOverride: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val effectiveDeviceId = deviceIdOverride ?: customDeviceId ?: cachedDeviceId
        val endpoint = getEndpoint("/cancel", portOverride, hostOverride)
        val cancelOk = runCatching {
            val url = URL(endpoint)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 2_000
                readTimeout = 2_000
                setRequestProperty("Content-Type", "application/json")
                if (!effectiveDeviceId.isNullOrBlank()) {
                    setRequestProperty("X-Device-Id", effectiveDeviceId)
                }
                doOutput = true
                outputStream.use { it.write("""{"signal":$signal}""".toByteArray(Charsets.UTF_8)) }
            }
            val ok = conn.responseCode in 200..299
            conn.disconnect()
            ok
        }.getOrDefault(false)

        if (cancelOk) return@withContext true

        // Fallback for AntiGem REST terminal: send control key (e.g. "c" for CTRL+C)
        runCatching {
            val ctrlKey = if (signal == 2) "c" else "c"
            val killUrl = URL(getEndpoint("/api/terminal/send", portOverride, hostOverride))
            val killConn = (killUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 2_000
                readTimeout = 2_000
                setRequestProperty("Content-Type", "application/json")
                if (!effectiveDeviceId.isNullOrBlank()) {
                    setRequestProperty("X-Device-Id", effectiveDeviceId)
                }
                doOutput = true
            }
            killConn.outputStream.use {
                it.write(JSONObject().put("control_key", ctrlKey).toString().toByteArray(Charsets.UTF_8))
            }
            val killCode = killConn.responseCode
            killConn.disconnect()
            killCode in 200..299
        }.getOrDefault(false)
    }
}
