package cc.identified.sdk.capture

internal object CaptureOverrides {
    val frames: ((CaptureKind) -> Map<String, ByteArray>)?
        get() = null
}
