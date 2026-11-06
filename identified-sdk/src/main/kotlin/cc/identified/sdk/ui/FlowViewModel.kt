package cc.identified.sdk.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.identified.sdk.*
import cc.identified.sdk.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject

/** A narrow boundary makes the coordinator testable without an Activity or camera. */
internal interface FlowApi {
    suspend fun currentSession(): HostedSession

    suspend fun saveMetadata(patch: JsonObject)

    suspend fun recordConsent(scopes: List<String>)

    suspend fun getOrCreateCase(session: HostedSession): VerificationCase

    suspend fun getCase(id: String): VerificationCase

    suspend fun uploadDocument(
        id: String,
        type: String,
        role: String?,
        data: ByteArray,
        mime: String,
    )

    suspend fun runBiometricCheck(id: String)

    suspend fun submitCase(id: String): String

    suspend fun resolveDigitalAddress(code: String): DigitalAddress

    suspend fun encodeDigitalAddress(latitude: Double, longitude: Double): DigitalAddress
}

internal class ClientFlowApi(private val client: ApiClient) : FlowApi {
    override suspend fun currentSession() = client.currentSession()

    override suspend fun saveMetadata(patch: JsonObject) {
        client.saveMetadata(patch)
    }

    override suspend fun recordConsent(scopes: List<String>) {
        client.recordConsent(scopes)
    }

    override suspend fun getOrCreateCase(session: HostedSession) = client.getOrCreateCase(session)

    override suspend fun getCase(id: String) = client.getCase(id)

    override suspend fun uploadDocument(
        id: String,
        type: String,
        role: String?,
        data: ByteArray,
        mime: String,
    ) {
        client.uploadDocument(id, type, role, data, mime)
    }

    override suspend fun runBiometricCheck(id: String) {
        client.runBiometricCheck(id)
    }

    override suspend fun submitCase(id: String) = client.submitCase(id).status

    override suspend fun resolveDigitalAddress(code: String) = client.resolveDigitalAddress(code)

    override suspend fun encodeDigitalAddress(latitude: Double, longitude: Double) =
        client.encodeDigitalAddress(latitude, longitude)
}

internal sealed interface Route {
    data object Entry : Route

    data object Consent : Route

    data object Terms : Route

    data class Step(val destination: Destination) : Route
}

internal enum class Stage {
    LOADING,
    UNAVAILABLE,
    READY,
}

internal data class FlowState(
    val stage: Stage = Stage.LOADING,
    val path: List<Route> = emptyList(),
    val session: HostedSession? = null,
    val current: VerificationCase? = null,
    val mode: NavigationMode = NavigationMode.FIRST_RUN,
    val busy: Boolean = false,
    val error: String? = null,
    val errorKey: String? = null,
    val result: IdentifiedResult? = null,
    val address: StatedAddress? = null,
    val candidate: DigitalAddress? = null,
    val candidateSource: AddressSource = AddressSource.TYPED,
    val lookupUnavailable: Boolean = false,
    val pages: Int = 0,
    val exhausted: Boolean = false,
    val addressRefusal: String? = null,
    val reviewLoadFailed: Boolean = false,
) {
    val canRetryReview: Boolean
        get() = reviewLoadFailed && !busy

    val route: Route
        get() = path.lastOrNull() ?: Route.Entry

    val caseId: String?
        get() = current?.id ?: session?.caseId

    val policy: ProofOfAddressPolicy
        get() = session?.proofOfAddressPolicy ?: ProofOfAddressPolicy.standard
}

/** All state is memory-only: no SavedStateHandle, token persistence or image files. */
internal class FlowViewModel(private val api: FlowApi) : ViewModel() {
    private val mutable = MutableStateFlow(FlowState())
    val state = mutable.asStateFlow()
    private var launchHadCase = false
    private var poll: Job? = null
    private var operation: Job? = null
    private var delivered = false
    val drafts = FlowDrafts()
    val acceptedCaptures = AcceptedCaptures()
    private var loaded = false

