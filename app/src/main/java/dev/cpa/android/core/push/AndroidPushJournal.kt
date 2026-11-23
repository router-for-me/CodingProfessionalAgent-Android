package dev.cpa.android.core.push

import android.app.NotificationManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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

    @Synchronized fun deliver(route: String, deliveryId: String?, post: () -> Boolean) {
        if (resolve(route) == null) return
        val key = "deliveries.$route"
        val recent = prefs.getString(key, null)?.let { Json.decodeFromString<List<String>>(it) }.orEmpty()
        if (deliveryId in recent) return
        if (post() && deliveryId != null) {
            // Suppress FCM redelivery even after dismissal. Bound storage per active binding.
            prefs.edit().putString(key, Json.encodeToString((recent + deliveryId).takeLast(32))).apply()
        }
    }

    @Synchronized override fun retire(connection: String) {
        val route = prefs.getString("route.$connection", null) ?: return
        prefs.edit().remove("route.$connection").remove("local.$route").remove("deliveries.$route")
            .putStringSet("retired.$connection", retired(connection) + route).apply()
        for (entry in manager.activeNotifications) {
            if (entry.notification.group == notificationGroup(route)) manager.cancel(entry.tag, entry.id)
        }
    }

    override suspend fun persistRetirement(connection: String) = withContext(Dispatchers.IO) {
        retire(connection)
        // A prior opt-out may already have removed this route in memory. commit()
        // also waits for outstanding apply() writes before the vault can change.
        check(prefs.edit().commit()) { "Failed to persist notification revocation" }
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
