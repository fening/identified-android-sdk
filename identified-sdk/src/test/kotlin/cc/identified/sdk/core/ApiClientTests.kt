package cc.identified.sdk.core

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ApiClientTests {
    private lateinit var server: MockWebServer
    private lateinit var client: ApiClient

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        client = ApiClient(server.url("/").toString(), "test-token")
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    private fun respond(data: String) {
        server.enqueue(MockResponse().setBody("""{"data":$data}"""))
    }

    @Test
    fun sessionAndMetadataAndConsent() =
        runBlocking<Unit> {
            respond(
                """{"id":"s","subject_ref":"u","profile_key":"p","status":"active","required_checks":null}"""
            )
            assertEquals("s", client.currentSession().id)
            server.takeRequest().also {
                assertEquals("/api/v1/sessions/current", it.path)
                assertEquals("Bearer test-token", it.getHeader("Authorization"))
                assertNull(it.getHeader("X-Management-Token"))
                assertNull(it.getHeader("X-API-Key"))
            }
            respond("{}")
            client.saveMetadata(MetadataPatch.name("Ama", "", "Mensah"))
            server.takeRequest().also {
                assertEquals("PATCH", it.method)
                assertEquals(
                    "Ama",
                    CoreJson.parseToJsonElement(it.body.readUtf8())
                        .jsonObject["metadata"]!!
                        .jsonObject["first_name"]!!
                        .jsonPrimitive
                        .content,
                )
            }
            respond("{}")
            client.recordConsent(listOf("identity", "biometric"))
            server.takeRequest().also {
                val body = CoreJson.parseToJsonElement(it.body.readUtf8()).jsonObject
                assertEquals("2026-09-04", body["version"]!!.jsonPrimitive.content)
                assertEquals(2, body["scopes"]!!.jsonArray.size)
            }
        }

    @Test
    fun caseCallsUseCamelCaseRequestAndSnakeCaseResponse() =
        runBlocking<Unit> {
            respond("""{"id":"c","status":"draft"}""")
            client.getOrCreateCase(HostedSession("s", "fallback", "p", "active", subjectId = ""))
            val body = CoreJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals("fallback", body["subjectId"]!!.jsonPrimitive.content)
            assertEquals("s", body["userId"]!!.jsonPrimitive.content)
            assertNotNull(body["requiredDocuments"])
            respond("""{"id":"c","status":"draft"}""")
            client.getCase("c/a")
            assertEquals("/api/verification/cases/c%2Fa", server.takeRequest().path)
            respond("""{"check_type":"biometric_face_match","status":"completed"}""")
            client.runBiometricCheck("c")
            server.takeRequest().also {
                assertEquals("/api/verification/cases/c/checks/biometric", it.path)
                assertEquals(
                    "biometric_face_match",
                    CoreJson.parseToJsonElement(it.body.readUtf8())
                        .jsonObject["checkType"]!!
                        .jsonPrimitive
                        .content,
                )
            }
            respond("""{"case_id":"c","status":"submitted"}""")
            assertEquals("c", client.submitCase("c").caseId)
            assertEquals("/api/verification/cases/c/submit", server.takeRequest().path)
        }

    @Test
    fun multipartIsOrderedAndUploadDoesNotNeedEnvelope() =
        runBlocking<Unit> {
            server.enqueue(MockResponse().setBody("""{"success":true,"documentUrl":"url"}"""))
            client.uploadDocument("c", "selfie", "center", byteArrayOf(0, 1, 2), "image/jpeg")
            val request = server.takeRequest()
            val body = request.body.readUtf8()
            assertTrue(body.indexOf("name=\"type\"") < body.indexOf("name=\"capture_role\""))
            assertTrue(body.indexOf("name=\"capture_role\"") < body.indexOf("name=\"file\""))
            assertTrue(body.contains("filename=\"selfie.jpg\""))
            assertTrue(body.contains("Content-Type: image/jpeg"))
        }

    @Test
    fun uploadRefusalAndHttpErrors() =
        runBlocking<Unit> {
            server.enqueue(
                MockResponse()
                    .setResponseCode(422)
                    .setBody("""{"error":"Too old","exhausted":true}""")
            )
            try {
                client.uploadDocument(
                    "c",
                    "proof_of_address",
                    null,
                    byteArrayOf(1),
                    "application/pdf",
                )
                fail()
            } catch (e: ApiException) {
                assertEquals(422, e.status)
                assertEquals("Too old", e.message)
                assertTrue(e.exhausted)
            }
            server.enqueue(
                MockResponse().setResponseCode(401).setBody("""{"error":"Session expired"}""")
            )
            try {
                client.currentSession()
                fail()
            } catch (e: ApiException) {
                assertEquals(SessionFailure.EXPIRED, e.sessionFailure)
            }
            server.enqueue(MockResponse().setResponseCode(503).setBody("not json"))
            try {
                client.currentSession()
                fail()
            } catch (e: ApiException) {
                assertEquals("Request failed (503)", e.message)
            }
        }

    @Test
    fun addressesAreEscapedAndCoordinatesAreNamed() =
        runBlocking<Unit> {
            val address =
                """{"code":"GA1839124","latitude":5.6,"longitude":-0.2,"post_code":"GA183"}"""
            respond(address)
            assertEquals("GA183", client.resolveDigitalAddress("GA + &").postCode)
            assertEquals("GA + &", server.takeRequest().requestUrl!!.queryParameter("code"))
            respond(address)
            client.encodeDigitalAddress(5.6, -0.2)
            server.takeRequest().requestUrl!!.also {
                assertEquals("5.6", it.queryParameter("lat"))
                assertEquals("-0.2", it.queryParameter("lng"))
            }
        }

    @Test
    fun cancellationCancelsInFlightCall() =
        runBlocking<Unit> {
            server.enqueue(MockResponse().setBody("{}").setBodyDelay(1, TimeUnit.SECONDS))
            val job = launch { client.currentSession() }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
        }

    @Test
    fun invalidEnvelopesAreTypedErrors() =
        runBlocking<Unit> {
            for (body in listOf("not JSON", "{}", "{\"data\":null}", "{\"data\":{}}")) {
                server.enqueue(MockResponse().setBody(body))
                try {
                    client.currentSession()
                    fail("Expected decoding error")
                } catch (e: ApiException) {
                    assertEquals(0, e.status)
                    assertTrue(e.message.startsWith("Invalid response:"))
                }
            }
            server.enqueue(MockResponse().setBody("{\"data\":[]}"))
            try {
                client.saveMetadata(MetadataPatch.name("a", "", "b"))
                fail()
            } catch (e: ApiException) {
                assertEquals(0, e.status)
            }
        }

    @Test
    fun uploadFallbackAndOversizeAndNoRole() =
        runBlocking<Unit> {
            server.enqueue(MockResponse().setResponseCode(422))
            try {
                client.uploadDocument(
                    "c",
                    "proof_of_address",
                    null,
                    byteArrayOf(),
                    "application/pdf",
                )
                fail()
            } catch (e: ApiException) {
                assertFalse(e.exhausted)
                assertEquals(
                    "We couldn't accept this document. Try a clearer photo, or a different document from the list.",
                    e.message,
                )
            }
            val body = server.takeRequest().body.readUtf8()
            assertFalse(body.contains("capture_role"))
            assertTrue(body.contains("proof_of_address.pdf"))
            server.enqueue(
                MockResponse().setResponseCode(413).setBody("{\"error\":\"file too large\"}")
            )
            try {
                client.uploadDocument("c", "license_front", null, byteArrayOf(), "image/jpeg")
                fail()
            } catch (e: ApiException) {
                assertEquals(413, e.status)
                assertEquals("file too large", e.message)
            }
        }

    @Test
    fun addressErrorsPreserveStatusAndMessage() =
        runBlocking<Unit> {
            for (status in listOf(400, 404, 503)) {
                server.enqueue(
                    MockResponse().setResponseCode(status).setBody("{\"error\":\"lookup failure\"}")
                )
                try {
                    client.resolveDigitalAddress("bad")
                    fail()
                } catch (e: ApiException) {
                    assertEquals(status, e.status)
                    assertEquals("lookup failure", e.message)
                }
                server.enqueue(
                    MockResponse().setResponseCode(status).setBody("{\"error\":\"encode failure\"}")
                )
                try {
                    client.encodeDigitalAddress(0.0, 0.0)
                    fail()
                } catch (e: ApiException) {
                    assertEquals(status, e.status)
                }
            }
        }

    @Test
    fun basePathsAndTransportFailure() =
        runBlocking<Unit> {
            val nested = ApiClient(server.url("/prefix/").toString(), "token")
            respond("{\"id\":\"c\",\"status\":\"draft\"}")
            nested.getCase("c")
            assertEquals("/prefix/api/verification/cases/c", server.takeRequest().path)
            val deadUrl = server.url("/").toString()
            server.shutdown()
            try {
                ApiClient(deadUrl, "token").getCase("c")
                fail()
            } catch (e: ApiException) {
                assertEquals(0, e.status)
            }
        }

    @Test
    fun caseFallbackProbesSessionOnceToDisambiguateExpiration() =
        runBlocking<Unit> {
            for ((status, message, expected) in
                listOf(
                    Triple(401, "session expired", SessionFailure.EXPIRED),
                    Triple(401, "invalid session token", SessionFailure.INVALID),
                    Triple(503, "upstream unavailable", SessionFailure.INVALID),
                    Triple(401, "invalid management session", SessionFailure.INVALID),
                )) {
                server.enqueue(
                    MockResponse()
                        .setResponseCode(401)
                        .setBody("""{"error":"invalid management session"}""")
                )
                server.enqueue(
                    MockResponse().setResponseCode(status).setBody("""{"error":"$message"}""")
                )
                try {
                    client.getCase("c")
                    fail()
                } catch (e: ApiException) {
                    assertEquals(expected, e.sessionFailure)
                    assertEquals(
                        if (status == 401) message else "invalid management session",
                        e.message,
                    )
                }
                assertEquals("/api/verification/cases/c", server.takeRequest().path)
                val probe = server.takeRequest(1, TimeUnit.SECONDS)
                assertNotNull(probe)
                assertEquals("/api/v1/sessions/current", probe!!.path)
                assertEquals("Bearer test-token", probe.getHeader("Authorization"))
                assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
            }
        }

    @Test
    fun currentAndOtherCaseFailuresNeverProbe() =
        runBlocking<Unit> {
            server.enqueue(
                MockResponse()
                    .setResponseCode(401)
                    .setBody("""{"error":"invalid management session"}""")
            )
            try {
                client.currentSession()
                fail()
            } catch (e: ApiException) {
                assertEquals(SessionFailure.INVALID, e.sessionFailure)
            }
            server.takeRequest()
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
            server.enqueue(
                MockResponse().setResponseCode(401).setBody("""{"error":"invalid session token"}""")
            )
            try {
                client.getCase("c")
                fail()
            } catch (e: ApiException) {
                assertEquals(SessionFailure.INVALID, e.sessionFailure)
            }
            server.takeRequest()
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
        }

    @Test
    fun cancellationDuringSessionProbePropagates() =
        runBlocking<Unit> {
            server.enqueue(
                MockResponse()
                    .setResponseCode(401)
                    .setBody("""{"error":"invalid management session"}""")
            )
            server.enqueue(MockResponse().setBody("{}").setBodyDelay(1, TimeUnit.SECONDS))
            val job = launch { client.getCase("c") }
            withContext(Dispatchers.IO) {
                assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                assertEquals(
                    "/api/v1/sessions/current",
                    server.takeRequest(2, TimeUnit.SECONDS)?.path,
                )
            }
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
        }
}
