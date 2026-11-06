package cc.identified.sdk.capture

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Rational
import android.util.Size
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import cc.identified.sdk.capture.core.PixelSize
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Owns only the use cases it binds. Closing this must never unbind a host app's cameras. */
internal class CameraBinding(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val previewView: PreviewView,
    private val kind: CaptureKind,
    private val feedback: (CaptureFeedback) -> Unit,
    private val state: (CameraRunState) -> Unit,
    private val captured: (Map<String, ByteArray>) -> Unit,
) {
    private val main = ContextCompat.getMainExecutor(context)
    private val executor = Executors.newSingleThreadExecutor()
    private val alive = AtomicBoolean(true)
    private val busy = AtomicBoolean(false)
    private val capturing = AtomicBoolean(false)
    private val processor = CaptureProcessor(kind)
    private val detector =
        if (kind == CaptureKind.SELFIE)
            FaceDetection.getClient(
                FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                    .setMinFaceSize(.1f)
                    .build()
            )
        else null
    private val providerFuture = ProcessCameraProvider.getInstance(context)
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var analysis: ImageAnalysis? = null
    private var photo: ImageCapture? = null
    private var cameraStateData: LiveData<CameraState>? = null
    private var cameraObserver: Observer<CameraState>? = null
    private var lastAnalysis = Long.MIN_VALUE
    private var loggedGeometry = false
    @Volatile private var view = PixelSize(0.0, 0.0)

    fun start() {
        view = PixelSize(previewView.width.toDouble(), previewView.height.toDouble())
        providerFuture.addListener(
            {
                if (!alive.get()) return@addListener
                try {
                    val p = providerFuture.get()
                    provider = p
                    val selector =
                        if (kind == CaptureKind.SELFIE) CameraSelector.DEFAULT_FRONT_CAMERA
                        else CameraSelector.DEFAULT_BACK_CAMERA
                    if (!p.hasCamera(selector)) {
                        state(CameraRunState.UNAVAILABLE)
                        return@addListener
                    }
                    val rotation = previewView.display?.rotation ?: android.view.Surface.ROTATION_0
                    val resolution =
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(
                                if (kind != CaptureKind.DOCUMENT) AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                                else AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                            )
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    if (kind != CaptureKind.DOCUMENT) Size(1280, 960)
                                    else Size(1920, 1080),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                                )
                            )
                            .build()
                    val pv =
                        CameraGeometryDiagnostics.configure(Preview.Builder())
                            .setPreviewStabilizationEnabled(false)
                            .setTargetRotation(rotation)
                            .setResolutionSelector(resolution)
                            .build()
                    preview = pv
                    val surfaceProvider = previewView.surfaceProvider
                    pv.setSurfaceProvider { request ->
                        CameraGeometryDiagnostics.surface(kind, request.resolution.width, request.resolution.height)
                        surfaceProvider.onSurfaceRequested(request)
                    }
                    val an =
                        ImageAnalysis.Builder()
                            .setTargetRotation(rotation)
                            .setResolutionSelector(
                                if (kind == CaptureKind.DOCUMENT) ResolutionSelector.Builder()
                                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                                    .setResolutionStrategy(ResolutionStrategy(
                                        Size(960, 540), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                                    ))
                                    .build()
                                else resolution
                            )
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                    analysis = an
                    an.setAnalyzer(executor) { image -> analyze(image) }
                    val group =
                        UseCaseGroup.Builder()
                            .addUseCase(pv)
                            .addUseCase(an)
                    // ID and selfie previews retain the complete camera frame. A
                    // fill-centred ViewPort would crop before FIT_CENTER can
                    // display it. Address-document framing retains its viewport.
                    if (kind == CaptureKind.DOCUMENT) group.setViewPort(
                                ViewPort.Builder(
                                        Rational(previewView.width, previewView.height),
                                        rotation,
                                    )
                                    .setScaleType(ViewPort.FILL_CENTER)
                                    .build()
                            )
                    if (kind != CaptureKind.SELFIE) {
                        // Still output selects the maximum resolution of its aspect, not the
                        // analysis size.
                        photo =
                            ImageCapture.Builder()
                                .setTargetRotation(rotation)
                                .setResolutionSelector(
                                    ResolutionSelector.Builder()
                                        .setAspectRatioStrategy(
                                            if (kind == CaptureKind.CARD) AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                                            else AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                                        )
                                        .setResolutionStrategy(
                                            ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY
                                        )
                                        .build()
                                )
                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                                .build()
                        group.addUseCase(photo!!)
                    }
                    val camera = p.bindToLifecycle(owner, selector, group.build())
                    CameraGeometryDiagnostics.bound(kind, previewView.width, previewView.height,
                        camera.cameraInfo.zoomState.value?.zoomRatio,
                        camera.cameraInfo.zoomState.value?.minZoomRatio,
                        camera.cameraInfo.zoomState.value?.maxZoomRatio)
                    val observer =
                        Observer<CameraState> { cameraState ->
                            if (alive.get() && cameraState.error != null)
                                state(CameraRunState.FAILED)
                            else if (alive.get() && cameraState.type == CameraState.Type.OPEN)
                                state(CameraRunState.RUNNING)
                        }
                    cameraStateData = camera.cameraInfo.cameraState
                    cameraObserver = observer
                    cameraStateData!!.observe(owner, observer)
                } catch (_: Exception) {
                    if (alive.get()) state(CameraRunState.FAILED)
                }
            },
            main,
        )
    }

    private fun analyze(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        val interval = if (kind == CaptureKind.SELFIE) 100 else 120
        if (
            !alive.get() ||
                (kind == CaptureKind.DOCUMENT && capturing.get()) ||
                (lastAnalysis != Long.MIN_VALUE && now - lastAnalysis < interval) ||
                !busy.compareAndSet(false, true)
        ) {
            image.close()
            return
        }
        lastAnalysis = now
        val bitmap: Bitmap
        try {
            if (!loggedGeometry) {
                loggedGeometry = true
                val crop = image.cropRect
                CameraGeometryDiagnostics.frame(kind, image.width, image.height,
                    crop.left, crop.top, crop.right, crop.bottom, image.imageInfo.rotationDegrees)
            }
            bitmap = CameraPixels.analysis(image)
        } catch (_: Exception) {
            busy.set(false)
            if (!alive.get()) detector?.close()
            return
        } finally {
            image.close()
        }
        if (!alive.get()) {
            bitmap.recycle()
            busy.set(false)
            detector?.close()
            return
        }
        if (detector != null) {
            try {
                detector.process(InputImage.fromBitmap(bitmap, 0)).addOnCompleteListener(
                    java.util.concurrent.Executor { it.run() }
                ) { task ->
                    finishFrame(bitmap, now, if (task.isSuccessful) task.result else emptyList())
                }
            } catch (_: Exception) {
                finishFrame(bitmap, now, emptyList())
            }
        } else finishFrame(bitmap, now, emptyList())
    }

    private fun finishFrame(
        bitmap: Bitmap,
        now: Long,
        faces: List<com.google.mlkit.vision.face.Face>,
    ) {
        try {
            if (!alive.get()) return
            val output =
                synchronized(processor) { processor.process(bitmap, view, now / 1000.0, faces).copy(sourceAspect = bitmap.width.toFloat() / bitmap.height) }
            main.execute {
                if (!alive.get()) return@execute
                if (kind == CaptureKind.DOCUMENT) {
                    if (capturing.get()) return@execute
                    if (SystemClock.elapsedRealtime() - now > 400) {
                        if (output.autoCapture) synchronized(processor) { processor.captureFailed() }
                        return@execute
                    }
                }
                feedback(output)
                if (output.autoCapture) shutter(output.documentPage)
                output.completed?.let { if (capturing.compareAndSet(false, true)) captured(it) }
            }
        } catch (_: Exception) {
            // A malformed/transition frame cannot end the verification flow.
        } finally {
            bitmap.recycle()
            busy.set(false)
            if (!alive.get()) detector?.close()
        }
    }

    fun startTimed() =
        synchronized(processor) { processor.startTimed(SystemClock.elapsedRealtime() / 1000.0) }

    fun shutter() = shutter(null)

    private fun shutter(expectedPage: PageQuad?) {
        if (!alive.get()) return
        if (kind == CaptureKind.SELFIE) {
            synchronized(processor) { processor.manual() }
            return
        }
        val still = photo ?: return
        if (!capturing.compareAndSet(false, true)) return
        state(CameraRunState.CAPTURING)
        // Main executor keeps callback delivery alive long enough to close every late ImageProxy.
        try {
            still.takePicture(
                main,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        if (!alive.get()) {
                            image.close()
                            return
                        }
                        try {
                            executor.execute {
                                var bitmap: Bitmap? = null
                                try {
                                    if (!alive.get()) return@execute
                                    bitmap = CameraPixels.photo(image, bounded = kind == CaptureKind.DOCUMENT)
                                    val bytes = if (kind == CaptureKind.DOCUMENT)
                                        CameraPixels.documentStill(bitmap, view, expectedPage)
                                    else CameraPixels.still(bitmap, view, kind)
                                    main.execute {
                                        if (alive.get()) captured(mapOf("image" to bytes))
                                    }
                                } catch (_: Exception) {
                                    failedCapture()
                                } finally {
                                    bitmap?.recycle()
                                    image.close()
                                }
                            }
                        } catch (_: java.util.concurrent.RejectedExecutionException) {
                            image.close()
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        failedCapture()
                    }
                },
            )
        } catch (_: Exception) {
            failedCapture()
        }
    }

    private fun failedCapture() {
        capturing.set(false)
        synchronized(processor) { processor.captureFailed() }
        main.execute { if (alive.get()) state(CameraRunState.RUNNING) }
    }

    fun close() {
        if (!alive.getAndSet(false)) return
        analysis?.clearAnalyzer()
        cameraObserver?.let { cameraStateData?.removeObserver(it) }
        preview?.setSurfaceProvider(null)
        val owned = listOfNotNull(preview, analysis, photo)
        provider?.unbind(*owned.toTypedArray())
        // Do not cancel the shared ProcessCameraProvider singleton's future: other camera owners
        // may use it.
        executor.shutdown()
        if (!busy.get()) detector?.close()
    }
}

internal enum class CameraRunState {
    STARTING,
    RUNNING,
    CAPTURING,
    FAILED,
    UNAVAILABLE,
}
