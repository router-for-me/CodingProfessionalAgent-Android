package dev.cpa.android.data.repository

import dev.cpa.android.core.attachments.*
import dev.cpa.android.core.model.DesktopEvent
import dev.cpa.android.core.network.*
import dev.cpa.android.core.network.tunnel.*
import dev.cpa.android.core.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.util.UUID

/** Application-scoped owner. A lifetime identity is the generation fence, never a wire sequence. */
class DefaultRemoteDesktopRepository(
    private val vault: ConnectionVault,
    private val scope: CoroutineScope,
    private val composerPreferences: ComposerPreferences = MemoryComposerPreferences(),
    private val recentTunnel: RecentTunnelStore = MemoryRecentTunnelStore(),
    private val tunnelConnections: TunnelConnectionProvider? = null,
    private val push: dev.cpa.android.core.push.DesktopPushSubscriptions? = null,
    private val transportFactory: (String) -> DesktopTransport = { DirectTransport(it) }
) : RemoteDesktopRepository {
    override val skillUsage = RemoteSkillUsage()
    override val collapseKeyboardAfterSend = composerPreferences.collapseKeyboardAfterSend
    override suspend fun setCollapseKeyboardAfterSend(value: Boolean) = composerPreferences.setCollapseKeyboardAfterSend(value)
    override val showContextUsage = composerPreferences.showContextUsage
    override suspend fun setShowContextUsage(value: Boolean) = composerPreferences.setShowContextUsage(value)
    override val wrapCode = composerPreferences.wrapCode
    override suspend fun setWrapCode(wrap: Boolean) = composerPreferences.setWrapCode(wrap)
    private val lock = Any()
    private val writes = Mutex()
    private val projectWrites = Mutex()
    private val forkWrites = Mutex()
    private val attachmentWrites = Mutex()
    private val projectUnknown = mutableSetOf<String>()
    private val projectNeedsRefresh = mutableSetOf<String>()
    private val stored = linkedMapOf<String, StoredConnection>()
    private val lifetimes = mutableMapOf<String, Lifetime>()
    private val wanted = mutableSetOf<String>()
    private val revokingPush = mutableSetOf<String>()
    private var foreground = false
    private val threads = mutableMapOf<Pair<String, String>, ThreadStore>()
    private val _connections = MutableStateFlow<List<ConnectionSummary>>(emptyList())
    private val _projects = MutableStateFlow<Map<String, List<RemoteProject>>>(emptyMap())
    private val _sessions = MutableStateFlow<Map<String, List<RemoteSession>>>(emptyMap())
    @Volatile private var rawSessions: Map<String, List<RemoteSession>> = emptyMap()
        set(value) {
            field = value
            publishSessionReads()
        }

    // RPC reconciliation uses raw metadata. Home rows reflect reading immediately,
    // including while a setMeta reply is pending or unavailable.
    private fun publishSessionReads() {
        _sessions.value = rawSessions.mapValues { (id, items) ->
            items.map { item ->
                val key = id to item.id
                val store = threads[key]
                val read = lifetimes[id]?.viewReads?.get(item.id)
                val newer = read?.newerUnread
                val repair = store?.pendingUnreadRepair
                val pending = store?.unreadRun?.takeUnless { it.settled }?.pendingWrite
                val manual = store?.manualMark
                val state = when {
                    key in viewers -> "read"
                    newer != null -> newer.unread
                    repair != null -> repair.unread
                    store?.readThrough?.let { item.updatedAt <= it } == true -> "read"
                    manual != null && manual.run === store.unreadRun ->
                        if (manual.unread) "unread" else "read"
                    pending != null -> if (pending.outcome == UnreadOutcome.Error) "error" else "unread"
                    else -> item.unread
                }
                val updated = maxOf(item.updatedAt, pending?.updatedAt ?: item.updatedAt)
                if (state == item.unread && updated == item.updatedAt) item else item.copy(unread = state, updatedAt = updated)
            }
        }
    }

    private val _capabilities = MutableStateFlow<Map<String, RemoteCapabilities>>(emptyMap())
    override val capabilities = _capabilities.asStateFlow()
    private val _runningSessions = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    override val runningSessions = _runningSessions.asStateFlow()
    private fun setHomeRunning(connection: String, session: String, running: Boolean) {
        val previous = _runningSessions.value[connection].orEmpty()
        val active = if (running && _connections.value.any { it.id == connection && it.phase == ConnectionPhase.Connected }) previous + session else previous - session
        _runningSessions.value = if (active.isEmpty()) _runningSessions.value - connection else _runningSessions.value + (connection to active)
    }
    private val _storageError = MutableStateFlow<RemoteProblem?>(null)
    override val connections = _connections.asStateFlow()
    override val projects = _projects.asStateFlow()
    override val sessions = _sessions.asStateFlow()
    override val storageError = _storageError.asStateFlow()
    private val viewers = mutableMapOf<Pair<String, String>, Int>()
    private class ManualMark(val unread: Boolean, val run: UnreadRun?, val epoch: Long)
    private class ReadEcho(val viewedAt: Long, val incoming: RemoteSession)
    private class ViewRead(val before: RemoteSession, val run: UnreadRun?) {
        var job: Job? = null
        var newerUnread: RemoteSession? = null
    }
    private class Lifetime(val id: String) {
        val metricSignals = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
        val viewReads = mutableMapOf<String, ViewRead>()
        var job: Job? = null
        var rpc: DesktopRpcClient? = null
        var pushJob: Job? = null
        val refresh = Mutex()
        val mutations = mutableMapOf<String, Int>()
        val runs = mutableMapOf<String, String>()
        val runTimes = mutableMapOf<String, Double>()
        val retired = mutableMapOf<String, MutableSet<String>>()
        val runTouched = mutableSetOf<String>()
        var projectTouched = false
        var projectRevision = 0L
        val metaTouched = mutableSetOf<String>()
    }
    private class PendingEdit(val entryId: String, val text: String, val references: List<SkillReference>,
        val droppedIds: Set<String>, val previousRun: String?, val previous: PendingEdit? = null) {
        val started = CompletableDeferred<Unit>()
        var confirmedEntry: RemoteEntry? = null
    }
    private fun editChain(edit: PendingEdit?): List<PendingEdit> = generateSequence(edit) { it.previous }.toList()
    private class ThreadStore(connection: String, session: String) {
        var unreadRun: UnreadRun? = null
        var unreadJob: Job? = null
        var writingUnreadAt: Long? = null
        var manualMark: ManualMark? = null
        var writingRun: UnreadRun? = null
        var readThrough: Long? = null
        var viewedAt: Long? = null
        var readEcho: ReadEcho? = null
        var pendingUnreadRepair: RemoteSession? = null
        var viewEpoch = 0L
        var compactRequested = false
        var edit: PendingEdit? = null
        val removedFollowUps = mutableSetOf<String>()
        val state = MutableStateFlow(RemoteThread(connection, session))
        val reload = Mutex()
        var scheduledReload: Job? = null
        var overlay: LinkedHashMap<String, RemoteEntry>? = null
        var deleted = false
        var unknownNeedsRefresh = true
        var unknownRevision = 0L
    }
    private val initialized = scope.async {
        try {
            val loaded = vault.load()
            synchronized(lock) {
                loaded.forEach { stored[it.id] = it; if (it.enabled) wanted.add(it.id) }
                publish()
                if (foreground) wanted.toList().forEach(::start)
            }
            true
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) {
            _storageError.value = RemoteProblem.StorageReadFailed
            false
        }
    }
    private suspend fun ready() { check(initialized.await()) { "Connection storage unavailable" } }
    private fun publish() {
        val previous = _connections.value.associateBy { it.id }
        _connections.value = stored.values.map { c ->
            ConnectionSummary(c.id, c.name, if (c.mode == ConnectionMode.Tunnel) c.tunnelBaseUrl else c.address, c.allowCleartext,
                previous[c.id]?.phase ?: ConnectionPhase.Disconnected, previous[c.id]?.error, c.enabled, c.mode, c.tunnelServerId, c.tunnelServerName)
        }
    }
    private fun phase(id: String, phase: ConnectionPhase, error: RemoteProblem? = null) {
        if (phase != ConnectionPhase.Connected) {
            _runningSessions.value -= id
            threads.filterKeys { it.first == id }.values.forEach { store ->
                if (store.state.value.isBuildingSystemPrompt) store.state.value = store.state.value.copy(isBuildingSystemPrompt = false)
            }
        }
        _connections.value = _connections.value.map { if (it.id == id) it.copy(phase = phase, error = error) else it }
    }
    private fun current(life: Lifetime, rpc: DesktopRpcClient? = life.rpc) = lifetimes[life.id] === life && life.rpc === rpc
    private fun thread(c: String, s: String) = threads.getOrPut(c to s) { ThreadStore(c, s) }
    override fun observeThread(connectionId: String, sessionId: String): StateFlow<RemoteThread> =
        synchronized(lock) { thread(connectionId, sessionId).state.asStateFlow() }

    override suspend fun saveConnection(id: String?, name: String, address: String, password: String?, allowCleartext: Boolean): String {
        ready()
        val origin = DirectTransport.validateOrigin(address)
        require(origin.isHttps || allowCleartext) { "HTTP requires explicit authorization for this connection" }
        return writes.withLock {
            val key = id ?: UUID.randomUUID().toString()
            val old = synchronized(lock) { stored[key] }
            require(id == null || old != null) { "Connection no longer exists" }
            val value = StoredConnection(key, name.trim().ifEmpty { "Desktop" }, origin.toString(), password ?: old?.password.orEmpty(), allowCleartext, old?.enabled ?: true)
            persistConnection(value)
            key
        }
    }
    override suspend fun recentTunnelSummary(): RecentTunnelSummary? = recentTunnel.load()?.let { RecentTunnelSummary(it.baseUrl, it.apiKey.isNotEmpty()) }
    private suspend fun tunnelKey(existing: StoredConnection?, baseUrl: String, supplied: String?): String {
        supplied?.let { return it }
        if (existing?.mode == ConnectionMode.Tunnel) return existing.tunnelApiKey
        val last = recentTunnel.load() ?: return ""
        return if (TunnelProtocol.endpoint(last.baseUrl).webSocketUrl == TunnelProtocol.endpoint(baseUrl).webSocketUrl) last.apiKey else ""
    }
    override fun watchTunnel(id: String?, baseUrl: String, apiKey: String?, allowCleartext: Boolean): Flow<TunnelDiscoveryState> = flow {
        ready()
        val existing = synchronized(lock) { id?.let { stored[it] } }
        require(id == null || existing != null) { "Connection no longer exists" }
        val key = tunnelKey(existing, baseUrl, apiKey)
        var remembered = false
        (tunnelConnections ?: throw ProtocolFailure("Tunnel discovery unavailable")).watch(baseUrl, key, allowCleartext).collect { state ->
            var output = state
            if (!remembered && state.phase == TunnelDiscoveryPhase.Connected) {
                remembered = true
                try { recentTunnel.save(RecentTunnel(baseUrl, key)) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { output = state.copy(notice = TunnelDiscoveryNotice.RecentGatewaySaveFailed) }
            }
            emit(output)
        }
    }
    override suspend fun discoverTunnel(id: String?, baseUrl: String, apiKey: String?, allowCleartext: Boolean): List<TunnelProtocol.Server> {
        ready()
        val existing = synchronized(lock) { id?.let { stored[it] } }
        require(id == null || existing != null) { "Connection no longer exists" }
        val key = tunnelKey(existing, baseUrl, apiKey)
        val servers = (tunnelConnections ?: throw ProtocolFailure("Tunnel discovery unavailable")).discover(baseUrl, key, allowCleartext)
        recentTunnel.save(RecentTunnel(baseUrl, key))
        return servers
    }
    override suspend fun saveTunnelConnection(id: String?, name: String, baseUrl: String, apiKey: String?, serverId: String,
        serverName: String, password: String?, allowCleartext: Boolean): String {
        ready()
        return writes.withLock {
            val key = id ?: UUID.randomUUID().toString()
            val old = synchronized(lock) { stored[key] }
            require(id == null || old != null) { "Connection no longer exists" }
            val value = StoredConnection(key, name.trim().ifEmpty { serverName.trim().ifEmpty { "Desktop" } }, "",
                password ?: old?.password.orEmpty(), allowCleartext, old?.enabled ?: true, ConnectionMode.Tunnel,
                baseUrl, tunnelKey(old, baseUrl, apiKey), serverId, serverName).validated()
            val duplicate = synchronized(lock) { stored.values.any { other ->
                other.id != key && other.mode == ConnectionMode.Tunnel && other.tunnelServerId == value.tunnelServerId &&
                    runCatching { TunnelProtocol.endpoint(other.tunnelBaseUrl).webSocketUrl }.getOrNull() == TunnelProtocol.endpoint(value.tunnelBaseUrl).webSocketUrl
            } }
            if (duplicate) throw DuplicateTunnelConnection()
            persistConnection(value)
            try { recentTunnel.save(RecentTunnel(value.tunnelBaseUrl, value.tunnelApiKey)) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { _storageError.value = RemoteProblem.RecentGatewaySaveFailed }
            key
        }
    }
    private suspend fun persistConnection(value: StoredConnection) {
            val previous = synchronized(lock) { stored[value.id] }
            if (previous != null && (previous.mode != value.mode || previous.address != value.address ||
                    previous.tunnelBaseUrl != value.tunnelBaseUrl || previous.tunnelServerId != value.tunnelServerId)) {
                writeRevokingPush(listOf(value.id), forget = true) { vault.upsert(value) }
            } else {
                storageWrite { vault.upsert(value) }
            }
            synchronized(lock) {
                invalidate(value.id)
                stored[value.id] = value
                skillUsage.remove(value.id)
                _capabilities.value -= value.id
                _projects.value = _projects.value - value.id; rawSessions = rawSessions - value.id
                threads.filterKeys { it.first == value.id }.values.forEach {
                    it.deleted = false
                    it.unreadRun = null
                    it.writingRun = null
                    it.readThrough = null
                    it.readEcho = null
                    it.pendingUnreadRepair = null
                    it.viewedAt = null
                    it.state.value = RemoteThread(value.id, it.state.value.sessionId,
                        outcomeUnknown = it.state.value.outcomeUnknown, error = it.state.value.error)
                }
                if (value.enabled) wanted.add(value.id) else wanted.remove(value.id)
                publish()
                phase(value.id, ConnectionPhase.Disconnected)
                if (foreground && value.enabled) start(value.id)
            }
    }
    private suspend fun storageWrite(block: suspend () -> Unit) {
        try { block(); _storageError.value = null }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            _storageError.value = RemoteProblem.StorageWriteFailed
            throw IllegalStateException("Connection storage write failed")
        }
    }
    override suspend fun removeConnection(id: String) {
        ready()
        writes.withLock {
            writeRevokingPush(listOf(id), forget = true) { vault.remove(id) }
            synchronized(lock) {
                wanted.remove(id); invalidate(id); stored.remove(id)
                skillUsage.remove(id)
                _capabilities.value -= id
                projectUnknown.remove(id); projectNeedsRefresh.remove(id)
                _projects.value = _projects.value - id; rawSessions = rawSessions - id
                threads.filterKeys { it.first == id }.values.forEach {
                    it.deleted = true
                    it.removedFollowUps.clear()
                    it.state.value = it.state.value.copy(entries = emptyList(), subAgents = emptyList(), runningTools = emptyMap(), running = false, isBuildingSystemPrompt = false, loading = false, error = RemoteProblem.ConnectionRemoved)
                }
                threads.keys.removeAll { it.first == id }
                viewers.keys.removeAll { it.first == id }
                publish()
            }
        }
    }
    private fun invalidate(id: String) {
        _runningSessions.value -= id
        lifetimes.remove(id)?.let { life ->
            life.mutations.keys.forEach { session ->
                markUnknown(thread(id, session))
            }
            life.viewReads.values.forEach { it.job?.cancel() }; life.viewReads.clear()
            life.job?.cancel(); life.rpc?.close()
        }
        threads.filterKeys { it.first == id }.values.forEach {
            it.scheduledReload?.cancel(); it.scheduledReload = null
            it.unreadJob?.cancel(); it.unreadJob = null; it.writingUnreadAt = null
            it.overlay = null
            it.state.value = it.state.value.copy(loading = false, isBuildingSystemPrompt = false)
        }
    }
    private suspend fun setConnectionEnabled(id: String, enabled: Boolean) {
        ready()
        writes.withLock {
            val old = synchronized(lock) { stored[id] ?: error("Connection no longer exists") }
            val value = old.copy(enabled = enabled)
            if (old.enabled != enabled) {
                if (enabled) storageWrite { vault.upsert(value) }
                else writeRevokingPush(listOf(id), forget = false) { vault.upsert(value) }
            }
            synchronized(lock) {
                stored[id] = value
                if (enabled) wanted.add(id) else { wanted.remove(id); invalidate(id); phase(id, ConnectionPhase.Disconnected) }
                publish()
                if (enabled && foreground) start(id)
            }
        }
    }
    override suspend fun connect(id: String) = setConnectionEnabled(id, true)
    override suspend fun disconnect(id: String) = setConnectionEnabled(id, false)
    override suspend fun reorderConnections(ids: List<String>) {
        ready()
        writes.withLock {
            val ordered = synchronized(lock) {
                require(ids.size == stored.size && ids.toSet() == stored.keys)
                ids.map { stored.getValue(it) }
            }
            storageWrite { vault.replaceAll(ordered) }
            synchronized(lock) { stored.clear(); ordered.forEach { stored[it.id] = it }; publish() }
        }
    }
    override suspend fun disconnectAll() {
        ready()
        writes.withLock {
            val updated = synchronized(lock) { stored.values.map { it.copy(enabled = false) } }
            if (updated.isEmpty()) return@withLock
            writeRevokingPush(updated.map { it.id }, forget = false) { vault.replaceAll(updated) }
            synchronized(lock) {
                updated.forEach { stored[it.id] = it; wanted.remove(it.id); invalidate(it.id); phase(it.id, ConnectionPhase.Disconnected) }
                publish()
            }
        }
    }
    // Fail closed before saving a disabled, removed, or replaced connection. A pending
    // handshake must not recreate the just-retired route while the vault write is in flight.
    private suspend fun writeRevokingPush(ids: List<String>, forget: Boolean, write: suspend () -> Unit) {
        val subscriptions = push ?: return storageWrite(write)
        withContext(NonCancellable) {
            synchronized(lock) { revokingPush.addAll(ids) }
            try {
                coroutineScope { ids.forEach { launch { detachPush(it) } } }
                storageWrite(write)
                if (forget) ids.forEach(subscriptions::forget)
            } catch (error: Exception) {
                // The old desktop remains enabled if persistence failed. Redial and reconcile
                // the pending revocation and replacement binding on a fresh socket.
                synchronized(lock) {
                    revokingPush.removeAll(ids.toSet())
                    ids.forEach { id ->
                        invalidate(id)
                        if (foreground && stored[id]?.enabled == true) start(id)
                    }
                }
                throw error
            } finally {
                synchronized(lock) { revokingPush.removeAll(ids.toSet()) }
            }
        }
    }

    // Stop registration before unregistering over the same ordered socket. A sleeping desktop
    // may miss revocation. The next enabled connection always reconciles the stored intent.
    private suspend fun detachPush(id: String) {
        val subscriptions = push ?: return
        val life = synchronized(lock) { lifetimes[id] }
        life?.pushJob?.cancelAndJoin()
        subscriptions.retire(id)
        if (life == null) return
        val rpc = life.rpc ?: return
        try {
            subscriptions.unregister(id) { method, args -> rpc.call(method, args, timeoutMillis = 2_000) }
        } catch (_: TimeoutCancellationException) { currentCoroutineContext().ensureActive() }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { /* Keep revocation pending for a future connection. */ }
    }

    override suspend fun prepareNotificationTarget(id: String): Boolean {
        if (!initialized.await()) return false
        return synchronized(lock) {
            if (stored[id]?.enabled != true) return@synchronized false
            if (_connections.value.find { it.id == id }?.phase != ConnectionPhase.AuthenticationRequired) start(id)
            true
        }
    }

    override fun onForeground() {
        synchronized(lock) {
            if (foreground) return
            foreground = true
            wanted.toList().forEach { id ->
                // Bad credentials require explicit connect/edit, not an automatic wake retry.
                if (_connections.value.find { it.id == id }?.phase != ConnectionPhase.AuthenticationRequired) start(id)
            }
        }
    }
    override fun onBackground() {
        synchronized(lock) {
            foreground = false
            lifetimes.keys.toList().forEach { id -> invalidate(id); phase(id, ConnectionPhase.Disconnected) }
        }
    }
    private fun start(id: String) {
        if (!foreground || lifetimes.containsKey(id)) return
        val config = stored[id]?.takeIf { it.enabled } ?: return
        val life = Lifetime(id)
        lifetimes[id] = life
        life.job = scope.launch {
            var tunnel: TunnelConnectionHandle? = null
            try {
                if (config.mode == ConnectionMode.Tunnel) {
                    synchronized(lock) { if (current(life)) phase(id, ConnectionPhase.Connecting) }
                    tunnel = (tunnelConnections ?: throw ProtocolFailure("Tunnel connection provider unavailable")).acquire(config)
                }
                var attempt = 0
                while (tunnel != null || attempt <= 3) {
                    var rpc: DesktopRpcClient? = null
                    var events: Job? = null
                    var metrics: Job? = null
                    try {
                        config.validated()
                        synchronized(lock) { if (!current(life)) return@launch; phase(id, ConnectionPhase.Connecting) }
                        tunnel?.awaitReady()
                        rpc = DesktopRpcClient(tunnel?.transport() ?: transportFactory(config.address))
                        synchronized(lock) {
                            if (!current(life)) { rpc.close(); return@launch }
                            life.rpc = rpc; life.runs.clear(); life.runTimes.clear(); life.retired.clear()
                        }
                        val active = rpc
                        // Exactly one application-level consumer, irrespective of UI subscriptions.
                        events = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            try { active.events.collect { consume(life, active, it) } }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { /* terminal cause is handled by the owner */ }
                        }
                        rpc.connect(config.password)
                        synchronized(lock) { if (current(life, rpc)) phase(id, ConnectionPhase.Connected) }
                        synchronized(lock) {
                            if (current(life, active) && id !in revokingPush) {
                                life.pushJob = push?.let { subscriptions -> launch {
                                    subscriptions.synchronize(id) { method, args -> active.call(method, args, timeoutMillis = 5_000) }
                                } }
                            }
                        }
                        metrics = launch {
                            for (ignored in life.metricSignals) {
                                try {
                                    val result = active.call("session:queryMetrics", listOf(buildJsonObject { put("topSkillsLimit", 10000) }))
                                    synchronized(lock) { if (current(life, active)) skillUsage.merge(id, parseSkillUsage(result)) }
                                } catch (_: TimeoutCancellationException) { currentCoroutineContext().ensureActive() }
                                catch (e: CancellationException) { throw e }
                                catch (_: Exception) { /* The next save or resync requests fresh counts. */ }
                                delay(1000)
                            }
                        }
                        refreshLifetime(life, rpc)
                        throw rpc.awaitTermination()
                    } catch (e: CancellationException) {
                        if (e !is TimeoutCancellationException) throw e
                        synchronized(lock) { if (current(life)) phase(id, ConnectionPhase.Failed, RemoteProblem.ConnectionTimeout) }
                    } catch (e: Exception) {
                        val auth = e is HttpFailure && e.statusCode in listOf(401, 403) || e is WebSocketCloseFailure && e.authenticationExpired || e is TunnelApiKeyRevoked
                        synchronized(lock) { if (current(life)) phase(id, if (auth) ConnectionPhase.AuthenticationRequired else ConnectionPhase.Failed,
                            if (auth) (if (config.mode == ConnectionMode.Tunnel) RemoteProblem.TunnelCredentials else RemoteProblem.DesktopPassword) else RemoteProblem.DesktopUnavailable) }
                        if (auth || e is IllegalArgumentException || e is ProtocolFailure) break
                    } finally {
                        life.pushJob?.cancel(); life.pushJob = null
                        metrics?.cancel(); events?.cancel(); rpc?.close()
                        synchronized(lock) {
                            if (current(life, rpc)) {
                                life.mutations.keys.forEach { markUnknown(thread(id, it)) }
                                life.rpc = null
                            }
                        }
                    }
                    if (tunnel != null) delay(minOf(20_000L, 1_000L shl minOf(attempt, 5)))
                    else if (attempt < 3) delay(listOf(1_000L, 2_000L, 4_000L)[attempt])
                    attempt = minOf(attempt + 1, 100)
                }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                synchronized(lock) { if (current(life)) phase(id, ConnectionPhase.Failed, RemoteProblem.ConnectionSetupFailed) }
            } finally {
                tunnel?.close()
                synchronized(lock) { if (lifetimes[id] === life) lifetimes.remove(id) }
            }
        }
    }
    private fun active(id: String): Pair<Lifetime, DesktopRpcClient> = synchronized(lock) {
        val life = lifetimes[id] ?: throw Disconnected()
        if (_connections.value.find { it.id == id }?.phase != ConnectionPhase.Connected) throw Disconnected()
        life to (life.rpc ?: throw Disconnected())
    }
    override suspend fun refresh(id: String) { ready(); val (life, rpc) = active(id); refreshLifetime(life, rpc) }
    private suspend fun refreshLifetime(life: Lifetime, rpc: DesktopRpcClient) = life.refresh.withLock {
        synchronized(lock) {
            if (!current(life, rpc)) return@withLock
            life.metricSignals.trySend(Unit)
            life.runTouched.clear(); life.projectTouched = false; life.metaTouched.clear()
        }
        suspend fun read(method: String, args: List<JsonElement> = emptyList(), apply: (JsonElement) -> Unit) {
            try {
                val result = rpc.call(method, args)
                synchronized(lock) { if (current(life, rpc)) apply(result) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { synchronized(lock) { if (current(life, rpc)) phase(life.id, ConnectionPhase.Connected, RemoteProblem.PartialRefresh) } }
        }
        val syncedRuns = mutableMapOf<String, Pair<UnreadRun, Double?>>()
        val interrupted = mutableMapOf<String, Pair<UnreadRun, Long>>()
        read("session:getActiveRuns") { result ->
            require(result is JsonArray) { "Invalid active runs" }
            val runs = result.objects().associateBy { it.str("sessionId") }
            _runningSessions.value[life.id].orEmpty().filter { it !in runs && it !in life.runTouched }.forEach { setHomeRunning(life.id, it, false) }
            threads.filterKeys { it.first == life.id }.forEach { (key, value) ->
                if (key.second !in life.runTouched && key.second !in runs) {
                    value.state.value = value.state.value.copy(running = false, isBuildingSystemPrompt = false, runningTools = emptyMap())
                    value.unreadRun?.takeIf { !it.idle }?.let { run ->
                        interrupted[key.second] = run to value.viewEpoch
                    }
                }
            }
            for ((id, data) in runs) {
                if (id == null || id in life.runTouched) continue
                runStatus(life, data)
                threads[life.id to id]?.unreadRun?.takeIf { it.id == data.str("runId") }?.let { run ->
                    syncedRuns[id] = run to (data["updatedAt"] as? JsonPrimitive)?.doubleOrNull
                }
            }
        }
        read("kvstore:get", listOf(JsonPrimitive("projects"))) {
            if (!life.projectTouched) { life.projectRevision++; _projects.value += (life.id to parseProjects(it)) }
        }
        read("session:listSessions") { result ->
            require(result is JsonArray) { "Invalid session list" }
            val fresh = result.objects().mapNotNull(::parseSession).associateBy { it.id }.toMutableMap()
            life.metaTouched.forEach { id -> fresh.remove(id); rawSessions[life.id]?.find { it.id == id }?.let { fresh[id] = it } }
            rawSessions += (life.id to fresh.values.toList())
            for ((id, pair) in syncedRuns) {
                val (run, time) = pair
                val session = fresh[id] ?: continue
                if (threads[life.id to id]?.unreadRun === run && run.baseline == 0.0) {
                    run.baseline = minOf(session.updatedAt.toDouble(), time?.takeIf { it > 0 } ?: Double.MAX_VALUE)
                    run.initialUnread = session.unread
                }
            }
            interrupted.forEach { (id, snapshot) ->
                val (run, epoch) = snapshot
                val store = threads[life.id to id]
                if (id in fresh && id !in life.runTouched && store?.unreadRun === run && !run.idle) {
                    run.idle = true
                    val remoteState = fresh.getValue(id).unread
                    if (remoteState != run.initialUnread &&
                        ((remoteState == "error" && !run.completed) || (remoteState == "unread" && !run.error))) run.settled = true
                    run.viewEpoch = epoch
                    run.suppressed = viewers[life.id to id] != null
                }
            }
            for ((id, session) in fresh) {
                val store = threads[life.id to id] ?: continue
                val watermark = store.readThrough
                if (watermark != null && session.unread != "read" && session.updatedAt > watermark &&
                    !store.state.value.running && viewers[life.id to id] == null && id !in life.viewReads) {
                    // A rename and a new reply both advance updatedAt while this client is offline.
                    // The reload below checks the entries before flushing the old read.
                    store.readEcho = ReadEcho(watermark, session)
                    store.readThrough = null
                } else syncViewed(life, rpc, id)
            }
        }
        read("kvstore:get", listOf(JsonPrimitive("cachedModels"))) {
            if (it is JsonArray) _capabilities.value += (life.id to (_capabilities.value[life.id] ?: RemoteCapabilities()).copy(models = parseRemoteModels(it)))
        }
        read("kvstore:get", listOf(JsonPrimitive("app-state"))) {
            if (it is JsonObject) _capabilities.value += (life.id to (_capabilities.value[life.id] ?: RemoteCapabilities()).copy(defaults = parseRemoteDefaults(it), defaultsLoaded = true))
        }
        val ids = synchronized(lock) { threads.keys.filter { it.first == life.id }.map { it.second } }
        ids.forEach { session ->
            reload(life, rpc, session)
            synchronized(lock) { if (current(life, rpc)) verifyUnread(life, rpc, session) }
        }
    }
    private fun markUnknown(store: ThreadStore) {
        store.unknownNeedsRefresh = true
        store.unknownRevision++
        store.state.value = store.state.value.copy(outcomeUnknown = true,
            error = RemoteProblem.OutcomeUnknown)
    }
    override suspend fun acknowledgeOutcome(connectionId: String, sessionId: String) {
        ready()
        synchronized(lock) {
            val store = thread(connectionId, sessionId)
            check(!store.state.value.loading && !store.unknownNeedsRefresh && !store.deleted) {
                "请先成功刷新会话并核对操作结果"
            }
            store.state.value = store.state.value.copy(outcomeUnknown = false, error = null)
        }
    }
    override suspend fun openThread(connectionId: String, sessionId: String) {
        ready(); val (life, rpc) = active(connectionId); reload(life, rpc, sessionId)
    }
    // Persistence strips the pending flag; activation gives the user entry a new timestamp.
    private fun reconcileFollowUp(stored: RemoteEntry, local: RemoteEntry?, running: Boolean): RemoteEntry =
        if (running && stored.kind == "user" && local != null && local.pendingStatus in setOf("queue", "steer") &&
            local.details.createdAt != null && local.details.createdAt == stored.details.createdAt) local else stored

    private suspend fun reload(life: Lifetime, rpc: DesktopRpcClient, session: String) {
        val store = synchronized(lock) {
            if (!current(life, rpc)) return
            thread(life.id, session)
        }
        store.reload.withLock {
            val (revision, echo, removedAtStart) = synchronized(lock) {
                if (!current(life, rpc) || store.deleted) return@withLock
                store.overlay = linkedMapOf()
                if (store.state.value.outcomeUnknown) store.unknownNeedsRefresh = true
                store.state.value = store.state.value.copy(loading = true, error = null)
                Triple(store.unknownRevision, store.readEcho, store.removedFollowUps.toSet())
            }
            try {
                val snapshot = rpc.call("session:get", listOf(JsonPrimitive(session)))
                synchronized(lock) {
                    if (!current(life, rpc) || store.deleted) return@synchronized
                    val obj = snapshot as? JsonObject ?: error(if (snapshot == JsonNull) "Session not found" else "Invalid session snapshot")
                    val array = obj["entries"] as? JsonArray ?: error("Invalid session entries")
                    val entries = array.map { item ->
                        parseEntry(item as? JsonObject ?: error("Invalid entry")) ?: error("Missing entry id")
                    }
                    val merged = linkedMapOf<String, RemoteEntry>()
                    val running = store.state.value.running
                    val current = store.state.value.entries.associateBy { it.id }
                    // An older snapshot cannot acknowledge a removal that happened in flight.
                    if (removedAtStart.isNotEmpty()) store.removedFollowUps.removeAll(
                        removedAtStart - entries.filter { it.kind == "user" }.map { it.id }.toSet()
                    )
                    entries.filterNot { it.kind == "user" && it.id in store.removedFollowUps }.forEach {
                        merged[it.id] = reconcileFollowUp(it, current[it.id], running)
                    }
                    if (running) current.values.filter { it.pendingStatus != null && it.id !in merged }.forEach { merged[it.id] = it }
                    store.overlay?.forEach { (id, entry) -> if (running || entry.pendingStatus == null) merged[id] = entry }
                    store.edit?.let { edit ->
                        val confirmed = editChain(edit).filter { it.confirmedEntry != null }
                        val dropped = confirmed.flatMap { it.droppedIds }.toSet()
                        dropped.forEach(merged::remove)
                        val retained = linkedMapOf<String, RemoteEntry>()
                        confirmed.asReversed().forEach { fence -> fence.confirmedEntry?.takeUnless { it.id in dropped }?.let { retained[it.id] = it } }
                        retained.forEach { (id, entry) -> merged[id] = entry }
                        if (edit.confirmedEntry != null && entries.none { it.id in dropped } && retained.values.all { edited ->
                                entries.any { it.id == edited.id && it.text == edited.text && it.skillReferences == edited.skillReferences }
                            }) store.edit = null
                    }
                    store.state.value = store.state.value.copy(entries = merged.values.toList(), loading = false,
                        error = null, subAgents = mergeSubAgents(store.state.value.subAgents, parseSubAgents(obj["subAgents"]).filter { it.parentSessionId == session }, snapshot = true))
                    if (echo != null && store.readEcho === echo && !store.state.value.running) {
                        reconcileReadEcho(life, rpc, store, echo, entries)
                    }
                    if (revision == store.unknownRevision) store.unknownNeedsRefresh = false
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { synchronized(lock) { if (current(life, rpc)) store.state.value = store.state.value.copy(error = RemoteProblem.SessionLoadFailed) } }
            finally { synchronized(lock) { if (current(life, rpc)) { store.overlay = null; store.state.value = store.state.value.copy(loading = false) } } }
        }
    }
    private fun reconcileReadEcho(life: Lifetime, rpc: DesktopRpcClient, store: ThreadStore, echo: ReadEcho, entries: List<RemoteEntry>) {
        val session = rawSessions[life.id].orEmpty().find { it.id == echo.incoming.id } ?: return
        val outcome = classifyUnreadOutcome(entries, echo.viewedAt.toDouble())
        val latestActivity = entries.filter(RemoteEntry::isConversationActivity)
            .maxOfOrNull { maxOf(it.details.completedAt ?: 0.0, it.details.createdAt ?: 0.0) } ?: 0.0
        if (outcome != UnreadOutcome.Incomplete && latestActivity > echo.viewedAt) {
            val state = when (outcome) {
                UnreadOutcome.Completed -> "unread"
                UnreadOutcome.Error -> "error"
                else -> echo.incoming.unread
            }
            val updated = maxOf(session.updatedAt, latestActivity.toLong())
            store.readEcho = null
            store.readThrough = null
            store.viewedAt = null
            if (session.unread != state || session.updatedAt < updated || session.id in life.viewReads)
                store.pendingUnreadRepair = session.copy(unread = state, updatedAt = updated)
        } else if (session.unread != "read") {
            // The peer may publish metadata before its new entries reach SQLite.
            // Retain the watch for entries-updated or the next authoritative resync.
            store.readThrough = session.updatedAt
        }
        syncViewed(life, rpc, session.id)
    }

    override suspend fun createSession(connectionId: String, projectId: String?, title: String): String {
        ready(); val (life, rpc) = active(connectionId)
        val id = UUID.randomUUID().toString()
        val meta = buildJsonObject {
            put("id", id); put("title", title); put("pinned", false); put("workLocation", "local")
            put("createdAt", System.currentTimeMillis()); put("updatedAt", System.currentTimeMillis())
            projectId?.let { put("projectId", it) }
        }
        mutation(life, rpc, id, "session:setMeta", meta)
        return id
    }
    override suspend fun createConfiguredSession(connectionId: String, sessionId: String, title: String, options: RemoteNewSessionOptions) {
        require(UUID.fromString(sessionId).toString() == sessionId)
        require(title.isNotBlank() && options.workLocation in setOf("local", "worktree"))
        require(options.speed in setOf("standard", "fast"))
        ready(); val (life, rpc) = active(connectionId)
        val meta = synchronized(lock) {
            require(rawSessions[connectionId].orEmpty().none { it.id == sessionId }) { "Session already exists" }
            if (options.projectId != null) require(_projects.value[connectionId].orEmpty().any { it.id == options.projectId }) { "Project no longer exists" }
            val caps = _capabilities.value[connectionId] ?: RemoteCapabilities()
            val model = options.modelId ?: caps.preferredModel()?.id
            require(caps.models.isEmpty() || model != null) { "No enabled model" }
            val reasoning = options.reasoningEffort ?: model?.let { caps.preferredReasoning(it) ?: "off" }
            val speed = if (options.speed == "fast" && caps.availableModels.any { it.id == model && it.supportsFast }) "fast" else "standard"
            if (model != null) validateCapabilities(caps, model, reasoning, speed)
            buildJsonObject {
                put("id", sessionId); put("title", title); put("pinned", false); put("workLocation", options.workLocation)
                put("createdAt", System.currentTimeMillis()); put("updatedAt", System.currentTimeMillis())
                options.projectId?.let { put("projectId", it) }
                options.branch?.takeIf { it.isNotBlank() }?.let { put("branch", it) }
                model?.let { put("modelId", it) }; reasoning?.let { put("reasoningEffort", it) }; put("speed", speed)
            }
        }
        mutation(life, rpc, sessionId, "session:setMeta", meta)
        synchronized(lock) {
            if (!current(life, rpc)) throw Disconnected()
            // Make the first delegateRun carry these settings even when no broadcast precedes the ACK.
            if (rawSessions[connectionId].orEmpty().none { it.id == sessionId }) {
                parseSession(meta)?.let { rawSessions += (connectionId to (rawSessions[connectionId].orEmpty() + it)) }
            }
        }
    }
    override suspend fun send(connectionId: String, sessionId: String, text: String, modelId: String?) {
        sendPrompt(connectionId, sessionId, text, modelId, emptyList())
    }
    override suspend fun sendWithAttachments(connectionId: String, sessionId: String, text: String, images: List<LocalAttachment>) {
        sendPrompt(connectionId, sessionId, text, null, images)
    }
    override suspend fun editMessage(connectionId: String, sessionId: String, entryId: String, text: String, references: List<SkillReference>) {
        ready(); require(text.isNotBlank()); require(validSkillReferences(text, references) == references)
        val (life, rpc) = active(connectionId)
        val pending = synchronized(lock) {
            val store = thread(connectionId, sessionId)
            require(!store.state.value.running && !store.state.value.loading && !store.state.value.outcomeUnknown && !store.state.value.editAwaitingStart) { "Session is busy or uncertain" }
            val entries = store.state.value.entries
            val index = entries.indexOfFirst { it.id == entryId && it.kind == "user" && it.pendingStatus == null }
            require(index >= 0) { "Message to edit was not found" }
            PendingEdit(entryId, text, references, entries.drop(index + 1).map { it.id }.toSet() + store.edit?.droppedIds.orEmpty(), life.runs[sessionId], store.edit).also {
                store.edit = it
                store.state.value = store.state.value.copy(editAwaitingStart = true)
            }
        }
        try {
            sendPrompt(connectionId, sessionId, text, null, emptyList(), references, edit = pending)
            // The desktop acknowledgement means queued/accepted. Only a corresponding user-entry proves start.
            withTimeout(20_000) { pending.started.await() }
        } catch (e: Exception) {
            synchronized(lock) {
                val store = thread(connectionId, sessionId)
                if (store.edit === pending && connectionId in stored) {
                    if (current(life, rpc) && (e is IllegalArgumentException || e.message == "Remote request rejected") && !pending.started.isCompleted) store.edit = pending.previous
                    else markUnknown(store)
                }
            }
            throw e
        } finally {
            synchronized(lock) {
                val store = thread(connectionId, sessionId)
                if (store.edit === pending || store.edit === pending.previous || pending.started.isCompleted) store.state.value = store.state.value.copy(editAwaitingStart = false)
            }
        }
    }
    override suspend fun sendWithSkills(connectionId: String, sessionId: String, text: String, references: List<SkillReference>, images: List<LocalAttachment>) {
        sendPrompt(connectionId, sessionId, text, null, images, references)
    }
    override suspend fun sendFollowUp(connectionId: String, sessionId: String, text: String, references: List<SkillReference>, images: List<LocalAttachment>) {
        sendPrompt(connectionId, sessionId, text, null, images, references)
    }
    private suspend fun sendPrompt(connectionId: String, sessionId: String, text: String, modelId: String?, images: List<LocalAttachment>, references: List<SkillReference> = emptyList(), edit: PendingEdit? = null) {
        require(validSkillReferences(text, references) == references) { "Invalid skill reference positions" }
        ready(); require(text.isNotBlank() || images.isNotEmpty()); val (life, rpc) = active(connectionId)
        val request = synchronized(lock) {
            check(edit != null || !thread(connectionId, sessionId).state.value.editAwaitingStart) { "Wait for the edited message to start" }
            val session = rawSessions[connectionId]?.find { it.id == sessionId }
            val caps = _capabilities.value[connectionId] ?: RemoteCapabilities()
            val selected = modelId ?: session?.modelId ?: caps.preferredModel()?.id
            require(caps.models.isEmpty() || selected != null) { "No enabled model available" }
            if (modelId != null) validateRuntime(connectionId, sessionId, modelId, null, null)
            if (images.isNotEmpty()) require(caps.availableModels.any { it.id == selected && it.supportsImages }) { "Selected model does not support images" }
            val choices = caps.reasoningChoices(selected)
            val reasoning = if (selected == null) null else session?.reasoningEffort
                ?.takeIf { level -> choices.any { it.id == level } } ?: caps.preferredReasoning(selected) ?: "off"
            val speed = (session?.speed ?: caps.defaults.speed).takeIf {
                it == "standard" || it == "fast" && caps.models.find { m -> m.id == selected }?.supportsFast == true
            } ?: "standard"
            if (selected != null) validateCapabilities(caps, selected, reasoning, speed)
            buildJsonObject {
            put("requestId", "${if (edit == null) "req" else "edit"}-${UUID.randomUUID()}"); put("sessionId", sessionId); put("text", text)
            put("userEntryId", edit?.entryId ?: UUID.randomUUID().toString());
            edit?.let { put("editMessageId", it.entryId) }; put("userEntryCreatedAt", System.currentTimeMillis())
            put("projectId", rawSessions[connectionId]?.find { it.id == sessionId }?.projectId?.let(::JsonPrimitive) ?: JsonNull)
            put("branch", rawSessions[connectionId]?.find { it.id == sessionId }?.branch?.let(::JsonPrimitive) ?: JsonNull)
            put("skillReferences", JsonArray(references.map { reference -> buildJsonObject {
                put("start", reference.start); put("name", reference.name)
            } }))
            selected?.let { put("modelId", it) }
            reasoning?.let { put("reasoningEffort", it) }
            put("speed", speed)
        } }
        require(images.sumOf { it.size } <= MAX_ATTACHMENT_BYTES) { "单次图片合计不能超过25 MiB" }
        val encoded = if (images.isEmpty()) emptyList() else withContext(Dispatchers.IO) {
            images.map { image ->
                require(image.image)
                val bytes = image.checkedFile().readBytes()
                buildJsonObject {
                    put("data", java.util.Base64.getEncoder().encodeToString(bytes)); put("mimeType", image.mimeType); put("name", image.name)
                }
            }
        }
        val payload = if (encoded.isEmpty()) request else JsonObject(request + ("images" to JsonArray(encoded)))
        val followUpEntry = synchronized(lock) {
            check(current(life, rpc)) { "Connection changed before sending" }
            val store = thread(connectionId, sessionId)
            check(!store.deleted && !store.state.value.outcomeUnknown) { "Session unavailable or uncertain" }
            if (edit != null || !store.state.value.running) null else RemoteEntry(
                requireNotNull(request.str("userEntryId")), "user", text,
                images = encoded.map { RemoteSentImage(requireNotNull(it.str("data")), requireNotNull(it.str("mimeType"))) },
                skillReferences = references, pendingStatus = "pending",
                details = RemoteEntryDetails(createdAt = request["userEntryCreatedAt"]?.jsonPrimitive?.doubleOrNull)
            ).also {
                store.state.value = store.state.value.copy(entries = store.state.value.entries + it)
                store.overlay?.set(it.id, it)
            }
        }
        synchronized(lock) { if (!thread(connectionId, sessionId).state.value.running) thread(connectionId, sessionId).compactRequested = false }
        try {
            if (images.isEmpty()) mutation(life, rpc, sessionId, "session:delegateRun", payload)
            else withContext(Dispatchers.Default) { mutation(life, rpc, sessionId, "session:delegateRun", payload) }
        } catch (failure: Exception) {
            synchronized(lock) {
                val store = threads[connectionId to sessionId]
                if (followUpEntry != null && store != null && !store.deleted) {
                    val status = store.state.value.entries.find { it.id == followUpEntry.id }?.pendingStatus
                    if (failure.message == "Remote request rejected") {
                        if (status == "pending") {
                            store.overlay?.remove(followUpEntry.id)
                            store.state.value = store.state.value.copy(entries = store.state.value.entries.filterNot { it.id == followUpEntry.id })
                        }
                    } else if (status != null) markUnknown(store)
                }
            }
            throw failure
        }
    }
    override suspend fun uploadAttachments(connectionId: String, sessionId: String, files: List<LocalAttachment>): List<String> {
        if (files.isEmpty()) return emptyList()
        ready(); val (life, rpc) = active(connectionId)
        return attachmentWrites.withLock {
            withContext(Dispatchers.IO) { files.forEach { require(!it.image); it.checkedFile() } }
            val info = rpc.call("native:runtimeInfo") as? JsonObject ?: error("Missing runtime info")
            val home = info.str("homeDir")?.takeIf { it.isNotBlank() } ?: error("Missing desktop home")
            val config = info.str("appConfigDirName") ?: if ((info["isDebug"] as? JsonPrimitive)?.booleanOrNull == true) ".coding-professional-agent-dev" else ".coding-professional-agent"
            val folder = "$home/${safeAttachmentName(config)}/attachments/${safeAttachmentName(sessionId)}"
            val paths = mutableListOf<String>()
            for (file in files) {
                val name = safeAttachmentName(file.name)
                var path = "$folder/$name"
                var copy = 1
                while (true) {
                    synchronized(lock) { if (!current(life, rpc)) throw Disconnected() }
                    val exists = rpc.call("native:fileExists", listOf(JsonPrimitive(path))) as? JsonPrimitive
                    when (exists?.booleanOrNull) {
                        false -> break
                        true -> { copy++; path = "$folder/${numberedAttachmentName(name, copy)}" }
                        null -> error("Invalid file existence response")
                    }
                }
                withContext(Dispatchers.IO) {
                    val data = java.util.Base64.getEncoder().encodeToString(file.checkedFile().readBytes())
                    mutation(life, rpc, sessionId, "native:writeFile", listOf(JsonPrimitive(path), JsonPrimitive(data)), 300_000)
                }
                paths += path
            }
            paths
        }
    }
    override suspend fun gitReview(connectionId: String, path: String, base: String?, target: String?): dev.cpa.android.data.repository.git.GitReview {
        require(path.isNotBlank())
        ready(); val (life, rpc) = active(connectionId)
        val loader = dev.cpa.android.data.repository.git.GitReviewLoader { args, cwd ->
            synchronized(lock) { if (!current(life, rpc)) throw Disconnected() }
            val request = buildJsonObject {
                put("operationId", "android-git-${UUID.randomUUID()}"); put("executable", "git")
                put("args", JsonArray(args.map(::JsonPrimitive))); put("cwd", cwd); put("env", JsonNull)
            }
            val result = rpc.call("native:runProcess", listOf(request)) as? JsonObject ?: error("Invalid Git response")
            synchronized(lock) { if (!current(life, rpc)) throw Disconnected() }
            val code = (result["exitCode"] as? JsonPrimitive)?.intOrNull ?: error("Missing exit code")
            val encoded = result.str("stdoutBase64") ?: error("Missing Git output")
            val output = java.util.Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8)
            dev.cpa.android.data.repository.git.GitResult(code, output)
        }
        val review = withContext(Dispatchers.Default) { loader.load(path, base, target) }
        synchronized(lock) { if (!current(life, rpc)) throw Disconnected() }
        return review
    }
    override suspend fun gitBranches(connectionId: String, path: String): RemoteBranches {
        require(path.isNotBlank())
        val request = buildJsonObject {
            put("operationId", "android-git-${UUID.randomUUID()}"); put("executable", "git")
            put("args", JsonArray(listOf("branch", "--format=%(HEAD)%(refname:short)").map(::JsonPrimitive)))
            put("cwd", path); put("env", JsonNull)
        }
        val result = readWorkspace(connectionId, "native:runProcess", listOf(request)) as? JsonObject ?: error("Invalid git result")
        if ((result["exitCode"] as? JsonPrimitive)?.intOrNull != 0) return RemoteBranches()
        val encoded = result.str("stdoutBase64") ?: return RemoteBranches()
        return parseBranches(java.util.Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8))
    }
    override suspend fun environmentProjects(connectionId: String): List<RemoteProject> {
        ready(); val (life, rpc) = active(connectionId)
        val projects = _projects.value[connectionId].orEmpty()
        val candidates = listOf(".cpa/environments/environment.toml", ".codex/environments/environment.toml", "environment.toml", ".cpa/environment.toml", ".codex/environment.toml")
        val permits = kotlinx.coroutines.sync.Semaphore(4)
        return coroutineScope {
            projects.map { project -> async {
                permits.acquire()
                try {
                    for (folder in project.allPaths) for (candidate in candidates) {
                        ensureActive()
                        synchronized(lock) { if (!current(life, rpc)) throw Disconnected() }
                        val file = try { rpc.call("native:readFileIfExists", listOf(JsonPrimitive("$folder/$candidate")), 10_000) }
                        catch (e: CancellationException) { throw e }
                        catch (_: RemoteRpcFailure) { null }
                        synchronized(lock) { if (!current(life, rpc)) throw Disconnected() }
                        if ((file as? JsonObject)?.get("dataBase64") is JsonPrimitive) return@async project
                    }
                    null
                } finally { permits.release() }
            } }.awaitAll().filterNotNull()
        }
    }
    override suspend fun forkSession(connectionId: String, sessionId: String, newSessionId: String, atEntryId: String?, worktree: RemoteWorktreeFork?) {
        require(newSessionId != sessionId && UUID.fromString(newSessionId).toString() == newSessionId)
        if (worktree != null) require(worktree.branch.isNotBlank())
        ready(); val (life, rpc) = active(connectionId)
        forkWrites.withLock {
            synchronized(lock) {
                if (!current(life, rpc)) throw Disconnected()
                check(!thread(connectionId, sessionId).state.value.outcomeUnknown) { "Source outcome unknown" }
            }
            val snapshot = rpc.call("session:get", listOf(JsonPrimitive(sessionId))) as? JsonObject ?: error("Invalid session")
            val sessions = rpc.call("session:listSessions") as? JsonArray ?: error("Invalid sessions")
            val source = sessions.objects().find { it.str("id") == sessionId && it.str("parentSessionId").isNullOrEmpty() }
                ?: error("Cannot fork a missing or sub-agent session")
            require(sessions.objects().none { it.str("id") == newSessionId }) { "Fork already exists" }
            if (worktree != null) require(_projects.value[connectionId].orEmpty().find { it.id == source.str("projectId") }?.allPaths?.isNotEmpty() == true) { "Project source path required" }
            val payload = withContext(Dispatchers.Default) { buildForkPayload(source, snapshot, newSessionId, atEntryId, worktree) }
            var copied = false
            try {
                // JSON encoding of a long conversation must not block the main/UI dispatcher.
                withContext(Dispatchers.Default) {
                    mutation(life, rpc, sessionId, "session:set", listOf(JsonPrimitive(newSessionId), payload.session), 60_000)
                }
                copied = true
                mutation(life, rpc, sessionId, "session:setMeta", payload.meta)
                synchronized(lock) {
                    if (!current(life, rpc)) throw Disconnected()
                    if (rawSessions[connectionId].orEmpty().none { it.id == newSessionId }) {
                        parseSession(payload.meta)?.let { rawSessions += (connectionId to (rawSessions[connectionId].orEmpty() + it)) }
                    }
                }
            } catch (e: Exception) {
                // A copied snapshot with missing metadata confirmation is a partial fork, never replay it.
                if (copied || e is CancellationException) synchronized(lock) { if (connectionId in stored) markUnknown(thread(connectionId, sessionId)) }
                throw e
            }
        }
    }
    override suspend fun loadSkills(connectionId: String, projectPath: String?): RemoteSkillCatalog {
        ready(); val (life, rpc) = active(connectionId)
        val result = scanRemoteSkills(projectPath) { method, args ->
            synchronized(lock) { if (!current(life, rpc)) throw Disconnected() }
            val value = rpc.call(method, args, timeoutMillis = 10_000)
            synchronized(lock) { if (!current(life, rpc)) throw Disconnected() }
            value
        }
        synchronized(lock) { if (!current(life, rpc)) throw Disconnected() }
        return RemoteSkillCatalog(result, skillUsage.state.value[connectionId].orEmpty())
    }
    private suspend fun readWorkspace(connectionId: String, method: String, args: List<JsonElement> = emptyList()): JsonElement {
        ready(); val (life, rpc) = active(connectionId)
        val result = rpc.call(method, args)
        synchronized(lock) { if (!current(life, rpc)) throw Disconnected() }
        return result
    }
    override suspend fun readRemoteFile(connectionId: String, path: String): String {
        require(path.isNotBlank())
        ready(); val (life, rpc) = active(connectionId)
        val value = rpc.call("native:readFile", listOf(JsonPrimitive(path))) as? JsonObject ?: error("Invalid file")
        val encoded = value.str("dataBase64") ?: error("Missing file data")
        val decoded = withContext(Dispatchers.Default) { java.util.Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8) }
        synchronized(lock) { if (!current(life, rpc)) throw Disconnected() }
        return decoded
    }
    override suspend fun homeDirectory(connectionId: String): String =
        (readWorkspace(connectionId, "native:runtimeInfo") as? JsonObject)?.str("homeDir")?.takeIf { it.isNotBlank() } ?: "/"

    override suspend fun directoryEntries(connectionId: String, path: String): List<RemoteDirectoryEntry> {
        require(path.isNotBlank())
        val result = readWorkspace(connectionId, "native:readDir", listOf(JsonPrimitive(path)))
        return (result as? JsonArray ?: error("Invalid directory")).mapNotNull { entry ->
            val item = entry as? JsonObject ?: return@mapNotNull null
            if ((item["isSymbolicLink"] as? JsonPrimitive)?.booleanOrNull == true) return@mapNotNull null
            val name = item.str("name") ?: return@mapNotNull null
            RemoteDirectoryEntry(name, (item["isDir"] as? JsonPrimitive)?.booleanOrNull == true)
        }.sortedWith(compareByDescending<RemoteDirectoryEntry> { it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }
    override suspend fun refreshProjects(connectionId: String) {
        ready(); val (life, rpc) = active(connectionId)
        // Serialize with writes so an earlier refresh cannot unlock a later uncertain write.
        projectWrites.withLock {
            val revision = synchronized(lock) { life.projectRevision }
            val result = rpc.call("kvstore:get", listOf(JsonPrimitive("projects")))
            check(result is JsonArray || result == JsonNull) { "Invalid projects" }
            synchronized(lock) {
                if (!current(life, rpc)) throw Disconnected()
                if (revision == life.projectRevision) {
                    life.projectTouched = true; life.projectRevision++
                    _projects.value += (connectionId to parseProjects(result))
                }
                projectNeedsRefresh.remove(connectionId)
            }
        }
    }
    override suspend fun acknowledgeProjectOutcome(connectionId: String) {
        ready()
        synchronized(lock) {
            check(connectionId !in projectNeedsRefresh) { "Refresh projects first" }
            projectUnknown.remove(connectionId)
        }
    }
    override suspend fun updateProject(connectionId: String, projectId: String, name: String, paths: List<String>) {
        require(name.isNotBlank())
        val folders = paths.map(String::trim).filter(String::isNotEmpty).distinct()
        changeProject(connectionId, projectId, buildJsonObject {
            put("name", name.trim()); put("paths", JsonArray(folders.map(::JsonPrimitive)))
            put("path", folders.firstOrNull()?.let(::JsonPrimitive) ?: JsonNull)
        })
    }
    override suspend fun setProjectPinned(connectionId: String, projectId: String, pinned: Boolean) =
        changeProject(connectionId, projectId, buildJsonObject { put("pinned", pinned) })
    override suspend fun removeProject(connectionId: String, projectId: String) = changeProject(connectionId, projectId, null)

    /** Raw read/modify/write preserves fields unknown to Android; no local filesystem operations. */
    private suspend fun changeProject(connectionId: String, projectId: String, patch: JsonObject?) {
        ready(); val (life, rpc) = active(connectionId)
        projectWrites.withLock {
            synchronized(lock) {
                if (!current(life, rpc)) throw Disconnected()
                check(connectionId !in projectUnknown) { "Project outcome unknown" }
            }
            val stored = rpc.call("kvstore:get", listOf(JsonPrimitive("projects")))
            val list = if (stored == JsonNull) JsonArray(emptyList()) else stored as? JsonArray ?: error("Invalid projects")
            require(list.any { (it as? JsonObject)?.str("id") == projectId }) { "Project no longer exists" }
            val updated = JsonArray(list.mapNotNull { value ->
                val item = value as? JsonObject
                if (item?.str("id") != projectId) value
                else patch?.let { JsonObject(item + it + ("updatedAt" to JsonPrimitive(System.currentTimeMillis()))) }
            })
            var attempted = false
            try {
                if (patch == null) {
                    // Detach even archived sessions; removing a project never deletes conversations or files.
                    val sessions = rpc.call("session:listSessions") as? JsonArray ?: error("Invalid sessions")
                    for (item in sessions.objects().filter { it.str("projectId") == projectId }) {
                        val id = item.str("id") ?: continue
                        synchronized(lock) { if (!current(life, rpc)) throw Disconnected() }
                        attempted = true
                        applyMeta(life, rpc, id, buildJsonObject { put("id", id); put("projectId", JsonNull) })
                    }
                }
                val revision = synchronized(lock) {
                    if (!current(life, rpc)) throw Disconnected()
                    life.projectRevision
                }
                attempted = true
                rpc.call("kvstore:set", listOf(JsonPrimitive("projects"), updated))
                synchronized(lock) {
                    if (!current(life, rpc)) throw Disconnected()
                    if (revision == life.projectRevision) {
                        life.projectTouched = true; life.projectRevision++
                        _projects.value += (connectionId to parseProjects(updated))
                    }
                }
            } catch (e: Exception) {
                // Multi-step removal may have partially applied even if a later request was rejected.
                if (attempted) synchronized(lock) {
                    if (connectionId in this.stored) { projectUnknown.add(connectionId); projectNeedsRefresh.add(connectionId) }
                }
                throw e
            }
        }
    }
    override suspend fun searchSessions(connectionId: String, query: String): List<RemoteSearchResult> {
        ready(); val (life, rpc) = active(connectionId)
        val result = rpc.call("session:search", listOf(JsonPrimitive(query.trim()), JsonPrimitive(30)))
        synchronized(lock) { if (!current(life, rpc)) throw Disconnected() }
        return (result as? JsonArray ?: error("Invalid search results")).mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = item.str("sessionId") ?: return@mapNotNull null
            RemoteSearchResult(connectionId, id, item.str("title").orEmpty(),
                (item["updatedAt"] as? JsonPrimitive)?.doubleOrNull?.toLong() ?: 0,
                item.str("matchType") != "content", item.str("snippet"))
        }.take(30)
    }
    override suspend fun runAction(connectionId: String, sessionId: String, action: RemoteRunAction) {
        ready(); val (life, rpc) = active(connectionId)
        synchronized(lock) {
            val store = thread(connectionId, sessionId)
            check(!store.state.value.running && !store.state.value.loading) { "Session is busy" }
            if (action == RemoteRunAction.Resume) {
                check(rawSessions[connectionId].orEmpty().any { it.id == sessionId }) { "Top-level session required" }
                check(store.state.value.canResume()) { "Session is not resumable" }
            }
            check((life.mutations[sessionId] ?: 0) == 0) { "Operation pending" }
        }
        val request = buildJsonObject {
            put("requestId", "${action.wireValue}-${UUID.randomUUID()}")
            put("sessionId", sessionId)
            put("text", "")
            put("action", action.wireValue)
        }
        synchronized(lock) { thread(connectionId, sessionId).compactRequested = action == RemoteRunAction.Compact }
        try { mutation(life, rpc, sessionId, "session:delegateRun", request) }
        catch (failure: Exception) {
            if (failure.message == "Remote request rejected") synchronized(lock) { thread(connectionId, sessionId).compactRequested = false }
            throw failure
        }
        if (action == RemoteRunAction.Resume) synchronized(lock) {
            if (current(life, rpc)) thread(connectionId, sessionId).state.let { it.value = it.value.copy(wasStopped = false) }
        }
        // Authority comes from run events and snapshots; never fabricate an assistant entry.
        openThread(connectionId, sessionId)
    }
    override suspend fun stop(connectionId: String, sessionId: String) {
        ready(); val (life, rpc) = active(connectionId)
        val run = synchronized(lock) {
            thread(connectionId, sessionId).unreadRun?.aborted = true
            life.runs[sessionId]
        }
        mutation(life, rpc, sessionId, "session:abortRun", JsonPrimitive(sessionId))
        synchronized(lock) {
            if (current(life, rpc) && life.runs[sessionId] == run) {
                val store = thread(connectionId, sessionId)
                if (!store.deleted) store.state.value = store.state.value.copy(wasStopped = true)
            }
        }
    }
    private suspend fun mutation(life: Lifetime, rpc: DesktopRpcClient, session: String, method: String, argument: JsonElement): JsonElement =
        mutation(life, rpc, session, method, listOf(argument))
    private suspend fun mutation(life: Lifetime, rpc: DesktopRpcClient, session: String, method: String, args: List<JsonElement>, timeoutMillis: Long = 30_000): JsonElement {
        synchronized(lock) {
            if (!current(life, rpc)) throw Disconnected()
            check(!thread(life.id, session).state.value.outcomeUnknown) { "请先刷新并确认上次操作结果" }
            life.mutations[session] = (life.mutations[session] ?: 0) + 1
        }
        try {
            val result = rpc.call(method, args, timeoutMillis) // Never replay, even on timeout or reconnect.
            synchronized(lock) {
                if (!current(life, rpc)) throw IllegalStateException("Remote result unknown: connection changed")
            }
            return result
        } catch (e: Exception) {
            val unknown = e !is RemoteRpcFailure
            synchronized(lock) {
                if (current(life, rpc)) {
                    val store = thread(life.id, session)
                    if (unknown) markUnknown(store)
                    else store.state.value = store.state.value.copy(error = RemoteProblem.RequestRejected)
                }
            }
            if (e is CancellationException) throw e
            throw IllegalStateException(if (unknown) "Remote result unknown" else "Remote request rejected")
        } finally {
            synchronized(lock) {
                val remaining = (life.mutations[session] ?: 1) - 1
                if (remaining == 0) life.mutations.remove(session) else life.mutations[session] = remaining
            }
        }
    }
    override suspend fun callSessionRpc(connectionId: String, sessionId: String, method: String, args: List<JsonElement>, mutation: Boolean): JsonElement {
        val writing = when (method) {
            "ask:getRequests" -> false
            "ask:submitAnswer", "ask:cancelCountdown" -> true
            else -> throw IllegalArgumentException("RPC method not allowed")
        }
        require(mutation == writing) { "Incorrect RPC mutation flag" }
        if (writing) {
            require(args.size == if (method == "ask:submitAnswer") 3 else 2)
            require(args[0] == JsonPrimitive(sessionId) && (args[1] as? JsonPrimitive)?.isString == true)
            require(args[1].jsonPrimitive.content.isNotBlank())
            if (method == "ask:submitAnswer") require(args[2] is JsonObject)
        } else {
            require(args.size == 1 && (args[0] as? JsonObject)?.get("sessionId") == JsonPrimitive(sessionId))
        }
        ready(); val (life, rpc) = active(connectionId)
        if (writing) return this.mutation(life, rpc, sessionId, method, args)
        synchronized(lock) { if (!current(life, rpc)) throw Disconnected() }
        val result = rpc.call(method, args)
        synchronized(lock) { if (!current(life, rpc)) throw Disconnected() }
        return result
    }
    private fun validateCapabilities(caps: RemoteCapabilities, modelId: String, reasoning: String?, speed: String?) {
        val model = caps.availableModels.find { it.id == modelId }
        require(model != null) { "Model unavailable; refresh desktop capabilities" }
        if (reasoning != null) require(caps.reasoningChoices(modelId).any { it.id == reasoning } || (reasoning == "off" && caps.reasoningChoices(modelId).isEmpty())) { "Unsupported reasoning level" }
        if (speed != null) require(speed == "standard" || speed == "fast" && model.supportsFast) { "Unsupported speed" }
    }
    private fun validateRuntime(connectionId: String, sessionId: String, modelId: String?, reasoning: String?, speed: String?) {
        val session = rawSessions[connectionId]?.find { it.id == sessionId }
        val caps = _capabilities.value[connectionId] ?: RemoteCapabilities()
        val selected = modelId ?: session?.modelId ?: caps.preferredModel()?.id
        check(!thread(connectionId, sessionId).state.value.running ||
            ((modelId == null || modelId == session?.modelId) && (speed == null || speed == (session?.speed ?: "standard")))) { "Cannot change model or speed while running" }
        require(selected != null) { "No model available" }
        validateCapabilities(caps, selected, reasoning, speed)
    }
    override suspend fun setSessionRuntime(connectionId: String, sessionId: String, modelId: String?, reasoningEffort: String?, speed: String?) {
        ready(); val (life, rpc) = active(connectionId)
        val patch = synchronized(lock) {
            val previous = rawSessions[connectionId]?.find { it.id == sessionId }
            val caps = _capabilities.value[connectionId] ?: RemoteCapabilities()
            val switching = modelId != null && modelId != previous?.modelId
            val effectiveReasoning = reasoningEffort ?: if (switching) {
                previous?.reasoningEffort?.takeIf { level -> caps.reasoningChoices(modelId).any { it.id == level } }
                    ?: caps.preferredReasoning(modelId) ?: "off"
            } else null
            val effectiveSpeed = speed ?: if (switching) {
                (previous?.speed ?: caps.defaults.speed).takeIf { it == "standard" || caps.models.find { m -> m.id == modelId }?.supportsFast == true } ?: "standard"
            } else null
            validateRuntime(connectionId, sessionId, modelId, effectiveReasoning, effectiveSpeed)
            buildJsonObject {
                put("id", sessionId)
                modelId?.let { put("modelId", it) }
                effectiveReasoning?.let { put("reasoningEffort", it) }
                effectiveSpeed?.let { put("speed", it) }
            }
        }
        if (patch.size > 1) applyMeta(life, rpc, sessionId, patch)
    }
    private suspend fun setMeta(connectionId: String, sessionId: String, fields: JsonObject, keepsUpdatedAt: Boolean = false) {
        ready(); val (life, rpc) = active(connectionId)
        val patch = synchronized(lock) { buildJsonObject {
            put("id", sessionId); fields.forEach { (key, value) -> put(key, value) }
            if (keepsUpdatedAt) rawSessions[connectionId]?.find { it.id == sessionId }?.updatedAt?.takeIf { it > 0 }?.let { put("updatedAt", it) }
        } }
        applyMeta(life, rpc, sessionId, patch)
    }
    /** Ack is enough to project the fields written, but must not overwrite a newer event/snapshot. */
    private suspend fun applyMeta(life: Lifetime, rpc: DesktopRpcClient, sessionId: String, patch: JsonObject) {
        val before = synchronized(lock) { rawSessions[life.id]?.find { it.id == sessionId } }
        mutation(life, rpc, sessionId, "session:setMeta", patch)
        synchronized(lock) {
            if (!current(life, rpc)) return
            val items = rawSessions[life.id].orEmpty()
            val latest = items.find { it.id == sessionId }
            if (before == null || latest !== before) return
            val updated = before.copy(
                title = patch.str("title") ?: before.title,
                projectId = if (patch.containsKey("projectId")) patch.str("projectId") else before.projectId,
                modelId = patch.str("modelId") ?: before.modelId,
                reasoningEffort = patch.str("reasoningEffort") ?: before.reasoningEffort,
                speed = patch.str("speed") ?: before.speed,
                pinned = (patch["pinned"] as? JsonPrimitive)?.booleanOrNull ?: before.pinned,
                unread = (patch["unread"] as? JsonPrimitive)?.booleanOrNull?.let { if (it) "unread" else "read" } ?: before.unread,
                updatedAt = (patch["updatedAt"] as? JsonPrimitive)?.longOrNull ?: before.updatedAt,
                archivedAt = (patch["archivedAt"] as? JsonPrimitive)?.longOrNull ?: before.archivedAt,
            )
            life.metaTouched.add(sessionId)
            rawSessions += (life.id to items.map { if (it.id == sessionId) updated else it }
                .filter { (it.archivedAt ?: 0) <= 0 })
        }
    }
    override suspend fun renameSession(connectionId: String, sessionId: String, title: String) {
        require(title.isNotBlank())
        setMeta(connectionId, sessionId, buildJsonObject { put("title", title.trim()) })
    }
    override suspend fun setSessionPinned(connectionId: String, sessionId: String, pinned: Boolean) =
        setMeta(connectionId, sessionId, buildJsonObject { put("pinned", pinned) })
    override suspend fun archiveSession(connectionId: String, sessionId: String) =
        setMeta(connectionId, sessionId, buildJsonObject { put("archivedAt", System.currentTimeMillis()) }, true)
    override suspend fun moveSession(connectionId: String, sessionId: String, projectId: String?) =
        setMeta(connectionId, sessionId, buildJsonObject { put("projectId", projectId?.let(::JsonPrimitive) ?: JsonNull) })
    override fun beginViewing(connectionId: String, sessionId: String) = synchronized(lock) {
        val key = connectionId to sessionId
        val store = thread(connectionId, sessionId)
        store.viewEpoch++
        val displayed = rawSessions[connectionId].orEmpty().find { it.id == sessionId && (it.unread != "read" || store.manualMark != null) }
        listOfNotNull(store.writingUnreadAt, store.unreadRun?.pendingWrite?.updatedAt, displayed?.updatedAt, store.pendingUnreadRepair?.updatedAt).maxOrNull()?.let {
            store.readThrough = maxOf(store.readThrough ?: it, it)
        }
        store.pendingUnreadRepair = null
        store.unreadRun?.takeIf { it.pendingWrite != null }?.let { it.pendingWrite = null; it.settled = true }
        store.readEcho = null
        viewers[key] = (viewers[key] ?: 0) + 1
        publishSessionReads()
        lifetimes[connectionId]?.let { life -> life.rpc?.let { syncViewed(life, it, sessionId) } }
        Unit
    }
    override fun endViewing(connectionId: String, sessionId: String) = synchronized(lock) {
        val key = connectionId to sessionId
        val count = (viewers[key] ?: 0) - 1
        if (count > 0) viewers[key] = count else viewers.remove(key)
        publishSessionReads()
        Unit
    }
    // Entry persistence can advance the SQLite row after its last meta-updated broadcast.
    // Reading a cached timestamp and writing it back would move the conversation backwards.
    private suspend fun readSortTime(rpc: DesktopRpcClient, sessionId: String): Long? {
        val rows = rpc.call("session:listSessions") as? JsonArray ?: error("Invalid session catalog")
        val row = rows.objects().find { it.str("id") == sessionId } ?: error("Session missing")
        return (row["updatedAt"] as? JsonPrimitive)?.longOrNull
    }

    /** Idempotent read-state sync must never make prompt delivery unknown or replay a prompt. */
    private fun syncViewed(life: Lifetime, rpc: DesktopRpcClient, sessionId: String) {
        val store = threads[life.id to sessionId] ?: return
        if (!current(life, rpc) || sessionId in life.viewReads || store.unreadJob != null || store.manualMark != null) return
        val before = rawSessions[life.id].orEmpty().find { it.id == sessionId } ?: return
        if (before.updatedAt > (store.readThrough ?: Long.MAX_VALUE)) store.readThrough = null
        if (before.updatedAt > (store.pendingUnreadRepair?.updatedAt ?: Long.MAX_VALUE)) store.pendingUnreadRepair = null
        val retry = store.pendingUnreadRepair
        if (retry == null && (before.unread == "read" || (viewers[life.id to sessionId] == null && store.readThrough == null))) return
        val runAtRead = store.unreadRun
        val epochAtRead = store.viewEpoch
        val read = ViewRead(before, runAtRead)
        life.viewReads[sessionId] = read
        read.job = scope.launch {
            var succeeded = false
            try {
                val repair = retry ?: run {
                    val serverTime = readSortTime(rpc, sessionId)
                    val updatedAt = synchronized(lock) {
                        if (!current(life, rpc) || store.deleted) return@launch
                        if (store.unreadRun !== runAtRead) {
                            succeeded = true // A new run may be waiting for this read slot.
                            return@launch
                        }
                        val latest = rawSessions[life.id].orEmpty().find { it.id == sessionId } ?: return@launch
                        if (latest === before) serverTime ?: before.updatedAt else latest.updatedAt
                    }
                    rpc.call("session:setMeta", listOf(buildJsonObject {
                        put("id", sessionId); put("unread", false)
                        if (updatedAt > 0) put("updatedAt", updatedAt)
                    }))
                    synchronized(lock) {
                        if (!current(life, rpc)) return@launch
                        val items = rawSessions[life.id].orEmpty()
                        if (items.find { it.id == sessionId } === before) {
                            rawSessions += (life.id to items.map { if (it.id == sessionId) it.copy(unread = "read", updatedAt = updatedAt) else it })
                        }
                        if ((store.readThrough ?: 0L) <= before.updatedAt) store.readThrough = null
                        if (store.unreadRun === runAtRead && store.viewEpoch == epochAtRead && read.newerUnread == null)
                            store.viewedAt = maxOf(store.viewedAt ?: 0, before.updatedAt)
                        read.newerUnread.takeIf { viewers[life.id to sessionId] == null }?.also { store.pendingUnreadRepair = it }
                    }
                }
                if (repair != null) {
                    rpc.call("session:setMeta", listOf(buildJsonObject {
                        put("id", sessionId)
                        put("unread", if (repair.unread == "error") JsonPrimitive("error") else JsonPrimitive(true))
                        if (repair.updatedAt > 0) put("updatedAt", repair.updatedAt)
                    }))
                    synchronized(lock) {
                        if (!current(life, rpc)) return@launch
                        if (store.pendingUnreadRepair === repair) {
                            store.pendingUnreadRepair = null
                            val items = rawSessions[life.id].orEmpty()
                            rawSessions += (life.id to items.map {
                                if (it.id == sessionId && it.updatedAt <= repair.updatedAt)
                                    it.copy(unread = repair.unread, updatedAt = repair.updatedAt) else it
                            })
                        }
                    }
                }
                succeeded = true
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Retry only on later metadata, refresh, or a new view, never a tight loop. */ }
            finally {
                synchronized(lock) {
                    if (life.viewReads[sessionId] === read) {
                        life.viewReads.remove(sessionId)
                        publishSessionReads()
                        if ((succeeded || life.rpc !== rpc) && current(life)) life.rpc?.let {
                            syncViewed(life, it, sessionId)
                            verifyUnread(life, it, sessionId)
                        }
                    }
                }
            }
        }
    }
    override suspend fun setSessionUnread(connectionId: String, sessionId: String, unread: Boolean) {
        ready()
        val (life, rpc) = active(connectionId)
        val store = synchronized(lock) { thread(connectionId, sessionId) }
        val (mark, outstanding) = synchronized(lock) {
            check(store.manualMark == null) { "Read state change pending" }
            store.viewEpoch++
            val mark = ManualMark(unread, store.unreadRun, store.viewEpoch)
            store.manualMark = mark
            store.readEcho = null
            store.viewedAt = null
            publishSessionReads()
            mark to listOfNotNull(store.unreadJob, life.viewReads[sessionId]?.job)
        }
        try {
            outstanding.joinAll()
            val before = synchronized(lock) { rawSessions[connectionId]?.find { it.id == sessionId } }
            val serverTime = readSortTime(rpc, sessionId)
            val patch = synchronized(lock) {
                if (!current(life, rpc) || store.deleted) throw Disconnected()
                if (store.unreadRun !== mark.run || store.viewEpoch != mark.epoch) return
                buildJsonObject {
                    put("id", sessionId); put("unread", unread)
                    val latest = rawSessions[connectionId]?.find { it.id == sessionId }
                    val time = if (latest === before) serverTime ?: before?.updatedAt else latest?.updatedAt
                    time?.takeIf { it > 0 }?.let { put("updatedAt", it) }
                }
            }
            applyMeta(life, rpc, sessionId, patch)
            synchronized(lock) {
                if (current(life, rpc) && store.unreadRun === mark.run && store.viewEpoch == mark.epoch) {
                    store.pendingUnreadRepair = null
                    store.readThrough = null
                    mark.run?.takeIf { it.idle }?.settled = true
                }
            }
        } finally {
            synchronized(lock) {
                if (store.manualMark === mark) {
                    store.manualMark = null
                    publishSessionReads()
                    life.rpc?.takeIf { current(life, it) }?.let {
                        syncViewed(life, it, sessionId)
                        verifyUnread(life, it, sessionId)
                    }
                }
            }
        }
    }
    private fun consume(life: Lifetime, rpc: DesktopRpcClient, event: DesktopEvent) = synchronized(lock) {
        if (!current(life, rpc)) return@synchronized
        val data = event.payload as? JsonObject
        when (event.kind) {
            "session:subagent-state" -> {
                for (record in parseSubAgents(data?.get("agents"))) {
                    val parent = thread(life.id, record.parentSessionId)
                    if (parent.deleted) continue
                    val previous = parent.state.value.subAgents.firstOrNull { it.id == record.id }
                    if (previous != null && previous.updatedAt > record.updatedAt) continue
                    parent.state.value = parent.state.value.copy(subAgents = mergeSubAgents(parent.state.value.subAgents, listOf(record), snapshot = false))
                    val child = threads[life.id to record.conversationId]
                    if (child != null && !child.deleted) {
                        child.state.value = child.state.value.copy(running = record.inProgress,
                            runningTools = if (record.inProgress) child.state.value.runningTools else emptyMap())
                        setHomeRunning(life.id, record.conversationId, record.inProgress)
                        if (!record.inProgress && (previous?.inProgress != false)) scheduleReload(life, rpc, record.conversationId, 800)
                    }
                }
            }
            "projects:updated" -> { life.projectTouched = true; life.projectRevision++; _projects.value += (life.id to parseProjects(event.payload)) }
            "session:meta-updated" -> if (data != null) {
                val id = data.str("id") ?: return@synchronized
                life.metaTouched.add(id)
                val list = rawSessions[life.id].orEmpty().filterNot { it.id == id }
                val store = threads[life.id to id]
                val parsed = parseSession(data)
                val previous = rawSessions[life.id].orEmpty().find { it.id == id }
                val staleWrite = store?.writingUnreadAt != null && store.writingRun !== store.unreadRun &&
                    parsed != null && parsed.updatedAt == store.writingUnreadAt && parsed.unread == (if (store.writingRun?.error == true) "error" else "unread")
                if (!staleWrite && parsed?.unread == "error" && previous?.unread != "error") {
                    store?.unreadRun?.takeIf { !it.idle && it.initialUnread != "error" }?.error = true
                }
                val incoming = if (staleWrite && previous != null) parsed?.copy(unread = previous.unread,
                    updatedAt = maxOf(previous.updatedAt, parsed.updatedAt)) else parsed
                val read = life.viewReads[id]
                if (read != null && incoming != null && incoming.unread != "read" &&
                    (incoming.updatedAt > (read.newerUnread?.updatedAt ?: read.before.updatedAt) ||
                        (store?.unreadRun !== read.run && incoming.updatedAt == read.before.updatedAt && !staleWrite))) {
                    read.newerUnread = incoming
                }
                if (store?.readThrough != null && store.writingUnreadAt != null &&
                    store.writingRun === store.unreadRun && incoming != null && incoming.unread != "read")
                    store.readThrough = maxOf(store.readThrough ?: 0, incoming.updatedAt)
                val viewedAt = store?.viewedAt
                if (incoming != null && incoming.unread != "read" && store != null && viewedAt != null &&
                    viewers[life.id to id] == null && read == null) {
                    if (previous != null && incoming.updatedAt > maxOf(previous.updatedAt, viewedAt)) {
                        store.readEcho = ReadEcho(viewedAt, incoming)
                        scheduleReload(life, rpc, id, 0)
                    } else if (incoming.updatedAt == previous?.updatedAt) {
                        // Manual peer marks keep their timestamp.
                        store.readEcho = null
                        store.viewedAt = null
                    }
                }
                val shown = if (incoming?.unread == "read" && viewers[life.id to id] == null) read?.newerUnread ?: incoming else incoming
                rawSessions += (life.id to (list + listOfNotNull(shown)))
                syncViewed(life, rpc, id)
            }
            "session:deleted" -> {
                val id = data?.str("sessionId") ?: return@synchronized
                life.metaTouched.add(id); life.runTouched.add(id); life.runs.remove(id)
                life.viewReads.remove(id)?.job?.cancel()
                viewers.remove(life.id to id)
                setHomeRunning(life.id, id, false)
                rawSessions += (life.id to rawSessions[life.id].orEmpty().filterNot { it.id == id })
                val store = threads[life.id to id] ?: return@synchronized; store.deleted = true
                store.removedFollowUps.clear()
                store.unreadJob?.cancel(); store.unreadJob = null; store.unreadRun = null
                store.state.value = store.state.value.copy(entries = emptyList(), subAgents = emptyList(), runningTools = emptyMap(), running = false, isBuildingSystemPrompt = false, loading = false, error = RemoteProblem.SessionDeleted)
            }
            "session:abort-run" -> {
                val id = data?.str("sessionId") ?: return@synchronized
                val store = threads[life.id to id] ?: return@synchronized
                if (!store.deleted) {
                    store.unreadRun?.aborted = true
                    store.state.value = store.state.value.copy(wasStopped = true)
                }
            }
            "session:run-status" -> if (data != null) {
                data.str("sessionId")?.let { life.runTouched.add(it) }
                runStatus(life, data)
            }
            "session:entries-updated" -> {
                life.metricSignals.trySend(Unit)
                val id = data?.str("sessionId") ?: return@synchronized
                if (threads.containsKey(life.id to id)) {
                    verifyUnread(life, rpc, id)
                    if (thread(life.id, id).state.value.running) scheduleUserRefresh(life, rpc, id)
                    else scheduleReload(life, rpc, id, 300)
                }
            }
            "session:stream-event" -> {
                val id = data?.str("sessionId") ?: return@synchronized
                val run = data.str("runId") ?: return@synchronized
                if (run in life.retired[id].orEmpty()) return@synchronized
                val body = data["event"] as? JsonObject ?: return@synchronized
                if (body["nested"] == JsonPrimitive(true) || body.str("parentToolCallId") != null) return@synchronized
                val active = life.runs[id]
                val startsRun = body.str("type") == "agent-start" && body.str("runId") == run && body.str("sessionId") == id
                if (active != null && active != run && !startsRun) return@synchronized
                if (startsRun) {
                    life.runTouched.add(id)
                    if (active != null && active != run) retire(life, id, active)
                }
                life.runs[id] = run
                val store = thread(life.id, id)
                if (startsRun) store.state.value = store.state.value.copy(running = true, runningTools = emptyMap(), wasStopped = false,
                    isBuildingSystemPrompt = active == run && store.state.value.isBuildingSystemPrompt)
                if (store.deleted) return@synchronized
                if (startsRun) setHomeRunning(life.id, id, true)
                val unread = trackUnreadRun(life, id, run)
                unread.consume(body)
                if (unread.idle) verifyUnread(life, rpc, id)
                val beforeTools = store.state.value
                val afterTools = beforeTools.withToolEvent(body)
                if (afterTools !== beforeTools) {
                    store.state.value = afterTools
                    if (body.str("type") == "tool-display-update") afterTools.entries.forEach { entry ->
                        if (beforeTools.entries.find { it.id == entry.id } != entry) store.overlay?.set(entry.id, entry)
                    }
                }
                when (body.str("type")) {
                    "follow-up-pending" -> {
                        val entryId = body.str("userEntryId") ?: return@synchronized
                        val status = body.str("pendingStatus")?.takeIf { it == "queue" || it == "steer" } ?: return@synchronized
                        val time = (body["createdAt"] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() } ?: return@synchronized
                        val entries = store.state.value.entries
                        val previous = entries.find { it.kind == "user" && it.id == entryId } ?: return@synchronized
                        val confirmed = previous.copy(pendingStatus = status, details = previous.details.copy(createdAt = time))
                        store.overlay?.set(entryId, confirmed)
                        store.state.value = store.state.value.copy(entries = entries.map { if (it === previous) confirmed else it })
                    }
                    "follow-up-removed" -> {
                        val entryId = body.str("userEntryId")?.takeIf { it.isNotEmpty() } ?: return@synchronized
                        store.removedFollowUps.add(entryId)
                        if (store.overlay?.get(entryId)?.kind == "user") store.overlay?.remove(entryId)
                        store.state.value = store.state.value.copy(entries = store.state.value.entries.filterNot { it.kind == "user" && it.id == entryId })
                    }
                }
                if (body.str("type") in setOf("user-entry", "assistant-start", "assistant-update", "assistant-end", "compaction-end", "tool-end")) {
                    val entry = (body["entry"] as? JsonObject)?.let(::parseEntry) ?: return@synchronized
                    if (entry.kind == "user" && entry.id in store.removedFollowUps) return@synchronized
                    val fences = editChain(store.edit)
                    val pending = if (body.str("type") == "user-entry") fences.firstOrNull {
                        entry.id == it.entryId && run != it.previousRun && entry.text == it.text && entry.skillReferences == it.references && entry.pendingStatus == null
                    } else null
                    if (pending != null) {
                        pending.confirmedEntry = entry
                        pending.started.complete(Unit)
                        store.state.value = store.state.value.copy(entries = store.state.value.entries.filterNot { it.id in pending.droppedIds },
                            editAwaitingStart = if (store.edit === pending) false else store.state.value.editAwaitingStart, running = true)
                        setHomeRunning(life.id, id, true)
                        pending.droppedIds.forEach { store.overlay?.remove(it) }
                    } else {
                        if (fences.any { it.confirmedEntry != null && entry.id in it.droppedIds }) return@synchronized
                        // A later desktop-side edit of a retained prompt must supersede our confirmed text.
                        if (body.str("type") == "user-entry" && entry.pendingStatus == null)
                            fences.firstOrNull { it.confirmedEntry != null && it.entryId == entry.id }?.confirmedEntry = entry
                    }
                    val entries = store.state.value.entries.associateByTo(linkedMapOf()) { it.id }
                    entries[entry.id] = entry; store.overlay?.set(entry.id, entry)
                    store.state.value = store.state.value.copy(entries = entries.values.toList())
                }
                if (body.str("type") == "error") store.state.value = store.state.value.copy(error = RemoteProblem.RunError)
            }
        }
    }
    private fun runStatus(life: Lifetime, data: JsonObject) {
        val id = data.str("sessionId") ?: return
        val run = data.str("runId") ?: return
        val status = data.str("status") ?: return
        if (run in life.retired[id].orEmpty()) return
        val timestamp = (data["updatedAt"] as? JsonPrimitive)?.doubleOrNull
        if (timestamp != null && timestamp < (life.runTimes[id] ?: Double.NEGATIVE_INFINITY)) return
        val previous = life.runs[id]
        if (status == "idle" && previous != null && previous != run) return
        if (previous != null && previous != run) retire(life, id, previous)
        // Keep the last idle run identity: late final entries are valid, prior runs are not.
        life.runs[id] = run
        if (timestamp != null) life.runTimes[id] = timestamp
        val store = thread(life.id, id)
        if (store.deleted) return
        store.state.value = store.state.value.copy(running = status != "idle", isBuildingSystemPrompt = status == "building-system-prompt",
            wasStopped = if (status != "idle" && previous != run) false else store.state.value.wasStopped, runningTools = if (status == "idle" || previous != run) emptyMap() else store.state.value.runningTools)
        setHomeRunning(life.id, id, status != "idle")
        val unread = if (status != "idle") trackUnreadRun(life, id, run, timestamp) else store.unreadRun?.takeIf { it.id == run }
        if (status != "idle") unread?.idle = false
        if (status == "error") unread?.error = true
        if (status == "idle" && unread != null && !unread.idle) {
            unread.idle = true
            unread.viewEpoch = store.viewEpoch
            unread.suppressed = viewers[life.id to id] != null
            unread.latestTimestamp = maxOf(unread.latestTimestamp, timestamp ?: 0.0)
        }
        if (status == "idle") life.rpc?.let { verifyUnread(life, it, id) }
        if (status == "idle") life.rpc?.let { rpc -> scheduleReload(life, rpc, id, 800) }
    }
    private fun trackUnreadRun(life: Lifetime, sessionId: String, runId: String, updatedAt: Double? = null): UnreadRun {
        val store = thread(life.id, sessionId)
        store.unreadRun?.takeIf { it.id == runId }?.let { return it }
        // Once sent, an old write may still echo. Keep its owner until its reply.
        if (store.writingUnreadAt == null) {
            store.unreadJob?.cancel()
            store.unreadJob = null
        }
        store.readThrough = null
        store.pendingUnreadRepair = null
        store.readEcho = null
        store.viewedAt = null
        val session = rawSessions[life.id].orEmpty().find { it.id == sessionId }
        val sessionTime = session?.updatedAt?.toDouble() ?: 0.0
        val baseline = if (sessionTime > 0 && updatedAt != null && updatedAt > 0) minOf(sessionTime, updatedAt) else sessionTime
        return UnreadRun(runId, baseline, store.compactRequested, session?.unread ?: "read").also {
            store.compactRequested = false
            store.unreadRun = it
        }
    }
    private fun verifyUnread(life: Lifetime, rpc: DesktopRpcClient, sessionId: String) {
        val store = thread(life.id, sessionId)
        val run = store.unreadRun ?: return
        if (!current(life, rpc) || store.deleted || !run.idle || run.settled || store.unreadJob != null || store.manualMark != null ||
            sessionId in life.viewReads || rawSessions[life.id].orEmpty().none { it.id == sessionId }) return
        // Child conversations are absent from the top-level session catalog; nested stream events are filtered earlier.
        val verification = scope.launch(start = CoroutineStart.LAZY) {
            try {
                while (true) {
                    var outcome = synchronized(lock) {
                        if (!current(life, rpc) || store.deleted || store.unreadRun !== run || !run.idle || run.settled) return@launch
                        run.pendingWrite?.outcome ?: run.outcome()
                    }
                    if (outcome == UnreadOutcome.Pending) {
                        val snapshot = rpc.call("session:get", listOf(JsonPrimitive(sessionId))) as? JsonObject ?: return@launch
                        val raw = snapshot["entries"] as? JsonArray ?: return@launch
                        val entries = raw.map { parseEntry(it as? JsonObject ?: return@launch) ?: return@launch }
                        outcome = classifyUnreadOutcome(entries, run.baseline)
                        synchronized(lock) {
                            if (!current(life, rpc) || store.unreadRun !== run) return@launch
                            run.latestTimestamp = maxOf(run.latestTimestamp, entries.maxOfOrNull { maxOf(it.details.createdAt ?: 0.0, it.details.completedAt ?: 0.0) } ?: 0.0)
                        }
                    }
                    val patch = synchronized(lock) {
                        if (!current(life, rpc) || store.deleted || store.unreadRun !== run || !run.idle || run.settled) return@launch
                        if (outcome == UnreadOutcome.Incomplete) { run.settled = true; return@launch }
                        if (outcome == UnreadOutcome.Pending) null else {
                            if (run.suppressed || viewers[life.id to sessionId] != null || run.viewEpoch != store.viewEpoch) {
                                store.viewedAt = maxOf(store.viewedAt ?: 0, run.latestTimestamp.toLong())
                                rawSessions += (life.id to rawSessions[life.id].orEmpty().map {
                                    if (it.id == sessionId && it.updatedAt < run.latestTimestamp)
                                        it.copy(updatedAt = run.latestTimestamp.toLong()) else it
                                })
                                run.settled = true; return@launch
                            }
                            val session = rawSessions[life.id].orEmpty().find { it.id == sessionId } ?: return@launch
                            val updatedAt = maxOf(session.updatedAt, run.latestTimestamp.toLong())
                            run.pendingWrite = UnreadWrite(outcome, updatedAt)
                            store.writingUnreadAt = updatedAt
                            store.writingRun = run
                            publishSessionReads()
                            buildJsonObject {
                                put("id", sessionId)
                                put("unread", if (outcome == UnreadOutcome.Error) JsonPrimitive("error") else JsonPrimitive(true))
                                put("updatedAt", updatedAt)
                            }
                        }
                    }
                    if (patch != null) {
                        rpc.call("session:setMeta", listOf(patch))
                        synchronized(lock) {
                            if (!current(life, rpc) || store.deleted || store.unreadRun !== run) return@launch
                            val state = if (outcome == UnreadOutcome.Error) "error" else "unread"
                            rawSessions += (life.id to rawSessions[life.id].orEmpty().map {
                                if (it.id == sessionId) it.copy(unread = state, updatedAt = maxOf(it.updatedAt, run.latestTimestamp.toLong())) else it
                            })
                            run.pendingWrite = null
                            run.settled = true
                        }
                        return@launch
                    }
                    if (synchronized(lock) { run.retries-- <= 0 }) return@launch
                    delay(120)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Leave passive verification for a later entries event or resync. */ }
            finally {
                val job = currentCoroutineContext()[Job]
                synchronized(lock) {
                    if (store.unreadJob === job) {
                        store.unreadJob = null
                        store.writingUnreadAt = null
                        store.writingRun = null
                        publishSessionReads()
                        if (current(life, rpc)) {
                            syncViewed(life, rpc, sessionId)
                            if (store.unreadRun !== run) verifyUnread(life, rpc, sessionId)
                        }
                    }
                }
            }
        }
        store.unreadJob = verification
        verification.start()
    }
    private fun retire(life: Lifetime, session: String, run: String) {
        val retired = life.retired.getOrPut(session) { linkedSetOf() }
        retired.add(run)
        while (retired.size > 64) retired.remove(retired.first())
    }
    /** At most one queued event-driven reload per thread; lifecycle invalidation cancels it. */
    // Queued/steer entries are persisted by the desktop without a stream user-entry event.
    // Refresh user entries only while running so a delayed persisted assistant cannot rewind live text.
    private fun scheduleUserRefresh(life: Lifetime, rpc: DesktopRpcClient, session: String) {
        val store = thread(life.id, session)
        store.scheduledReload?.cancel()
        store.scheduledReload = scope.launch {
            delay(300)
            store.reload.withLock {
                val before = synchronized(lock) {
                    if (!current(life, rpc) || store.deleted) return@withLock
                    store.state.value.entries.associateBy { it.id }
                }
                try {
                    val snapshot = rpc.call("session:get", listOf(JsonPrimitive(session))) as? JsonObject ?: return@withLock
                    val users = snapshot["entries"].objects().mapNotNull(::parseEntry).filter { it.kind == "user" }
                    synchronized(lock) {
                        if (!current(life, rpc) || store.deleted) return@synchronized
                        val entries = store.state.value.entries.associateByTo(linkedMapOf()) { it.id }
                        users.forEach { user ->
                            if (editChain(store.edit).any { it.confirmedEntry != null && (user.id in it.droppedIds || user.id == it.entryId) }) return@forEach
                            if (user.id !in store.removedFollowUps && entries[user.id] == before[user.id])
                                entries[user.id] = reconcileFollowUp(user, entries[user.id], store.state.value.running)
                        }
                        store.state.value = store.state.value.copy(entries = entries.values.toList())
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* The foreground snapshot/next persisted event can recover this read. */ }
            }
        }
    }
    private fun scheduleReload(life: Lifetime, rpc: DesktopRpcClient, session: String, wait: Long) {
        val store = thread(life.id, session)
        store.scheduledReload?.cancel()
        store.scheduledReload = scope.launch { delay(wait); reload(life, rpc, session) }
    }
    private fun parseProjects(value: JsonElement) = value.objects().mapNotNull { item ->
        item.str("id")?.let { RemoteProject(it, item.str("name") ?: "Project", item.str("path"),
            (item["paths"] as? JsonArray)?.mapNotNull { p -> (p as? JsonPrimitive)?.takeIf { it.isString }?.content }.orEmpty(),
            (item["pinned"] as? JsonPrimitive)?.booleanOrNull == true) }
    }
    private fun parseSession(item: JsonObject): RemoteSession? {
        val id = item.str("id") ?: return null
        if (!item.str("parentSessionId").isNullOrEmpty() || ((item["archivedAt"] as? JsonPrimitive)?.doubleOrNull ?: 0.0) > 0) return null
        return RemoteSession(id, item.str("title") ?: "Session", item.str("projectId"), item.str("branch"),
            item.str("modelId")?.takeIf { it.isNotBlank() }, item.str("reasoningEffort")?.takeIf { it.isNotBlank() }, item.str("speed")?.takeIf { it.isNotBlank() },
            (item["pinned"] as? JsonPrimitive)?.booleanOrNull == true,
            (item["archivedAt"] as? JsonPrimitive)?.longOrNull,
            when (val unread = item["unread"]) { JsonPrimitive(true) -> "unread"; is JsonPrimitive -> if (unread.isString) unread.content else "read"; else -> "read" },
            (item["updatedAt"] as? JsonPrimitive)?.longOrNull ?: 0,
            item.str("workLocation"), item.str("worktreePath"), item.str("environmentId"),
            (item["createdAt"] as? JsonPrimitive)?.longOrNull ?: 0)
    }
    private fun parseEntry(item: JsonObject): RemoteEntry? = parseRemoteEntry(item)
    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonElement?.objects(): List<JsonObject> = (this as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
}
