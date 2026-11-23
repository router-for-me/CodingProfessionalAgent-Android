package dev.cpa.android.core.push

import android.app.NotificationManager
import android.content.Context
import java.util.UUID

/** The wire route is a revocable per-desktop binding. A deleted/offline desktop can still
 * know our FID but can no longer cause a notification, even after another connection is added. */
internal class AndroidPushJournal(context: Context) : PushJournal {
    private val prefs = context.getSharedPreferences("push_subscriptions", Context.MODE_PRIVATE)
    private val manager = context.getSystemService(NotificationManager::class.java)

    override val clientId: String by lazy {
        prefs.getString("client", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("client", it).apply()
        }
    }

    @Synchronized override fun authorize(connection: String): String {
        return prefs.getString("route.$connection", null) ?: UUID.randomUUID().toString().also { route ->
            prefs.edit().putString("route.$connection", route).putString("local.$route", connection).apply()
        }
    }

    @Synchronized fun resolve(route: String): String? = prefs.getString("local.$route", null)

    @Synchronized fun withAuthorizedRoute(route: String, action: () -> Unit) {
        if (resolve(route) != null) action()
    }

    @Synchronized override fun retire(connection: String) {
        val route = prefs.getString("route.$connection", null) ?: return
        prefs.edit().remove("route.$connection").remove("local.$route")
            .putStringSet("retired.$connection", retired(connection) + route).apply()
        for (entry in manager.activeNotifications) {
            if (entry.notification.group == notificationGroup(route)) manager.cancel(entry.tag, entry.id)
        }
    }

    @Synchronized fun retireAll() {
        prefs.all.keys.filter { it.startsWith("route.") }.forEach { retire(it.removePrefix("route.")) }
    }

    @Synchronized override fun retired(connection: String): Set<String> =
        prefs.getStringSet("retired.$connection", emptySet()).orEmpty().toSet()

    @Synchronized override fun cleared(connection: String, route: String) {
        val remaining = retired(connection) - route
        if (remaining.isEmpty()) forget(connection)
        else prefs.edit().putStringSet("retired.$connection", remaining).apply()
    }

    @Synchronized override fun forget(connection: String) {
        prefs.edit().remove("retired.$connection").apply()
    }
}
