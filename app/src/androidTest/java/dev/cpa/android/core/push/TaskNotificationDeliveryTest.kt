package dev.cpa.android.core.push

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.messaging.RemoteMessage
import dev.cpa.android.R
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class TaskNotificationDeliveryTest {
    @Test fun localDeliveryFiltersRetiredRoutesAndForegroundBeforePosting() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(base.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val namespace = UUID.randomUUID().toString()
        var allowPosts = true
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("$namespace-$name", mode)
            override fun checkSelfPermission(permission: String): Int {
                if (!allowPosts && permission == Manifest.permission.POST_NOTIFICATIONS) return PackageManager.PERMISSION_DENIED
                return super.checkSelfPermission(permission)
            }
        }
        context.getSharedPreferences("task_push", 0).edit().putBoolean("enabled", true).apply()
        val scope = MainScope()
        val push = TaskPushController(context, scope)
        val a = push.bindings.authorize("desktop-A")
        val b = push.bindings.authorize("desktop-B")
        val session = "shared/session ü"
        val manager = context.getSystemService(NotificationManager::class.java)
        fun items() = manager.activeNotifications.filter { it.notification.group in setOf(notificationGroup(a), notificationGroup(b)) }
        fun deliver(route: String, type: String) {
            val remote = RemoteMessage.Builder("test").setData(mapOf("connectionId" to route, "sessionId" to session, "notificationType" to type)).build()
            push.present(remote.data)
        }
        fun awaitCount(count: Int) {
            val limit = SystemClock.elapsedRealtime() + 3_000L
            while (items().size != count && SystemClock.elapsedRealtime() < limit) SystemClock.sleep(25)
            assertEquals(count, items().size)
        }
        try {
            push.appVisible = true
            deliver(a, "ask")
            assertTrue(items().isEmpty())
            push.appVisible = false
            allowPosts = false
            deliver(a, "ask")
            assertTrue(items().isEmpty())
            allowPosts = true
            deliver(a, "ask")
            deliver(b, "taskCompleted")
            awaitCount(2)
            val first = items().single { it.notification.group == notificationGroup(a) }.notification
            val second = items().single { it.notification.group == notificationGroup(b) }.notification
            assertEquals(CHANNEL, first.channelId)
            assertEquals(context.getString(R.string.app_name), first.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertEquals(context.getString(R.string.cpa_push_ask_body), first.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            assertEquals(context.getString(R.string.cpa_push_completed_body), second.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            assertTrue(first.contentIntent.isImmutable)
            assertNotEquals(first.contentIntent, second.contentIntent)
            val intent = TaskNotificationTarget(b, session).intent(context)
            assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
            assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
            assertTrue(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
            val tap = TaskNotificationTarget.from(intent)!!
            assertEquals("desktop-B", push.localConnection(tap.route))
            assertEquals(session, tap.session)

            // Deletion while the old desktop is offline cannot send a remote unregister.
            push.bindings.retire("desktop-A")
            deliver(a, "ask")
            awaitCount(1)
            assertNull(push.localConnection(a))
            assertEquals("desktop-B", push.localConnection(b))
            assertNotEquals(a, push.bindings.authorize("desktop-A"))
            deliver(a, "taskCompleted")
            assertEquals(1, items().size)
        } finally {
            push.bindings.retireAll()
            base.deleteSharedPreferences("$namespace-task_push")
            base.deleteSharedPreferences("$namespace-push_subscriptions")
            scope.cancel()
        }
    }
}
