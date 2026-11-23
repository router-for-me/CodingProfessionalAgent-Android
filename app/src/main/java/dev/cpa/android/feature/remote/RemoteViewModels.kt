package dev.cpa.android.feature.remote

import androidx.annotation.StringRes
import dev.cpa.android.R

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.cpa.android.data.repository.*
import dev.cpa.android.core.network.Disconnected
import dev.cpa.android.core.network.HttpFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class HomeOperation(val pending: Boolean = false, @param:StringRes val error: Int? = null, val opened: Pair<String, String>? = null, val refreshing: Boolean = false)
class RemoteHomeViewModel(private val saved: SavedStateHandle, private val remote: RemoteDesktopRepository,
    private val homePreferences: dev.cpa.android.core.storage.HomePreferences = dev.cpa.android.core.storage.MemoryHomePreferences()) : ViewModel() {
    val collapsedGroups = homePreferences.collapsedGroups
    fun toggleGroup(key: String) = perform {
        try { homePreferences.toggleGroup(key) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutable.update { it.copy(error = R.string.preference_save_failed) } }
    }
    val newThread = RemoteNewThreadController(saved, remote, viewModelScope)
    val homeComposer = RemoteHomeComposer(saved, remote, viewModelScope, newThread)
    val projectEditor = RemoteProjectController(saved, remote, viewModelScope)
    val search = RemoteSearchController(remote, viewModelScope)
    val runningSessions = remote.runningSessions
    val connections = remote.connections
    val projects = remote.projects
    val storageError: StateFlow<RemoteProblem?> = remote.storageError
    val sessions = remote.sessions
    private val mutable = MutableStateFlow(HomeOperation(error = if (saved.get<Boolean>("mutation") == true) R.string.home_previous_unknown else null))
    val operation = mutable.asStateFlow()
    private fun perform(mutation: Boolean = false, refreshing: Boolean = false, action: suspend () -> Unit) {
        if (mutable.value.pending) return
        mutable.value = HomeOperation(pending = true, refreshing = refreshing)
        saved["mutation"] = mutation
        viewModelScope.launch {
            var cancelled = false
            try { action() } catch (e: CancellationException) { cancelled = true; throw e }
            catch (e: Exception) { mutable.update { it.copy(error = when {
                e is DuplicateTunnelConnection -> R.string.home_duplicate_tunnel
                e is IllegalArgumentException -> R.string.home_invalid_input
                e is HttpFailure && e.statusCode in listOf(401, 403) -> R.string.home_auth_failed
                e is Disconnected -> R.string.home_disconnected
                e.message == "Remote request rejected" -> R.string.home_rejected
                mutation -> R.string.home_mutation_unknown
                else -> R.string.home_operation_failed
            }) } }
            finally { saved["mutation"] = mutation && cancelled; mutable.update { it.copy(pending = false, refreshing = false) } }
        }
    }
    fun save(id: String?, name: String, address: String, password: String?, cleartext: Boolean, onSuccess: () -> Unit = {}) =
        saveWithIcon(id, name, address, password, cleartext, null, onSuccess)
    fun saveWithIcon(id: String?, name: String, address: String, password: String?, cleartext: Boolean,
        icon: dev.cpa.android.core.storage.ConnectionIcon?, onSuccess: () -> Unit = {}) = perform {
        val saved = remote.saveConnection(id, name, address, password, cleartext)
        if (icon != null) runCatching { homePreferences.setConnectionIcon(saved, icon) }
        onSuccess()
    }
    val connectionIcons = homePreferences.connectionIcons
    fun setIcon(id: String, icon: dev.cpa.android.core.storage.ConnectionIcon) { viewModelScope.launch {
        try { homePreferences.setConnectionIcon(id, icon) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutable.update { it.copy(error = R.string.preference_save_failed) } }
    } }
    // Long-press menu actions (iOS HomeView threadMenu / projectMenu); results arrive through repository snapshots.
    fun pinSession(connection: String, session: String, pinned: Boolean) = perform(true) { remote.setSessionPinned(connection, session, pinned) }
    fun moveSession(connection: String, session: String, projectId: String?) = perform(true) { remote.moveSession(connection, session, projectId) }
    fun renameSession(connection: String, session: String, title: String) = perform(true) { remote.renameSession(connection, session, title) }
    fun markUnread(connection: String, session: String, unread: Boolean) = perform(true) { remote.setSessionUnread(connection, session, unread) }
    fun archiveSession(connection: String, session: String) = perform(true) { remote.archiveSession(connection, session) }
    fun pinProject(connection: String, project: String, pinned: Boolean) = perform(true) { remote.setProjectPinned(connection, project, pinned) }
    fun removeProject(connection: String, project: String) = perform(true) { remote.removeProject(connection, project) }
    fun forkSession(connection: String, session: String, worktree: RemoteWorktreeFork? = null, onDone: () -> Unit = {}) = perform(true) {
        val target = java.util.UUID.randomUUID().toString()
        remote.forkSession(connection, session, target, null, worktree)
        onDone()
        mutable.update { it.copy(opened = connection to target) }
    }
    suspend fun gitBranches(connection: String, path: String) = remote.gitBranches(connection, path)
    suspend fun environmentProjects(connection: String) = remote.environmentProjects(connection)
    fun dismissError() { mutable.update { it.copy(error = null) } }
    // Composer and behavior preferences shown in the settings sheet.
    val collapseKeyboardAfterSend = remote.collapseKeyboardAfterSend
    val showContextUsage = remote.showContextUsage
    val wrapCode = remote.wrapCode
    private fun preference(update: suspend () -> Unit) { viewModelScope.launch {
        try { update() } catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutable.update { it.copy(error = R.string.preference_save_failed) } }
    } }
    fun setCollapseKeyboard(value: Boolean) = preference { remote.setCollapseKeyboardAfterSend(value) }
    fun setShowContextUsage(value: Boolean) = preference { remote.setShowContextUsage(value) }
    fun setWrapCode(value: Boolean) = preference { remote.setWrapCode(value) }
    suspend fun recentTunnel() = remote.recentTunnelSummary()
    fun watchTunnel(id: String?, address: String, key: String?, cleartext: Boolean) = remote.watchTunnel(id, address, key, cleartext)
    suspend fun discoverTunnel(id: String?, address: String, key: String?, cleartext: Boolean) = remote.discoverTunnel(id, address, key, cleartext)
    fun saveTunnel(draft: TunnelConnectionDraft, onSuccess: () -> Unit = {}) = saveTunnelWithIcon(draft, null, onSuccess)
    fun saveTunnelWithIcon(draft: TunnelConnectionDraft, icon: dev.cpa.android.core.storage.ConnectionIcon?, onSuccess: () -> Unit = {}) = perform {
        val saved = remote.saveTunnelConnection(draft.id, draft.name, draft.address, draft.apiKey, draft.serverId, draft.serverName, draft.password, draft.allowCleartext)
        if (icon != null) runCatching { homePreferences.setConnectionIcon(saved, icon) }
        onSuccess()
    }
    fun remove(id: String) = perform { remote.removeConnection(id) }
    fun disconnectAll() = perform { remote.disconnectAll() }
    fun reorder(ids: List<String>) = perform { remote.reorderConnections(ids) }
    fun connect(id: String) = perform { remote.connect(id) }
    fun disconnect(id: String) = perform { remote.disconnect(id) }
    fun refreshVisible(ids: List<String>) = perform(refreshing = true) {
        var failure: Exception? = null
        for (id in ids) {
            try { remote.refresh(id) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (failure == null) failure = e }
        }
        failure?.let { throw it }
    }
    fun create(id: String, projectId: String?, title: String) = perform(true) {
        val session = remote.createSession(id, projectId, title)
        mutable.update { it.copy(opened = id to session) }
    }
    fun consumedNavigation() { mutable.update { it.copy(opened = null) } }
}

