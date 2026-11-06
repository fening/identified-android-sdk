package cc.identified.sdk.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.identified.sdk.*
import cc.identified.sdk.capture.*
import cc.identified.sdk.core.*

@Composable
internal fun EntryScreen(m: FlowViewModel, s: FlowState) {
    val c = LocalCopy.current
    val brand =
        s.session?.branding?.displayName?.takeIf { it.isNotEmpty() }
            ?: c.text("entry.brand_fallback")
    Shell(
        m,
        s,
        footer = {
            Primary(
                c.text(if (s.session?.caseId == null) "entry.begin" else "entry.continue"),
                !s.busy,
                action = m::begin,
            )
        },
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            IdentityHero()
            Spacer(Modifier.height(32.dp))
            Text(
                c.text("entry.title"),
                style = MaterialTheme.typography.titleLarge,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(12.dp))
            Text(
                c.text("entry.body", brand),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.widthIn(max = 304.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                c.text(
                    "entry.small_print",
                    c.text(
                        if (DocType.proofOfAddress in s.session?.requiredDocuments.orEmpty())
                            "entry.duration_long"
                        else "entry.duration_short"
                    ),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
internal fun ConsentScreen(m: FlowViewModel, s: FlowState) {
    val c = LocalCopy.current
    val session = s.session ?: return
    var agreed by m.drafts::agreed
    Shell(
        m,
        s,
        "consent.title",
        lockup = false,
        subtitleCopy = c.text("consent.subtitle", ConsentContent.brand(session, c)),
        footer = {
            Primary(
                c.text(if (s.busy) "consent.button_busy" else "consent.button"),
                agreed && !s.busy,
                action = m::consent,
            )
        },
    ) {
        ConsentContent.sections(session, c).forEach { section ->
            var expanded by remember(section.title) { mutableStateOf(false) }
            Panel {
                Text(
                    section.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp),
                )
                Text(
                    section.body,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (expanded)
                    section.more.forEach {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = PaddingValues(0.dp),
                    modifier =
                        Modifier.heightIn(min = 44.dp).semantics {
                            stateDescription =
                                c.text(if (expanded) "consent.show_less" else "consent.learn_more")
                        },
                ) {
                    Text(
                        c.text(if (expanded) "consent.show_less" else "consent.learn_more"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                    )
                    Text(
                        if (expanded) "⌃" else "⌄",
                        modifier = Modifier.padding(start = 4.dp).clearAndSetSemantics {},
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .06f),
                    MaterialTheme.shapes.large,
                )
                .padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Checkbox(
                checked = agreed,
                onCheckedChange = { agreed = it },
                enabled = !s.busy,
                modifier =
                    Modifier.testTag("identified-consent-toggle").semantics {
                        contentDescription = c.text("consent.agree.checkbox")
                    },
            )
            val termsColor = MaterialTheme.colorScheme.onSurface
            val agreement =
                androidx.compose.ui.text.buildAnnotatedString {
                    append(c.text("consent.agree.prefix"))
                    append(" ")
                    pushStringAnnotation("terms", "terms")
                    pushStyle(
                        androidx.compose.ui.text.SpanStyle(
                            color = termsColor,
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                        )
                    )
                    append(c.text("consent.agree.terms"))
                    pop()
                    pop()
                    append(" ")
                    append(c.text("consent.agree.suffix", ConsentContent.agreedData(session, c)))
                }
            @Suppress("DEPRECATION")
            androidx.compose.foundation.text.ClickableText(
                text = agreement,
                style =
                    MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                modifier =
                    Modifier.weight(1f).padding(top = 12.dp).semantics {
                        onClick(label = c.text("consent.agree.terms")) {
                            if (!s.busy) m.terms()
                            true
                        }
                    },
                onClick = { offset ->
                    if (
                        !s.busy &&
                            agreement.getStringAnnotations("terms", offset, offset).isNotEmpty()
                    )
                        m.terms()
                },
            )
        }
    }
}

@Composable
internal fun TermsScreen(m: FlowViewModel, s: FlowState) {
    val c = LocalCopy.current
    val brand =
        s.session?.branding?.displayName?.takeIf { it.isNotEmpty() }
            ?: c.text("terms.brand_fallback")
    Shell(
        m,
        s,
        "terms.title",
        lockup = false,
        footer = { Primary(c.text("flow.done"), tag = "identified-done", action = m::back) },
    ) {
        for (i in 1..7) {
            Text(c.text("terms.$i.title"), style = MaterialTheme.typography.titleMedium)
            val count =
                when (i) {
                    6 -> 2
                    7 -> 4
                    else -> 3
                }
            for (p in 1..count) Body(
                when ("$i.$p") {
                    "1.2" -> c.text("terms.1.p2", brand)
                    "7.3" -> c.text("terms.7.p3", ConsentVersion.current)
                    else -> c.text("terms.$i.p$p")
                }
            )
        }
    }
}
