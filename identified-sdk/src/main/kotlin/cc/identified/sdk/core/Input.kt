package cc.identified.sdk.core

import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.serialization.json.*

internal object NameInput {
    const val maxLength = 256

    enum class Problem {
        MISSING,
        TOO_LONG,
    }

    fun normalise(value: String) =
        value.split(Regex("[\\s\\p{Z}]+")).filter { it.isNotEmpty() }.joinToString(" ")

    fun validate(first: String, middle: String, last: String): Problem? {
        val parts = listOf(first, middle, last).map(::normalise)
        if (parts[0].isEmpty() || parts[2].isEmpty()) return Problem.MISSING
        val full = parts.filter { it.isNotEmpty() }.joinToString(" ")
        return if (full.codePointCount(0, full.length) > maxLength) Problem.TOO_LONG else null
    }
}

internal data class IDTypeOption(val key: String, val label: String)

internal object IDDetailsInput {
    const val nationalID = "national_id"
    val catalogue =
        listOf(
            IDTypeOption(nationalID, "Ghana Card"),
            IDTypeOption("passport", "Passport"),
            IDTypeOption("drivers_license", "Driver's License"),
        )

    enum class Problem {
        MISSING_DATE_OF_BIRTH,
        MISSING_TYPE,
        INVALID_GHANA_CARD,
    }

    fun options(allowed: List<String>) =
        if (allowed.isEmpty()) catalogue else catalogue.filter { it.key in allowed }

    fun label(key: String) =
        catalogue.firstOrNull { it.key == key }?.label
            ?: key.split('_')
                .filter { it.isNotEmpty() }
                .joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } }

    fun isValidGhanaCard(value: String) =
        Regex("^GHA-[0-9]{9}-[0-9A-Za-z]$", RegexOption.IGNORE_CASE).matches(value.trim())

    fun validate(dob: Date?, idType: String?, idNumber: String): Problem? =
        when {
            dob == null -> Problem.MISSING_DATE_OF_BIRTH
            idType.isNullOrEmpty() -> Problem.MISSING_TYPE
            idType == nationalID && !isValidGhanaCard(idNumber) -> Problem.INVALID_GHANA_CARD
            else -> null
        }

    fun dobString(date: Date): String =
        Calendar.getInstance().run {
            time = date
            String.format(
                Locale.ROOT,
                "%04d-%02d-%02d",
                get(Calendar.YEAR),
                get(Calendar.MONTH) + 1,
                get(Calendar.DAY_OF_MONTH),
            )
        }

    fun date(fromDOB: String): Date? {
        val parts = fromDOB.split('-').mapNotNull { it.toIntOrNull() }
        if (parts.size != 3) return null
        return runCatching {
                Calendar.getInstance()
                    .apply {
                        clear()
                        set(parts[0], parts[1] - 1, parts[2])
                    }
                    .time
            }
            .getOrNull()
    }
}

internal object DigitalAddressCode {
    const val pattern = "^[A-Z]{2}-?[0-9]{3,4}-?[0-9]{4}$"

    fun isValid(code: String) = Regex(pattern).matches(code.trim().uppercase(Locale.ROOT))

    fun storageForm(code: String) =
        if (isValid(code)) code.trim().uppercase(Locale.ROOT).replace("-", "") else ""

    fun displayForm(code: String): String {
        val raw = code.trim().uppercase(Locale.ROOT).replace("-", "")
        if (
            raw.length !in 9..10 ||
                !raw.take(2).all { it.isLetter() } ||
                !raw.drop(2).all { it.isDigit() }
        )
            return code.trim().uppercase(Locale.ROOT)
        return raw.take(2) + "-" + raw.drop(2).dropLast(4) + "-" + raw.takeLast(4)
    }

    fun mask(input: String): String {
        val cleaned = input.uppercase(Locale.ROOT).filter { it in 'A'..'Z' || it in '0'..'9' }
        val letters = cleaned.take(2).filter { it in 'A'..'Z' }
        val digits =
            if (letters.length == 2) cleaned.drop(2).filter { it in '0'..'9' }.take(8) else ""
        if (digits.isEmpty()) return letters
        val areaLength = if (digits.length > 7) 4 else 3
        return letters +
            "-" +
            digits.take(areaLength) +
            (if (digits.length > areaLength) "-" + digits.drop(areaLength) else "")
    }

    fun describe(address: DigitalAddress) =
        listOfNotNull(address.area, address.district, address.region)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" · ")
}

internal object ProofOfAddressRules {
    const val maxPages = 4

    enum class FormatName {
        PHOTO,
        PDF,
    }

    fun formatNames(mimeTypes: List<String>) =
        mimeTypes
            .map { if (it == "application/pdf") FormatName.PDF else FormatName.PHOTO }
            .distinct()

    fun months(days: Int) = (days / 30.0).roundToInt()

    fun megabytes(bytes: Int) = bytes / (1024 * 1024)

    fun captureRole(page: Int): String? = if (page <= 1) null else "page_$page"
}

internal object ReviewInput {
    fun isValidEmail(value: String) = Regex("^\\S+@\\S+\\.\\S+$").matches(value.trim())
}

internal object MetadataPatch {
    private fun patch(body: JsonObject) = buildJsonObject { put("metadata", body) }

    fun name(first: String, middle: String, last: String) =
        patch(
            buildJsonObject {
                put("first_name", first)
                put("middle_name", middle)
                put("last_name", last)
            }
        )

    fun idDetails(dob: String, idType: String, idNumber: String, idTypeDisplay: String) =
        patch(
            buildJsonObject {
                put("dob", dob)
                put("id_type", idType)
                put("id_number", idNumber)
                put("id_type_display", idTypeDisplay)
                put("id_type_other", "")
            }
        )

    fun statedAddress(address: StatedAddress) =
        patch(
            buildJsonObject {
                put(
                    "stated_address",
                    CoreJson.encodeToJsonElement(StatedAddress.serializer(), address),
                )
            }
        )

    fun reportDelivery(optIn: Boolean, email: String) =
        patch(
            buildJsonObject {
                put("report_email_opt_in", optIn)
                put("report_email", email)
            }
        )
}
