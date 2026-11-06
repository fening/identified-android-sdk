package cc.identified.sdk

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import cc.identified.sdk.ui.IdentifiedContent

/** Internal Activity. Launch it using [IdentifiedVerification.Contract]. */
public class IdentifiedActivity : ComponentActivity() {
    private var resultDelivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val holder =
            ViewModelProvider(
                this,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        ConfigurationHolder(
                            LaunchRegistry.take(
                                intent.getStringExtra(IdentifiedVerification.LAUNCH_ID)
                            )
                        )
                            as T
                },
            )[ConfigurationHolder::class.java]
        val configuration = holder.configuration
        if (configuration == null) {
            deliver(IdentifiedResult.Failed(IdentifiedError.SessionInvalid))
            return
        }
        setContent {
            val dark =
                when (configuration.appearance.colorScheme) {
                    ColorSchemePreference.DARK -> true
                    ColorSchemePreference.LIGHT -> false
                    ColorSchemePreference.SYSTEM -> isSystemInDarkTheme()
                }
            SideEffect {
                val palette =
                    if (dark) configuration.appearance.dark else configuration.appearance.light
                window.decorView.setBackgroundColor(palette.background.toInt())
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            IdentifiedContent(configuration, ::deliver)
        }
    }

    private fun deliver(result: IdentifiedResult) {
        if (resultDelivered || isFinishing) return
        resultDelivered = true
        setResult(Activity.RESULT_OK, Intent().putExtra(IdentifiedVerification.RESULT, result))
        finish()
    }
}

private class ConfigurationHolder(val configuration: IdentifiedConfiguration?) : ViewModel()
