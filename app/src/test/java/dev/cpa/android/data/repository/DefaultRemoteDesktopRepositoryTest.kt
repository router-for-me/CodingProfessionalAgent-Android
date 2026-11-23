package dev.cpa.android.data.repository

import dev.cpa.android.core.network.*
import dev.cpa.android.core.network.tunnel.*
import dev.cpa.android.core.storage.*
import dev.cpa.android.core.push.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultRemoteDesktopRepositoryTest {
    private class Vault(var rows: List<StoredConnection> = emptyList()) : ConnectionVault {
        var fail = false
        override suspend fun load(): List<StoredConnection> { check(!fail); return rows }
        override suspend fun upsert(connection: StoredConnection) { check(!fail); rows = rows.filterNot { it.id == connection.id } + connection }
        override suspend fun replaceAll(connections: List<StoredConnection>) { check(!fail); rows = connections }
        override suspend fun remove(id: String) { check(!fail); rows = rows.filterNot { it.id == id } }
    }
    private class Wire : DesktopTransport {
        lateinit var listener: DesktopTransport.Listener
        val calls = mutableListOf<JsonObject>()
        var password: String? = null
        var closed = false
        var rejectAuth = false
        val held = mutableSetOf<String>()
        override suspend fun authenticate(password: String) { this.password = password; if (rejectAuth) throw HttpFailure(401) }
        override fun openSocket(listener: DesktopTransport.Listener): DesktopTransport.Socket {
            this.listener = listener
            listener.onText("""{"type":"hello","clientId":"fake"}""")
            return object : DesktopTransport.Socket {
                override fun close() = Unit
                override fun send(text: String): Boolean {
                    val call = Json.parseToJsonElement(text).jsonObject
                    calls.add(call)
                    val method = call["method"]!!.jsonPrimitive.content
                    if (method !in held) reply(call, when (method) {
                        "notification:registerClient", "notification:unregisterClient" -> """{"ok":true}"""
                        "session:getActiveRuns", "session:listSessions" -> "[]"
                        "kvstore:get" -> when (call["args"]!!.jsonArray[0].jsonPrimitive.content) {
                            "cachedModels" -> """[{"id":"model","label":"Test model","reasoningLevels":[]}]"""
                            "app-state" -> """{"settings":{"modelId":"model","speed":"standard"}}"""
                            else -> """[{"id":"p","name":"Project"}]"""
                        }
                        "session:get" -> """{"id":"s","entries":[]}"""
                        else -> "null"
                    })
                    return true
                }
            }
        }
        fun reply(call: JsonObject, result: String) = listener.onText("""{"type":"rpc_result","id":${call["id"]},"result":$result}""")
        fun event(kind: String, data: String) = listener.onText(buildJsonObject {
            put("type", "event"); put("event", buildJsonObject { put("kind", kind); put("data", data) })
        }.toString())
        fun run(id: String, status: String) = event("session:run-status", """{"sessionId":"s","runId":"$id","status":"$status"}""")
        fun entry(run: String, text: String) = event("session:stream-event", """{"sessionId":"s","runId":"$run","event":{"type":"assistant-update","entry":{"id":"a","kind":"assistant","content":[{"type":"text","text":"$text"}]}}}""")
        fun count(method: String) = calls.count { it["method"]?.jsonPrimitive?.content == method }
        override fun close() { closed = true }
    }
    private fun connection(id: String = "c", password: String = "secret") = StoredConnection(id, "Desktop", "https://desktop.example/", password, false)

    @Test fun pushDetachesBeforeSocketClosureAndRecoversFromFailedDisable() = runTest {
        val vault = Vault(listOf(connection()))
        val journal = DesktopPushSubscriptionsTest.Journal()
        val push = DesktopPushSubscriptions(journal)
        push.target.value = PushTarget.Registered("sEnSiTive")
        val wires = mutableListOf<Wire>()
        val repo = DefaultRemoteDesktopRepository(vault, backgroundScope, push = push) { Wire().also(wires::add) }
        runCurrent(); repo.onForeground(); runCurrent()
        assertTrue(repo.prepareNotificationTarget("c"))
        assertEquals(1, wires.single().count("notification:registerClient"))
        vault.fail = true
        assertTrue(runCatching { repo.disconnect("c") }.isFailure)
        runCurrent()
        assertTrue(repo.connections.value.single().enabled)
        assertEquals(1, wires.first().count("notification:unregisterClient"))
        assertEquals(1, wires.last().count("notification:registerClient"))
        vault.fail = false
        repo.disconnect("c")
        assertEquals(1, wires.last().count("notification:unregisterClient"))
        assertFalse(repo.prepareNotificationTarget("c"))
        assertTrue(journal.ids.isEmpty())
        repo.connect("c"); runCurrent()
        assertEquals(1, wires.last().count("notification:registerClient"))
        repo.onBackground(); runCurrent()
        repo.removeConnection("c")
        assertTrue(journal.ids.isEmpty())
        assertFalse(repo.prepareNotificationTarget("c"))
    }

    @Test fun offlineRemovalRequiresDurablePushRevocation() = runTest {
        val vault = Vault(listOf(connection()))
        val memory = DesktopPushSubscriptionsTest.Journal()
        memory.authorize("c")
        val commit = CompletableDeferred<Unit>()
        val journal = object : PushJournal by memory {
            override suspend fun persistRetirement(connection: String) {
                memory.retire(connection)
                commit.await()
            }
        }
        val repo = DefaultRemoteDesktopRepository(vault, backgroundScope, push = DesktopPushSubscriptions(journal))
        val removal = async { runCatching { repo.removeConnection("c") } }
        runCurrent()
        assertFalse(removal.isCompleted)
        assertEquals("c", vault.rows.single().id)
        commit.completeExceptionally(IllegalStateException("Write failed"))
        assertTrue(removal.await().isFailure)
        assertEquals("c", vault.rows.single().id)
    }

    @Test fun promptBuildEventsAreTransientAndKeepTheRunActive() = runTest {
        val wire = Wire()
        val repo = DefaultRemoteDesktopRepository(Vault(listOf(connection())), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent()
        val thread = repo.observeThread("c", "s")
        for (status in listOf("running", "building-system-prompt", "running", "building-system-prompt", "idle")) {
            wire.run("r", status); runCurrent()
            assertEquals(status != "idle", thread.value.running)
            assertEquals(status == "building-system-prompt", thread.value.isBuildingSystemPrompt)
            assertTrue(thread.value.entries.isEmpty())
        }
    }

    @Test fun activeRunSnapshotsRestoreAndClearBuildStateWithoutOverwritingConcurrentEvents() = runTest {
        val method = "session:getActiveRuns"
        val wire = Wire().apply { held.add(method) }
        val repo = DefaultRemoteDesktopRepository(Vault(listOf(connection())), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent()
        val thread = repo.observeThread("c", "s")
        fun snapshot(status: String?) {
            val result = if (status == null) "[]" else """[{"sessionId":"s","runId":"r","status":"$status"}]"""
            wire.reply(wire.calls.last { it["method"] == JsonPrimitive(method) }, result); runCurrent()
        }
        snapshot("building-system-prompt")
        assertTrue(thread.value.isBuildingSystemPrompt && thread.value.running)
        launch { repo.refresh("c") }; runCurrent(); snapshot("running")
        assertFalse(thread.value.isBuildingSystemPrompt); assertTrue(thread.value.running)
        launch { repo.refresh("c") }; runCurrent()
        wire.run("r", "building-system-prompt"); runCurrent(); snapshot("running")
        assertTrue(thread.value.isBuildingSystemPrompt)
        launch { repo.refresh("c") }; runCurrent(); snapshot(null)
        assertFalse(thread.value.isBuildingSystemPrompt); assertFalse(thread.value.running)
        assertTrue(thread.value.entries.isEmpty())
    }

    private fun tunnelConnection() = StoredConnection("t", "Tunnel", "", "desktop-password", false,
        mode = ConnectionMode.Tunnel, tunnelBaseUrl = "https://gateway.example", tunnelApiKey = "private-key", tunnelServerId = "pc")
    private class TunnelHandle : TunnelConnectionHandle {
        val wires = mutableListOf<Wire>()
        var closed = false
        var available = CompletableDeferred(Unit)
        var failure: Exception? = null
        override suspend fun awaitReady() { failure?.let { throw it }; available.await() }
        override fun transport(): DesktopTransport = Wire().also(wires::add)
        override fun close() { closed = true }
    }
    @Test fun skillMetricsCoalesceEntrySavesAndPreserveLocalUsage() = runTest {
        val wire = Wire().apply { held.add("session:queryMetrics") }
        val repo = DefaultRemoteDesktopRepository(Vault(listOf(connection())), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent()
        fun metrics() = wire.calls.filter { it["method"] == JsonPrimitive("session:queryMetrics") }
        assertEquals(JsonPrimitive(10000), metrics().single()["args"]!!.jsonArray.single().jsonObject["topSkillsLimit"])
        repeat(4) { wire.event("session:entries-updated", """{"sessionId":"unopened"}""") }
        runCurrent()
        wire.reply(metrics().single(), """{"summary":{"topSkills":[{"name":"Review","count":5}]}}""")
        runCurrent(); repo.skillUsage.sent("c", listOf(SkillReference(0, "review")))
        advanceTimeBy(999); runCurrent(); assertEquals(1, metrics().size)
        advanceTimeBy(1); runCurrent(); assertEquals(2, metrics().size)
        wire.reply(metrics().last(), """{"summary":{"topSkills":[{"name":"review","count":3},{"name":"edit","count":9}]}}""")
        runCurrent()
        assertEquals(mapOf("review" to 6L, "edit" to 9L), repo.skillUsage.state.value["c"])
        repo.removeConnection("c")
        assertTrue(repo.skillUsage.state.value.isEmpty())
    }
    @Test fun tunnelRetainsOuterLeaseAcrossRpcRetriesAndReleasesOnBackground() = runTest {
        val handle = TunnelHandle(); var acquired = 0
        val repo = DefaultRemoteDesktopRepository(Vault(listOf(tunnelConnection())), backgroundScope,
            tunnelConnections = TunnelConnectionProvider { acquired++; handle }) { error("Must not dial directly") }
        runCurrent(); repo.onForeground(); runCurrent()
        assertEquals(ConnectionPhase.Connected, repo.connections.value.single().phase)
        repeat(5) {
            handle.wires.last().listener.onClosed(NetworkFailure()); runCurrent()
            advanceTimeBy(20_001); runCurrent()
        }
        assertEquals(1, acquired); assertEquals(6, handle.wires.size); assertFalse(handle.closed)
        assertEquals("desktop-password", handle.wires.last().password)
        repo.onBackground(); runCurrent(); assertTrue(handle.closed)
        advanceTimeBy(60_000); runCurrent(); assertEquals(6, handle.wires.size)
    }
    @Test fun offlineTunnelWaitIsCancelledOnDisableWithoutOpeningRpc() = runTest {
        val handle = TunnelHandle().apply { available = CompletableDeferred() }
        val repo = DefaultRemoteDesktopRepository(Vault(listOf(tunnelConnection())), backgroundScope,
            tunnelConnections = TunnelConnectionProvider { handle })
        runCurrent(); repo.onForeground(); runCurrent()
        assertTrue(handle.wires.isEmpty())
        repo.disconnect("t"); runCurrent()
        assertTrue(handle.closed)
        handle.available.complete(Unit); runCurrent()
        assertTrue(handle.wires.isEmpty()); assertFalse(repo.connections.value.single().enabled)
    }
    @Test fun revokedGatewayRequiresExplicitRetryAndReleasesLease() = runTest {
        val handle = TunnelHandle().apply { failure = TunnelApiKeyRevoked() }; var acquired = 0
        val repo = DefaultRemoteDesktopRepository(Vault(listOf(tunnelConnection())), backgroundScope,
            tunnelConnections = TunnelConnectionProvider { acquired++; handle })
        runCurrent(); repo.onForeground(); runCurrent()
        assertEquals(ConnectionPhase.AuthenticationRequired, repo.connections.value.single().phase)
        assertTrue(handle.closed)
        repo.onBackground(); repo.onForeground(); advanceTimeBy(60_000); runCurrent()
        assertEquals(1, acquired); assertTrue(handle.wires.isEmpty())
    }
    @Test fun discoveryUsesStoredKeyWithoutSavingDraftOrDialingDesktop() = runTest {
        val vault = Vault(listOf(tunnelConnection().copy(enabled = false)))
        var received: String? = null
        val provider = object : TunnelConnectionProvider {
            override suspend fun acquire(configuration: StoredConnection): TunnelConnectionHandle = error("Unexpected connection")
            override suspend fun discover(baseUrl: String, apiKey: String, allowCleartext: Boolean): List<TunnelProtocol.Server> {
                received = apiKey
                return listOf(TunnelProtocol.Server("pc", "PC", TunnelProtocol.SERVER_TYPE))
            }
        }
        val repo = DefaultRemoteDesktopRepository(vault, backgroundScope, tunnelConnections = provider); runCurrent()
        val original = vault.rows
        assertEquals("pc", repo.discoverTunnel("t", "https://gateway.example", null, false).single().uuid)
        assertEquals("private-key", received)
        repo.discoverTunnel("t", "https://gateway.example", "replacement", false)
        assertEquals("replacement", received); assertEquals(original, vault.rows)
    }
    @Test fun recentGatewayKeyIsReusedOnlyForMatchingAddressAndNeverInSummary() = runTest {
        val recent = MemoryRecentTunnelStore()
        val vault = Vault()
        var usedKey: String? = null
        val provider = object : TunnelConnectionProvider {
            override suspend fun acquire(configuration: StoredConnection): TunnelConnectionHandle = error("unexpected")
            override suspend fun discover(baseUrl: String, apiKey: String, allowCleartext: Boolean): List<TunnelProtocol.Server> {
                TunnelProtocol.apiKey(apiKey)
                usedKey = apiKey; return emptyList()
            }
        }
        val repo = DefaultRemoteDesktopRepository(vault, backgroundScope, recentTunnel = recent, tunnelConnections = provider); runCurrent()
        repo.discoverTunnel(null, "https://gateway.test", "remember-me", false)
        val summary = repo.recentTunnelSummary()!!
        assertEquals("https://gateway.test", summary.baseUrl); assertTrue(summary.hasApiKey)
        assertFalse(summary.toString().contains("remember-me"))
        repo.discoverTunnel(null, "wss://gateway.test/v1/tunnel", null, false)
        assertEquals("remember-me", usedKey)
        assertTrue(runCatching { repo.discoverTunnel(null, "https://other.test", null, false) }.isFailure)
        repo.saveTunnelConnection(null, "PC", "https://gateway.test", null, "pc", "PC", "pw", false)
        assertEquals("remember-me", vault.rows.single().tunnelApiKey)
    }
    @Test fun recentPreferenceFailureDoesNotUndoSuccessfullySavedConnection() = runTest {
        val recent = object : RecentTunnelStore {
            override suspend fun load(): RecentTunnel? = null
            override suspend fun save(value: RecentTunnel) { throw RecentTunnelStoreException() }
        }
        val vault = Vault(); val repo = DefaultRemoteDesktopRepository(vault, backgroundScope, recentTunnel = recent); runCurrent()
        val id = repo.saveTunnelConnection(null, "PC", "https://gateway.test", "key", "pc", "PC", "pw", false)
        assertEquals(id, vault.rows.single().id)
        assertEquals(id, repo.connections.value.single().id)
        assertEquals(RemoteProblem.RecentGatewaySaveFailed, repo.storageError.value)
    }
    @Test fun duplicateTunnelTargetIsRejectedAcrossNormalizedGatewayAliases() = runTest {
        val vault = Vault(listOf(tunnelConnection().copy(enabled = false)))
        val repo = DefaultRemoteDesktopRepository(vault, backgroundScope); runCurrent()
        val duplicate = runCatching { repo.saveTunnelConnection(null, "duplicate", "wss://gateway.example/v1/tunnel", "different-key", "pc", "PC", "", false) }
        assertTrue(duplicate.exceptionOrNull() is DuplicateTunnelConnection)
        assertEquals(1, vault.rows.size)
        repo.saveTunnelConnection("t", "edited", "https://gateway.example/v1", null, "pc", "PC", null, false)
        assertEquals(1, vault.rows.size)
        repo.saveTunnelConnection(null, "other target", "https://gateway.example", "key", "pc2", "PC2", "", false)
        assertEquals(2, vault.rows.size)
    }
    @Test fun saveTunnelPreservesOmittedSecretsAndDisabledPreferenceWithoutPublishingKey() = runTest {
        val vault = Vault(listOf(tunnelConnection().copy(enabled = false)))
        val repo = DefaultRemoteDesktopRepository(vault, backgroundScope); runCurrent()
        repo.saveTunnelConnection("t", "renamed", "wss://gateway.example/v1/tunnel", null, "pc2", "PC2", null, false)
        assertEquals("private-key", vault.rows.single().tunnelApiKey)
        assertEquals("desktop-password", vault.rows.single().password)
        val summary = repo.connections.value.single()
        assertFalse(summary.enabled); assertEquals(ConnectionMode.Tunnel, summary.mode)
        assertEquals("pc2", summary.tunnelServerId); assertEquals("https://gateway.example", summary.address)
        assertFalse(summary.toString().contains("private-key"))
        vault.fail = true
        assertTrue(runCatching { repo.saveTunnelConnection("t", "bad", "https://other.example", "new", "pc3", "", "new", false) }.isFailure)
        assertEquals(summary, repo.connections.value.single())
    }
    @Test fun disabledConnectionStaysDisabledAcrossRecreationAndEditing() = runTest {
        val vault = Vault(listOf(StoredConnection("c", "Desktop", "https://desktop.example", "secret", false)))
        val wires = mutableListOf<Wire>()
        fun repository() = DefaultRemoteDesktopRepository(vault, backgroundScope) { Wire().also { wires += it } }
        val first = repository(); runCurrent(); first.onForeground(); runCurrent()
        assertEquals(1, wires.size)
        first.disconnect("c"); runCurrent()
        assertTrue(wires.single().closed); assertFalse(vault.rows.single().enabled)
        first.saveConnection("c", "renamed", "https://desktop.example", null, false)
        assertFalse(first.connections.value.single().enabled)
        val restored = repository(); runCurrent(); restored.onForeground(); runCurrent()
        assertEquals(1, wires.size)
        restored.connect("c"); runCurrent()
        assertEquals(2, wires.size); assertEquals("secret", wires.last().password)
        assertTrue(vault.rows.single().enabled)
    }
    @Test fun reorderAndDisconnectAllPersistWithoutReconnectingDuringReorder() = runTest {
        val vault = Vault(listOf(StoredConnection("a", "A", "https://a.example", "one", false), StoredConnection("b", "B", "https://b.example", "two", false)))
        val wires = mutableListOf<Wire>()
        val repo = DefaultRemoteDesktopRepository(vault, backgroundScope) { Wire().also { wires += it } }
        runCurrent(); repo.onForeground(); runCurrent()
        repo.reorderConnections(listOf("b", "a"))
        assertEquals(listOf("b", "a"), repo.connections.value.map { it.id })
        assertEquals(listOf("b", "a"), vault.rows.map { it.id }); assertEquals(2, wires.size)
        assertTrue(runCatching { repo.reorderConnections(listOf("b", "b")) }.isFailure)
        repo.disconnectAll(); runCurrent()
        assertTrue(vault.rows.none { it.enabled }); assertTrue(wires.all { it.closed })
        repo.onBackground(); repo.onForeground(); runCurrent(); assertEquals(2, wires.size)
    }
    @Test fun failedPreferenceWriteLeavesLiveConnectionAndOrderUnchanged() = runTest {
        val vault = Vault(listOf(StoredConnection("c", "Desktop", "https://desktop.example", "", false)))
        val wire = Wire(); val repo = DefaultRemoteDesktopRepository(vault, backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent(); vault.fail = true
        assertTrue(runCatching { repo.disconnect("c") }.isFailure)
        assertTrue(repo.connections.value.single().enabled); assertFalse(wire.closed)
        assertNotNull(repo.storageError.value)
    }
    @Test fun saveEditDeleteAndIndependentLifetimes() = runTest {
        val vault = Vault()
        val wires = mutableListOf<Wire>()
        val repo = DefaultRemoteDesktopRepository(vault, backgroundScope) { Wire().also(wires::add) }
        runCurrent(); repo.onForeground()
        val a = repo.saveConnection(null, "A", "https://same.example", "a password", false)
        val b = repo.saveConnection(null, "B", "https://same.example", "b password", false)
        runCurrent()
        assertEquals(listOf("a password", "b password"), wires.map { it.password })
        assertEquals(2, repo.projects.value.size)
        repo.saveConnection(a, "Edited", "https://same.example", null, false)
        runCurrent()
        assertTrue(wires[0].closed); assertFalse(wires[1].closed)
        assertEquals("a password", wires[2].password)
        repo.saveConnection(a, "Edited", "https://same.example", "", false); runCurrent()
        assertEquals("", wires[3].password)
        repo.removeConnection(a); runCurrent()
        assertTrue(wires[3].closed)
        assertEquals(listOf(b), repo.connections.value.map { it.id })
        assertFalse(repo.projects.value.containsKey(a))
        // Late transport callbacks cannot repopulate removed data.
        wires[0].event("projects:updated", """[{"id":"stale","name":"Stale"}]"""); runCurrent()
        assertFalse(repo.projects.value.containsKey(a))
    }

    @Test fun storageErrorsVisibleAndCleartextRequiresPerConnectionConsent() = runTest {
        val badVault = Vault().apply { fail = true }
        val broken = DefaultRemoteDesktopRepository(badVault, backgroundScope)
        runCurrent(); assertNotNull(broken.storageError.value)
        assertTrue(runCatching { broken.saveConnection(null, "X", "https://a.example", "", false) }.isFailure)
        val vault = Vault()
        val repo = DefaultRemoteDesktopRepository(vault, backgroundScope)
        runCurrent()
        assertTrue(runCatching { repo.saveConnection(null, "X", "http://a.example", "", false) }.isFailure)
        val id = repo.saveConnection(null, "X", "http://a.example", "", true)
        assertTrue(repo.connections.value.single().allowCleartext)
        vault.fail = true
        assertTrue(runCatching { repo.removeConnection(id) }.isFailure)
        assertNotNull(repo.storageError.value)
        assertEquals(id, repo.connections.value.single().id)
    }

    @Test fun backgroundClosesAndForegroundResyncsWithoutReplayingWrites() = runTest {
        val wires = mutableListOf<Wire>()
        val repo = DefaultRemoteDesktopRepository(Vault(listOf(connection())), backgroundScope) { Wire().also(wires::add) }
        runCurrent(); repo.onForeground(); runCurrent()
        repo.openThread("c", "s")
        repo.send("c", "s", "hello")
        repo.onBackground(); runCurrent()
        assertTrue(wires.single().closed)
        advanceTimeBy(60_000); runCurrent(); assertEquals(1, wires.size)
        repo.onForeground(); runCurrent()
        assertEquals(2, wires.size)
        assertEquals(1, wires[1].count("session:get"))
        assertEquals(0, wires[1].count("session:delegateRun"))
    }

    @Test fun fullCreateSendStopUsesRealWireAndRemoteRunAuthority() = runTest {
        val wire = Wire()
        val repo = DefaultRemoteDesktopRepository(Vault(listOf(connection())), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent()
        val created = repo.createSession("c", "p", "New session")
        val meta = wire.calls.last()["args"]!!.jsonArray[0].jsonObject
        assertEquals(created, meta["id"]!!.jsonPrimitive.content)
        assertEquals("p", meta["projectId"]!!.jsonPrimitive.content)
        wire.event("session:meta-updated", """{"id":"s","title":"Session","projectId":"p"}"""); runCurrent()
        val state = repo.observeThread("c", "s")
        repo.send("c", "s", "Hello", "model")
        val send = wire.calls.last()["args"]!!.jsonArray[0].jsonObject
        assertEquals("session:delegateRun", wire.calls.last()["method"]!!.jsonPrimitive.content)
        assertTrue(send["skillReferences"]!!.jsonArray.isEmpty())
        assertNotEquals(send["requestId"], send["userEntryId"])
        assertEquals("p", send["projectId"]!!.jsonPrimitive.content)
        assertFalse(state.value.running)
        wire.run("r1", "running"); runCurrent(); assertTrue(state.value.running)
        assertEquals(setOf("s"), repo.runningSessions.value["c"])
        repo.stop("c", "s")
        assertEquals("session:abortRun", wire.calls.last()["method"]!!.jsonPrimitive.content)
        assertEquals("s", wire.calls.last()["args"]!!.jsonArray[0].jsonPrimitive.content)
        assertTrue(state.value.running)
        wire.run("r1", "idle"); runCurrent(); assertFalse(state.value.running)
        assertTrue(repo.runningSessions.value["c"].isNullOrEmpty())
        wire.run("r2", "running"); runCurrent()
        assertEquals(setOf("s"), repo.runningSessions.value["c"])
        repo.disconnect("c"); runCurrent()
        assertTrue(repo.runningSessions.value["c"].isNullOrEmpty())
    }

    @Test fun snapshotOverlaidByWholeLiveEntriesAndStaleRunCannotOverwrite() = runTest {
        val wire = Wire()
        val repo = DefaultRemoteDesktopRepository(Vault(listOf(connection())), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent()
        val state = repo.observeThread("c", "s")
        wire.run("r1", "running"); runCurrent()
        wire.held.add("session:get")
        val opening = launch { repo.openThread("c", "s") }; runCurrent()
        wire.entry("r1", "new"); wire.entry("r1", "new"); runCurrent()
        wire.reply(wire.calls.last(), """{"entries":[{"id":"a","kind":"assistant","content":[{"type":"text","text":"old"}]},{"id":"b","kind":"unknown"}]}""")
        runCurrent(); opening.join()
        assertEquals(listOf("a", "b"), state.value.entries.map { it.id })
        assertEquals("new", state.value.entries.first().text)
        wire.run("r2", "running"); runCurrent()
        wire.run("r1", "idle"); wire.entry("r1", "stale"); wire.event("future:event", "{}"); runCurrent()
        assertTrue(state.value.running); assertEquals("new", state.value.entries.first().text)
        wire.entry("r2", "replacement"); runCurrent()
        assertEquals("replacement", state.value.entries.first().text)
    }

    @Test fun timeoutIsUnknownAndNeverAutomaticallyReplayed() = runTest {
        val wires = mutableListOf<Wire>()
        val repo = DefaultRemoteDesktopRepository(Vault(listOf(connection())), backgroundScope) { Wire().also(wires::add) }
        runCurrent(); repo.onForeground(); runCurrent()
        wires[0].held.add("session:delegateRun")
        val send = launch { runCatching { repo.send("c", "s", "only once") } }
        runCurrent(); advanceTimeBy(30_001); runCurrent(); send.join()
        assertTrue(repo.observeThread("c", "s").value.outcomeUnknown)
        wires[0].listener.onClosed(NetworkFailure()); runCurrent(); advanceTimeBy(1_001); runCurrent()
        assertEquals(2, wires.size)
        assertEquals(1, wires.sumOf { it.count("session:delegateRun") })
    }

    @Test fun disconnectDuringMutationRetainsUnknownAndLateSnapshotCannotCrossGeneration() = runTest {
        val wires = mutableListOf<Wire>()
        val repo = DefaultRemoteDesktopRepository(Vault(listOf(connection())), backgroundScope) { Wire().also(wires::add) }
        runCurrent(); repo.onForeground(); runCurrent()
        val state = repo.observeThread("c", "s")
        wires[0].held.addAll(listOf("session:delegateRun", "session:get"))
        val opening = launch { runCatching { repo.openThread("c", "s") } }
        val sending = launch { runCatching { repo.send("c", "s", "sent before background") } }
        runCurrent()
        val oldGet = wires[0].calls.first { it["method"]!!.jsonPrimitive.content == "session:get" }
        repo.onBackground(); runCurrent(); sending.join(); opening.join()
        assertTrue(state.value.outcomeUnknown)
        repo.onForeground(); runCurrent()
        wires[0].reply(oldGet, """{"entries":[{"id":"old","kind":"assistant"}]}""")
        wires[0].entry("r-old", "late")
        runCurrent()
        assertTrue(state.value.entries.isEmpty())
        assertEquals(1, wires.sumOf { it.count("session:delegateRun") })
    }

    @Test fun unknownRequiresSuccessfulSnapshotAndExplicitAcknowledgement() = runTest {
        val wire = Wire()
        val repo = DefaultRemoteDesktopRepository(Vault(listOf(connection())), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent()
        val state = repo.observeThread("c", "s")
        wire.entry("r", "keep"); runCurrent()
        wire.held.add("session:delegateRun")
        val sending = launch { runCatching { repo.send("c", "s", "once") } }
        runCurrent(); advanceTimeBy(30_001); runCurrent(); sending.join()
        assertTrue(runCatching { repo.acknowledgeOutcome("c", "s") }.isFailure)
        wire.held.add("session:get")
        for (bad in listOf("null", "[]", "{}", "{\"entries\":null}", "{\"entries\":[null]}")) {
            val loading = launch { repo.openThread("c", "s") }; runCurrent()
            assertTrue(runCatching { repo.acknowledgeOutcome("c", "s") }.isFailure)
            wire.reply(wire.calls.last(), bad); runCurrent(); loading.join()
            assertEquals("keep", state.value.entries.single().text)
            assertNotNull(state.value.error)
            assertTrue(runCatching { repo.acknowledgeOutcome("c", "s") }.isFailure)
        }
        wire.held.remove("session:get")
        repo.openThread("c", "s")
        assertTrue(state.value.outcomeUnknown)
        repo.acknowledgeOutcome("c", "s")
        assertFalse(state.value.outcomeUnknown); assertNull(state.value.error)
        wire.held.remove("session:delegateRun")
        repo.send("c", "s", "confirmed")
    }

    @Test fun successfulReplyResumedAfterDisconnectIsNotSuccess() = runTest {
        val wires = mutableListOf<Wire>()
        val repo = DefaultRemoteDesktopRepository(Vault(listOf(connection())), backgroundScope) { Wire().also(wires::add) }
        runCurrent(); repo.onForeground(); runCurrent()
        wires[0].held.add("session:delegateRun")
        val sending = async { runCatching { repo.send("c", "s", "draft") } }; runCurrent()
        wires[0].reply(wires[0].calls.last(), "null")
        // Reply completed the RPC deferred, but the repository continuation has not resumed.
        repo.disconnect("c"); repo.connect("c")
        runCurrent()
        assertTrue(sending.await().isFailure)
        assertTrue(repo.observeThread("c", "s").value.outcomeUnknown)
        wires[0].entry("old", "must not leak"); runCurrent()
        assertTrue(repo.observeThread("c", "s").value.entries.isEmpty())
    }

    @Test fun confirmedAgentStartBeforeRunStatusReplacesPreviousIdleIdentity() = runTest {
        val wire = Wire()
        val repo = DefaultRemoteDesktopRepository(Vault(listOf(connection())), backgroundScope) { wire }
        runCurrent(); repo.onForeground(); runCurrent()
        val state = repo.observeThread("c", "s")
        wire.run("old", "idle"); runCurrent()
        wire.event("session:stream-event", """{"sessionId":"s","runId":"new","event":{"type":"agent-start","sessionId":"s","runId":"new"}}""")
        wire.entry("new", "new entry"); wire.entry("old", "stale")
        wire.run("old", "idle"); runCurrent()
        assertTrue(state.value.running)
        assertEquals("new entry", state.value.entries.single().text)
        wire.run("new", "running"); runCurrent()
        assertTrue(state.value.running)
    }

    @Test fun authenticationFailureStopsRetriesIncludingWake() = runTest {
        val wires = mutableListOf<Wire>()
        val repo = DefaultRemoteDesktopRepository(Vault(listOf(connection())), backgroundScope) { Wire().apply { rejectAuth = true }.also(wires::add) }
        runCurrent(); repo.onForeground(); runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(ConnectionPhase.AuthenticationRequired, repo.connections.value.single().phase)
        assertEquals(1, wires.size)
        repo.onBackground(); repo.onForeground(); runCurrent()
        assertEquals(1, wires.size)
        repo.connect("c"); runCurrent(); assertEquals(2, wires.size)
    }

    @Test fun transientRetryBudgetIsFiniteAndBackgroundCancelsBackoff() = runTest {
        var attempts = 0
        val repo = DefaultRemoteDesktopRepository(Vault(listOf(connection())), backgroundScope) {
            attempts++; throw NetworkFailure()
        }
        runCurrent(); repo.onForeground(); runCurrent()
        repeat(4) { advanceTimeBy(10_000); runCurrent() }
        assertEquals(4, attempts)
        repo.connect("c"); runCurrent(); assertEquals(5, attempts)
        repo.onBackground(); advanceTimeBy(60_000); runCurrent(); assertEquals(5, attempts)
    }
}
