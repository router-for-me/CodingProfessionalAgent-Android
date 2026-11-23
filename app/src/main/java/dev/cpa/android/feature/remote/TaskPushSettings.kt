package dev.cpa.android.feature.remote

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.cpa.android.CpaApplication
import dev.cpa.android.R
import dev.cpa.android.core.designsystem.*
import dev.cpa.android.core.push.DesktopPushStatus
import dev.cpa.android.core.push.PushAvailability
import dev.cpa.android.data.repository.ConnectionPhase
import dev.cpa.android.data.repository.ConnectionSummary

@Composable
internal fun TaskPushSettings(connections: List<ConnectionSummary>) {
    val context = LocalContext.current
    val push = (context.applicationContext as CpaApplication).push
    val enabled by push.enabled.collectAsStateWithLifecycle()
    val availability by push.availability.collectAsStateWithLifecycle()
    val desktops by push.subscriptions.status.collectAsStateWithLifecycle()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) push.setEnabled(true) else push.refresh()
    }
    LifecycleResumeEffect(push) {
        push.refresh()
        onPauseOrDispose { }
    }
    val supported = availability !in setOf(PushAvailability.Unconfigured, PushAvailability.NoPlayServices)
    FormSectionHeader(stringResource(R.string.push_section), prominent = true)
    FormGroup {
        FormToggleRow(stringResource(R.string.push_title), enabled, { value ->
            if (value && availability == PushAvailability.PermissionDenied) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else push.setEnabled(value)
        }, enabled = enabled || supported, modifier = Modifier.testTag("settings-task-notifications"))
        if (enabled && availability == PushAvailability.Available) {
            connections.filter { it.enabled }.forEach { row ->
                val label = if (row.phase != ConnectionPhase.Connected) R.string.push_waiting_connection
                    else when (desktops[row.id]) {
                        DesktopPushStatus.Registered -> R.string.push_registered
                        DesktopPushStatus.Unsupported -> R.string.push_update_desktop
                        DesktopPushStatus.Retrying -> R.string.push_retrying
                        null -> R.string.push_registering
                    }
                FormDivider()
                FormValueRow(row.displayName(), stringResource(label))
            }
        }
        if (availability == PushAvailability.PermissionDenied || availability == PushAvailability.ChannelBlocked) {
            FormDivider()
            FormRow(onClick = {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }) { Text(stringResource(R.string.push_system_settings), color = Cpa.colors.blue, style = cpaText(17)) }
        }
        if (enabled && availability == PushAvailability.RegistrationFailed) {
            FormDivider()
            FormRow(onClick = push::refresh) { Text(stringResource(R.string.push_retry), color = Cpa.colors.blue, style = cpaText(17)) }
        }
    }
    val hint = when (availability) {
        PushAvailability.Unconfigured -> R.string.push_unconfigured
        PushAvailability.NoPlayServices -> R.string.push_no_gms
        PushAvailability.PermissionDenied -> R.string.push_permission
        PushAvailability.ChannelBlocked -> R.string.push_channel_blocked
        PushAvailability.RegistrationFailed -> R.string.push_firebase_failed
        PushAvailability.Available -> R.string.push_hint
    }
    Text(stringResource(hint), modifier = Modifier.padding(horizontal = 32.dp, vertical = 6.dp),
        style = cpaText(14), color = Cpa.colors.secondaryLabel)
}
