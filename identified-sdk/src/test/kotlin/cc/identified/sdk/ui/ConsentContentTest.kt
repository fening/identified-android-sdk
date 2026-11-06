package cc.identified.sdk.ui

import androidx.test.core.app.ApplicationProvider
import cc.identified.sdk.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ConsentContentTest {
    private val copy = Copy(ApplicationProvider.getApplicationContext())

    private fun session() =
        HostedSession(
            "s",
            "u",
            "p",
            "active",
            requiredDocuments = listOf("license_front", "license_back", "selfie"),
            branding = HostedSession.Branding(displayName = "Acme"),
        )

    @Test
    fun standardConsentIsExactAndOnlyNamesRequiredData() {
        val sections = ConsentContent.sections(session(), copy)
        assertEquals(
            listOf(
                "What we collect",
                "Biometric data",
                "Human review",
                "How it’s used",
                "Data protection",
                "Retention",
                "Your rights",
            ),
            sections.map { it.title },
        )
        assertEquals(
            "Front of ID, Back of ID, Biometric selfie, a short liveness check (turning your head), and the details you enter.",
            sections[0].body,
        )
        assertEquals(
            "Your information is kept in line with Acme’s policy and applicable law, then deleted.",
            sections[5].body,
        )
        assertEquals("identity and biometric data", ConsentContent.agreedData(session(), copy))
    }

    @Test
    fun screeningConsentPreservesLeadingSpacesAndExtraDisclosures() {
        val s =
            session()
                .copy(
                    requiredDocuments = listOf("license_front", "selfie", "proof_of_address"),
                    requiredChecks = listOf("id_document", "background_screening"),
                )
        val sections = ConsentContent.sections(s, copy)
        assertEquals(
            "Only to verify your identity and background for Acme. The result of the check is shared with Acme.",
            sections.first { it.title == "How it’s used" }.body,
        )
        assertEquals(3, sections[0].more.size)
        assertEquals(5, sections.first { it.title == "Background screening" }.more.size)
        assertEquals(
            "identity, biometric and background screening data",
            ConsentContent.agreedData(s, copy),
        )
    }

    @Test
    fun agreementAndTermsVersionRetainExactCopy() {
        assertEquals(
            "I have read and agree to the Terms of use, and I consent to the collection and processing of my identity and biometric data for verification.",
            copy.text("consent.agree.prefix") +
                copy.text("consent.agree.terms") +
                copy.text("consent.agree.suffix", ConsentContent.agreedData(session(), copy)),
        )
        assertEquals(
            "Terms in force for this session: version 2026-09-04. If they change, the version recorded against your session stays the one you agreed to.",
            copy.text("terms.7.p3", ConsentVersion.current),
        )
        assertEquals("Case ID: ", copy.text("status.case_id"))
    }

    @Test
    fun noDocumentsUsesDetailsOnlyAndFallbackBusiness() {
        val sections =
            ConsentContent.sections(
                session()
                    .copy(
                        requiredDocuments = emptyList(),
                        requiredChecks = emptyList(),
                        branding = null,
                    ),
                copy,
            )
        assertEquals("The details you enter.", sections[0].body)
        assertFalse(
            sections.any { it.title == "Biometric data" || it.title == "Background screening" }
        )
        assertEquals(
            "Your information is kept in line with this business’s policy and applicable law, then deleted.",
            sections.first { it.title == "Retention" }.body,
        )
    }
}
