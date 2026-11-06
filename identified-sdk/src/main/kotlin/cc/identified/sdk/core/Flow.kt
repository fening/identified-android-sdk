package cc.identified.sdk.core

import kotlinx.coroutines.CancellationException

internal enum class FlowScreen {
    YOUR_NAME,
    ID_DETAILS,
    DOCUMENTS,
    SELFIE,
    PROOF_OF_ADDRESS,
    REVIEW,
}

internal enum class CapturePhase {
    DOCUMENTS,
    PROOF_OF_ADDRESS,
}

internal enum class NavigationMode {
    FIRST_RUN,
    RESUME,
}

internal sealed interface Destination {
    data object YourName : Destination

    data object IdDetails : Destination

    data class Documents(val startAt: String? = null) : Destination

    data object Selfie : Destination

    data object ProofOfAddress : Destination

    data object Review : Destination

    data object Checklist : Destination

    data object Status : Destination
}

internal sealed interface EntryDestination {
    data object Consent : EntryDestination

    data class Checklist(val caseId: String) : EntryDestination
}

internal sealed interface LaunchRoute {
    data class Entry(val destination: EntryDestination) : LaunchRoute

    data class Status(val caseId: String) : LaunchRoute
}

internal data class ChecklistItem(val key: String, val complete: Boolean)

internal data class StepPosition(val step: Int, val total: Int)

internal object FlowRules {
    fun hasDocument(c: VerificationCase, key: String): Boolean {
        val docs =
            c.documents.filter {
                it.docType == key && it.superseded != true && it.status != "rejected"
            }
        if (key == DocType.selfie && docs.any { it.captureRole in SelfieRole.all })
            return SelfieRole.all.all { role -> docs.any { it.captureRole == role } }
        return docs.isNotEmpty()
    }

    fun checklist(c: VerificationCase, requiredDocuments: List<String>) =
        requiredDocuments.map { ChecklistItem(it, hasDocument(c, it)) }

    fun nextIncompleteStep(c: VerificationCase, requiredDocuments: List<String>) =
        checklist(c, requiredDocuments).firstOrNull { !it.complete }?.key

    fun orderedCaptureSteps(requiredDocuments: List<String>) =
        requiredDocuments.filter { it != DocType.selfie && it != DocType.proofOfAddress } +
            requiredDocuments.filter { it == DocType.selfie } +
            requiredDocuments.filter { it == DocType.proofOfAddress }

    fun documentSteps(requiredDocuments: List<String>, phase: CapturePhase) =
        if (phase == CapturePhase.PROOF_OF_ADDRESS)
            requiredDocuments.filter { it == DocType.proofOfAddress }
        else
            orderedCaptureSteps(requiredDocuments).filter {
                it != DocType.selfie && it != DocType.proofOfAddress
            }

    fun collectsName(session: HostedSession) = session.collectName != false

    fun screens(requiredDocuments: List<String>, collectName: Boolean): List<FlowScreen> =
        buildList {
            if (collectName) add(FlowScreen.YOUR_NAME)
            add(FlowScreen.ID_DETAILS)
            if (documentSteps(requiredDocuments, CapturePhase.DOCUMENTS).isNotEmpty())
                add(FlowScreen.DOCUMENTS)
            if (DocType.selfie in requiredDocuments) add(FlowScreen.SELFIE)
            if (DocType.proofOfAddress in requiredDocuments) add(FlowScreen.PROOF_OF_ADDRESS)
            add(FlowScreen.REVIEW)
        }

    fun position(
        screen: FlowScreen,
        requiredDocuments: List<String>,
        collectName: Boolean,
    ): StepPosition {
        val all = screens(requiredDocuments, collectName)
        return StepPosition((all.indexOf(screen) + 1).coerceAtLeast(1), all.size)
    }
}

internal object FlowRouting {
    val intakeStatuses =
        setOf(
            CaseStatus.draft,
            CaseStatus.intakeInProgress,
            CaseStatus.documentsPending,
            CaseStatus.biometricPending,
            CaseStatus.changesRequested,
        )
    val finalStatuses =
        setOf(
            CaseStatus.approved,
            CaseStatus.rejected,
            CaseStatus.closed,
            CaseStatus.changesRequested,
            CaseStatus.expired,
            CaseStatus.revoked,
        )

    fun launchRoute(session: HostedSession): LaunchRoute {
        val id = session.caseId
        if (id != null && session.caseStatus != null && session.caseStatus !in intakeStatuses)
            return LaunchRoute.Status(id)
        return LaunchRoute.Entry(
            if (id != null && session.consentRecorded == true) EntryDestination.Checklist(id)
            else EntryDestination.Consent
        )
    }

