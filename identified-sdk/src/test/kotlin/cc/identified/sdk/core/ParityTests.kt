package cc.identified.sdk.core

import java.util.Date
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ParityTests {
    private fun session() =
        HostedSession(
            "s",
            "u",
            "p",
            "active",
            requiredDocuments =
                listOf("license_front", "license_back", "selfie", "proof_of_address"),
            firstName = "Ama",
            lastName = "Mensah",
        )

    private fun c(status: String = "draft", docs: List<VerificationDocument> = emptyList()) =
        VerificationCase("c", status, documents = docs)

    @Test
    fun launchRoutesAndModes() {
        val s = session()
        assertEquals(LaunchRoute.Entry(EntryDestination.Consent), FlowRouting.launchRoute(s))
        assertEquals(
            LaunchRoute.Entry(EntryDestination.Consent),
            FlowRouting.launchRoute(
                s.copy(caseId = "c", caseStatus = "draft", consentRecorded = false)
            ),
        )
        FlowRouting.intakeStatuses.forEach {
            assertEquals(
                LaunchRoute.Entry(EntryDestination.Checklist("c")),
                FlowRouting.launchRoute(
                    s.copy(caseId = "c", caseStatus = it, consentRecorded = true)
                ),
            )
        }
        listOf(
                "submitted",
                "manual_review",
                "automated_review",
                "background_pending",
                "approved",
                "rejected",
            )
            .forEach {
                assertEquals(
                    LaunchRoute.Status("c"),
                    FlowRouting.launchRoute(s.copy(caseId = "c", caseStatus = it)),
                )
            }
        assertEquals(NavigationMode.RESUME, FlowRouting.navigationMode(false, "changes_requested"))
        assertEquals(NavigationMode.RESUME, FlowRouting.navigationMode(true, "draft"))
        assertEquals(NavigationMode.FIRST_RUN, FlowRouting.navigationMode(false, "draft"))
    }

    @Test
    fun consentUsesOnlyRequiredScopes() {
        val s = session().copy(requiredDocuments = emptyList(), requiredChecks = emptyList())
        assertEquals(listOf("identity"), FlowRouting.consentScopes(s))
        listOf(CheckType.biometricLiveness, CheckType.biometricFaceMatch).forEach {
            assertEquals(
                listOf("identity", "biometric"),
                FlowRouting.consentScopes(s.copy(requiredChecks = listOf(it))),
            )
        }
        assertEquals(
            listOf("identity", "biometric", "background"),
            FlowRouting.consentScopes(
                s.copy(
                    requiredDocuments = listOf("selfie"),
                    requiredChecks = listOf("background_screening"),
                )
            ),
        )
    }

    @Test
    fun guardsAndChecklistPriority() {
        val s = session()
        assertEquals(
            Destination.IdDetails,
            FlowRouting.guarded(Destination.YourName, s.copy(collectName = false)),
        )
        assertEquals(
            Destination.YourName,
            FlowRouting.guarded(Destination.IdDetails, s.copy(lastName = " ")),
        )
        assertEquals(
            Destination.Review,
            FlowRouting.guarded(Destination.Selfie, s.copy(requiredDocuments = emptyList())),
        )
        assertEquals(
            Destination.YourName,
            FlowRouting.checklistContinue(
                c(),
                s.copy(identityNameComplete = false, identityDetailsComplete = false),
                NavigationMode.RESUME,
            ),
        )
        assertEquals(
            Destination.IdDetails,
            FlowRouting.checklistContinue(
                c(),
                s.copy(identityNameComplete = true, identityDetailsComplete = false),
                NavigationMode.RESUME,
            ),
        )
        val docs = listOf(VerificationDocument(docType = "license_front"))
        assertEquals(
            Destination.Documents("license_back"),
            FlowRouting.checklistContinue(c(docs = docs), s, NavigationMode.RESUME),
        )
        val both = docs + VerificationDocument(docType = "license_back")
        assertEquals(
            Destination.Selfie,
            FlowRouting.checklistContinue(c(docs = both), s, NavigationMode.RESUME),
        )
        val selfie = both + VerificationDocument(docType = "selfie")
        assertEquals(
            Destination.ProofOfAddress,
            FlowRouting.checklistContinue(c(docs = selfie), s, NavigationMode.RESUME),
        )
        assertEquals(
            Destination.Review,
            FlowRouting.checklistContinue(
                c(docs = selfie + VerificationDocument(docType = "proof_of_address")),
                s,
                NavigationMode.RESUME,
            ),
        )
        assertFalse(FlowRouting.correctionNeeded(c("changes_requested"), s))
    }

    @Test
    fun orderedScreensAndStepPositions() {
        val s = session()
        assertEquals(FlowScreen.entries, FlowRules.screens(s.requiredDocuments, true))
        assertEquals(
            StepPosition(2, 6),
            FlowRules.position(FlowScreen.ID_DETAILS, s.requiredDocuments, true),
        )
        assertEquals(
            listOf(FlowScreen.ID_DETAILS, FlowScreen.PROOF_OF_ADDRESS, FlowScreen.REVIEW),
            FlowRules.screens(listOf("proof_of_address"), false),
        )
        assertEquals(
            listOf("license_front", "license_back"),
            FlowRules.documentSteps(s.requiredDocuments, CapturePhase.DOCUMENTS),
        )
        assertEquals(
            listOf("proof_of_address"),
            FlowRules.documentSteps(s.requiredDocuments, CapturePhase.PROOF_OF_ADDRESS),
        )
        val next =
            listOf(
                Destination.IdDetails,
                Destination.Documents(),
                Destination.Selfie,
                Destination.ProofOfAddress,
                Destination.Review,
                Destination.Status,
            )
        FlowScreen.entries.forEachIndexed { index, screen ->
            assertEquals(
                next[index],
                FlowRouting.afterCompleting(screen, s, NavigationMode.FIRST_RUN),
            )
        }
        FlowScreen.entries.dropLast(1).forEach {
            assertEquals(
                Destination.Checklist,
                FlowRouting.afterCompleting(it, s, NavigationMode.RESUME),
            )
        }
        FlowRouting.finalStatuses.forEach { assertFalse(FlowRouting.shouldKeepPolling(it)) }
        listOf("submitted", "in_review", "manual_review", "background_pending", "automated_review")
            .forEach { assertTrue(FlowRouting.shouldKeepPolling(it)) }
    }

    @Test
    fun inputAndMetadataContracts() {
        assertEquals(NameInput.Problem.MISSING, NameInput.validate(" ", "", "Mensah"))
        assertNull(NameInput.validate("a".repeat(200), "", "b".repeat(55)))
        assertNull(NameInput.validate("😀".repeat(200), "", "b".repeat(55)))
        assertEquals(
            listOf("national_id", "drivers_license"),
            IDDetailsInput.options(listOf("drivers_license", "national_id")).map { it.key },
        )
        assertEquals("Voter Card", IDDetailsInput.label("voter_card"))
        assertEquals(
            IDDetailsInput.Problem.MISSING_DATE_OF_BIRTH,
            IDDetailsInput.validate(null, null, ""),
        )
        assertEquals(IDDetailsInput.Problem.MISSING_TYPE, IDDetailsInput.validate(Date(), null, ""))
        assertEquals(
            IDDetailsInput.Problem.INVALID_GHANA_CARD,
            IDDetailsInput.validate(Date(), "national_id", "123"),
        )
        assertEquals("1990-01-05", IDDetailsInput.dobString(IDDetailsInput.date("1990-01-05")!!))
        assertNull(IDDetailsInput.date(""))
        mapOf(
                "GA-1839" to "GA-183-9",
                "ga12345678" to "GA-1234-5678",
                "GA 183 9124 99" to "GA-1839-1249",
                "g" to "G",
            )
            .forEach { (input, output) -> assertEquals(output, DigitalAddressCode.mask(input)) }
        assertEquals("GA-183-9124", DigitalAddressCode.displayForm("GA1839124"))
        assertEquals(
            "Osu · Accra",
            DigitalAddressCode.describe(DigitalAddress("c", 0.0, 0.0, "Osu", " ", "Accra")),
        )
        assertFalse(ReviewInput.isValidEmail("a@b"))
        assertFalse(ReviewInput.isValidEmail("a b@example.com"))
        assertEquals(
            listOf(ProofOfAddressRules.FormatName.PHOTO, ProofOfAddressRules.FormatName.PDF),
            ProofOfAddressRules.formatNames(listOf("image/jpeg", "image/png", "application/pdf")),
        )
        assertEquals(3, ProofOfAddressRules.months(90))
        assertEquals(8, ProofOfAddressRules.megabytes(8388613))
        assertNull(ProofOfAddressRules.captureRole(1))
        assertEquals(
            "",
            MetadataPatch.idDetails("d", "passport", "n", "Passport")["metadata"]!!
                .jsonObject["id_type_other"]!!
                .jsonPrimitive
                .content,
        )
        val address =
            MetadataPatch.statedAddress(
                    StatedAddress("GA1839124", source = AddressSource.CURRENT_LOCATION)
                )["metadata"]!!
                .jsonObject["stated_address"]!!
                .jsonObject
        assertEquals("current_location", address["source"]!!.jsonPrimitive.content)
        assertFalse(address.containsKey("area"))
        assertFalse(
            MetadataPatch.reportDelivery(false, "")["metadata"]!!
                .jsonObject["report_email_opt_in"]!!
                .jsonPrimitive
                .boolean
        )
    }

    @Test
    fun everyArrayToleratesMissingAndNull() {
        val s =
            CoreJson.decodeFromString<HostedSession>(
                """{"id":"s","subject_ref":"u","profile_key":"p","status":"active","allowed_id_types":null,"required_checks":null,"required_documents":null,"collect_name":null,"branding":{"display_name":"Tenant"},"id_details":{"id_number":"x"},"proof_of_address_policy":{"accepted_types":null,"accepted_formats":null,"max_age_days":90,"max_size_bytes":1}}"""
            )
        assertTrue(s.allowedIdTypes.isEmpty())
        assertTrue(s.requiredChecks.isEmpty())
        assertTrue(s.requiredDocuments.isEmpty())
        assertNull(s.collectName)
        assertEquals("Tenant", s.branding!!.displayName)
        assertEquals("x", s.idDetails!!.idNumber)
        assertTrue(s.proofOfAddressPolicy!!.acceptedTypes.isEmpty())
        assertTrue(s.proofOfAddressPolicy.acceptedFormats.isEmpty())
        val case =
            CoreJson.decodeFromString<VerificationCase>(
                """{"id":"c","status":"draft","required_documents":null,"required_checks":null,"checks":null,"documents":null}"""
            )
        assertTrue(case.requiredDocuments.isEmpty())
        assertTrue(case.requiredChecks.isEmpty())
        assertTrue(case.checks.isEmpty())
        assertTrue(case.documents.isEmpty())
    }

    @Test
    fun backgroundCountsAndStatusPriorities() {
        val background =
            VerificationCheck(
                "background_screening",
                "completed",
                resultJson =
                    """{"background":{"status":"in_progress","screenings":[{"status":"pending"},{"status":"awaiting_subject"},{"status":"clear"}]}}""",
            )
        val c = c("submitted").copy(checks = listOf(background))
        assertEquals(BackgroundProgress(true, 2), StatusRules.backgroundProgress(c))
        assertEquals(StatusKind.BACKGROUND_IN_PROGRESS, StatusRules.summary(c).kind)
        assertEquals(
            StatusKind.MANUAL_REVIEW,
            StatusRules.summary(c.copy(status = "manual_review")).kind,
        )
        val expected =
            mapOf(
                "approved" to StatusKind.VERIFIED,
                "rejected" to StatusKind.NOT_APPROVED,
                "closed" to StatusKind.CLOSED,
                "automated_review" to StatusKind.AUTOMATED_REVIEW,
                "in_review" to StatusKind.IN_REVIEW,
                "submitted" to StatusKind.SUBMITTED,
                "changes_requested" to StatusKind.CHANGES_REQUESTED,
                "expired" to StatusKind.PENDING,
                "revoked" to StatusKind.PENDING,
            )
        expected.forEach { (status, kind) ->
            assertEquals(kind, StatusRules.summary(c(status)).kind)
        }
        assertEquals(
            StatusKind.FULLY_VERIFIED,
            StatusRules.summary(
                    c("approved").copy(checks = listOf(background.copy(resultJson = null)))
                )
                .kind,
        )
        assertEquals(
            "Retake",
            StatusRules.summary(c("changes_requested").copy(reviewNotes = " Retake ")).reviewerNote,
        )
        assertNull(
            StatusRules.summary(c("approved").copy(reviewNotes = "Private note")).reviewerNote
        )
    }

    @Test
    fun reviewIssueOrderAndMalformedResults() {
        fun issue(json: String) =
            StatusRules.reviewIssue(
                c().copy(
                    checks =
                        listOf(VerificationCheck("id_document", "completed", resultJson = json))
                )
            )
        assertEquals(
            ReviewIssue.TypeMismatch("Passport", "Ghana Card"),
            issue(
                """{"selected_id_type":"passport","detected_id_type":"national_id","name_comparison":{"status":"needs_review"},"data_mismatch":"private"}"""
            ),
        )
        listOf("unavailable", "needs_review").forEach {
            assertEquals(
                ReviewIssue.NameNeedsConfirmation,
                issue("""{"name_comparison":{"status":"$it"},"data_mismatch":"private"}"""),
            )
        }
        listOf("null", "[]", "not json", "{}", """{"data_mismatch":true}""").forEach {
            assertNull(issue(it))
        }
    }

    @Test
    fun launchErrorMappingAndMalformedBaseUrl() =
        runBlocking<Unit> {
            assertEquals(
                LaunchFailure.SessionExpired,
                Launch.failure(ApiException(401, "SESSION EXPIRED")),
            )
            assertEquals(
                LaunchFailure.SessionInvalid,
                Launch.failure(ApiException(404, "not found")),
            )
            assertEquals(
                LaunchFailure.SessionInvalid,
                Launch.failure(ApiException(401, "missing token")),
            )
            assertEquals(
                LaunchFailure.Unavailable("offline"),
                Launch.failure(ApiException(0, "offline")),
            )
            val client = ApiClient("not a URL", "token")
            assertTrue(Launch.load(client).exceptionOrNull() is LaunchFailure.Unavailable)
        }
}
