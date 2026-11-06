package cc.identified.sdk.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Material3 calendar selections are UTC midnight, never the device's local midnight. */
internal class DobDates(today: String = format(System.currentTimeMillis())) {
    val maximum: Long = requireNotNull(parse(today)) { "Invalid current date" }
    val minimum: Long = yearsBefore(maximum, 120)
    val firstYear: Int = year(minimum)
    val lastYear: Int = year(maximum)

    fun isSelectable(utcMillis: Long): Boolean = utcMillis in minimum..maximum

    fun initial(existing: String): Long =
        (parse(existing) ?: yearsBefore(maximum, 25)).coerceIn(minimum, maximum)

    companion object {
        private fun formatter() =
            SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply {
                timeZone = TimeZone.getTimeZone("UTC")
                isLenient = false
            }

        private fun calendar(millis: Long) =
            Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT).apply {
                timeInMillis = millis
            }

        private fun year(millis: Long): Int = calendar(millis).get(Calendar.YEAR)

        private fun yearsBefore(millis: Long, years: Int): Long =
            calendar(millis).apply { add(Calendar.YEAR, -years) }.timeInMillis

        fun parse(value: String): Long? {
            if (!Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(value)) return null
            val position = ParsePosition(0)
            val date = formatter().parse(value, position) ?: return null
            return date.time.takeIf { position.index == value.length }
        }

        fun format(utcMillis: Long): String = formatter().format(Date(utcMillis))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DobPicker(value: String, onSelected: (String) -> Unit, onDismiss: () -> Unit) {
    val c = LocalCopy.current
    val palette = LocalPalette.current
    val surface = pickerSurface()
    val primaryText = Color(palette.textPrimary)
    val secondaryText = Color(palette.textSecondary)
    val accent = Color(palette.accent)
    val onAccent = Color(palette.onAccent)
    val dates = remember { DobDates() }
    val selectableDates =
        remember(dates) {
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    dates.isSelectable(utcTimeMillis)

                override fun isSelectableYear(year: Int): Boolean =
                    year in dates.firstYear..dates.lastYear
            }
        }
    val picker =
        rememberDatePickerState(
            initialSelectedDateMillis = dates.initial(value),
            yearRange = dates.firstYear..dates.lastYear,
            selectableDates = selectableDates,
        )
    PickerSheet(c.text("id.dob"), onDismiss = onDismiss) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Text(
                    c.text("id.dob").uppercase(Locale.getDefault()),
                    style =
                        MaterialTheme.typography.bodySmall.copy(
                            fontSize = 13.sp,
                            letterSpacing = 1.04.sp,
                        ),
                    color = secondaryText,
                )
                val selectedDate = picker.selectedDateMillis
                Text(
                    remember(selectedDate) {
                        selectedDate?.let { millis ->
                            java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
                                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                                .format(Date(millis))
                        } ?: value
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = primaryText,
                    modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
                )
            }
            DatePicker(
                state = picker,
                title = null,
                headline = null,
                showModeToggle = false,
                modifier = Modifier.testTag("identified-dob-dialog"),
                colors =
                    DatePickerDefaults.colors(
                        containerColor = surface,
                        titleContentColor = secondaryText,
                        headlineContentColor = primaryText,
                        weekdayContentColor = secondaryText,
                        subheadContentColor = primaryText,
                        yearContentColor = primaryText,
                        currentYearContentColor = accent,
                        selectedYearContentColor = onAccent,
                        selectedYearContainerColor = accent,
                        dayContentColor = primaryText,
                        disabledDayContentColor = secondaryText.copy(alpha = 0.38f),
                        selectedDayContentColor = onAccent,
                        selectedDayContainerColor = accent,
                        todayContentColor = accent,
                        todayDateBorderColor = accent,
                        dividerColor = Color(palette.border),
                    ),
            )
        }
        HorizontalDivider(color = Color(palette.border))
        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Primary(
                c.text("flow.done"),
                enabled = picker.selectedDateMillis?.let(dates::isSelectable) == true,
                tag = "identified-dob-confirm",
            ) {
                picker.selectedDateMillis?.takeIf(dates::isSelectable)?.let {
                    onSelected(DobDates.format(it))
                    onDismiss()
                }
            }
        }
    }
}
