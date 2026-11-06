package cc.identified.sdk.integration

import cc.identified.sdk.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in test against the real local gateway/session/orchestrator services. */
class LocalBackendSmokeTest {
    @Test
    fun localSessionConsentMetadataCaseAndResumeRoundTrip() = runBlocking {
        val token = System.getenv("IDENTIFIED_LOCAL_SMOKE_TOKEN")
        assumeTrue("run scripts/local-backend-smoke.py with the local Docker stack", token != null)
        val api = ApiClient("http://127.0.0.1:8101", token!!)
        val session = api.currentSession()
        assertEquals("android_sdk_smoke", session.profileKey)
        assertNull(session.caseId)
        assertEquals(listOf("identity", "biometric"), FlowRouting.consentScopes(session))
        api.recordConsent(FlowRouting.consentScopes(session))
        val created = api.getOrCreateCase(session)
        assertEquals("draft", created.status)
        api.saveMetadata(MetadataPatch.name("Synthetic", "", "SDK Test"))
        api.saveMetadata(
            MetadataPatch.idDetails("1990-01-02", "passport", "SYNTHETIC123", "Passport")
        )
        val resumed = api.currentSession()
        assertEquals(created.id, resumed.caseId)
        assertEquals("Synthetic", resumed.firstName)
        assertEquals("SDK Test", resumed.lastName)
        assertTrue(resumed.identityDetailsComplete == true)
        assertEquals(created.id, api.getCase(created.id).id)
        assertEquals(
            LaunchRoute.Entry(EntryDestination.Checklist(created.id)),
            FlowRouting.launchRoute(resumed),
        )
    }
}
