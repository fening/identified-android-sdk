package cc.identified.sdk.capture

import android.graphics.BitmapFactory
import android.util.TypedValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.identified.sdk.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Keep guide decoding independent of device DPI: MDPI scaling can turn a
 * 1024px guide into a 2688px bitmap and consume ~29 MB on a 420 DPI device. */
@RunWith(AndroidJUnit4::class)
class CameraGuideResourcesTest {
    @Test fun faceGuidesRemainUnscaled1024PixelResources() {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        for (id in listOf(
            R.drawable.identified_face_guide_center,
            R.drawable.identified_face_guide_left,
            R.drawable.identified_face_guide_right,
        )) {
            val name = resources.getResourceEntryName(id)
            val value = TypedValue()
            resources.getValue(id, value, true)
            assertEquals("$name must stay in drawable-nodpi", TypedValue.DENSITY_NONE, value.density)
            // Exercise the normal density-aware decode path rather than opting
            // out of scaling in test options and hiding a resource regression.
            val bitmap = requireNotNull(BitmapFactory.decodeResource(resources, id)) {
                "$name could not be decoded"
            }
            try {
                assertEquals("$name decoded width", 1024, bitmap.width)
                assertEquals("$name decoded height", 1024, bitmap.height)
            } finally {
                bitmap.recycle()
            }
        }
    }
}
