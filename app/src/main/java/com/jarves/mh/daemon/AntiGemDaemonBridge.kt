package com.jarves.mh.daemon

import android.content.Context
import com.jarves.mh.model.ChangeItem
import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.ProjectKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.RuntimeEvent
import com.jarves.mh.model.ToolRequest
import com.jarves.mh.runtime.AntigravityEventParser
import com.jarves.mh.runtime.AntigravityParsedEvent
import com.jarves.mh.runtime.RuntimeBridge
import com.jarves.mh.runtime.WorkspaceCheckpoints
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * RuntimeBridge implementation connecting to the local AntiGem daemon.
 * Routes session events and terminal commands over localhost HTTP without PRoot rootfs overhead.
 */
class AntiGemDaemonBridge(
    private val context: Context,
    private val checkpoints: WorkspaceCheckpoints = WorkspaceCheckpoints(context.filesDir, context),
) : RuntimeBridge {
    private val eventBus = MutableSharedFlow<RuntimeEvent>(extraBufferCapacity = 64)
    override val events: Flow<RuntimeEvent> = eventBus

    @Volatile
    private var activeSessionId: String? = null

    @Volatile
    private var activeConnection: HttpURLConnection? = null

    private val projectRoots = ConcurrentHashMap<String, String>()

    fun configureProjectRoot(projectId: String, path: String) {
        projectRoots[projectId] = path
        checkpoints.configureProjectRoot(projectId, path)
    }

    override suspend fun startSession(
        projectId: String,
        projectSlug: String,
        projectKind: ProjectKind,
        prompt: String,
        conversationHistory: List<ChatMessage>,
        provider: ProviderProfile,
    ): String = withContext(Dispatchers.IO) {
        val sessionId = UUID.randomUUID().toString()
        activeSessionId = sessionId
        eventBus.emit(RuntimeEvent.SessionStarted(sessionId))

        val workspace = checkpoints.ensureWorkspace(projectId)
        checkpoints.createCheckpoint(projectId, workspace)
        val before = checkpoints.snapshot(workspace)
        val workspaceDir = workspace.absolutePath

        try {
            val port = AntiGemDaemonClient.cachedActivePort ?: 1234
            val sessionEndpoint = "http://127.0.0.1:$port/session"

            val payload = JSONObject().apply {
                put("sessionId", sessionId)
                put("projectId", projectId)
                put("projectSlug", projectSlug)
                put("prompt", prompt)
                put("cwd", workspaceDir)
                put("provider", JSONObject().apply {
                    put("kind", provider.kind.name)
                    put("model", provider.model)
                    put("baseUrl", provider.baseUrl)
                })
                put("history", JSONArray().apply {
                    conversationHistory.takeLast(20).forEach { msg ->
                        put(JSONObject().apply {
                            put("role", if (msg.fromUser) "user" else "assistant")
                            put("content", msg.text)
                        })
                    }
                })
            }

            val conn = (URL(sessionEndpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5_000
                readTimeout = 0 // Streaming, no read timeout
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/x-ndjson, text/event-stream, application/json")
                doOutput = true
            }
            activeConnection = conn

            conn.outputStream.use { out ->
                out.write(payload.toString().toByteArray(Charsets.UTF_8))
                out.flush()
            }

            val respCode = conn.responseCode
            val inputStream = if (respCode in 200..299) conn.inputStream else conn.errorStream

            BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                var line: String? = reader.readLine()
                while (line != null) {
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty()) {
                        handleDaemonStreamLine(sessionId, trimmed)
                    }
                    line = reader.readLine()
                }
            }

            val paths = checkpoints.changedFiles(workspace, before)
            checkpoints.saveChangedPaths(projectId, paths)
            if (paths.isNotEmpty()) {
                eventBus.emit(RuntimeEvent.FilesChanged(sessionId, checkpoints.buildChangeDetails(projectId, workspace, paths)))
            }
            eventBus.emit(RuntimeEvent.SessionCompleted(sessionId))
        } catch (e: Exception) {
            eventBus.emit(
                RuntimeEvent.SessionFailed(
                    sessionId,
                    e.message ?: "AntiGem daemon session error",
                ),
            )
        } finally {
            activeConnection = null
            activeSessionId = null
        }
        sessionId
    }

    private suspend fun handleDaemonStreamLine(sessionId: String, line: String) {
        val agyEvent = AntigravityEventParser.parse(line)
        if (agyEvent != null) {
            when (agyEvent) {
                is AntigravityParsedEvent.Text -> {
                    eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, agyEvent.value))
                }
                is AntigravityParsedEvent.ToolStarted -> {
                    eventBus.emit(RuntimeEvent.ToolStarted(sessionId, agyEvent.name, agyEvent.detail))
                }
                is AntigravityParsedEvent.ToolCompleted -> {
                    eventBus.emit(RuntimeEvent.ToolCompleted(sessionId, agyEvent.name, agyEvent.detail))
                }
                is AntigravityParsedEvent.Result -> {
                    if (agyEvent.response != null) {
                        eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, agyEvent.response))
                    }
                }
                else -> Unit
            }
            return
        }

        val json = runCatching { JSONObject(line) }.getOrNull()
        if (json == null) {
            eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, line + "\n"))
            return
        }

        when (json.optString("type")) {
            "text", "delta", "assistant_delta" -> {
                val delta = json.optString("text").ifEmpty { json.optString("content") }
                if (delta.isNotEmpty()) eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, delta))
            }
            "tool_start", "tool_started" -> {
                val name = json.optString("name", "Tool")
                val detail = json.optString("detail", name)
                eventBus.emit(RuntimeEvent.ToolStarted(sessionId, name, detail))
            }
            "tool_complete", "tool_completed" -> {
                val name = json.optString("name", "Tool")
                val summary = json.optString("summary", name)
                eventBus.emit(RuntimeEvent.ToolCompleted(sessionId, name, summary))
            }
            "reasoning" -> {
                val summary = json.optString("summary")
                eventBus.emit(RuntimeEvent.ReasoningSummary(sessionId, summary, System.currentTimeMillis()))
            }
        }
    }

    override suspend fun respondToApproval(request: ToolRequest, approved: Boolean) {
        val port = AntiGemDaemonClient.cachedActivePort ?: 1234
        withContext(Dispatchers.IO) {
            runCatching {
                val url = URL("http://127.0.0.1:$port/approval")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 2000
                    readTimeout = 2000
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                }
                val body = JSONObject().apply {
                    put("approvalId", request.approvalId)
                    put("approved", approved)
                }.toString()
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                conn.responseCode
                conn.disconnect()
            }
        }
    }

    override suspend fun stopSession(sessionId: String) {
        if (activeSessionId == sessionId) {
            stopActiveSession()
        }
    }

    override suspend fun stopActiveSession() {
        withContext(Dispatchers.IO) {
            runCatching { activeConnection?.disconnect() }
            activeConnection = null
            AntiGemDaemonClient.stopExecution(signal = 9)
        }
    }

    override suspend fun undoLastChanges(projectId: String): Boolean = withContext(Dispatchers.IO) {
        val checkpoint = checkpoints.checkpointDir(projectId)
        val backup = File(checkpoint, "project")
        val paths = checkpoints.readChangedPaths(projectId)
        if (!backup.isDirectory || paths.isEmpty()) return@withContext false
        val workspace = checkpoints.ensureWorkspace(projectId)
        paths.forEach { restore(workspace, backup, it) }
        checkpoint.deleteRecursively()
        true
    }

    override suspend fun acceptLastChanges(projectId: String): Unit = withContext(Dispatchers.IO) {
        checkpoints.checkpointDir(projectId).deleteRecursively()
        Unit
    }

    override suspend fun loadPendingChanges(projectId: String): List<ChangeItem> = withContext(Dispatchers.IO) {
        val paths = checkpoints.readChangedPaths(projectId)
        if (paths.isEmpty()) emptyList() else checkpoints.buildChangeDetails(projectId, checkpoints.ensureWorkspace(projectId), paths)
    }

    override suspend fun undoFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        if (path !in checkpoints.readChangedPaths(projectId)) return@withContext false
        restore(checkpoints.ensureWorkspace(projectId), File(checkpoints.checkpointDir(projectId), "project"), path)
        checkpoints.removeChangedPath(projectId, path)
        true
    }

    override suspend fun acceptFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        if (path !in checkpoints.readChangedPaths(projectId)) return@withContext false
        val workspace = checkpoints.ensureWorkspace(projectId)
        val backup = File(checkpoints.checkpointDir(projectId), "project")
        val current = checkpoints.safeWorkspaceFile(workspace, path)
        val baseline = checkpoints.safeWorkspaceFile(backup, path)
        if (current.isFile) {
            baseline.parentFile?.mkdirs()
            current.copyTo(baseline, overwrite = true)
        } else baseline.delete()
        checkpoints.removeChangedPath(projectId, path)
        true
    }

    private fun restore(workspace: File, backup: File, path: String) {
        val target = checkpoints.safeWorkspaceFile(workspace, path)
        val original = checkpoints.safeWorkspaceFile(backup, path)
        if (original.isFile) {
            target.parentFile?.mkdirs()
            original.copyTo(target, overwrite = true)
        } else target.delete()
    }

    /**
     * Executes a terminal command on the AntiGem daemon.
     */
    suspend fun runTerminalCommand(
        projectId: String,
        command: String,
        cwd: String,
        onOutputChunk: ((String) -> Unit)? = null,
    ): DaemonCommandResult {
        val workspaceDir = projectRoots[projectId] ?: checkpoints.ensureWorkspace(projectId).absolutePath
        val targetCwd = when {
            cwd.startsWith("/workspace") -> workspaceDir + cwd.removePrefix("/workspace")
            cwd.isBlank() -> workspaceDir
            else -> cwd
        }
        return AntiGemDaemonClient.executeCommand(
            command = command,
            cwd = targetCwd,
            onOutputChunk = onOutputChunk,
        )
    }

    suspend fun stopTerminalCommand() {
        AntiGemDaemonClient.stopExecution(signal = 15)
    }

    suspend fun interruptTerminalCommand() {
        AntiGemDaemonClient.stopExecution(signal = 2)
    }
}
