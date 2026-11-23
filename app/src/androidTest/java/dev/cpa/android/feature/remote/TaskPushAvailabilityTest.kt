package dev.cpa.android.feature.remote

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailabilityLight
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import dev.cpa.android.R
import dev.cpa.android.core.designsystem.CpaTheme
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class TaskPushAvailabilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun configuredAppOnAospShowsGooglePlayRequirement() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(GoogleApiAvailabilityLight.getInstance().isGooglePlayServicesAvailable(context) != ConnectionResult.SUCCESS)
        val configured = FirebaseApp.getApps(context).any { it.name == FirebaseApp.DEFAULT_APP_NAME }
        // Exercise the configured-build branch on an AOSP emulator. No FCM registration or network call.
        val temporary = if (configured) null else FirebaseApp.initializeApp(context, FirebaseOptions.Builder()
            .setApplicationId("1:123456789:android:abcdef").setProjectId("demo-cpa-test").build())
        try {
            compose.setContent { CpaTheme { TaskPushSettings(emptyList()) } }
            compose.onNodeWithTag("settings-task-notifications").assertIsNotEnabled()
            compose.onNodeWithText(context.getString(R.string.push_no_gms)).assertExists()
        } finally {
            temporary?.delete()
        }
    }
}
