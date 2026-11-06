package cc.identified.sdk.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cc.identified.sdk.*
import cc.identified.sdk.capture.*
import cc.identified.sdk.core.*

@Composable
internal fun ReviewScreen(m: FlowViewModel, s: FlowState) {
    val c = LocalCopy.current
    var optIn by m.drafts::reportOptIn
    var email by m.drafts::reportEmail
    LaunchedEffect(m) { m.loadCase() }
    val items =
        s.current?.let {
            FlowRules.checklist(
                it,
                FlowRules.orderedCaptureSteps(s.session?.requiredDocuments.orEmpty()),
            )
        }
    if (s.busy && s.current != null) {
        Processing(
            listOf(
                "review.processing.scanning",
                "review.processing.checking",
                "review.processing.running",
                "review.processing.almost",
            ),
            "review.processing.detail",
            m::close,
        )
        return
    }
    Shell(
        m,
        s,
        "review.title",
        "review.subtitle",
        FlowScreen.REVIEW,
        footer = {
            Primary(
                c.text("review.submit"),
                !s.busy && items != null && items.all { it.complete },
                "identified-submit",
            ) {
                m.submit(optIn, email)
            }
        },
    ) {
        if (items == null && s.busy) CircularProgressIndicator()
        if (s.canRetryReview || (items == null && !s.busy)) {
            Secondary(c.text("checklist.retry"), action = m::loadCase)
        }
        items?.let { ReviewRows(it) }
        CapturedStrip(m.acceptedCaptures)
        TintedPanel {
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("identified-report-opt-in")
                        .toggleable(
                            value = optIn,
                            role = Role.Checkbox,
                            onValueChange = { optIn = it },
                        )
                        .semantics(mergeDescendants = true) {
                            contentDescription = c.text("review.email.opt_in")
                        },
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val checkShape =
                    androidx.compose.foundation.shape.RoundedCornerShape(
                        (LocalAppearance.current.cornerRadius.coerceAtLeast(0f) * .125f).dp
                    )
                val checkInk = MaterialTheme.colorScheme.onPrimary
                Box(
                    Modifier.padding(top = 2.dp)
                        .size(20.dp)
                        .background(
                            if (optIn) MaterialTheme.colorScheme.primary else webSurface(),
                            checkShape,
                        )
                        .border(
                            1.dp,
                            if (optIn) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            checkShape,
                        )
                        .clearAndSetSemantics {},
                    contentAlignment = Alignment.Center,
                ) {
                    if (optIn)
                        Canvas(Modifier.size(16.dp)) {
                            val check =
                                androidx.compose.ui.graphics.Path().apply {
                                    moveTo(size.width * .2f, size.height * .5f)
                                    lineTo(size.width * .43f, size.height * .73f)
                                    lineTo(size.width * .82f, size.height * .28f)
                                }
                            drawPath(
                                check,
                                checkInk,
                                style =
                                    androidx.compose.ui.graphics.drawscope.Stroke(
                                        2.dp.toPx(),
                                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                        join = androidx.compose.ui.graphics.StrokeJoin.Round,
                                    ),
                            )
                        }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        c.text("review.email.opt_in"),
                        style =
                            MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                            ),
                    )
                    Support(c.text("review.email.detail"))
                }
            }
            if (optIn) {
                Field(
                    c.text("review.email.label"),
                    email,
                    "identified-report-email",
                    { email = it },
                    KeyboardType.Email,
                )
                Support(c.text("review.email.note"))
            }
        }
    }
}

