package cc.identified.sdk.capture

import cc.identified.sdk.capture.core.CaptureGeometry
import cc.identified.sdk.capture.core.PixelRect
import org.junit.Assert.assertEquals
import org.junit.Test

class CardPreviewGeometryTest {
    @Test fun guideMatchesTheUncroppedCameraPictureInBothOrientations() {
        for ((sourceW, sourceH, stageW, stageH) in listOf(
            listOf(960f,1280f,1080f,2209f),
            listOf(1280f,960f,2209f,1080f),
            listOf(720f,1280f,1080f,2209f),
        )) {
            val scale = minOf(stageW/sourceW,stageH/sourceH)
            val guide = cardGuideSize(stageW,stageH,sourceW/sourceH)
            val crop = CaptureGeometry.guideCrop(PixelRect(0.0,0.0,sourceW.toDouble(),sourceH.toDouble()),margin=1.0)
            assertEquals((crop.width*scale).toFloat(),guide.width,.001f)
            assertEquals((crop.height*scale).toFloat(),guide.height,.001f)
        }
    }
}
