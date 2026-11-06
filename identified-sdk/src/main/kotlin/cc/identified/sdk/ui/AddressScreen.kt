package cc.identified.sdk.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.core.content.ContextCompat
import cc.identified.sdk.capture.CameraScreen
import cc.identified.sdk.capture.CaptureKind
import cc.identified.sdk.core.*
import cc.identified.sdk.platform.FilePreparation
import cc.identified.sdk.platform.currentLocation
import kotlinx.coroutines.TimeoutCancellationException

@Composable
internal fun AddressScreen(m: FlowViewModel, s: FlowState) {
    val c = LocalCopy.current
    val context = LocalContext.current
    val draft = m.drafts
    val pendingPhoto = draft.addressPhoto
    var sources by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf(false) }
    val read: (android.net.Uri) -> Unit = { uri ->
        m.importAddressPage {
            val file = FilePreparation.read(context.applicationContext, uri, s.policy.maxSizeBytes)
            file.data to file.mimeType
        }
    }
    val files =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(read) }
    val photos =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {
            it?.let(read)
        }
    val locate: () -> Unit = {
        m.locate {
            try {
                currentLocation(context.applicationContext)
            } catch (e: TimeoutCancellationException) {
                throw IllegalStateException(c.text("poa.location.timeout"))
            } catch (e: SecurityException) {
                throw IllegalStateException(c.text("poa.location.denied"))
            }
        }
    }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            grants ->
            if (grants.values.any { it }) locate() else m.errorKey("poa.location.denied")
        }
    if (camera) {
        CameraScreen(
            CaptureKind.DOCUMENT,
            docLabel(DocType.proofOfAddress, c),
            onCaptured = { captured ->
                camera = false
                captured["image"]?.let(m::previewAddressPhoto)
            },
            onClose = { camera = false },
            onNoCamera = m::noCamera,
        )
        return
    }
    Shell(
        m,
        s,
        step = FlowScreen.PROOF_OF_ADDRESS,
        titleCopy = docLabel(DocType.proofOfAddress, c),
        subtitle = if (s.address == null) null else "poa.subtitle",
        footer = {
            if (s.address != null && pendingPhoto == null) {
                if (s.pages > 0)
                    Primary(c.text("flow.continue"), !s.busy) {
                        m.completed(FlowScreen.PROOF_OF_ADDRESS)
                    }
                if (!s.exhausted && s.pages < 4) {
                    if (s.pages == 0)
                        Primary(c.text("poa.choose_file"), !s.busy, "identified-choose-file") {
                            sources = true
                        }
                    else Secondary(c.text("poa.choose_file"), !s.busy) { sources = true }
                    Secondary(
                        c.text(if (s.pages == 0) "poa.take_photo" else "poa.add_page"),
                        !s.busy,
                    ) {
                        camera = true
                    }
                }
            }
        },
    ) {
        s.addressRefusal?.let { refusal ->
            Text(
                refusal.ifBlank { c.text("poa.refused") },
                color = MaterialTheme.colorScheme.error,
                modifier =
                    Modifier.testTag("identified-address-refusal").semantics {
                        liveRegion = LiveRegionMode.Polite
                    },
            )
        }
        if (s.address == null) {
            Text(c.text("poa.address.prompt"), style = MaterialTheme.typography.titleMedium)
            val candidate = s.candidate
            if (candidate == null) {
                Field(
                    c.text("poa.address.label"),
                    draft.addressCode,
                    "identified-address-code",
                    { draft.addressCode = DigitalAddressCode.mask(it) },
                )
                Support(c.text("poa.address.helper"))
                Primary(
                    c.text(if (s.busy) "poa.address.checking" else "poa.address.find"),
                    !s.busy && DigitalAddressCode.isValid(draft.addressCode),
                    "identified-find-address",
                ) {
                    m.resolveAddress(draft.addressCode)
                }
                Body(c.text("poa.address.unknown"))
                Secondary(c.text("poa.address.location"), !s.busy) {
                    if (
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                        ) == PackageManager.PERMISSION_GRANTED ||
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            ) == PackageManager.PERMISSION_GRANTED
                    )
                        locate()
                    else
                        permission.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            )
                        )
                }
                Support(c.text("poa.address.footnote"))
            } else
                Panel {
                    Body(c.text("poa.address.confirm"))
                    Text(
                        DigitalAddressCode.displayForm(candidate.code),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Body(
                        DigitalAddressCode.describe(candidate).ifEmpty {
                            c.text("poa.address.no_locality")
                        }
                    )
                    candidate.street?.takeIf(String::isNotEmpty)?.let { Body(it) }
                    Primary(
                        c.text("poa.address.right"),
                        !s.busy,
                        "identified-confirm-address",
                        m::confirmCandidate,
                    )
                    Secondary(c.text("poa.address.change_it"), !s.busy) {
                        m.clearCandidate()
                        draft.addressCode = ""
                    }
                }
        } else {
            Panel {
                if (s.policy.acceptedTypes.isNotEmpty())
                    Text(
                        c.text("poa.requirements.title"),
                        style = MaterialTheme.typography.titleMedium,
                    )
                s.policy.acceptedTypes.forEach { type ->
                    Body(
                        type.hint?.let { c.text("poa.requirements.type", type.label, it) }
                            ?: type.label
                    )
                }
                Body(
                    c.text("poa.requirements.age", ProofOfAddressRules.months(s.policy.maxAgeDays))
                )
                val formats =
                    ProofOfAddressRules.formatNames(s.policy.acceptedFormats).map {
                        c.text(
                            if (it == ProofOfAddressRules.FormatName.PDF) "poa.format.pdf"
                            else "poa.format.photo"
                        )
                    }
                val names =
                    if (formats.isEmpty()) c.text("poa.format.any")
                    else if (formats.size == 1) formats[0]
                    else
                        c.text(
                            "poa.format.or",
                            formats.dropLast(1).joinToString(", "),
                            formats.last(),
                        )
                Body(
                    c.text(
                        "poa.requirements.formats",
                        names,
                        ProofOfAddressRules.megabytes(s.policy.maxSizeBytes),
                    )
                )
            }
            Panel {
                Body(c.text("poa.your_address"))
                Text(
                    DigitalAddressCode.displayForm(s.address.code),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (s.pages == 0 && pendingPhoto == null)
                    TextButton(onClick = m::changeAddress, enabled = !s.busy) {
                        Text(c.text("poa.change"))
                    }
                if (s.lookupUnavailable) Body(c.text("poa.address.unchecked"))
            }
            if (s.pages > 0) {
                Text(
                    if (s.pages == 1) c.text("poa.pages.one") else c.text("poa.pages.many", s.pages)
                )
                Body(c.text(if (s.pages < 4) "poa.pages.more" else "poa.pages.max"))
            }
            if (pendingPhoto != null) {
                CapturePreview(
                    data = pendingPhoto,
                    label = c.text("camera.preview", docLabel(DocType.proofOfAddress, c)),
                    selfie = false,
                    enabled = !s.busy && !s.exhausted && s.pages < 4,
                    onUse = m::useAddressPhoto,
                    onRetake = {
                        draft.addressPhoto = null
                        camera = true
                    },
                )
            }
            if (s.busy) {
                ProcessingArt(page = true)
                Body(c.text("documents.uploading"))
            }
        }
        CapturedStrip(m.acceptedCaptures)
    }
    if (sources)
        AlertDialog(
            onDismissRequest = { sources = false },
            title = { Text(c.text("poa.choose_file")) },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            sources = false
                            files.launch(
                                s.policy.acceptedFormats
                                    .ifEmpty { listOf("image/*", "application/pdf") }
                                    .toTypedArray()
                            )
                        },
                        modifier = Modifier.testTag("identified-source-files"),
                    ) {
                        Text(c.text("poa.source.files"))
                    }
                    TextButton(
                        onClick = {
                            sources = false
                            photos.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        },
                        modifier = Modifier.testTag("identified-source-photos"),
                    ) {
                        Text(c.text("poa.source.photos"))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { sources = false }) { Text(c.text("flow.done")) }
            },
        )
}
