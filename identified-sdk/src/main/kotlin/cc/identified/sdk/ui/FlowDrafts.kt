package cc.identified.sdk.ui

import androidx.compose.runtime.*
import cc.identified.sdk.core.HostedSession

/** In-memory editing state survives Activity rotation but is never serialized. */
internal class FlowDrafts {
    var first by mutableStateOf("")
    var middle by mutableStateOf("")
    var last by mutableStateOf("")
    var dob by mutableStateOf("")
    var idType by mutableStateOf("")
    var idNumber by mutableStateOf("")
    var reportOptIn by mutableStateOf(false)
    var reportEmail by mutableStateOf("")
    var addressCode by mutableStateOf("")
    var addressPhoto by mutableStateOf<ByteArray?>(null)
    var agreed by mutableStateOf(false)
    var captureIndex by mutableIntStateOf(0)
    var captureRoute: Any? = null
    var frames by mutableStateOf<Map<String, ByteArray>?>(null)

    fun prefill(s: HostedSession) {
        first = s.firstName.orEmpty()
        middle = s.middleName.orEmpty()
        last = s.lastName.orEmpty()
        dob = s.idDetails?.dob.orEmpty()
        idType = s.idDetails?.idType.orEmpty()
        idNumber = s.idDetails?.idNumber.orEmpty()
        reportOptIn = s.reportEmailOptIn == true
        reportEmail = s.reportEmail.orEmpty()
    }

    fun clearImages() {
        frames = null
        addressPhoto = null
        captureIndex = 0
        captureRoute = null
    }
}
