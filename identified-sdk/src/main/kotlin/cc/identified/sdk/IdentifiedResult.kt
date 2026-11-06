package cc.identified.sdk

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** Restore the host UI from this result; trust the server webhook for the verification decision. */
public sealed class IdentifiedResult : Parcelable {
    @Parcelize
    public data class Completed(public val caseId: String, public val status: String) :
        IdentifiedResult()

    @Parcelize public data object Cancelled : IdentifiedResult()

    @Parcelize public data class Failed(public val error: IdentifiedError) : IdentifiedResult()
}

public sealed class IdentifiedError : Parcelable {
    @Parcelize public data object SessionInvalid : IdentifiedError()

    @Parcelize public data object SessionExpired : IdentifiedError()

    @Parcelize public data object NoCamera : IdentifiedError()

    @Parcelize public data class Unexpected(public val message: String) : IdentifiedError()
}
