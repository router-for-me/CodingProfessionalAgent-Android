package dev.cpa.android.core.push

import dev.cpa.android.core.network.RemoteRpcFailure
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopPushSubscriptionsTest {
    internal class Journal : PushJournal {
        override val clientId = "installation"
        val ids = mutableSetOf<String>()
        private val tombstones = mutableSetOf<String>()
        override fun authorize(connection: String): String { ids.add(connection); return "route-$connection" }
        override fun retire(connection: String) { if (ids.remove(connection)) tombstones += connection }
        override fun retired(connection: String) = if (connection in tombstones) setOf("route-$connection") else emptySet()
        override fun cleared(connection: String, route: String) { tombstones.remove(connection) }
        override fun forget(connection: String) { tombstones.remove(connection) }
    }

    private val ok = buildJsonObject { put("ok", true) }

    @Test fun preservesOpaqueIdAndReconcilesEachDesktopAcrossRotationDisablingAndReconnect() = runTest {
        val journal = Journal()
        val subscriptions = DesktopPushSubscriptions(journal)
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val rpc: PushRpc = { method, args -> calls += method to args.single().jsonObject; ok }
        val a = backgroundScope.launch { subscriptions.synchronize("A", rpc) }
        backgroundScope.launch { subscriptions.synchronize("B", rpc) }
        runCurrent()
        assertTrue(calls.isEmpty())
        subscriptions.target.value = PushTarget.Registered("AbCd-123")
        runCurrent()
        assertEquals(setOf("A", "B"), journal.ids)
        assertTrue(calls.all { (method, row) ->
            method == "notification:registerClient" && row["deviceToken"] == JsonPrimitive("AbCd-123") &&
                row["clientId"] == JsonPrimitive("installation") && row["provider"] == JsonPrimitive("fcm") &&
                row["platform"] == JsonPrimitive("android") && "environment" !in row
        })
        a.cancelAndJoin()
        subscriptions.target.value = PushTarget.Registered("nEwId")
        runCurrent()
        assertEquals(JsonPrimitive("nEwId"), calls.last().second["deviceToken"])
        subscriptions.target.value = PushTarget.Off
        runCurrent()
        assertEquals(setOf("A"), journal.ids)
        backgroundScope.launch { subscriptions.synchronize("A", rpc) }
        runCurrent()
        assertTrue(journal.ids.isEmpty())
        assertEquals("notification:unregisterClient", calls.last().first)
        assertEquals(JsonPrimitive("route-A"), calls.last().second["connectionId"])
    }

    @Test fun lostReplyRetriesIdempotentlyAndOldDesktopRemainsUsable() = runTest {
        val journal = Journal()
        val subscriptions = DesktopPushSubscriptions(journal)
        subscriptions.target.value = PushTarget.Registered("MiXeD")
        var tries = 0
        backgroundScope.launch { subscriptions.synchronize("old") { _, _ -> throw RemoteRpcFailure("Unknown RPC method notification:registerClient") } }
        backgroundScope.launch { subscriptions.synchronize("new") { _, _ ->
            tries++
            if (tries == 1) JsonNull else ok
        } }
        runCurrent()
        assertEquals(DesktopPushStatus.Unsupported, subscriptions.status.value["old"])
        assertEquals(DesktopPushStatus.Retrying, subscriptions.status.value["new"])
        assertTrue("new" in journal.ids)
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(DesktopPushStatus.Registered, subscriptions.status.value["new"])
        assertEquals(2, tries)
    }
}
