package cc.identified.sdk.capture

/** Release builds never emit camera diagnostics. */
@Suppress("UNUSED_PARAMETER")
internal object CameraGeometryDiagnostics {
    fun configure(builder: androidx.camera.core.Preview.Builder) = builder
    fun bound(kind: CaptureKind, width: Int, height: Int, zoom: Float?, minimum: Float?, maximum: Float?) = Unit
    fun surface(kind: CaptureKind, width: Int, height: Int) = Unit
    fun frame(kind: CaptureKind, width: Int, height: Int, left: Int, top: Int,
              right: Int, bottom: Int, rotation: Int) = Unit
}
