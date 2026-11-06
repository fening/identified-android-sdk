package cc.identified.sdk.capture

import android.util.Log

/** Geometry only: no image pixels, landmarks, tokens, or subject information. */
internal object CameraGeometryDiagnostics {
    data class Frame(val width: Int, val height: Int, val left: Int, val top: Int,
                     val right: Int, val bottom: Int, val rotation: Int)
    @Volatile var latestFrame: Frame? = null
        private set
    @Volatile var videoStabilization: Int? = null
        private set
    @Volatile var captureResultCount: Int = 0
        private set
    fun reset() { latestFrame = null; videoStabilization = null; captureResultCount = 0 }
    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    fun configure(builder: androidx.camera.core.Preview.Builder): androidx.camera.core.Preview.Builder {
        val logged=java.util.concurrent.atomic.AtomicBoolean(false)
        androidx.camera.camera2.interop.Camera2Interop.Extender(builder).setSessionCaptureCallback(
            object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: android.hardware.camera2.CameraCaptureSession,
                    request: android.hardware.camera2.CaptureRequest, result: android.hardware.camera2.TotalCaptureResult) {
                    videoStabilization=result.get(android.hardware.camera2.CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE)
                    captureResultCount++
                    if(!logged.compareAndSet(false,true)) return
                    val values=linkedMapOf<String,Any?>(
                        "camera" to session.device.id,
                        "crop" to result.get(android.hardware.camera2.CaptureResult.SCALER_CROP_REGION),
                        "focal" to result.get(android.hardware.camera2.CaptureResult.LENS_FOCAL_LENGTH),
                        "videoStabilization" to result.get(android.hardware.camera2.CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE),
                        "opticalStabilization" to result.get(android.hardware.camera2.CaptureResult.LENS_OPTICAL_STABILIZATION_MODE))
                    if(android.os.Build.VERSION.SDK_INT>=28) values["distortion"]=result.get(android.hardware.camera2.CaptureResult.DISTORTION_CORRECTION_MODE)
                    if(android.os.Build.VERSION.SDK_INT>=30) values["zoom"]=result.get(android.hardware.camera2.CaptureResult.CONTROL_ZOOM_RATIO)
                    if(android.os.Build.VERSION.SDK_INT>=31) values["rotateCrop"]=result.get(android.hardware.camera2.CaptureResult.SCALER_ROTATE_AND_CROP)
                    Log.i("IdentifiedCameraGeometry", "captureResult=$values")
                }
            })
        return builder
    }


    fun bound(kind: CaptureKind, width: Int, height: Int, zoom: Float?, minimum: Float?, maximum: Float?) {
        reset()
        Log.i("IdentifiedCameraGeometry", "kind=$kind preview=${width}x$height zoom=$zoom range=[$minimum,$maximum]")
    }

    fun surface(kind: CaptureKind, width: Int, height: Int) {
        Log.i("IdentifiedCameraGeometry", "kind=$kind previewBuffer=${width}x$height")
    }

    fun frame(kind: CaptureKind, width: Int, height: Int, left: Int, top: Int,
              right: Int, bottom: Int, rotation: Int) {
        latestFrame = Frame(width, height, left, top, right, bottom, rotation)
        Log.i("IdentifiedCameraGeometry",
            "kind=$kind frame=${width}x$height crop=[$left,$top,$right,$bottom] rotation=$rotation")
    }
}
