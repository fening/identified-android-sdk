package cc.identified.sdk

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.closeSoftKeyboard
import cc.identified.sdk.capture.CaptureKind
import cc.identified.sdk.capture.CaptureOverrides
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/**
 * Exercises the real Activity, Compose screens, API client and result contract. Camera fixtures
 * replace hardware only, with explicit debug-only injection.
 */
@RunWith(AndroidJUnit4::class)
class FlowIntegrationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var server: MockWebServer
    private var scenario: ActivityScenario<IdentifiedActivity>? = null
    private lateinit var backend: FixtureBackend

    @Before
    fun setup() {
        backend = FixtureBackend()
        server =
            MockWebServer().apply {
                dispatcher = backend
                start()
            }
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        val bytes =
            ByteArrayOutputStream()
                .also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                .toByteArray()
        bitmap.recycle()
        CaptureOverrides.frames = { kind ->
            if (kind == CaptureKind.SELFIE)
                mapOf("center" to bytes, "left" to bytes, "right" to bytes)
            else mapOf("image" to bytes)
        }
    }

    @After
    fun cleanup() {
        scenario?.close()
        CaptureOverrides.frames = null
        server.shutdown()
    }

    private fun launch(appearance: Appearance = Appearance()) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent =
            IdentifiedVerification.Contract()
                .createIntent(
                    context,
                    IdentifiedConfiguration("fixture-token", server.url("/").toString(), appearance),
                )
        scenario = ActivityScenario.launchActivityForResult(intent)
    }

    private fun mappedTag(name: String) =
        "identified-" +
            when (name) {
                "begin" -> "continue"
                "address-find" -> "find-address"
                else -> name
            }

    private fun tag(name: String) = compose.onNodeWithTag(mappedTag(name))

    private fun awaitTag(name: String) {
        try {
            compose.waitUntil(15_000) {
                runCatching {
                        compose
                            .onAllNodesWithTag(mappedTag(name))
                            .fetchSemanticsNodes()
                            .isNotEmpty()
                    }
                    .getOrDefault(false)
            }
        } catch (error: Throwable) {
            val hierarchy =
                runCatching { compose.onRoot().printToString() }.getOrElse { it.message.orEmpty() }
            val ended = if (scenario?.state == androidx.lifecycle.Lifecycle.State.DESTROYED)
                runCatching { scenario!!.result.let { IdentifiedVerification.Contract().parseResult(it.resultCode, it.resultData) } }.getOrNull() else null
            throw AssertionError(
                "Missing tag ${mappedTag(name)}; activity=${scenario?.state}; requests=${backend.requests.map { it.path }}; result=$ended; hierarchy=$hierarchy",
                error,
            )
        }
    }

    private fun tap(name: String) {
        awaitTag(name)
        compose.waitUntil(15_000) {
            runCatching {
                    compose
                        .onAllNodes(hasTestTag(mappedTag(name)) and isEnabled())
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                .getOrDefault(false)
        }
        runCatching { tag(name).performScrollTo() }
        tag(name).performClick()
    }

    private fun tapText(text: String) {
        compose.waitUntil(15_000) {
            runCatching { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
                .getOrDefault(false)
        }
        runCatching { compose.onNodeWithText(text).performScrollTo() }
        compose.onNodeWithText(text).performClick()
    }

    private fun awaitResult(): IdentifiedResult {
        compose.waitUntil(15_000) {
            scenario!!.state == androidx.lifecycle.Lifecycle.State.DESTROYED
        }
        val result = scenario!!.result
        assertEquals(Activity.RESULT_OK, result.resultCode)
        return IdentifiedVerification.Contract().parseResult(result.resultCode, result.resultData)
    }

    @Test
    fun invalidAndExpiredSessionsReturnTypedFailure() {
        backend.failure = "session expired"
        launch()
        assertEquals(IdentifiedResult.Failed(IdentifiedError.SessionExpired), awaitResult())
        assertEquals(1, backend.requests.size)
        assertEquals("Bearer fixture-token", backend.requests.single().getHeader("Authorization"))
    }

    @Test
    fun unknownSessionReturnsInvalid() {
        backend.failure = "invalid session token"
        launch()
        assertEquals(IdentifiedResult.Failed(IdentifiedError.SessionInvalid), awaitResult())
    }

    @Test
    fun activityIsPrivateAndSecureAndRotationKeepsSessionInMemory() {
        launch()
        awaitTag("begin")
        scenario!!.onActivity {
            assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            assertFalse(it.intent.extras.toString().contains("fixture-token"))
            @Suppress("DEPRECATION")
            val info = it.packageManager.getActivityInfo(it.componentName, 0)
            assertFalse(info.exported)
        }
        scenario!!.recreate()
        awaitTag("begin")
        tap("close")
        assertEquals(IdentifiedResult.Cancelled, awaitResult())
    }

    @Test
    fun resumedSubmittedCaseShowsStatusAndReturnsItsLatestValue() {
        backend.resumed = true
        backend.caseStatus = "manual_review"
        launch()
        tap("done")
        assertEquals(IdentifiedResult.Completed("case-1", "manual_review"), awaitResult())
    }

    @Test
    fun fullStandardProfileSubmitsAllEvidenceInOrder() {
        launch()
        tap("begin")
        tap("consent-toggle")
        tap("continue")
        tap("continue") // checklist to name
        awaitTag("first-name")
        tag("first-name").performTextInput("Ama")
        tag("last-name").performTextInput("Mensah")
        closeSoftKeyboard()
        tap("continue")
        tap("dob")
        tap("dob-confirm")
        awaitTag("id-number")
        tag("id-number").performTextInput("GHA-123456789-0")
        closeSoftKeyboard()
        tap("continue")
        repeat(2) {
            tap("open-camera")
            tapText("Capture test frame")
            tap("use-photo")
        }
        tap("open-camera")
        tapText("Capture test frame")
        tap("use-photo")
        tap("submit")
        tap("done")
        assertEquals(IdentifiedResult.Completed("case-1", "manual_review"), awaitResult())
        assertEquals(
            listOf("license_front", "license_back", "selfie:left", "selfie:right", "selfie:center"),
            backend.uploads,
        )
        assertTrue(backend.requests.any { it.path?.endsWith("/checks/biometric") == true })
        assertTrue(backend.requests.all { it.getHeader("X-API-Key") == null })
    }

    @Test
    fun screeningResumeSavesAddressBeforePrimaryAndExtraPage() {
        backend.resumed = true
        backend.screening = true
        backend.includeIdentity = true
        launch()
        tap("begin")
        tap("continue")
        awaitTag("address-code")
        tag("address-code").performTextInput("GA1839124")
        tap("address-find") // server 503; unresolved typed address is accepted
        tapText("Take a photo instead")
        tapText("Capture test frame")
        awaitTag("use-photo")
        assertTrue("Camera evidence must wait for explicit acceptance", backend.uploads.isEmpty())
        tap("use-photo")
        tapText("Add another page")
        tapText("Capture test frame")
        awaitTag("use-photo")
        assertEquals(1, backend.uploads.size)
        tap("use-photo")
        tap("continue")
        tap("continue") // resumed checklist to review
        tap("submit")
        tap("done")
        assertEquals(IdentifiedResult.Completed("case-1", "manual_review"), awaitResult())
        val paths = backend.requests.map { it.path.orEmpty() }
        val addressIndex =
            backend.requests.indexOfFirst { it.body.clone().readUtf8().contains("stated_address") }
        val uploadIndex = paths.indexOfFirst { it.endsWith("/documents") }
        assertTrue(addressIndex >= 0 && addressIndex < uploadIndex)
        assertEquals(listOf("proof_of_address", "proof_of_address:page_2"), backend.uploads)
    }

    @Test
    fun requestedNameCorrectionUnlocksResubmission() {
        backend.resumed = true
        backend.includeIdentity = true
        backend.caseStatus = "changes_requested"
        launch()
        tap("begin")
        tapText("Update name")
        awaitTag("first-name")
        tag("first-name").performTextReplacement("Akosua")
        tap("continue")
        tap("continue")
        tap("submit")
        tap("done")
        assertEquals(IdentifiedResult.Completed("case-1", "manual_review"), awaitResult())
        assertTrue(backend.correctionApplied)
        assertTrue(backend.uploads.isEmpty())
    }

    @Test
    fun datePickerUsesTheHostsDarkSurfaceAndAccent() {
        backend.resumed = true
        backend.includeIdentity = true
        backend.caseStatus = "changes_requested"
        val palette = Palette.Dark.copy(surface = 0xFF123456, accent = 0xFF33AADD)
        launch(Appearance(dark = palette, colorScheme = ColorSchemePreference.DARK))
        tap("begin")
        tapText("Update ID details")
        // Synthetic fixture only: allow a pixel assertion in this test. The
        // production FLAG_SECURE behavior is asserted in a separate test.
        scenario!!.onActivity { it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        tap("dob")
        awaitTag("dob-dialog")
        val image = tag("dob-dialog").captureToImage().toPixelMap()
        assertEquals(Color(palette.surface), image[image.width / 2, 2])
        var accentPixels = 0
        for (y in 0 until image.height step 2) for (x in 0 until image.width step 2) if (
            image[x, y] == Color(palette.accent)
        )
            accentPixels++
        assertTrue("Selected calendar date must use the host accent", accentPixels > 20)
        tap("dob-confirm")
        tap("close")
        assertEquals(IdentifiedResult.Cancelled, awaitResult())
    }

    @Test
    fun exhaustedAddressRefusalStaysVisibleAfterAddressChange() {
        backend.resumed = true
        backend.screening = true
        backend.includeIdentity = true
        backend.refuseAddress = true
        launch()
        tap("begin")
        tap("continue")
        awaitTag("address-code")
        tag("address-code").performTextInput("GA1839124")
        tap("address-find")
        tapText("Take a photo instead")
        tapText("Capture test frame")
        tap("use-photo")
        awaitTag("address-refusal")
        tag("address-refusal").assertTextEquals("Synthetic proof refused: no attempts remain")
        tapText("Change")
        awaitTag("address-code")
        tag("address-code").performTextReplacement("GA1849124")
        tap("address-find")
        awaitTag("address-refusal")
        tag("address-refusal").assertTextEquals("Synthetic proof refused: no attempts remain")
        compose.onNodeWithText("Take a photo instead").assertDoesNotExist()
        tap("close")
        assertEquals(IdentifiedResult.Cancelled, awaitResult())
    }
}

/** Synthetic data only. This is an API-contract fixture, not an OCR/biometric provider. */
private class FixtureBackend : Dispatcher() {
    @Volatile var failure: String? = null
    @Volatile var resumed = false
    @Volatile var screening = false
    @Volatile var includeIdentity = false
    @Volatile var caseStatus = "draft"
    @Volatile var correctionApplied = false
    @Volatile var refuseAddress = false
    private var first = ""
    private var last = ""
    private var idComplete = false
    private var created = false
    private var consent = false
    val uploads = CopyOnWriteArrayList<String>()
    val requests = CopyOnWriteArrayList<RecordedRequest>()

    private fun response(data: JSONObject) =
        MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(JSONObject().put("data", data).toString())

    private fun documents(): List<JSONObject> {
        val all =
            if (includeIdentity)
                listOf(
                    "license_front",
                    "license_back",
                    "selfie:left",
                    "selfie:right",
                    "selfie:center",
                ) + uploads
            else uploads
        return all.map { value ->
            JSONObject()
                .put("id", value)
                .put("doc_type", value.substringBefore(':'))
                .put("status", "uploaded")
                .apply { if (value.contains(':')) put("capture_role", value.substringAfter(':')) }
        }
    }

    private fun currentCase() =
        JSONObject()
            .put("id", "case-1")
            .put("status", caseStatus)
            .put("documents", JSONArray(documents()))
            .put("checks", JSONArray())

    override fun dispatch(request: RecordedRequest): MockResponse {
        requests += request
        val path = request.requestUrl!!.encodedPath
        failure?.let {
            return MockResponse()
                .setResponseCode(401)
                .setBody(JSONObject().put("error", it).toString())
        }
        if (path == "/api/v1/sessions/current") {
            val docs =
                mutableListOf("license_front", "license_back", "selfie").apply {
                    if (screening) add("proof_of_address")
                }
            return response(
                JSONObject()
                    .put("id", "session-1")
                    .put("subject_ref", "synthetic-person")
                    .put(
                        "profile_key",
                        if (screening) "screening_identity" else "standard_identity",
                    )
                    .put("status", "active")
                    .put("required_documents", JSONArray(docs))
                    .put(
                        "required_checks",
                        JSONArray(
                            listOf("id_document", "biometric_liveness", "biometric_face_match") +
                                if (screening) listOf("background_screening")
                                else emptyList<String>()
                        ),
                    )
                    .put("collect_name", true)
                    .put("allowed_id_types", JSONArray(listOf("national_id")))
                    .put("consent_recorded", consent || resumed)
                    .put("identity_name_complete", includeIdentity || first.isNotEmpty())
                    .put("identity_details_complete", includeIdentity || idComplete)
                    .put("first_name", first.ifEmpty { if (includeIdentity) "Ama" else "" })
                    .put("last_name", last.ifEmpty { if (includeIdentity) "Mensah" else "" })
                    .put("correction_applied", correctionApplied)
                    .put("branding", JSONObject().put("display_name", "Test business"))
                    .apply {
                        if (created || resumed) {
                            put("case_id", "case-1")
                            put("case_status", caseStatus)
                        }
                    }
            )
        }
        if (path == "/api/v1/sessions/consent") {
            consent = true
            return response(JSONObject())
        }
        if (path == "/api/verification/cases" && request.method == "POST") {
            created = true
            return response(currentCase())
        }
        if (path == "/api/v1/sessions/metadata") {
            val meta = JSONObject(request.body.clone().readUtf8()).getJSONObject("metadata")
            if (meta.has("first_name")) first = meta.getString("first_name")
            if (meta.has("last_name")) last = meta.getString("last_name")
            if (meta.has("id_number")) idComplete = true
            if (meta.has("first_name") || meta.has("id_number")) correctionApplied = true
            return response(JSONObject())
        }
        if (path.endsWith("/documents")) {
            val body = request.body.clone().readUtf8()
            val type =
                Regex("name=\"type\".*?\\r\\n\\r\\n([^\\r\\n]+)", RegexOption.DOT_MATCHES_ALL)
                    .find(body)
                    ?.groupValues
                    ?.get(1)
                    .orEmpty()
            val role =
                Regex(
                        "name=\"capture_role\".*?\\r\\n\\r\\n([^\\r\\n]+)",
                        RegexOption.DOT_MATCHES_ALL,
                    )
                    .find(body)
                    ?.groupValues
                    ?.get(1)
            if (type == "proof_of_address" && refuseAddress)
                return MockResponse()
                    .setResponseCode(422)
                    .setBody(
                        "{\"error\":\"Synthetic proof refused: no attempts remain\",\"exhausted\":true}"
                    )
            uploads += type + (role?.let { ":$it" } ?: "")
            return MockResponse().setBody("{\"success\":true}")
        }
        if (path.endsWith("/checks/biometric"))
            return response(
                JSONObject().put("check_type", "biometric_face_match").put("status", "completed")
            )
        if (path.endsWith("/submit")) {
            caseStatus = "manual_review"
            return response(JSONObject().put("case_id", "case-1").put("status", caseStatus))
        }
        if (path == "/api/verification/cases/case-1") return response(currentCase())
        if (path.contains("/addresses/"))
            return MockResponse().setResponseCode(503).setBody("{\"error\":\"lookup unavailable\"}")
        return MockResponse().setResponseCode(404).setBody("{\"error\":\"not found\"}")
    }
}
