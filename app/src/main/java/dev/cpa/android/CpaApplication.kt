package dev.cpa.android

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.cpa.android.core.platform.hasLocalNetworkPermission
import dev.cpa.android.core.storage.AndroidConnectionVault
import dev.cpa.android.core.storage.AndroidComposerPreferences
import dev.cpa.android.core.storage.AndroidTunnelIdentityStore
import dev.cpa.android.core.storage.AndroidRecentTunnelStore
import dev.cpa.android.core.network.tunnel.TunnelClientManager
import dev.cpa.android.core.network.tunnel.ManagedTunnelConnectionProvider
import dev.cpa.android.data.repository.DefaultRemoteDesktopRepository
import dev.cpa.android.data.repository.RemoteDesktopRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class CpaApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gatewayScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tunnelManager by lazy { TunnelClientManager(gatewayScope, AndroidTunnelIdentityStore(this)) }
    val push by lazy { dev.cpa.android.core.push.TaskPushController(this, applicationScope) }
    val remote: RemoteDesktopRepository by lazy {
        DefaultRemoteDesktopRepository(AndroidConnectionVault(this), applicationScope, AndroidComposerPreferences(this),
            push = push.subscriptions, recentTunnel = AndroidRecentTunnelStore(this),
            tunnelConnections = ManagedTunnelConnectionProvider(tunnelManager, gatewayScope))
    }

    override fun onCreate() {
        super.onCreate()
        // Track visible activities immediately; ProcessLifecycleOwner intentionally delays STOP.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(activity: android.app.Activity) { started++; push.appVisible = true }
            override fun onActivityStopped(activity: android.app.Activity) { started--; push.appVisible = started > 0 }
            override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) = Unit
            override fun onActivityResumed(activity: android.app.Activity) = Unit
            override fun onActivityPaused(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        })
        // Process lifecycle delays STOP across configuration changes; individual pages never own sockets.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                push.refresh()
                if (hasLocalNetworkPermission()) remote.onForeground() else remote.onBackground()
            }
            override fun onStop(owner: LifecycleOwner) = remote.onBackground()
        })
    }
}
