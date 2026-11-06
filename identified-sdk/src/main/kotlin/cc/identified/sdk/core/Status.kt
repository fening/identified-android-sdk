package cc.identified.sdk.core

import kotlinx.serialization.json.*

internal enum class StatusKind {
    FULLY_VERIFIED,
    VERIFIED,
    NOT_APPROVED,
    CLOSED,
    AUTOMATED_REVIEW,
    MANUAL_REVIEW,
    BACKGROUND_PENDING,
    IN_REVIEW,
    SUBMITTED,
    CHANGES_REQUESTED,
    BACKGROUND_IN_PROGRESS,
    PENDING,
}

internal enum class StatusTone {
    SUCCESS,
    FAILURE,
    ATTENTION,
    WAITING,
}

internal sealed interface ReviewIssue {
    data class TypeMismatch(val selected: String, val detected: String) : ReviewIssue

    data object NameNeedsConfirmation : ReviewIssue

    data object NumberNeedsConfirmation : ReviewIssue
}

internal data class BackgroundProgress(val pending: Boolean, val count: Int)

internal data class StatusSummary(
    val kind: StatusKind,
    val tone: StatusTone,
    val background: BackgroundProgress,
    val reviewIssue: ReviewIssue?,
    val reviewerNote: String?,
)

internal object StatusRules {
    private fun json(text: String?): JsonObject? =
        text?.let { runCatching { CoreJson.parseToJsonElement(it) as? JsonObject }.getOrNull() }

    private fun JsonObject.string(key: String) =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun backgroundProgress(c: VerificationCase): BackgroundProgress {
        val check =
            c.checks.firstOrNull { it.checkType == CheckType.backgroundScreening }
                ?: return BackgroundProgress(false, 0)
        val background = json(check.resultJson)?.get("background") as? JsonObject
        val count =
            (background?.get("screenings") as? JsonArray)?.count {
                (it as? JsonObject)?.string("status") in setOf("pending", "awaiting_subject")
            } ?: 0
        return BackgroundProgress(
            count > 0 || background?.string("status") == "pending" || check.status == "pending",
            count,
        )
    }

    fun reviewIssue(c: VerificationCase): ReviewIssue? {
        val parsed =
            json(c.checks.firstOrNull { it.checkType == CheckType.idDocument }?.resultJson)
                ?: return null
        val selected = parsed.string("selected_id_type").orEmpty()
        val detected = parsed.string("detected_id_type").orEmpty()
        if (selected.isNotEmpty() && detected.isNotEmpty() && selected != detected)
            return ReviewIssue.TypeMismatch(
                IDDetailsInput.label(selected),
                IDDetailsInput.label(detected),
            )
        if (
            (parsed["name_comparison"] as? JsonObject)?.string("status") in
                setOf("needs_review", "unavailable")
        )
            return ReviewIssue.NameNeedsConfirmation
        if (!parsed.string("data_mismatch").isNullOrBlank())
            return ReviewIssue.NumberNeedsConfirmation
        return null
    }

    fun summary(c: VerificationCase): StatusSummary {
        val background = backgroundProgress(c)
        val kind =
            when {
                c.status == CaseStatus.backgroundPending -> StatusKind.BACKGROUND_PENDING
                c.status == CaseStatus.manualReview -> StatusKind.MANUAL_REVIEW
                background.pending -> StatusKind.BACKGROUND_IN_PROGRESS
                else ->
                    when (c.status) {
                        CaseStatus.approved ->
                            if (c.checks.any { it.checkType == CheckType.backgroundScreening })
                                StatusKind.FULLY_VERIFIED
                            else StatusKind.VERIFIED
                        CaseStatus.rejected -> StatusKind.NOT_APPROVED
                        CaseStatus.closed -> StatusKind.CLOSED
                        CaseStatus.automatedReview -> StatusKind.AUTOMATED_REVIEW
                        CaseStatus.inReview -> StatusKind.IN_REVIEW
                        CaseStatus.submitted -> StatusKind.SUBMITTED
                        CaseStatus.changesRequested -> StatusKind.CHANGES_REQUESTED
                        else -> StatusKind.PENDING
                    }
            }
        val tone =
            when (c.status) {
                CaseStatus.approved -> StatusTone.SUCCESS
                CaseStatus.rejected -> StatusTone.FAILURE
                CaseStatus.changesRequested -> StatusTone.ATTENTION
                else -> StatusTone.WAITING
            }
        return StatusSummary(
            kind,
            tone,
            background,
            if (c.status == CaseStatus.manualReview) reviewIssue(c) else null,
            if (c.status == CaseStatus.changesRequested)
                c.reviewNotes?.trim()?.takeIf { it.isNotEmpty() }
            else null,
        )
    }
}
