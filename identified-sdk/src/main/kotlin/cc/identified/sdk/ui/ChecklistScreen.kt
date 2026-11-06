package cc.identified.sdk.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import cc.identified.sdk.*
import cc.identified.sdk.capture.*
import cc.identified.sdk.core.*

@Composable
internal fun ChecklistScreen(m: FlowViewModel, s: FlowState) {
    val c = LocalCopy.current
    LaunchedEffect(m) { m.refreshChecklist() }
    val session = s.session
    val current = s.current
    Shell(
        m,
        s,
        "checklist.title",
        "checklist.subtitle",
        footer = {
            Primary(
                c.text("flow.continue"),
                !s.busy &&
                    session != null &&
                    current != null &&
                    !FlowRouting.correctionNeeded(current, session),
                action = m::continueChecklist,
            )
        },
    ) {
        if (s.busy && current == null) {
            val rows = FlowRules.orderedCaptureSteps(session?.requiredDocuments.orEmpty()).size
            if (rows > 0) ChecklistSkeleton(rows) else CircularProgressIndicator()
        }
        if (current == null && !s.busy)
            Secondary(c.text("checklist.retry"), action = m::refreshChecklist)
        if (session != null && current != null) {
            val changes = current.status == CaseStatus.changesRequested
            if (changes)
                Panel {
                    Text(
                        c.text("checklist.changes.title"),
                        color = Color(LocalPalette.current.warning),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Body(
                        current.reviewNotes?.takeIf { it.isNotEmpty() }
                            ?: c.text("checklist.changes.body")
                    )
                    if (FlowRules.collectsName(session))
                        TextButton(onClick = { m.open(Destination.YourName) }, enabled = !s.busy) {
                            Text(c.text("checklist.changes.name"))
                        }
                    TextButton(onClick = { m.open(Destination.IdDetails) }, enabled = !s.busy) {
                        Text(c.text("checklist.changes.details"))
                    }
                }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRules.checklist(
                        current,
                        FlowRules.orderedCaptureSteps(session.requiredDocuments),
                    )
                    .forEachIndexed { index, item ->
                        DocumentRow(
                            index + 1,
                            docLabel(item.key, c),
                            item.complete,
                            c.text(if (item.complete) "checklist.done" else "checklist.required"),
                            replacement =
                                if (changes && item.complete)
                                    c.text("checklist.replace", docLabel(item.key, c))
                                else null,
                            enabled = !s.busy,
                            onReplace = { m.replace(item.key) },
                        )
                    }
            }
        }
    }
}
