package dev.cpa.android.core.push

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailabilityLight
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dev.cpa.android.CpaApplication
import dev.cpa.android.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal const val CHANNEL = "cpa_tasks"

enum class PushAvailability { Available, Unconfigured, NoPlayServices, PermissionDenied, ChannelBlocked, RegistrationFailed }

class TaskPushController(private val context: Context, private val scope: CoroutineScope) {
    internal val bindings = AndroidPushJournal(context)
    val subscriptions = DesktopPushSubscriptions(bindings)
    @Volatile var appVisible = false
        internal set
    private val prefs = context.getSharedPreferences("task_push", Context.MODE_PRIVATE)
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val requested = MutableStateFlow(prefs.getBoolean("enabled", false))
    val enabled = requested.asStateFlow()
    private val state = MutableStateFlow(availability())
    val availability = state.asStateFlow()
    private var inFlight = false
    private var revision = 0

    fun setEnabled(value: Boolean) {
        if (requested.value != value) {
            revision++
            prefs.edit().putBoolean("enabled", value).apply()
            requested.value = value
        }
        refresh()
    }

    private fun availability(): PushAvailability = when {
        FirebaseApp.getApps(context).none { it.name == FirebaseApp.DEFAULT_APP_NAME } -> PushAvailability.Unconfigured
        GoogleApiAvailabilityLight.getInstance().isGooglePlayServicesAvailable(context) != ConnectionResult.SUCCESS -> PushAvailability.NoPlayServices
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ||
            !manager.areNotificationsEnabled() -> PushAvailability.PermissionDenied
        manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE -> PushAvailability.ChannelBlocked
        else -> PushAvailability.Available
    }

    /** Serializes Firebase registration and revocation across rapid preference/permission changes. */
    fun refresh() {
        val available = availability()
        if (state.value != available) revision++
        state.value = available
        val active = requested.value && available == PushAvailability.Available
        if (!active) {
            subscriptions.target.value = PushTarget.Off
            bindings.retireAll()
        }
        if (available == PushAvailability.Unconfigured || available == PushAvailability.NoPlayServices || inFlight) return
        val pendingRevocation = prefs.getBoolean("firebaseRegistered", false)
        if (!active && !pendingRevocation) return

        if (active) {
            createTaskChannel(context)
            if (subscriptions.target.value == PushTarget.Off) subscriptions.target.value = PushTarget.Pending
            prefs.edit().putBoolean("firebaseRegistered", true).apply()
        }
        val generation = revision
        inFlight = true
        val task = try {
            val messaging = FirebaseMessaging.getInstance()
            if (active) messaging.register() else messaging.unregister()
        } catch (_: RuntimeException) {
            inFlight = false
            if (active) state.value = PushAvailability.RegistrationFailed
            return
        }
        task.addOnCompleteListener { result ->
            inFlight = false
            if (!active && result.isSuccessful) prefs.edit().remove("firebaseRegistered").apply()
            if (generation != revision) refresh()
            else if (!result.isSuccessful && active) state.value = PushAvailability.RegistrationFailed
        }
    }

    fun localConnection(route: String): String? = if (requested.value) bindings.resolve(route) else null

    fun present(data: Map<String, String>) {
        val message = TaskPushMessage.from(data) ?: return
        bindings.withAuthorizedRoute(message.route) {
            if (requested.value && !appVisible && manager.areNotificationsEnabled() &&
                manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE) {
                postTaskNotification(context, message)
            }
        }
    }

    fun registered(fid: String) {
        // FirebaseMessagingService callbacks arrive on an SDK worker.
        scope.launch {
            if (fid.isNotBlank() && requested.value && availability() == PushAvailability.Available &&
                (subscriptions.target.value as? PushTarget.Registered)?.fid != fid) {
                subscriptions.target.value = PushTarget.Registered(fid)
            }
        }
    }
}

class TaskMessagingService : FirebaseMessagingService() {
    override fun onRegistered(installationId: String) {
        (application as CpaApplication).push.registered(installationId)
    }
    override fun onMessageReceived(message: RemoteMessage) {
        (application as CpaApplication).push.present(message.data)
    }
}
