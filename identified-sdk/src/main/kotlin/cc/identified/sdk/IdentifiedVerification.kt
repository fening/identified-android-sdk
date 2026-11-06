package cc.identified.sdk

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.content.IntentCompat
import java.util.UUID

/** Register this contract from a Fragment, ComponentActivity or Compose launcher. */
public object IdentifiedVerification {
    internal const val LAUNCH_ID = "cc.identified.sdk.launch"
    internal const val RESULT = "cc.identified.sdk.result"

    public class Contract : ActivityResultContract<IdentifiedConfiguration, IdentifiedResult>() {
        override fun createIntent(context: Context, input: IdentifiedConfiguration): Intent =
            Intent(context, IdentifiedActivity::class.java)
                .putExtra(LAUNCH_ID, LaunchRegistry.put(input))

        override fun parseResult(resultCode: Int, intent: Intent?): IdentifiedResult =
            if (resultCode == Activity.RESULT_OK && intent != null)
                IntentCompat.getParcelableExtra(
                    intent.apply { setExtrasClassLoader(IdentifiedResult::class.java.classLoader) },
                    RESULT,
                    IdentifiedResult::class.java,
                ) ?: IdentifiedResult.Cancelled
            else IdentifiedResult.Cancelled
    }
}

/** Intent extras survive process death. Only an opaque ID crosses that boundary. */
internal object LaunchRegistry {
    private data class Entry(val configuration: IdentifiedConfiguration, val insertedAt: Long)

    private val entries = linkedMapOf<String, Entry>()

    @Synchronized
    fun put(configuration: IdentifiedConfiguration): String {
        val now = android.os.SystemClock.elapsedRealtime()
        entries.entries.removeAll { now - it.value.insertedAt > 30 * 60 * 1000 }
        while (entries.size >= 16) entries.remove(entries.keys.first())
        return UUID.randomUUID().toString().also { entries[it] = Entry(configuration, now) }
    }

    @Synchronized
    fun take(id: String?): IdentifiedConfiguration? = entries.remove(id)?.configuration
}
