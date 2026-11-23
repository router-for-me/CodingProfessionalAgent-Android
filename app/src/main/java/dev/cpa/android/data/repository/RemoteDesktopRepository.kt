package dev.cpa.android.data.repository

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement

class DuplicateTunnelConnection : IllegalArgumentException("Tunnel desktop already added")

/** UI boundary: IDs and immutable projections, never sockets or credentials. */
enum class ConnectionPhase { Disconnected, Connecting, Connected, Failed, AuthenticationRequired }
enum class RemoteRunAction(val wireValue: String) { Resume("resume"), Compact("compact") }
data class ConnectionSummary(
    val id: String, val name: String, val address: String, val allowCleartext: Boolean,
    val phase: ConnectionPhase = ConnectionPhase.Disconnected, val error: RemoteProblem? = null, val enabled: Boolean = true,
    val mode: dev.cpa.android.core.storage.ConnectionMode = dev.cpa.android.core.storage.ConnectionMode.Direct,
    val tunnelServerId: String = "", val tunnelServerName: String = ""
)
data class RemoteProject(val id: String, val name: String, val path: String? = null,
    val paths: List<String> = emptyList(), val pinned: Boolean = false) {
    val allPaths: List<String> get() {
        val result = paths.map(String::trim).filter(String::isNotEmpty).distinct()
        val legacy = path?.trim()?.takeIf(String::isNotEmpty)
        return if (legacy != null && legacy !in result) listOf(legacy) + result else result
    }
}
data class RemoteDirectoryEntry(val name: String, val isDirectory: Boolean)
data class RemoteSession(val id: String, val title: String, val projectId: String? = null, val branch: String? = null,
    val modelId: String? = null, val reasoningEffort: String? = null, val speed: String? = null,
    val pinned: Boolean = false, val archivedAt: Long? = null, val unread: String = "read", val updatedAt: Long = 0,
    val workLocation: String? = null, val worktreePath: String? = null, val environmentId: String? = null, val createdAt: Long = 0)
data class RemoteBranches(val current: String? = null, val names: List<String> = emptyList())
data class RemoteWorktreeFork(val branch: String, val environmentId: String? = null)
data class RemoteNewSessionOptions(val projectId: String? = null, val branch: String? = null,
    val workLocation: String = "local", val modelId: String? = null, val reasoningEffort: String? = null,
    val speed: String = "standard")
data class RemoteSentImage(val data: String, val mimeType: String)
data class RemoteEntry(val id: String, val kind: String, val text: String, val toolName: String? = null, val images: List<RemoteSentImage> = emptyList(), val skillReferences: List<SkillReference> = emptyList(), val pendingStatus: String? = null, val details: RemoteEntryDetails = RemoteEntryDetails(), val hasSkillReferences: Boolean = true)
data class RemoteSearchResult(val connectionId: String, val sessionId: String, val title: String,
    val updatedAt: Long, val titleMatch: Boolean = true, val snippet: String? = null)
data class RemoteThread(
    val connectionId: String, val sessionId: String, val entries: List<RemoteEntry> = emptyList(),
    val running: Boolean = false, val loading: Boolean = false, val error: RemoteProblem? = null,
    val outcomeUnknown: Boolean = false, val editAwaitingStart: Boolean = false, val runningTools: Map<String, RemoteRunningTool> = emptyMap(), val subAgents: List<RemoteSubAgent> = emptyList(), val wasStopped: Boolean = false,
    val isBuildingSystemPrompt: Boolean = false
)
private val DefaultWrapCode = kotlinx.coroutines.flow.MutableStateFlow(true)

private val DefaultCollapseKeyboard = kotlinx.coroutines.flow.MutableStateFlow(true)
private val DefaultShowContextUsage = kotlinx.coroutines.flow.MutableStateFlow(false)
private val NoStorageError = kotlinx.coroutines.flow.MutableStateFlow<RemoteProblem?>(null)

private val NoCapabilities = kotlinx.coroutines.flow.MutableStateFlow<Map<String, RemoteCapabilities>>(emptyMap())

private val NoRunningSessions = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Set<String>>>(emptyMap())

