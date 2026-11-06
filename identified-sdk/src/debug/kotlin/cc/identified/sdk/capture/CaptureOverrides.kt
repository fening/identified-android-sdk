package cc.identified.sdk.capture

/** Explicit test-APK injection only. Never activated by camera failure or app preferences. */
internal object CaptureOverrides {
    var frames: ((CaptureKind) -> Map<String, ByteArray>)? = null
}
