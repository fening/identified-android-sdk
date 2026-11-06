package cc.identified.sdk.core

import kotlinx.serialization.*
import kotlinx.serialization.json.*

@OptIn(ExperimentalSerializationApi::class)
internal val CoreJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}

@Serializable
internal data class HostedSession(
    val id: String,
    @SerialName("subject_ref") val subjectRef: String,
    @SerialName("profile_key") val profileKey: String,
    val status: String,
    @SerialName("subject_id") val subjectId: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("required_documents") val requiredDocuments: List<String> = emptyList(),
    @SerialName("required_checks") val requiredChecks: List<String> = emptyList(),
    @SerialName("collect_name") val collectName: Boolean? = null,
    @SerialName("allowed_id_types") val allowedIdTypes: List<String> = emptyList(),
    @SerialName("report_email_opt_in") val reportEmailOptIn: Boolean? = null,
    @SerialName("report_email") val reportEmail: String? = null,
    val branding: Branding? = null,
    @SerialName("proof_of_address_policy") val proofOfAddressPolicy: ProofOfAddressPolicy? = null,
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("middle_name") val middleName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    @SerialName("correction_applied") val correctionApplied: Boolean? = null,
    @SerialName("case_id") val caseId: String? = null,
    @SerialName("case_status") val caseStatus: String? = null,
    @SerialName("consent_recorded") val consentRecorded: Boolean? = null,
    @SerialName("identity_name_complete") val identityNameComplete: Boolean? = null,
    @SerialName("identity_details_complete") val identityDetailsComplete: Boolean? = null,
    @SerialName("id_details") val idDetails: IDDetails? = null,
) {
    @Serializable
    data class IDDetails(
        val dob: String? = null,
        @SerialName("id_type") val idType: String? = null,
        @SerialName("id_number") val idNumber: String? = null,
    )

    @Serializable
    data class Branding(
        @SerialName("display_name") val displayName: String? = null,
        @SerialName("primary_color") val primaryColor: String? = null,
    )
}

@Serializable
internal data class ProofOfAddressPolicy(
    @SerialName("accepted_types") val acceptedTypes: List<AcceptedType> = emptyList(),
    @SerialName("max_age_days") val maxAgeDays: Int,
    @SerialName("accepted_formats") val acceptedFormats: List<String> = emptyList(),
    @SerialName("max_size_bytes") val maxSizeBytes: Int,
) {
    @Serializable
    data class AcceptedType(val key: String, val label: String, val hint: String? = null)

    companion object {
        val standard =
            ProofOfAddressPolicy(
                emptyList(),
                90,
                listOf("image/jpeg", "image/png", "image/webp", "application/pdf"),
                8 * 1024 * 1024,
            )
    }
}

@Serializable
internal data class VerificationCase(
    val id: String,
    val status: String,
    @SerialName("review_notes") val reviewNotes: String? = null,
    @SerialName("required_documents") val requiredDocuments: List<String> = emptyList(),
    @SerialName("required_checks") val requiredChecks: List<String> = emptyList(),
    val documents: List<VerificationDocument> = emptyList(),
    val checks: List<VerificationCheck> = emptyList(),
    @SerialName("identity_outcome") val identityOutcome: String? = null,
    @SerialName("background_outcome") val backgroundOutcome: String? = null,
    @SerialName("submitted_at") val submittedAt: String? = null,
)

@Serializable
internal data class VerificationDocument(
    val id: String? = null,
    @SerialName("doc_type") val docType: String,
    @SerialName("capture_role") val captureRole: String? = null,
    @SerialName("file_url") val fileUrl: String? = null,
    val status: String? = null,
    val superseded: Boolean? = null,
)

@Serializable
internal data class VerificationCheck(
    @SerialName("check_type") val checkType: String,
    val status: String,
    val provider: String? = null,
    @SerialName("result_json") val resultJson: String? = null,
)

@Serializable
internal data class SubmitResult(
    @SerialName("case_id") val caseId: String,
    val status: String,
    @SerialName("ready_for_review") val readyForReview: Boolean? = null,
    @SerialName("closure_reason") val closureReason: String? = null,
)

@Serializable
internal data class DigitalAddress(
    val code: String,
    val latitude: Double,
    val longitude: Double,
    val area: String? = null,
    val district: String? = null,
    val region: String? = null,
    @SerialName("post_code") val postCode: String? = null,
    val street: String? = null,
)

@Serializable
internal data class StatedAddress(
    val code: String,
    val area: String? = null,
    val district: String? = null,
    val region: String? = null,
    val source: AddressSource,
)

internal object ConsentVersion {
    const val current = "2026-09-04"
}

internal object DocType {
    const val governmentId = "government_id"
    const val licenseFront = "license_front"
    const val licenseBack = "license_back"
    const val selfie = "selfie"
    const val proofOfAddress = "proof_of_address"
}

internal object CheckType {
    const val idDocument = "id_document"
    const val biometricLiveness = "biometric_liveness"
    const val biometricFaceMatch = "biometric_face_match"
    const val backgroundScreening = "background_screening"
}

internal object CaseStatus {
    const val draft = "draft"
    const val intakeInProgress = "intake_in_progress"
    const val documentsPending = "documents_pending"
    const val biometricPending = "biometric_pending"
    const val submitted = "submitted"
    const val inReview = "in_review"
    const val automatedReview = "automated_review"
    const val backgroundPending = "background_pending"
    const val manualReview = "manual_review"
    const val changesRequested = "changes_requested"
    const val approved = "approved"
    const val rejected = "rejected"
    const val closed = "closed"
    const val expired = "expired"
    const val revoked = "revoked"
}

internal object SelfieRole {
    const val center = "center"
    const val left = "left"
    const val right = "right"
    val all = setOf(center, left, right)
}

@Serializable
internal enum class AddressSource {
    @SerialName("typed") TYPED,
    @SerialName("pin") PIN,
    @SerialName("current_location") CURRENT_LOCATION,
}