    fun navigationMode(launchHadCase: Boolean, caseStatus: String?) =
        if (launchHadCase || caseStatus == CaseStatus.changesRequested) NavigationMode.RESUME
        else NavigationMode.FIRST_RUN

    fun consentScopes(session: HostedSession): List<String> = buildList {
        add("identity")
        if (
            DocType.selfie in session.requiredDocuments ||
                session.requiredChecks.any {
                    it == CheckType.biometricLiveness || it == CheckType.biometricFaceMatch
                }
        )
            add("biometric")
        if (CheckType.backgroundScreening in session.requiredChecks) add("background")
    }

    fun correctionNeeded(c: VerificationCase, session: HostedSession) =
        c.status == CaseStatus.changesRequested &&
            FlowRules.checklist(c, FlowRules.orderedCaptureSteps(session.requiredDocuments)).all {
                it.complete
            } &&
            session.correctionApplied != true

    fun checklistContinue(
        c: VerificationCase,
        session: HostedSession,
        mode: NavigationMode,
    ): Destination? {
        if (correctionNeeded(c, session)) return null
        val steps = FlowRules.orderedCaptureSteps(session.requiredDocuments)
        if (mode == NavigationMode.RESUME) {
            if (session.identityNameComplete == false) return guarded(Destination.YourName, session)
            if (session.identityDetailsComplete == false)
                return guarded(Destination.IdDetails, session)
        } else if (FlowRules.checklist(c, steps).none { it.complete })
            return guarded(Destination.YourName, session)
        return when (val next = FlowRules.nextIncompleteStep(c, steps)) {
            null -> Destination.Review
            DocType.selfie -> Destination.Selfie
            DocType.proofOfAddress -> Destination.ProofOfAddress
            else -> Destination.Documents(next)
        }
    }

    fun guarded(destination: Destination, session: HostedSession): Destination =
        when {
            destination == Destination.YourName && !FlowRules.collectsName(session) ->
                guarded(Destination.IdDetails, session)
            destination == Destination.IdDetails &&
                FlowRules.collectsName(session) &&
                (session.firstName.isNullOrBlank() || session.lastName.isNullOrBlank()) ->
                Destination.YourName
            destination == Destination.Selfie && DocType.selfie !in session.requiredDocuments ->
                Destination.Review
            else -> destination
        }

    fun afterCompleting(
        screen: FlowScreen,
        session: HostedSession,
        mode: NavigationMode,
    ): Destination {
        if (screen == FlowScreen.REVIEW) return Destination.Status
        if (mode == NavigationMode.RESUME) return Destination.Checklist
        val all = FlowRules.screens(session.requiredDocuments, FlowRules.collectsName(session))
        val index = all.indexOf(screen)
        if (index < 0 || index + 1 >= all.size) return Destination.Review
        return guarded(
            when (all[index + 1]) {
                FlowScreen.YOUR_NAME -> Destination.YourName
                FlowScreen.ID_DETAILS -> Destination.IdDetails
                FlowScreen.DOCUMENTS -> Destination.Documents()
                FlowScreen.SELFIE -> Destination.Selfie
                FlowScreen.PROOF_OF_ADDRESS -> Destination.ProofOfAddress
                FlowScreen.REVIEW -> Destination.Review
            },
            session,
        )
    }

    fun shouldKeepPolling(status: String) = status !in finalStatuses
}

internal sealed class LaunchFailure(message: String) : Exception(message) {
    data object SessionInvalid : LaunchFailure("Invalid session")

    data object SessionExpired : LaunchFailure("Session expired")

    data class Unavailable(val reason: String) : LaunchFailure(reason)
}

internal data class LaunchState(
    val session: HostedSession,
    val route: LaunchRoute,
    val mode: NavigationMode,
)

internal object Launch {
    suspend fun load(client: ApiClient): Result<LaunchState> =
        try {
            val s = client.currentSession()
            Result.success(
                LaunchState(
                    s,
                    FlowRouting.launchRoute(s),
                    FlowRouting.navigationMode(s.caseId != null, s.caseStatus),
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(failure(e))
        }

    fun failure(error: Throwable): LaunchFailure =
        when {
            error is ApiException && error.sessionFailure == SessionFailure.EXPIRED ->
                LaunchFailure.SessionExpired
            error is ApiException &&
                (error.sessionFailure == SessionFailure.INVALID || error.status == 404) ->
                LaunchFailure.SessionInvalid
            else -> LaunchFailure.Unavailable(error.message ?: "Request failed")
        }
}
