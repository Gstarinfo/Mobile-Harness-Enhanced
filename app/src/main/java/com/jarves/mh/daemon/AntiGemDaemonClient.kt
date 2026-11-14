package com.jarves.mh.daemon

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Client for discovering and communicating with the AntiGem daemon on localhost.
 */
object AntiGemDaemonClient {
    val CANDIDATE_PORTS = listOf(1234, 1236, 8765, 8767)
    const val HEALTH_TIMEOUT_MS = 500

    val KNOWN_PACKAGES = listOf("com.antigem", "com.termux")

    @Volatile
    var customHost: String = "127.0.0.1"
        private set

    @Volatile
    var customPort: Int? = null
        private set

    @Volatile
    var cachedActiveHost: String = "127.0.0.1"
        private set

    @Volatile
    var cachedActivePort: Int? = null
        private set

    @Volatile
    var cachedStatus: DaemonStatus = DaemonStatus.CHECKING
        private set

    fun configure(host: String?, port: Int?) {
        customHost = if (!host.isNullOrBlank()) host.trim() else "127.0.0.1"
        customPort = if (port != null && port > 0) port else null
    }

    fun isDaemonCachedAvailable(): Boolean =
        cachedStatus == DaemonStatus.AVAILABLE && cachedActivePort != null

    fun getEndpoint(path: String, portOverride: Int? = null, hostOverride: String? = null): String {
        val host = (hostOverride ?: cachedActiveHost).ifBlank { customHost.ifBlank { "127.0.0.1" } }
        val port = portOverride ?: cachedActivePort ?: customPort ?: 1234
        val normalizedPath = if (path.startsWith("/")) path else "/$path"
        return "http://$host:$port$normalizedPath"
    }

    /**
     * Pings candidate health endpoints (/api/health, /health, /) on the given port.
     */
    suspend fun pingPort(
        port: Int,
        host: String = cachedActiveHost.ifBlank { customHost.ifBlank { "127.0.0.1" } },
        timeoutMs: Int = HEALTH_TIMEOUT_MS,
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
                }
                val code = conn.responseCode
                conn.disconnect()
                code in 200..399
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
        timeoutMs: Int = HEALTH_TIMEOUT_MS,
    ): DaemonDiscoveryState = withContext(Dispatchers.IO) {
        val host = (hostOverride ?: customHost).ifBlank { "127.0.0.1" }
        val explicitPort = portOverride ?: customPort
        val installedPkg = getInstalledAntiGemPackage(context)
        var activePort: Int? = null

        val candidatePorts = if (explicitPort != null && explicitPort > 0) {
            listOf(explicitPort)
        } else {
            CANDIDATE_PORTS
        }

        for (port in candidatePorts) {
            if (pingPort(port = port, host = host, timeoutMs = timeoutMs)) {
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
        cachedStatus = status

        DaemonDiscoveryState(
            status = status,
            activePort = activePort,
            activeHost = host,
            installedPackage = installedPkg,
            lastCheckedMillis = System.currentTimeMillis(),
            isChecking = false,
            customHost = host,
            customPort = explicitPort?.toString() ?: "",
        )
    }

    /**
     * Sets cached port directly (used for testing or explicit config).
     */
    fun setCachedPort(port: Int?, host: String = "127.0.0.1") {
        cachedActivePort = port
        cachedActiveHost = host.ifBlank { "127.0.0.1" }
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
     */
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
        timeoutMs: Int = 120_000,
        onOutputChunk: ((String) -> Unit)? = null,
    ): DaemonCommandResult = withContext(Dispatchers.IO) {
        val endpoint = getEndpoint("/execute", portOverride, hostOverride)
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
            val sendUrl = getEndpoint("/api/terminal/send", portOverride, hostOverride)
            val sendConn = (URL(sendUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 4_000
                readTimeout = 4_000
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }
            val sendBody = JSONObject()
                .put("command", command)
                .put("append_enter", true)
                .toString()
            sendConn.outputStream.use { it.write(sendBody.toByteArray(Charsets.UTF_8)) }
            val sendCode = sendConn.responseCode
            sendConn.disconnect()
            if (sendCode !in 200..299) return@runCatching null

            // Poll /api/terminal/read for command completion
            val readUrl = getEndpoint("/api/terminal/read", portOverride, hostOverride)
            var finished = false
            var finalOutput = ""
            var lastExitCode = 0
            var finalCwd = cwd
            val startTime = System.currentTimeMillis()

            while (!finished && (System.currentTimeMillis() - startTime) < timeoutMs) {
                kotlinx.coroutines.delay(150)
                val readConn = (URL(readUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 3_000
                    readTimeout = 3_000
                    setRequestProperty("Content-Type", "application/json")
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

        val activeHost = hostOverride ?: cachedActiveHost.ifBlank { customHost.ifBlank { "127.0.0.1" } }
        val activePort = portOverride ?: cachedActivePort ?: customPort ?: 1234
        DaemonCommandResult(
            output = "Error: AntiGem daemon at $activeHost:$activePort did not respond. Ensure AntiGem or termux is running.",
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
    ): Boolean = withContext(Dispatchers.IO) {
        val inputOk = runCatching {
            val endpoint = getEndpoint("/input", portOverride, hostOverride)
            val body = JSONObject().put("input", text).toString()
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 2_000
                readTimeout = 2_000
                setRequestProperty("Content-Type", "application/json")
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
    ): Boolean = withContext(Dispatchers.IO) {
        val endpoint = getEndpoint("/cancel", portOverride, hostOverride)
        val cancelOk = runCatching {
            val url = URL(endpoint)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 2_000
                readTimeout = 2_000
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                outputStream.use { it.write("""{"signal":$signal}""".toByteArray(Charsets.UTF_8)) }
            }
            val ok = conn.responseCode in 200..299
            conn.disconnect()
            ok
        }.getOrDefault(false)

        if (cancelOk) return@withContext true

        // Fallback for AntiGem REST terminal: send control key (e.g. CTRL+C)
        runCatching {
            val ctrlKey = if (signal == 2) "CTRL+C" else "SIGTERM"
            val killUrl = URL(getEndpoint("/api/terminal/send", portOverride, hostOverride))
            val killConn = (killUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 2_000
                readTimeout = 2_000
                setRequestProperty("Content-Type", "application/json")
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
