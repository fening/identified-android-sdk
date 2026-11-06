package cc.identified.sdk.capture.core

internal enum class DocumentHint {
    tooDark,
    tooMuchGlare,
    tooBright,
    blurry,
    looksSharp,
}

internal data class DocumentQuality(
    var interior: InteriorQuality,
    var ready: Boolean,
    var hint: DocumentHint,
) {
    companion object {
        fun assess(frame: RGBAFrame): DocumentQuality {
            val q =
                CardDetector.assessInterior(
                    frame,
                    frame.luminance(),
                    CardBounds(
                        frame.width * 0.05,
                        frame.height * 0.05,
                        frame.width * 0.95,
                        frame.height * 0.95,
                    ),
                )
            val hint =
                when {
                    q.brightness < CardDetector.minBrightness -> DocumentHint.tooDark
                    q.glare > CardDetector.maxGlare -> DocumentHint.tooMuchGlare
                    q.brightness > CardDetector.maxBrightness -> DocumentHint.tooBright
                    q.sharpness < CardDetector.minSharpness -> DocumentHint.blurry
                    else -> DocumentHint.looksSharp
                }
            val ready =
                q.brightness >= CardDetector.minBrightness &&
                    q.brightness <= CardDetector.maxBrightness &&
                    q.glare <= CardDetector.maxGlare &&
                    q.sharpness >= CardDetector.minSharpness
            return DocumentQuality(q, ready, hint)
        }
    }
}
