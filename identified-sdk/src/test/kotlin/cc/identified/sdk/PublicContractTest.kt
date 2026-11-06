package cc.identified.sdk

import android.app.Activity
import android.content.Intent
import android.os.Parcel
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 28, 35])
class PublicContractTest {
    @Test
    fun tokenNeverEntersTheLaunchIntentAndCanOnlyBeConsumedOnce() {
        val config = IdentifiedConfiguration("very-secret-session-token")
        val intent =
            IdentifiedVerification.Contract()
                .createIntent(ApplicationProvider.getApplicationContext(), config)
        assertFalse(intent.toUri(0).contains(config.sessionToken))
        assertFalse(intent.extras.toString().contains(config.sessionToken))
        val id = intent.getStringExtra(IdentifiedVerification.LAUNCH_ID)!!
        assertEquals(config, LaunchRegistry.take(id))
        assertNull(LaunchRegistry.take(id))
    }

    @Test
    fun processDeathIsReportedInsteadOfRestoringCredentials() {
        assertNull(LaunchRegistry.take("lost-process-launch"))
    }

    @Test
    fun completedAndFailedResultsRoundTripThroughTheContract() {
        val contract = IdentifiedVerification.Contract()
        listOf(
                IdentifiedResult.Completed("case", "background_pending"),
                IdentifiedResult.Failed(IdentifiedError.SessionExpired),
            )
            .forEach {
                val data = Intent().putExtra(IdentifiedVerification.RESULT, it)
                assertEquals(it, contract.parseResult(Activity.RESULT_OK, data))
            }
        assertEquals(
            IdentifiedResult.Cancelled,
            contract.parseResult(Activity.RESULT_CANCELED, null),
        )
    }

    @Test
    fun configurationAndAppearanceAreParcelable() {
        val original =
            IdentifiedConfiguration(
                "token",
                appearance = Appearance(showsLockup = false, cornerRadius = 8f),
            )
        val parcel = Parcel.obtain()
        try {
            parcel.writeParcelable(original, 0)
            parcel.setDataPosition(0)
            @Suppress("DEPRECATION")
            val decoded =
                parcel.readParcelable<IdentifiedConfiguration>(
                    IdentifiedConfiguration::class.java.classLoader
                )
            assertEquals(original, decoded)
        } finally {
            parcel.recycle()
        }
    }
}
