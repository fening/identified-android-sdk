package cc.identified.sdk.core

import kotlinx.serialization.decodeFromString
import org.junit.Assert.*
import org.junit.Test

class CoreTests {
    @Test
    fun nullArraysAndUnknownFieldsDecode() {
        val s =
            CoreJson.decodeFromString<HostedSession>(
                """{"id":"s","subject_ref":"u","profile_key":"p","status":"active","required_documents":null,"unknown":true}"""
            )
        assertEquals(emptyList<String>(), s.requiredDocuments)
        assertNull(s.collectName)
        assertTrue(FlowRules.collectsName(s))
        val c =
            CoreJson.decodeFromString<VerificationCase>(
                """{"id":"c","status":"draft","documents":null,"checks":null}"""
            )
        assertEquals(emptyList<VerificationDocument>(), c.documents)
    }

    @Test
    fun namedSelfieNeedsEveryAcceptedPose() {
        val c =
            VerificationCase(
                "c",
                "draft",
                documents = listOf(VerificationDocument(docType = "selfie", captureRole = "left")),
            )
        assertFalse(FlowRules.hasDocument(c, "selfie"))
        val full =
            c.copy(
                documents =
                    SelfieRole.all.map {
                        VerificationDocument(docType = "selfie", captureRole = it)
                    }
            )
        assertTrue(FlowRules.hasDocument(full, "selfie"))
        assertFalse(
            FlowRules.hasDocument(
                full.copy(
                    documents =
                        full.documents.map {
                            if (it.captureRole == "center") it.copy(status = "rejected") else it
                        }
                ),
                "selfie",
            )
        )
    }

    @Test
    fun routingAndCorrectionGate() {
        val s =
            HostedSession(
                "s",
                "u",
                "p",
                "active",
                requiredDocuments = listOf("selfie", "proof_of_address", "government_id"),
                firstName = "Ama",
                lastName = "Mensah",
            )
        assertEquals(
            listOf("government_id", "selfie", "proof_of_address"),
            FlowRules.orderedCaptureSteps(s.requiredDocuments),
        )
        assertEquals(
            Destination.IdDetails,
            FlowRouting.afterCompleting(FlowScreen.YOUR_NAME, s, NavigationMode.FIRST_RUN),
        )
        assertEquals(
            Destination.Checklist,
            FlowRouting.afterCompleting(FlowScreen.SELFIE, s, NavigationMode.RESUME),
        )
        val complete =
            VerificationCase(
                "c",
                "changes_requested",
                documents = s.requiredDocuments.map { VerificationDocument(docType = it) },
            )
        assertNull(FlowRouting.checklistContinue(complete, s, NavigationMode.RESUME))
        assertEquals(
            Destination.Review,
            FlowRouting.checklistContinue(
                complete,
                s.copy(correctionApplied = true),
                NavigationMode.RESUME,
            ),
        )
    }

    @Test
    fun inputParity() {
        assertEquals("Ama Serwaa", NameInput.normalise(" Ama \t Serwaa "))
        assertEquals(
            NameInput.Problem.TOO_LONG,
            NameInput.validate("a".repeat(200), "", "b".repeat(56)),
        )
        assertTrue(IDDetailsInput.isValidGhanaCard(" gha-123456789-x "))
        assertEquals("GA-183-9124", DigitalAddressCode.mask("ga1839124"))
        assertEquals("G", DigitalAddressCode.mask("1GA"))
        assertFalse(DigitalAddressCode.isValid("GA-183-9124-5"))
        assertEquals("GA1839124", DigitalAddressCode.storageForm(" ga-183-9124 "))
        assertTrue(ReviewInput.isValidEmail(" ama@example.com "))
        assertEquals("page_2", ProofOfAddressRules.captureRole(2))
    }

    @Test
    fun applicantReasonsNeverExposeIdNumbers() {
        val c =
            VerificationCase(
                "c",
                "manual_review",
                checks =
                    listOf(
                        VerificationCheck(
                            "id_document",
                            "completed",
                            resultJson = """{"data_mismatch":"GHA-secret vs other"}""",
                        )
                    ),
            )
        assertEquals(ReviewIssue.NumberNeedsConfirmation, StatusRules.summary(c).reviewIssue)
        assertEquals(
            StatusKind.BACKGROUND_PENDING,
            StatusRules.summary(
                    c.copy(
                        status = "background_pending",
                        checks = listOf(VerificationCheck("background_screening", "pending")),
                    )
                )
                .kind,
        )
    }
}
