package cc.identified.sdk.ui

import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class DobDatesTest {
    @Test
    fun pickerDatesUseUtcAndIsoRegardlessOfDeviceLocaleAndZone() {
        val locale = Locale.getDefault()
        val zone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-GH"))
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"))
            val millis =
                LocalDate.of(1990, 1, 5).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            assertEquals("1990-01-05", DobDates.format(millis))
            assertEquals(millis, DobDates.parse("1990-01-05"))
        } finally {
            Locale.setDefault(locale)
            TimeZone.setDefault(zone)
        }
    }

    @Test
    fun boundsIncludeTodayAndExactly120YearsAgo() {
        val dates = DobDates("2026-11-03")
        assertTrue(dates.isSelectable(dates.minimum))
        assertTrue(dates.isSelectable(dates.maximum))
        assertFalse(dates.isSelectable(dates.minimum - 86400000L))
        assertFalse(dates.isSelectable(dates.maximum + 86400000L))
        assertEquals("1906-11-03", DobDates.format(dates.minimum))
        assertEquals("2001-11-03", DobDates.format(dates.initial("")))
    }

    @Test
    fun existingDobIsSelectedAndInvalidOrOutOfRangeDatesAreHandled() {
        val dates = DobDates("2024-02-29")
        assertEquals("1999-02-28", DobDates.format(dates.initial("")))
        assertEquals("1990-01-05", DobDates.format(dates.initial("1990-01-05")))
        assertNull(DobDates.parse("2023-02-29"))
        assertNull(DobDates.parse("not-a-date"))
        assertNull(DobDates.parse("2024-02-29trailing"))
        assertNull(DobDates.parse("2024-2-9"))
        assertEquals(dates.maximum, dates.initial("2030-01-01"))
        assertEquals(dates.minimum, dates.initial("1800-01-01"))
    }
}