@Composable
internal fun StatusScreen(m: FlowViewModel, s: FlowState) {
    val c = LocalCopy.current
    DisposableEffect(m) {
        m.startPolling()
        onDispose { m.stopPolling() }
    }
    val current = s.current
    Shell(
        m,
        s,
        centeredContent = true,
        footer = {
            if (current?.status == CaseStatus.changesRequested)
                Primary(c.text("status.resubmit"), action = m::resubmit)
            else Primary(c.text("flow.done"), tag = "identified-done", action = m::close)
        },
    ) {
        if (current == null) {
            if (s.error == null) CircularProgressIndicator()
        } else {
            val summary = StatusRules.summary(current)
            val prefix =
                when (summary.kind) {
                    StatusKind.FULLY_VERIFIED -> "fully_verified"
                    StatusKind.VERIFIED -> "verified"
                    StatusKind.NOT_APPROVED -> "not_approved"
                    StatusKind.CLOSED -> "closed"
                    StatusKind.AUTOMATED_REVIEW -> "automated"
                    StatusKind.MANUAL_REVIEW -> "manual"
                    StatusKind.BACKGROUND_PENDING -> "background_pending"
                    StatusKind.IN_REVIEW -> "in_review"
                    StatusKind.SUBMITTED -> "submitted"
                    StatusKind.CHANGES_REQUESTED -> "changes"
                    StatusKind.BACKGROUND_IN_PROGRESS -> "background"
                    StatusKind.PENDING -> "pending"
                }
            val toneColor =
                Color(
                    when (summary.tone) {
                        StatusTone.SUCCESS -> LocalPalette.current.success
                        StatusTone.FAILURE -> LocalPalette.current.error
                        StatusTone.ATTENTION -> LocalPalette.current.warning
                        StatusTone.WAITING -> LocalPalette.current.textPrimary
                    }
                )
            Box(
                Modifier.align(Alignment.CenterHorizontally)
                    .size(88.dp)
                    .background(
                        if (
                            summary.tone == StatusTone.WAITING &&
                                LocalPalette.current == Palette.Light
                        )
                            Color(0xFFDCD6FF)
                        else toneColor.copy(alpha = .12f),
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                StatusGlyph(summary.tone, toneColor)
            }
            Text(
                c.text("status.$prefix.title"),
                style = MaterialTheme.typography.titleLarge,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().semantics { heading() },
            )
            Text(
                c.text("status.$prefix.body"),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.widthIn(max = 304.dp).align(Alignment.CenterHorizontally),
            )
            if (summary.background.pending) {
                val verified =
                    current.status == CaseStatus.backgroundPending ||
                        current.status == CaseStatus.approved
                val review = current.status == CaseStatus.manualReview
                StatusCard(
                    c.text("status.identity.title"),
                    c.text(
                        if (verified) "status.identity.done"
                        else if (review) "status.identity.review" else "status.identity.running"
                    ),
                    c.text(
                        if (verified) "status.badge.verified"
                        else if (review) "status.badge.needs_review" else "status.badge.in_progress"
                    ),
                    tone =
                        Color(
                            if (review) LocalPalette.current.warning
                            else LocalPalette.current.success
                        ),
                )
                StatusCard(
                    if (summary.background.count == 0) c.text("status.screening.none")
                    else
                        c.text(
                            if (summary.background.count == 1) "status.screening.one"
                            else "status.screening.many",
                            summary.background.count,
                        ),
                    c.text("status.auto.updates"),
                    c.text("status.badge.in_progress"),
                    c.text("status.screening.note"),
                    active = true,
                )
            } else if (current.status == CaseStatus.automatedReview)
                StatusCard(
                    c.text("status.auto.title"),
                    c.text("status.auto.updates"),
                    c.text("status.badge.live"),
                    c.text("status.auto.note"),
                    active = true,
                )
            summary.reviewerNote?.let { Body(c.text("status.note") + " " + it) }
            summary.reviewIssue?.let { issue ->
                Panel {
                    Body(c.text("status.reason.eyebrow"))
                    when (issue) {
                        is ReviewIssue.TypeMismatch -> {
                            Text(
                                c.text("status.reason.mismatch.title"),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(c.text("status.reason.selected") + ": " + issue.selected)
                            Text(c.text("status.reason.detected") + ": " + issue.detected)
                            Body(c.text("status.reason.mismatch.message"))
                        }
                        ReviewIssue.NameNeedsConfirmation -> {
                            Text(
                                c.text("status.reason.name.title"),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Body(c.text("status.reason.name.message"))
                        }
                        ReviewIssue.NumberNeedsConfirmation -> {
                            Text(
                                c.text("status.reason.number.title"),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Body(c.text("status.reason.number.message"))
                        }
                    }
                }
            }
            Text(
                c.text("status.case_id") + current.id,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
        }
    }
}
