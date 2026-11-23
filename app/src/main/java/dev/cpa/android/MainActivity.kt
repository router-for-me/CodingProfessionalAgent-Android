package dev.cpa.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.cpa.android.core.designsystem.CpaTheme
import dev.cpa.android.core.localization.AppLanguageResources
import dev.cpa.android.core.platform.LocalNetworkPermissionGate
import dev.cpa.android.core.push.TaskNotificationTarget
import dev.cpa.android.feature.remote.RemoteNavigation

class MainActivity : ComponentActivity() {
    private var pending by mutableStateOf<TaskNotificationTarget?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pending = if (savedInstanceState == null) TaskNotificationTarget.from(intent) else TaskNotificationTarget.from(savedInstanceState.getBundle("push"))
        enableEdgeToEdge()
        setContent {
            val app = application as CpaApplication
            AppLanguageResources {
                CpaTheme {
                    LocalNetworkPermissionGate(onGranted = app.remote::onForeground) {
                        RemoteNavigation(app.remote, pending, onConsumed = { pending = null },
                            resolveNotification = app.push::localConnection)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pending = TaskNotificationTarget.from(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBundle("push", pending?.toBundle())
    }
}
