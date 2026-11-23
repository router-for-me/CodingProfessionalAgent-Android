package dev.cpa.android.feature.remote

import dev.cpa.android.core.push.TaskPushMessage
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import dev.cpa.android.core.designsystem.CpaTheme
import dev.cpa.android.core.push.TaskNotificationTarget
import dev.cpa.android.data.repository.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TaskNotificationRouteTest {
    @get:Rule val compose = createComposeRule()

    @Test fun coldAndWarmTargetsUseTheCorrectDesktopAndIgnoreDisabledConnections() {
        fun target(connection: String) = TaskNotificationTarget("wire-$connection", "same-session")
        val base = RemoteWorkspaceScreenTest.Fake()
        fun row(id: String, phase: ConnectionPhase, enabled: Boolean = true) =
            ConnectionSummary(id, id, "", false, phase, enabled = enabled)
        base.connections.value = listOf(row("A", ConnectionPhase.Connecting), row("B", ConnectionPhase.Connected),
            row("disabled", ConnectionPhase.Disconnected, false))
        val opened = mutableListOf<Pair<String, String>>()
        val repo = object : RemoteDesktopRepository by base {
            override suspend fun openThread(connectionId: String, sessionId: String) { opened += connectionId to sessionId }
        }
        val pending = mutableStateOf<TaskNotificationTarget?>(target("A"))
        compose.setContent { CpaTheme {
            RemoteNavigation(repo, pending.value, onConsumed = { pending.value = null }, resolveNotification = { it.removePrefix("wire-") })
        } }
        compose.runOnIdle {
            assertTrue(opened.isEmpty())
            base.connections.value = base.connections.value.map { if (it.id == "A") it.copy(phase = ConnectionPhase.Connected) else it }
        }
        compose.waitUntil(5_000) { "A" to "same-session" in opened }
        compose.runOnIdle { pending.value = target("B") }
        compose.waitUntil(5_000) { "B" to "same-session" in opened }
        compose.runOnIdle { pending.value = target("disabled") }
        compose.waitUntil(5_000) { pending.value == null }
        compose.runOnIdle {
            assertFalse(opened.any { it.first == "disabled" })
            assertNull(TaskPushMessage.from(mapOf("connectionId" to "wire-A", "notificationType" to "ask")))
            assertNull(TaskPushMessage.from(mapOf("connectionId" to "wire-A", "sessionId" to "s", "notificationType" to "unknown")))
        }
    }
}