data class ForkChoices(val loading: Boolean = false, val branches: RemoteBranches = RemoteBranches(),
    val environments: List<RemoteProject> = emptyList(), @param:StringRes val error: Int? = null)
data class ComposerState(val draft: String = "", val pending: Boolean = false, @param:StringRes val error: Int? = null, val unknown: Boolean = false)
class RemoteThreadViewModel(private val saved: SavedStateHandle, private val remote: RemoteDesktopRepository) : ViewModel() {
    val connectionId: String = checkNotNull(saved["connectionId"])
    val sessionId: String = checkNotNull(saved["sessionId"])
    // Length framing prevents collisions even when IDs contain separators.
    private val draftKey = "draft:${connectionId.length}:$connectionId:$sessionId"
    private val pendingKey = "$draftKey:pending"
    val attachments = RemoteAttachments(saved, "$draftKey:attachments", viewModelScope)
    val skills = RemoteSkillsController(saved, "$draftKey:skills", remote, viewModelScope, saved[draftKey] ?: "")
    val collapseKeyboardAfterSend = remote.collapseKeyboardAfterSend
    val showContextUsage = remote.showContextUsage
    fun collapseKeyboard(value: Boolean) = preference { remote.setCollapseKeyboardAfterSend(value) }
    fun showContext(value: Boolean) = preference { remote.setShowContextUsage(value) }
    private fun preference(update: suspend () -> Unit) { viewModelScope.launch {
        try { update() } catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutable.update { it.copy(error = R.string.preference_save_failed) } }
    } }
    val wrapCode = remote.wrapCode
    fun wrapCode(wrap: Boolean) { viewModelScope.launch {
        try { remote.setWrapCode(wrap) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutable.update { it.copy(error = R.string.wrap_save_failed) } }
    } }
    fun gitBrowser(scope: kotlinx.coroutines.CoroutineScope) = RemoteGitController(connectionId, remote, scope)
    fun fileBrowser(scope: kotlinx.coroutines.CoroutineScope) = RemoteFilesController(connectionId, remote, scope)
    fun observeChild(session: String) = remote.observeThread(connectionId, session)
    suspend fun loadChild(session: String) = remote.openThread(connectionId, session)
    val thread = remote.observeThread(connectionId, sessionId)
    val connections = remote.connections
    val sessions = remote.sessions
    val projects = remote.projects
    val capabilities = remote.capabilities
    private val mutable = MutableStateFlow(ComposerState(saved[draftKey] ?: "", unknown = saved.get<Boolean>(pendingKey) == true))
    val composer = mutable.asStateFlow()
    val editor = RemoteMessageEditor(saved, "$draftKey:edit", connectionId, sessionId, remote, viewModelScope,
        blocked = { mutable.value.pending || mutable.value.unknown }, knownSkills = { skills.state.value.catalog.enabled(remote.capabilities.value[connectionId]?.defaults ?: RemoteDefaults()) })
    private val mutableForkChoices = MutableStateFlow(ForkChoices())
    val forkChoices = mutableForkChoices.asStateFlow()
    val forkTarget = saved.getStateFlow<String?>("fork.target", null)
    val forkOpened = saved.getStateFlow<String?>("fork.opened", null)
    private var forkLoad: kotlinx.coroutines.Job? = null
    private var forkGeneration = 0L
    fun loadForkChoices() {
        val token = ++forkGeneration
        forkLoad?.cancel()
        mutableForkChoices.value = ForkChoices(loading = true)
        forkLoad = viewModelScope.launch {
            val source = sessions.value[connectionId].orEmpty().find { it.id == sessionId }
            val project = projects.value[connectionId].orEmpty().find { it.id == source?.projectId }
            var branches = RemoteBranches()
            var failed = false
            for (path in listOfNotNull(source?.worktreePath, project?.allPaths?.firstOrNull()).distinct()) {
                try { branches = remote.gitBranches(connectionId, path) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { failed = true }
                if (branches.names.isNotEmpty()) break
            }
            if (token != forkGeneration) return@launch
            mutableForkChoices.value = ForkChoices(loading = true, branches = branches)
            val environments = try { remote.environmentProjects(connectionId) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed = true; emptyList() }
            if (token == forkGeneration) mutableForkChoices.value = ForkChoices(branches = branches, environments = environments,
                error = if (failed) R.string.fork_choices_failed else null)
        }
    }
    fun stopForkChoices() { forkGeneration++; forkLoad?.cancel() }
    fun fork(atEntryId: String? = null, worktree: RemoteWorktreeFork? = null) = manage {
        val target = java.util.UUID.randomUUID().toString()
        saved["fork.target"] = target
        remote.forkSession(connectionId, sessionId, target, atEntryId, worktree)
        saved["fork.opened"] = target
    }
    fun consumedForkNavigation() { saved["fork.opened"] = null; saved["fork.target"] = null }
    private val askPendingKey = "$draftKey:askPending"
    private val askToolKey = "$draftKey:askTool"
    val ask = RemoteAskController(sessionId, viewModelScope, { method, args, mutation ->
        if (mutation) {
            check(!editor.state.value.pending && !editor.state.value.unknown && !thread.value.editAwaitingStart) { "请先核对编辑结果" }
            saved[askPendingKey] = true
            saved[askToolKey] = (args.getOrNull(1) as? kotlinx.serialization.json.JsonPrimitive)?.content
        }
        // A lost confirmation leaves the restore marker set; reads never clear it.
        val result = remote.callSessionRpc(connectionId, sessionId, method, args, mutation)
        if (mutation && (result == kotlinx.serialization.json.JsonPrimitive(true) || result == kotlinx.serialization.json.JsonPrimitive(false))) {
            saved[askPendingKey] = false
            saved.remove<String>(askToolKey)
        }
        result
    }, initialOutcomeUnknown = saved.get<Boolean>(askPendingKey) == true,
        initialToolCallId = saved[askToolKey], onOutcomeAcknowledged = {
            check(!thread.value.outcomeUnknown && !mutable.value.unknown)
            saved[askPendingKey] = false
            saved.remove<String>(askToolKey)
        })
    override fun onCleared() { ask.close() }
    init {
        if (mutable.value.draft.length > 20_000) saved.remove<String>(draftKey)
        // Reconnect refreshes all observed threads, including a notification opened offline.
        if (connections.value.any { it.id == connectionId && it.phase == ConnectionPhase.Connected }) reload()
        viewModelScope.launch {
            combine(connections, sessions, projects) { _, _, _ -> Unit }.collect {
                val session = sessions.value[connectionId].orEmpty().find { it.id == sessionId }
                val path = projects.value[connectionId].orEmpty().find { it.id == session?.projectId }?.allPaths?.firstOrNull()
                skills.context(connectionId, path, connections.value.any { it.id == connectionId && it.phase == ConnectionPhase.Connected })
            }
        }
    }
    fun edit(text: String) {
        // Never truncate IME text. Oversized drafts remain memory-only, with stale saved copies removed.
        if (text.length <= 20_000) saved[draftKey] = text else saved.remove<String>(draftKey)
        if (skills.state.value.draft.text != text) skills.edit(text)
        mutable.update { it.copy(draft = text) }
    }
    fun reload() { if (editor.state.value.unknown) { editor.inspect(); return }; viewModelScope.launch {
        try { remote.openThread(connectionId, sessionId) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutable.update { it.copy(error = R.string.thread_load_failed) } }
    } }
    fun acknowledgeUnknown() {
        if (editor.state.value.unknown) { editor.acknowledge(); return }
        if (mutable.value.pending) return
        mutable.update { it.copy(pending = true) }
        viewModelScope.launch {
            try {
                remote.acknowledgeOutcome(connectionId, sessionId)
                saved[pendingKey] = false
                mutable.update { it.copy(unknown = false, error = null) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                mutable.update { it.copy(error = R.string.send_verify_required) }
            } finally { mutable.update { it.copy(pending = false) } }
        }
    }
    fun clearError() { mutable.update { it.copy(error = null) } }
    fun send() = sendWithAcceptance {}
    fun sendWithAcceptance(onAccepted: () -> Unit) {
        if (editor.state.value.pending || editor.state.value.unknown || thread.value.editAwaitingStart) return
        val state = mutable.value
        if (state.pending || state.unknown || thread.value.outcomeUnknown || thread.value.loading || (state.draft.isBlank() && attachments.state.value.items.isEmpty())) return
        if (connections.value.none { it.id == connectionId && it.phase == ConnectionPhase.Connected }) return
        val caps = capabilities.value[connectionId] ?: RemoteCapabilities()
        val model = sessions.value[connectionId].orEmpty().find { it.id == sessionId }?.modelId ?: caps.preferredModel()?.id
        try { attachments.requireImageSupport(caps, model) }
        catch (e: AttachmentValidationException) { mutable.update { it.copy(error = e.messageResource) }; return }
        if (!attachments.lock()) return
        val picked = attachments.state.value.items
        val text = state.draft
        val payload = skills.payload(caps.defaults)
        saved[pendingKey] = true
        mutable.update { it.copy(pending = true, error = null) }
        onAccepted()
        viewModelScope.launch {
            try {
                val files = picked.filterNot { it.image }
                val paths = if (files.isEmpty()) emptyList() else remote.uploadAttachments(connectionId, sessionId, files)
                val prompt = dev.cpa.android.core.attachments.attachmentPrompt(payload.text, paths)
                val images = picked.filter { it.image }
                if (thread.value.running) remote.sendFollowUp(connectionId, sessionId, prompt, payload.references, images)
                else if (payload.references.isNotEmpty()) remote.sendWithSkills(connectionId, sessionId, prompt, payload.references, images)
                else if (images.isEmpty()) remote.send(connectionId, sessionId, prompt)
                else remote.sendWithAttachments(connectionId, sessionId, prompt, images)
                if (thread.value.outcomeUnknown) mutable.update { it.copy(unknown = true) }
                else { skills.sent(payload.references); if (mutable.value.draft == text) edit(""); attachments.clearSent() }
            } catch (e: CancellationException) {
                mutable.update { it.copy(unknown = true) }
                saved[pendingKey] = true
                throw e
            }
            catch (e: Exception) { mutable.update { it.copy(error = R.string.send_failed_unknown, unknown = e !is IllegalArgumentException) } }
            finally { attachments.unlock(); saved[pendingKey] = mutable.value.unknown; mutable.update { it.copy(pending = false) } }
        }
    }
    /** All test-panel mutations share the composer's pending/restore guard. */
    private fun manage(action: suspend () -> Unit) {
        if (editor.state.value.pending || editor.state.value.unknown || thread.value.editAwaitingStart) return
        if (mutable.value.pending || mutable.value.unknown || thread.value.outcomeUnknown || thread.value.loading) return
        if (connections.value.none { it.id == connectionId && it.phase == ConnectionPhase.Connected }) return
        saved[pendingKey] = true
        mutable.update { it.copy(pending = true, error = null) }
        viewModelScope.launch {
            try { action() }
            catch (e: CancellationException) {
                mutable.update { it.copy(unknown = true) }
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(error = if (e is IllegalArgumentException) R.string.session_invalid_parameters
                    else R.string.session_operation_failed, unknown = thread.value.outcomeUnknown) }
            } finally {
                saved[pendingKey] = mutable.value.unknown
                mutable.update { it.copy(pending = false) }
            }
        }
    }
    fun runtime(modelId: String?, reasoning: String?, speed: String?, onApplied: () -> Unit = {}) = manage {
        remote.setSessionRuntime(connectionId, sessionId, modelId, reasoning, speed)
        onApplied()
    }
    fun rename(title: String) = manage { remote.renameSession(connectionId, sessionId, title) }
    fun runAction(action: RemoteRunAction) {
        if (thread.value.running) return
        manage { remote.runAction(connectionId, sessionId, action) }
    }
    fun pin(pinned: Boolean) = manage { remote.setSessionPinned(connectionId, sessionId, pinned) }
    fun archive() = manage { remote.archiveSession(connectionId, sessionId) }
    fun move(projectId: String?) = manage { remote.moveSession(connectionId, sessionId, projectId) }
    fun beginViewing() = remote.beginViewing(connectionId, sessionId)
    fun endViewing() = remote.endViewing(connectionId, sessionId)
    fun unread(unread: Boolean) = manage { remote.setSessionUnread(connectionId, sessionId, unread) }
    fun refreshCapabilities() {
        viewModelScope.launch {
            try { remote.refresh(connectionId) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(error = R.string.runtime_refresh_failed) } }
        }
    }
    fun stop() {
        if (mutable.value.pending) return
        mutable.update { it.copy(pending = true, error = null) }
        viewModelScope.launch {
            try { remote.stop(connectionId, sessionId) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(error = R.string.stop_failed_unknown) } }
            finally { mutable.update { it.copy(pending = false) } }
        }
    }
}