    fun loadIfNeeded() {
        if (!loaded && !state.value.busy) load()
    }

    private fun update(change: (FlowState) -> FlowState) {
        if (mutable.value.result == null) mutable.value = change(mutable.value)
    }

    private fun work(block: suspend () -> Unit) {
        if (state.value.busy || state.value.result != null) return
        update { it.copy(busy = true, error = null, errorKey = null) }
        operation =
            viewModelScope.launch {
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    handle(e)
                } finally {
                    update { it.copy(busy = false) }
                }
            }
    }

    fun load() = work {
        acceptedCaptures.clear()
        update { it.copy(stage = Stage.LOADING) }
        try {
            val s = api.currentSession()
            ensureActiveOperation()
            loaded = true
            drafts.prefill(s)
            launchHadCase = s.caseId != null
            val route = FlowRouting.launchRoute(s)
            update {
                it.copy(
                    session = s,
                    mode = FlowRouting.navigationMode(launchHadCase, s.caseStatus),
                    stage = Stage.READY,
                    path =
                        if (route is LaunchRoute.Status) listOf(Route.Step(Destination.Status))
                        else emptyList(),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is ApiException && e.status == 404)
                finish(IdentifiedResult.Failed(IdentifiedError.SessionInvalid))
            else {
                handle(e)
                update { it.copy(stage = Stage.UNAVAILABLE) }
            }
        }
    }

    fun begin() {
        if (state.value.busy) return
        val s = state.value.session ?: return
        acceptedCaptures.clear()
        update {
            it.copy(
                path =
                    listOf(
                        if (s.caseId != null && s.consentRecorded == true)
                            Route.Step(Destination.Checklist)
                        else Route.Consent
                    ),
                error = null,
                errorKey = null,
            )
        }
    }

    fun terms() {
        if (!state.value.busy) update { it.copy(path = it.path + Route.Terms) }
    }

    fun consent() = work {
        val s = state.value.session ?: return@work
        api.recordConsent(FlowRouting.consentScopes(s))
        val c = api.getOrCreateCase(s)
        ensureActiveOperation()
        update { it.copy(current = c, path = listOf(Route.Step(Destination.Checklist))) }
    }

    fun refreshChecklist() = work {
        val c = api.getCase(requireCase())
        val s =
            if (launchHadCase || c.status == CaseStatus.changesRequested) api.currentSession()
            else state.value.session
        update {
            it.copy(
                current = c,
                session = s,
                mode = FlowRouting.navigationMode(launchHadCase, c.status),
            )
        }
    }

    fun loadCase() = work {
        update { it.copy(reviewLoadFailed = false) }
        try {
            val c = api.getCase(requireCase())
            update { it.copy(current = c) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            update { it.copy(reviewLoadFailed = true) }
            throw e
        }
    }

    fun continueChecklist() {
        if (state.value.busy) return
        val s = state.value.session ?: return
        val c = state.value.current ?: return
        FlowRouting.checklistContinue(c, s, state.value.mode)?.let(::go)
    }

    fun open(destination: Destination) {
        if (state.value.busy) return
        val s = state.value.session ?: return
        if (destination == Destination.ProofOfAddress) resetAddress()
        go(FlowRouting.guarded(destination, s))
    }

    fun replace(type: String) =
        open(
            when (type) {
                DocType.selfie -> Destination.Selfie
                DocType.proofOfAddress -> Destination.ProofOfAddress
                else -> Destination.Documents(type)
            }
        )

    private suspend fun save(patch: JsonObject) {
        try {
            api.saveMetadata(patch)
        } catch (e: ApiException) {
            if (
                e.status == 500 &&
                    e.message.orEmpty().contains("verification details changed", ignoreCase = true)
            ) {
                try {
                    val latest = api.currentSession()
                    update { it.copy(session = latest) }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {}
            }
            throw e
        }
    }

    fun saveName(first: String, middle: String, last: String) {
        val f = NameInput.normalise(first)
        val m = NameInput.normalise(middle)
        val l = NameInput.normalise(last)
        when (NameInput.validate(f, m, l)) {
            NameInput.Problem.MISSING -> return errorKey("name.missing")
            NameInput.Problem.TOO_LONG -> return errorKey("name.too_long")
            null -> Unit
        }
        work {
            save(MetadataPatch.name(f, m, l))
            refetchComplete(FlowScreen.YOUR_NAME)
        }
    }

    fun saveIdDetails(dob: String, type: String, number: String) {
        if (dob.isBlank()) return errorKey("id.missing_dob")
        if (type.isBlank()) return errorKey("id.missing_type")
        if (type == "national_id" && !IDDetailsInput.isValidGhanaCard(number))
            return errorKey("id.invalid_ghana")
        if (number.isBlank()) return
        work {
            save(MetadataPatch.idDetails(dob, type, number.trim(), IDDetailsInput.label(type)))
            refetchComplete(FlowScreen.ID_DETAILS)
        }
    }

    private suspend fun refetchComplete(screen: FlowScreen) {
        val s = api.currentSession()
        ensureActiveOperation()
        update { it.copy(session = s) }
        completed(screen)
    }

    fun completed(screen: FlowScreen) {
        if (state.value.result != null) return
        state.value.session?.let { go(FlowRouting.afterCompleting(screen, it, state.value.mode)) }
    }

    fun uploadCard(type: String, data: ByteArray, onAccepted: () -> Unit) = work {
        api.uploadDocument(requireCase(), type, null, data, "image/jpeg")
        ensureActiveOperation()
        acceptedCaptures.record(type, data, "image/jpeg")
        onAccepted()
    }

    fun uploadSelfie(frames: Map<String, ByteArray>) {
        if (!SelfieRole.all.all { frames[it]?.isNotEmpty() == true })
            return errorKey("selfie.too_few")
        work {
            val id = requireCase()
            for (role in listOf("left", "right", "center")) api.uploadDocument(
                id,
                DocType.selfie,
                role,
                frames.getValue(role),
                "image/jpeg",
            )
            ensureActiveOperation()
            acceptedCaptures.record(DocType.selfie, frames.getValue("center"), "image/jpeg")
            api.runBiometricCheck(id)
            ensureActiveOperation()
            completed(FlowScreen.SELFIE)
        }
    }

    fun resolveAddress(code: String) = work {
        val normalized = DigitalAddressCode.storageForm(code)
        try {
            val address = api.resolveDigitalAddress(normalized)
            update { it.copy(candidate = address, candidateSource = AddressSource.TYPED) }
        } catch (e: ApiException) {
            if (e.status == 503)
                saveAddress(StatedAddress(normalized, source = AddressSource.TYPED), true)
            else throw e
        }
    }

    fun locate(location: suspend () -> Pair<Double, Double>) = work {
        val (lat, lng) = location()
        val address = api.encodeDigitalAddress(lat, lng)
        update { it.copy(candidate = address, candidateSource = AddressSource.CURRENT_LOCATION) }
    }

    fun clearCandidate() {
        if (!state.value.busy) update { it.copy(candidate = null, error = null, errorKey = null) }
    }

    fun confirmCandidate() {
        val s = state.value
        val c = s.candidate ?: return
        confirmAddress(
            StatedAddress(
                DigitalAddressCode.storageForm(c.code),
                c.area,
                c.district,
                c.region,
                s.candidateSource,
            )
        )
    }

    fun confirmAddress(address: StatedAddress) = work { saveAddress(address, false) }

    private suspend fun saveAddress(address: StatedAddress, unavailable: Boolean) {
        try {
            save(MetadataPatch.statedAddress(address))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handle(e) /* Supporting evidence: nonfatal failures do not block capture. */
        }
        if (state.value.result == null)
            update {
                it.copy(
                    address = address,
                    candidate = null,
                    lookupUnavailable = unavailable,
                    error = null,
                    errorKey = null,
                )
            }
    }

    fun resetAddress() {
        if (!state.value.busy) drafts.addressPhoto = null
        if (!state.value.busy)
            update {
                it.copy(address = null, candidate = null, pages = 0, lookupUnavailable = false)
            }
    }

    fun changeAddress() {
        if (state.value.pages == 0) resetAddress()
    }

    /** Camera evidence is memory-only and cannot upload until explicitly accepted. */
    fun previewAddressPhoto(data: ByteArray) {
        val s = state.value
        if (
            s.result != null ||
                s.busy ||
                s.route != Route.Step(Destination.ProofOfAddress) ||
                s.address == null ||
                s.exhausted ||
                s.pages >= 4
        )
            return
        if (data.size > s.policy.maxSizeBytes) return errorKey("poa.too_large")
        drafts.addressPhoto = data
    }

    fun useAddressPhoto() {
        val s = state.value
        val photo = drafts.addressPhoto ?: return
        if (
            s.result != null ||
                s.busy ||
                s.route != Route.Step(Destination.ProofOfAddress) ||
                s.address == null ||
                s.exhausted ||
                s.pages >= 4
        )
            return
        drafts.addressPhoto = null
        uploadAddressPage(photo, "image/jpeg")
    }

    fun uploadAddressPage(data: ByteArray, mime: String) {
        val s = state.value
        if (s.address == null || s.exhausted || s.pages >= 4) return
        if (data.size > s.policy.maxSizeBytes) return errorKey("poa.too_large")
        work {
            update { it.copy(addressRefusal = null) }
            try {
                api.uploadDocument(
                    requireCase(),
                    DocType.proofOfAddress,
                    if (s.pages == 0) null else "page_${s.pages+1}",
                    data,
                    mime,
                )
                ensureActiveOperation()
                acceptedCaptures.record(DocType.proofOfAddress, data, mime)
                update { it.copy(pages = it.pages + 1) }
            } catch (e: ApiException) {
                if (e.status == 422)
                    update {
                        it.copy(
                            exhausted = e.exhausted,
                            addressRefusal = e.message,
                            error = null,
                            errorKey = null,
                        )
                    }
                else throw e
            }
        }
    }

    /** Picker reads are also owned by the ViewModel and cancelled with it. */
    fun importAddressPage(read: suspend () -> Pair<ByteArray, String>) {
        if (
            state.value.busy ||
                state.value.exhausted ||
                state.value.pages >= 4 ||
                state.value.address == null
        )
            return
        work {
            val (data, mime) = read()
            if (data.size > state.value.policy.maxSizeBytes) {
                errorKey("poa.too_large")
                return@work
            }
            val s = state.value
            update { it.copy(addressRefusal = null) }
            try {
                api.uploadDocument(
                    requireCase(),
                    DocType.proofOfAddress,
                    if (s.pages == 0) null else "page_${s.pages+1}",
                    data,
                    mime,
                )
                ensureActiveOperation()
                acceptedCaptures.record(DocType.proofOfAddress, data, mime)
                update { it.copy(pages = it.pages + 1) }
            } catch (e: ApiException) {
                if (e.status == 422)
                    update {
                        it.copy(
                            exhausted = e.exhausted,
                            addressRefusal = e.message,
                            error = null,
                            errorKey = null,
                        )
                    }
                else throw e
            }
        }
    }

    fun submit(optIn: Boolean, email: String) {
        if (optIn && !ReviewInput.isValidEmail(email)) return errorKey("review.email.invalid")
        work {
            val id = requireCase()
            save(MetadataPatch.reportDelivery(optIn, if (optIn) email.trim() else ""))
            val status = api.submitCase(id)
            ensureActiveOperation()
            update {
                it.copy(
                    current = it.current?.copy(status = status),
                    session = it.session?.copy(caseStatus = status),
                )
            }
            go(Destination.Status)
        }
    }

    fun startPolling(interval: Long = 5000) {
        if (poll?.isActive == true || state.value.result != null) return
        poll =
            viewModelScope.launch {
                while (
                    isActive &&
                        state.value.result == null &&
                        state.value.route == Route.Step(Destination.Status)
                ) {
                    try {
                        val c = api.getCase(requireCase())
                        update { it.copy(current = c, error = null, errorKey = null) }
                        if (!FlowRouting.shouldKeepPolling(c.status)) break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        handle(e)
                    }
                    delay(interval)
                }
            }
    }

    fun stopPolling() {
        poll?.cancel()
        poll = null
    }

    fun resubmit() {
        if (!state.value.busy) {
            stopPolling()
            update {
                it.copy(
                    mode = NavigationMode.RESUME,
                    path = listOf(Route.Step(Destination.Checklist)),
                    error = null,
                    errorKey = null,
                )
            }
        }
    }

    fun back() {
        if (state.value.result != null) return
        if (state.value.busy) return
        val route = state.value.route
        if (
            route is Route.Step &&
                route.destination is Destination.Documents &&
                drafts.captureIndex > 0
        ) {
            drafts.captureIndex--
            drafts.frames = null
            update { it.copy(error = null, errorKey = null) }
            return
        }
        drafts.clearImages()
        when (state.value.route) {
            Route.Step(Destination.Status),
            Route.Step(Destination.Checklist),
            Route.Entry -> close()
            else -> update { it.copy(path = it.path.dropLast(1), error = null, errorKey = null) }
        }
    }

    fun close() {
        val s = state.value
        val id = s.caseId
        finish(
            if (s.route == Route.Step(Destination.Status) && id != null)
                IdentifiedResult.Completed(id, s.current?.status ?: s.session?.caseStatus.orEmpty())
            else IdentifiedResult.Cancelled
        )
    }

    fun noCamera() = finish(IdentifiedResult.Failed(IdentifiedError.NoCamera))

    fun errorKey(key: String) {
        update { it.copy(error = null, errorKey = key) }
    }

    private fun requireCase() = state.value.caseId ?: error("No verification case is available")

    private fun go(destination: Destination) {
        if (state.value.result != null) return
        drafts.clearImages()
        if (destination != Destination.Status) stopPolling()
        update {
            it.copy(
                path =
                    if (destination == Destination.Checklist || destination == Destination.Status)
                        listOf(Route.Step(destination))
                    else it.path + Route.Step(destination),
                error = null,
                errorKey = null,
            )
        }
    }

    private fun handle(e: Exception) {
        if (e is CancellationException) throw e
        val failure =
            if (e is ApiException)
                when (e.sessionFailure) {
                    SessionFailure.EXPIRED -> IdentifiedError.SessionExpired
                    SessionFailure.INVALID -> IdentifiedError.SessionInvalid
                    else -> null
                }
            else null
        if (failure != null) finish(IdentifiedResult.Failed(failure))
        else update { it.copy(error = e.message ?: "Request failed", errorKey = null) }
    }

    private fun finish(result: IdentifiedResult) {
        if (state.value.result == null) {
            stopPolling()
            operation?.cancel()
            drafts.clearImages()
            acceptedCaptures.clear()
            update { it.copy(result = result, busy = false) }
        }
    }

    private suspend fun ensureActiveOperation() {
        currentCoroutineContext().ensureActive()
        check(state.value.result == null)
    }

    override fun onCleared() {
        drafts.clearImages()
        acceptedCaptures.clear()
        super.onCleared()
    }

    fun takeResult(): IdentifiedResult? {
        if (delivered) return null
        return state.value.result?.also { delivered = true }
    }
}