interface RemoteDesktopRepository {
    val skillUsage: RemoteSkillUsage
    val runningSessions: StateFlow<Map<String, Set<String>>> get() = NoRunningSessions
    val collapseKeyboardAfterSend: StateFlow<Boolean> get() = DefaultCollapseKeyboard
    suspend fun setCollapseKeyboardAfterSend(value: Boolean): Unit = throw UnsupportedOperationException()
    val showContextUsage: StateFlow<Boolean> get() = DefaultShowContextUsage
    suspend fun setShowContextUsage(value: Boolean): Unit = throw UnsupportedOperationException()
    val wrapCode: StateFlow<Boolean> get() = DefaultWrapCode
    suspend fun setWrapCode(wrap: Boolean): Unit = throw UnsupportedOperationException()

    /** Completes only after the matching edited user entry starts, not merely after request acceptance. */
    suspend fun editMessage(connectionId: String, sessionId: String, entryId: String, text: String, references: List<SkillReference>): Unit = throw UnsupportedOperationException()

    suspend fun sendFollowUp(connectionId: String, sessionId: String, text: String,
        references: List<SkillReference> = emptyList(), images: List<dev.cpa.android.core.attachments.LocalAttachment> = emptyList()): Unit = throw UnsupportedOperationException()

    suspend fun sendWithSkills(connectionId: String, sessionId: String, text: String, references: List<SkillReference>, images: List<dev.cpa.android.core.attachments.LocalAttachment> = emptyList()) {
        require(references.isEmpty())
        if (images.isEmpty()) send(connectionId, sessionId, text) else sendWithAttachments(connectionId, sessionId, text, images)
    }
    suspend fun loadSkills(connectionId: String, projectPath: String?): RemoteSkillCatalog = throw UnsupportedOperationException()
    suspend fun uploadAttachments(connectionId: String, sessionId: String, files: List<dev.cpa.android.core.attachments.LocalAttachment>): List<String> = throw UnsupportedOperationException()
    suspend fun sendWithAttachments(connectionId: String, sessionId: String, text: String, images: List<dev.cpa.android.core.attachments.LocalAttachment>) {
        require(images.isEmpty()); send(connectionId, sessionId, text)
    }
    suspend fun createConfiguredSession(connectionId: String, sessionId: String, title: String, options: RemoteNewSessionOptions): Unit = throw UnsupportedOperationException()
    suspend fun gitReview(connectionId: String, path: String, base: String? = null, target: String? = null): dev.cpa.android.data.repository.git.GitReview = throw UnsupportedOperationException()
    suspend fun gitBranches(connectionId: String, path: String): RemoteBranches = throw UnsupportedOperationException()
    suspend fun environmentProjects(connectionId: String): List<RemoteProject> = throw UnsupportedOperationException()
    suspend fun forkSession(connectionId: String, sessionId: String, newSessionId: String, atEntryId: String? = null, worktree: RemoteWorktreeFork? = null): Unit = throw UnsupportedOperationException()
    suspend fun readRemoteFile(connectionId: String, path: String): String = throw UnsupportedOperationException()
    suspend fun homeDirectory(connectionId: String): String = throw UnsupportedOperationException()
    suspend fun directoryEntries(connectionId: String, path: String): List<RemoteDirectoryEntry> = throw UnsupportedOperationException()
    suspend fun updateProject(connectionId: String, projectId: String, name: String, paths: List<String>): Unit = throw UnsupportedOperationException()
    suspend fun setProjectPinned(connectionId: String, projectId: String, pinned: Boolean): Unit = throw UnsupportedOperationException()
    suspend fun removeProject(connectionId: String, projectId: String): Unit = throw UnsupportedOperationException()
    /** Refresh first, then require explicit confirmation before another project mutation. */
    suspend fun refreshProjects(connectionId: String): Unit = throw UnsupportedOperationException()
    suspend fun acknowledgeProjectOutcome(connectionId: String): Unit = throw UnsupportedOperationException()
    val capabilities: StateFlow<Map<String, RemoteCapabilities>> get() = NoCapabilities
    suspend fun setSessionRuntime(connectionId: String, sessionId: String, modelId: String? = null, reasoningEffort: String? = null, speed: String? = null): Unit = throw UnsupportedOperationException()
    suspend fun renameSession(connectionId: String, sessionId: String, title: String): Unit = throw UnsupportedOperationException()
    suspend fun setSessionPinned(connectionId: String, sessionId: String, pinned: Boolean): Unit = throw UnsupportedOperationException()
    suspend fun archiveSession(connectionId: String, sessionId: String): Unit = throw UnsupportedOperationException()
    suspend fun moveSession(connectionId: String, sessionId: String, projectId: String?): Unit = throw UnsupportedOperationException()
    /** Balanced foreground reader ownership; opening data for inspection is not viewing it. */
    fun beginViewing(connectionId: String, sessionId: String) = Unit
    fun endViewing(connectionId: String, sessionId: String) = Unit
    suspend fun setSessionUnread(connectionId: String, sessionId: String, unread: Boolean): Unit = throw UnsupportedOperationException()
    /** Ask only. args must target sessionId; writes require mutation=true and the unknown guard. */
    suspend fun callSessionRpc(connectionId: String, sessionId: String, method: String, args: List<JsonElement>, mutation: Boolean = false): JsonElement = throw UnsupportedOperationException()
    /** Asynchronous vault initialization/write errors, sanitized and safe for UI. */
    val storageError: StateFlow<RemoteProblem?> get() = NoStorageError
    val connections: StateFlow<List<ConnectionSummary>>
    val projects: StateFlow<Map<String, List<RemoteProject>>>
    val sessions: StateFlow<Map<String, List<RemoteSession>>>
    fun observeThread(connectionId: String, sessionId: String): StateFlow<RemoteThread>
    /** null password retains the existing secret, empty string explicitly clears it. */
    suspend fun saveConnection(id: String?, name: String, address: String, password: String?, allowCleartext: Boolean): String
    suspend fun recentTunnelSummary(): dev.cpa.android.core.storage.RecentTunnelSummary? = null
    fun watchTunnel(id: String?, baseUrl: String, apiKey: String?, allowCleartext: Boolean): kotlinx.coroutines.flow.Flow<dev.cpa.android.core.network.tunnel.TunnelDiscoveryState> = kotlinx.coroutines.flow.flow {
        emit(dev.cpa.android.core.network.tunnel.TunnelDiscoveryState(dev.cpa.android.core.network.tunnel.TunnelDiscoveryPhase.Connected, discoverTunnel(id, baseUrl, apiKey, allowCleartext)))
    }
    suspend fun discoverTunnel(id: String?, baseUrl: String, apiKey: String?, allowCleartext: Boolean): List<dev.cpa.android.core.network.tunnel.TunnelProtocol.Server> = throw UnsupportedOperationException()
    suspend fun saveTunnelConnection(id: String?, name: String, baseUrl: String, apiKey: String?, serverId: String,
        serverName: String, password: String?, allowCleartext: Boolean): String = throw UnsupportedOperationException()
    suspend fun removeConnection(id: String)
    suspend fun reorderConnections(ids: List<String>): Unit = throw UnsupportedOperationException()
    suspend fun disconnectAll(): Unit = throw UnsupportedOperationException()
    suspend fun connect(id: String)
    suspend fun disconnect(id: String)
    suspend fun refresh(id: String)
    /** Explicit confirmation after a successful authoritative reload; production rejects premature confirmation. */
    suspend fun acknowledgeOutcome(connectionId: String, sessionId: String) {}
    suspend fun openThread(connectionId: String, sessionId: String)
    suspend fun createSession(connectionId: String, projectId: String?, title: String): String
    suspend fun send(connectionId: String, sessionId: String, text: String, modelId: String? = null)
    suspend fun stop(connectionId: String, sessionId: String)
    suspend fun searchSessions(connectionId: String, query: String): List<RemoteSearchResult> = throw UnsupportedOperationException()
    suspend fun runAction(connectionId: String, sessionId: String, action: RemoteRunAction): Unit = throw UnsupportedOperationException()
    suspend fun prepareNotificationTarget(id: String): Boolean = connections.value.any { it.id == id && it.enabled }
    fun onForeground()
    fun onBackground()
}
