package cc.identified.sdk

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.espresso.Espresso.pressBack
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Full native display evidence using synthetic HTTP records and the production SDK Activity.
 * FLAG_SECURE is cleared only on this test's synthetic Activity. No camera or uploads are used.
 */
@RunWith(AndroidJUnit4::class)
class HostedUiScreenshotTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var server: MockWebServer? = null
    private var scenario: ActivityScenario<IdentifiedActivity>? = null
    private lateinit var fixture: ScreenshotBackend

    @After fun cleanup() {
        scenario?.close()
        server?.shutdown()
    }

    private fun awaitNode(matcher: SemanticsMatcher) {
        compose.waitUntil(20_000) {
            runCatching { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
        }
    }

    private fun tapContinue() {
        val matcher = hasTestTag("identified-continue") and isEnabled()
        awaitNode(matcher)
        compose.onNode(matcher).performClick()
    }

    private fun launch(scene: String, scheme: String) {
        fixture = ScreenshotBackend(scene)
        server = MockWebServer().apply { dispatcher = fixture; start() }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appearance = Appearance(colorScheme = if (scheme == "dark") ColorSchemePreference.DARK else ColorSchemePreference.LIGHT)
        val intent = IdentifiedVerification.Contract().createIntent(
            context, IdentifiedConfiguration("ui-$scene", server!!.url("/").toString(), appearance)
        )
        scenario = ActivityScenario.launchActivityForResult(intent)
        // This applies only to synthetic records launched by this instrumentation test.
        scenario!!.onActivity { it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        if (scene == "entry" || scene == "consent") {
            awaitNode(hasText("Begin verification"))
            if (scene == "consent") tapContinue()
        } else if (scene != "status") {
            awaitNode(hasText("Continue verification"))
            tapContinue()
            awaitNode(hasText("Continue") and isEnabled())
            if (scene != "checklist") tapContinue()
        }
        val title = mapOf(
            "entry" to "Verify your identity", "consent" to "Consent & privacy",
            "checklist" to "Verify your identity", "name" to "Your name", "id" to "Your ID details",
            "documents" to "Front of ID", "selfie" to "Liveness check",
            "address" to "Proof of address", "review" to "Review & submit", "status" to "Manual review required"
        ).getValue(scene)
        awaitNode(hasText(title))
        if (scene == "review") awaitNode(hasTestTag("identified-submit") and isEnabled())
        if (scene == "status") awaitNode(hasTestTag("identified-done") and isEnabled())
        compose.waitForIdle()
        assertTrue("Unexpected fixture requests: ${fixture.unexpected}", fixture.unexpected.isEmpty())
    }

    private fun capture(scene: String, scheme: String, datePicker: Boolean = false) {
        launch(scene, scheme)
        if (datePicker) {
            compose.onNodeWithTag("identified-dob").performClick()
            awaitNode(hasTestTag("identified-dob-dialog"))
            compose.waitForIdle()
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.let(::File) ?: File(context.externalMediaDirs.first { it != null }, "hosted-ui-screenshots")
        assertTrue("Cannot create screenshot directory $output", output.isDirectory || output.mkdirs())
        // Compose/Espresso idle does not await WindowManager's task/launcher transition.
        // Use real elapsed time: advancing the Compose clock cannot settle the compositor.
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(500)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Native screenshot unavailable" }
        try {
            File(output, "$scheme-$scene${if (datePicker) "-datepicker" else ""}.png").outputStream().use {
                assertTrue("PNG encoding failed", bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
        if (scene == "documents" || scene == "selfie") {
            // The inline action must stay reachable on compact screens with enlarged text.
            compose.onNodeWithText("Open camera").performScrollTo().assertIsDisplayed()
        }
    }

    /** Explicit human comparison only. Production capture protection is unchanged. */
    @Test fun recordableSelfieForManualComparison() {
        val args=InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue("Opt in with cameraComparison=true",
            args.getString("cameraComparison") == "true")
        cc.identified.sdk.capture.CaptureOverrides.frames = null
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName, android.Manifest.permission.CAMERA)
        launch("selfie", "light")
        scenario!!.onActivity {
            @Suppress("DEPRECATION")
            it.setTaskDescription(android.app.ActivityManager.TaskDescription("Identified camera test"))
        }
        compose.onNodeWithText("Open camera").performScrollTo().performClick()
        awaitNode(hasTestTag("camera-stage"))
        val marker=File(instrumentation.targetContext.externalMediaDirs.first { it != null },"manual-camera-ready")
        marker.writeText("ready")
        scenario!!.onActivity { activity ->
            fun find(view: android.view.View): androidx.camera.view.PreviewView? {
                if(view is androidx.camera.view.PreviewView) return view
                if(view is android.view.ViewGroup) for(i in 0 until view.childCount) {
                    find(view.getChildAt(i))?.let { return it }
                }
                return null
            }
            val view=find(activity.window.decorView)
            view?.postDelayed({
                val details=mutableMapOf<String,String>()
                details["view"]="${view.width}x${view.height} ${view.scaleType}"
                for(name in listOf("getSensorToViewTransform","getOutputTransform")) {
                    val result=runCatching { view.javaClass.getMethod(name).invoke(view) }.getOrNull()
                    details[name]=result.toString()
                    if(result!=null) for(property in listOf("getMatrix","getViewPortSize")) {
                        runCatching { result.javaClass.getMethod(property).invoke(result) }.getOrNull()?.let {
                            details["$name.$property"]=it.toString()
                        }
                    }
                }
                File(instrumentation.targetContext.externalMediaDirs.first { it!=null },"manual-camera-geometry.txt")
                    .writeText(details.entries.joinToString("\n") { "${it.key}=${it.value}" })
            },1500)
        }
        // No automated gestures while the person compares the two apps.
        val end=SystemClock.elapsedRealtime()+180_000
        while(SystemClock.elapsedRealtime()<end) {
            compose.mainClock.advanceTimeByFrame()
            SystemClock.sleep(100)
        }
        marker.delete()
    }

    @Test fun backAndCloseReturnCancellationWithoutMutation() {
        launch("name", "light")
        pressBack()
        awaitNode(hasText("Continue") and isEnabled())
        compose.onNodeWithTag("identified-close").performClick()
        compose.waitUntil(20_000) { scenario!!.state == Lifecycle.State.DESTROYED }
        val result = scenario!!.result
        assertEquals(Activity.RESULT_OK, result.resultCode)
        assertEquals(IdentifiedResult.Cancelled, IdentifiedVerification.Contract().parseResult(result.resultCode, result.resultData))
        assertTrue("Screenshot navigation must be read only", fixture.requests.all { it.method == "GET" })
        assertTrue("Unexpected requests: ${fixture.unexpected}", fixture.unexpected.isEmpty())
    }
    @Test fun light_id_datepicker() { capture("id", "light", datePicker = true) }
    @Test fun dark_id_datepicker() { capture("id", "dark", datePicker = true) }
    @Test fun light_entry() { capture("entry", "light") }
    @Test fun light_consent() { capture("consent", "light") }
    @Test fun light_checklist() { capture("checklist", "light") }
    @Test fun light_name() { capture("name", "light") }
    @Test fun light_id() { capture("id", "light") }
    @Test fun light_documents() { capture("documents", "light") }
    @Test fun light_selfie() { capture("selfie", "light") }
    @Test fun light_address() { capture("address", "light") }
    @Test fun light_review() { capture("review", "light") }
    @Test fun light_status() { capture("status", "light") }
    @Test fun dark_entry() { capture("entry", "dark") }
    @Test fun dark_consent() { capture("consent", "dark") }
    @Test fun dark_checklist() { capture("checklist", "dark") }
    @Test fun dark_name() { capture("name", "dark") }
    @Test fun dark_id() { capture("id", "dark") }
    @Test fun dark_documents() { capture("documents", "dark") }
    @Test fun dark_selfie() { capture("selfie", "dark") }
    @Test fun dark_address() { capture("address", "dark") }
    @Test fun dark_review() { capture("review", "dark") }
    @Test fun dark_status() { capture("status", "dark") }
}

/** Frozen synthetic records from the parent web/iOS server, served on-device without adb reverse.
 * Only GETs are accepted: navigation cannot silently mutate the fixture or hide network failures.
 */
private class ScreenshotBackend(private val scene: String) : Dispatcher() {
    val requests = CopyOnWriteArrayList<RecordedRequest>()
    val unexpected = CopyOnWriteArrayList<String>()

    override fun dispatch(request: RecordedRequest): MockResponse {
        requests += request
        val path = request.requestUrl!!.encodedPath
        val body = when (path) {
            "/api/v1/sessions/current" -> sessions[scene]
            "/api/verification/cases/ui-case" -> cases[scene]
            else -> null
        }
        if (request.method == "GET" && request.getHeader("Authorization") == "Bearer ui-$scene" && body != null)
            return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
        unexpected += "${request.method} ${request.path}"
        return MockResponse().setResponseCode(500).setBody("{\"error\":\"Unexpected screenshot fixture request\"}")
    }

    companion object {
        private val sessions = mapOf(
            "entry" to """{"data":{"id":"ui-session","subject_ref":"synthetic-ui-person","profile_key":"standard_identity","status":"active","required_documents":["license_front","license_back","selfie"],"required_checks":["id_document","biometric_liveness","biometric_face_match"],"collect_name":true,"allowed_id_types":["national_id","passport","drivers_license"],"first_name":"Ama","last_name":"Mensah","identity_name_complete":true,"identity_details_complete":true,"id_details":{"dob":"1990-01-02","id_type":"national_id","id_number":"GHA-123456789-0"},"branding":{"display_name":"Test business"}}}""",
            "consent" to """{"data":{"id":"ui-session","subject_ref":"synthetic-ui-person","profile_key":"standard_identity","status":"active","required_documents":["license_front","license_back","selfie"],"required_checks":["id_document","biometric_liveness","biometric_face_match"],"collect_name":true,"allowed_id_types":["national_id","passport","drivers_license"],"first_name":"Ama","last_name":"Mensah","identity_name_complete":true,"identity_details_complete":true,"id_details":{"dob":"1990-01-02","id_type":"national_id","id_number":"GHA-123456789-0"},"branding":{"display_name":"Test business"}}}""",
            "checklist" to """{"data":{"id":"ui-session","subject_ref":"synthetic-ui-person","profile_key":"standard_identity","status":"active","required_documents":["license_front","license_back","selfie"],"required_checks":["id_document","biometric_liveness","biometric_face_match"],"collect_name":true,"allowed_id_types":["national_id","passport","drivers_license"],"first_name":"Ama","last_name":"Mensah","identity_name_complete":true,"identity_details_complete":true,"id_details":{"dob":"1990-01-02","id_type":"national_id","id_number":"GHA-123456789-0"},"branding":{"display_name":"Test business"},"case_id":"ui-case","case_status":"draft","consent_recorded":true}}""",
            "name" to """{"data":{"id":"ui-session","subject_ref":"synthetic-ui-person","profile_key":"standard_identity","status":"active","required_documents":["license_front","license_back","selfie"],"required_checks":["id_document","biometric_liveness","biometric_face_match"],"collect_name":true,"allowed_id_types":["national_id","passport","drivers_license"],"first_name":"","last_name":"","identity_name_complete":false,"identity_details_complete":true,"id_details":{"dob":"1990-01-02","id_type":"national_id","id_number":"GHA-123456789-0"},"branding":{"display_name":"Test business"},"case_id":"ui-case","case_status":"draft","consent_recorded":true}}""",
            "id" to """{"data":{"id":"ui-session","subject_ref":"synthetic-ui-person","profile_key":"standard_identity","status":"active","required_documents":["license_front","license_back","selfie"],"required_checks":["id_document","biometric_liveness","biometric_face_match"],"collect_name":true,"allowed_id_types":["national_id","passport","drivers_license"],"first_name":"Ama","last_name":"Mensah","identity_name_complete":true,"identity_details_complete":false,"id_details":{"dob":"1990-01-02","id_type":"national_id","id_number":"GHA-123456789-0"},"branding":{"display_name":"Test business"},"case_id":"ui-case","case_status":"draft","consent_recorded":true}}""",
            "documents" to """{"data":{"id":"ui-session","subject_ref":"synthetic-ui-person","profile_key":"standard_identity","status":"active","required_documents":["license_front","license_back","selfie"],"required_checks":["id_document","biometric_liveness","biometric_face_match"],"collect_name":true,"allowed_id_types":["national_id","passport","drivers_license"],"first_name":"Ama","last_name":"Mensah","identity_name_complete":true,"identity_details_complete":true,"id_details":{"dob":"1990-01-02","id_type":"national_id","id_number":"GHA-123456789-0"},"branding":{"display_name":"Test business"},"case_id":"ui-case","case_status":"draft","consent_recorded":true}}""",
            "selfie" to """{"data":{"id":"ui-session","subject_ref":"synthetic-ui-person","profile_key":"standard_identity","status":"active","required_documents":["license_front","license_back","selfie"],"required_checks":["id_document","biometric_liveness","biometric_face_match"],"collect_name":true,"allowed_id_types":["national_id","passport","drivers_license"],"first_name":"Ama","last_name":"Mensah","identity_name_complete":true,"identity_details_complete":true,"id_details":{"dob":"1990-01-02","id_type":"national_id","id_number":"GHA-123456789-0"},"branding":{"display_name":"Test business"},"case_id":"ui-case","case_status":"draft","consent_recorded":true}}""",
            "address" to """{"data":{"id":"ui-session","subject_ref":"synthetic-ui-person","profile_key":"standard_identity","status":"active","required_documents":["license_front","license_back","selfie","proof_of_address"],"required_checks":["id_document","biometric_liveness","biometric_face_match"],"collect_name":true,"allowed_id_types":["national_id","passport","drivers_license"],"first_name":"Ama","last_name":"Mensah","identity_name_complete":true,"identity_details_complete":true,"id_details":{"dob":"1990-01-02","id_type":"national_id","id_number":"GHA-123456789-0"},"branding":{"display_name":"Test business"},"case_id":"ui-case","case_status":"draft","consent_recorded":true}}""",
            "review" to """{"data":{"id":"ui-session","subject_ref":"synthetic-ui-person","profile_key":"standard_identity","status":"active","required_documents":["license_front","license_back","selfie"],"required_checks":["id_document","biometric_liveness","biometric_face_match"],"collect_name":true,"allowed_id_types":["national_id","passport","drivers_license"],"first_name":"Ama","last_name":"Mensah","identity_name_complete":true,"identity_details_complete":true,"id_details":{"dob":"1990-01-02","id_type":"national_id","id_number":"GHA-123456789-0"},"branding":{"display_name":"Test business"},"case_id":"ui-case","case_status":"draft","consent_recorded":true}}""",
            "status" to """{"data":{"id":"ui-session","subject_ref":"synthetic-ui-person","profile_key":"standard_identity","status":"active","required_documents":["license_front","license_back","selfie"],"required_checks":["id_document","biometric_liveness","biometric_face_match"],"collect_name":true,"allowed_id_types":["national_id","passport","drivers_license"],"first_name":"Ama","last_name":"Mensah","identity_name_complete":true,"identity_details_complete":true,"id_details":{"dob":"1990-01-02","id_type":"national_id","id_number":"GHA-123456789-0"},"branding":{"display_name":"Test business"},"case_id":"ui-case","case_status":"manual_review","consent_recorded":true}}""",
        )
        private val cases = mapOf(
            "checklist" to """{"data":{"id":"ui-case","status":"draft","required_documents":["license_front","license_back","selfie"],"documents":[],"checks":[]}}""",
            "name" to """{"data":{"id":"ui-case","status":"draft","required_documents":["license_front","license_back","selfie"],"documents":[],"checks":[]}}""",
            "id" to """{"data":{"id":"ui-case","status":"draft","required_documents":["license_front","license_back","selfie"],"documents":[],"checks":[]}}""",
            "documents" to """{"data":{"id":"ui-case","status":"draft","required_documents":["license_front","license_back","selfie"],"documents":[],"checks":[]}}""",
            "selfie" to """{"data":{"id":"ui-case","status":"draft","required_documents":["license_front","license_back","selfie"],"documents":[{"id":"license_front","doc_type":"license_front","status":"uploaded"},{"id":"license_back","doc_type":"license_back","status":"uploaded"}],"checks":[]}}""",
            "address" to """{"data":{"id":"ui-case","status":"draft","required_documents":["license_front","license_back","selfie","proof_of_address"],"documents":[{"id":"license_front","doc_type":"license_front","status":"uploaded"},{"id":"license_back","doc_type":"license_back","status":"uploaded"},{"id":"selfie-left","doc_type":"selfie","capture_role":"left","status":"uploaded"},{"id":"selfie-right","doc_type":"selfie","capture_role":"right","status":"uploaded"},{"id":"selfie-center","doc_type":"selfie","capture_role":"center","status":"uploaded"}],"checks":[]}}""",
            "review" to """{"data":{"id":"ui-case","status":"draft","required_documents":["license_front","license_back","selfie"],"documents":[{"id":"license_front","doc_type":"license_front","status":"uploaded"},{"id":"license_back","doc_type":"license_back","status":"uploaded"},{"id":"selfie-left","doc_type":"selfie","capture_role":"left","status":"uploaded"},{"id":"selfie-right","doc_type":"selfie","capture_role":"right","status":"uploaded"},{"id":"selfie-center","doc_type":"selfie","capture_role":"center","status":"uploaded"}],"checks":[]}}""",
            "status" to """{"data":{"id":"ui-case","status":"manual_review","required_documents":["license_front","license_back","selfie"],"documents":[{"id":"license_front","doc_type":"license_front","status":"uploaded"},{"id":"license_back","doc_type":"license_back","status":"uploaded"},{"id":"selfie-left","doc_type":"selfie","capture_role":"left","status":"uploaded"},{"id":"selfie-right","doc_type":"selfie","capture_role":"right","status":"uploaded"},{"id":"selfie-center","doc_type":"selfie","capture_role":"center","status":"uploaded"}],"checks":[]}}""",
        )
    }
}
