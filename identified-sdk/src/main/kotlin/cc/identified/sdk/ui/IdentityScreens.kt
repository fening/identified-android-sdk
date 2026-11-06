package cc.identified.sdk.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.identified.sdk.*
import cc.identified.sdk.capture.*
import cc.identified.sdk.core.*

@Composable
internal fun NameScreen(m: FlowViewModel, s: FlowState) {
    val c = LocalCopy.current
    var first by m.drafts::first
    var middle by m.drafts::middle
    var last by m.drafts::last
    Shell(
        m,
        s,
        "name.title",
        "name.subtitle",
        FlowScreen.YOUR_NAME,
        footer = {
            Primary(c.text(if (s.busy) "flow.saving" else "flow.continue"), !s.busy) {
                m.saveName(first, middle, last)
            }
        },
    ) {
        if (s.session?.firstName.isNullOrEmpty() && !s.session?.fullName.isNullOrEmpty())
            Body(c.text("name.previous", s.session!!.fullName!!))
        Field(c.text("name.first"), first, "identified-first-name", { first = it })
        Field(c.text("name.middle"), middle, "identified-middle-name", { middle = it })
        Field(c.text("name.last"), last, "identified-last-name", { last = it })
    }
}

@Composable
internal fun IdScreen(m: FlowViewModel, s: FlowState) {
    val c = LocalCopy.current
    var showsDobPicker by remember { mutableStateOf(false) }
    val options = IDDetailsInput.options(s.session?.allowedIdTypes.orEmpty())
    var dob by m.drafts::dob
    var type by m.drafts::idType
    LaunchedEffect(options) {
        if (options.size == 1) type = options[0].key
        else if (options.none { it.key == type }) type = ""
    }
    var number by m.drafts::idNumber
    var expanded by remember { mutableStateOf(false) }
    Shell(
        m,
        s,
        "id.title",
        "id.subtitle",
        FlowScreen.ID_DETAILS,
        footer = {
            Primary(
                c.text(if (s.busy) "flow.saving" else "flow.continue"),
                !s.busy && number.isNotBlank(),
            ) {
                m.saveIdDetails(dob, type, number)
            }
        },
    ) {
        SecondaryDate(dob, enabled = !s.busy) { showsDobPicker = true }
        Box {
            SelectionField(
                c.text("id.type"),
                if (type.isEmpty()) "" else IDDetailsInput.label(type),
                "identified-id-type",
                options.size > 1 && !s.busy,
            ) {
                expanded = true
            }
        }
        Field(
            c.text(if (type == "national_id") "id.number_ghana" else "id.number"),
            number,
            "identified-id-number",
            { number = it },
        )
        if (type == "national_id") Support(c.text("id.ghana_helper"))
    }
    if (expanded) {
        PickerSheet(c.text("id.type"), onDismiss = { expanded = false }) {
            Column(
                Modifier.fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    c.text("id.type").uppercase(java.util.Locale.getDefault()),
                    style =
                        MaterialTheme.typography.bodySmall.copy(
                            fontSize = 13.sp,
                            letterSpacing = 1.04.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                        ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                options.forEach { option ->
                    val selected = option.key == type
                    Row(
                        Modifier.fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .background(
                                if (selected) MaterialTheme.colorScheme.outline
                                else androidx.compose.ui.graphics.Color.Transparent,
                                androidx.compose.foundation.shape.RoundedCornerShape(
                                    (LocalAppearance.current.cornerRadius.coerceAtLeast(0f) * .75f)
                                        .dp
                                ),
                            )
                            .selectable(
                                selected = selected,
                                role = Role.RadioButton,
                                onClick = {
                                    type = option.key
                                    expanded = false
                                },
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            option.label,
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp),
                            modifier = Modifier.weight(1f),
                            color =
                                if (selected) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (selected)
                            Text(
                                "✓",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.clearAndSetSemantics {},
                            )
                    }
                }
            }
        }
    }
    if (showsDobPicker) {
        DobPicker(value = dob, onSelected = { dob = it }, onDismiss = { showsDobPicker = false })
    }
}

@Composable
internal fun SecondaryDate(value: String, enabled: Boolean, onClick: () -> Unit) {
    val display =
        remember(value) {
            DobDates.parse(value)?.let { millis ->
                java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
                    .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                    .format(java.util.Date(millis))
            } ?: value
        }
    SelectionField(
        LocalCopy.current.text("id.dob"),
        display,
        "identified-dob",
        enabled,
        calendar = true,
        action = onClick,
    )
}
