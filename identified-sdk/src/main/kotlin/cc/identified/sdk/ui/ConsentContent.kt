package cc.identified.sdk.ui

import cc.identified.sdk.core.*

internal data class ConsentSection(val title: String, val body: String, val more: List<String>)

internal object ConsentContent {
    fun brand(s: HostedSession, c: Copy) =
        s.branding?.displayName?.takeIf { it.isNotEmpty() } ?: c.text("consent.brand_fallback")

    fun sections(s: HostedSession, c: Copy): List<ConsentSection> {
        val scopes = FlowRouting.consentScopes(s)
        val biometric = "biometric" in scopes
        val background = "background" in scopes
        val poa = DocType.proofOfAddress in s.requiredDocuments
        val brand = brand(s, c)
        fun texts(prefix: String, count: Int) = (1..count).map { c.text("$prefix$it") }
        return buildList {
            val labels = s.requiredDocuments.joinToString(", ") { docLabel(it, c) }
            add(
                ConsentSection(
                    c.text("consent.collect.title"),
                    if (labels.isEmpty()) c.text("consent.collect.none")
                    else
                        labels +
                            (if (biometric) c.text("consent.collect.liveness") else "") +
                            c.text("consent.collect.tail"),
                    texts("consent.collect.more", 2) +
                        (if (poa) listOf(c.text("consent.collect.more3")) else emptyList()),
                )
            )
            if (biometric)
                add(
                    ConsentSection(
                        c.text("consent.biometric.title"),
                        c.text("consent.biometric.body"),
                        texts("consent.biometric.more", 3),
                    )
                )
            if (background)
                add(
                    ConsentSection(
                        c.text("consent.background.title"),
                        c.text("consent.background.body"),
                        texts("consent.background.more", 5),
                    )
                )
            add(
                ConsentSection(
                    c.text("consent.review.title"),
                    c.text("consent.review.body"),
                    texts("consent.review.more", 2) +
                        (if (background) listOf(c.text("consent.review.more3")) else emptyList()) +
                        c.text("consent.review.more4", brand),
                )
            )
            add(
                ConsentSection(
                    c.text("consent.use.title"),
                    c.text(
                        "consent.use.body",
                        if (background) c.text("consent.use.and_background") else "",
                        brand,
                        brand,
                    ),
                    listOf(
                        c.text("consent.use.more1", brand),
                        c.text("consent.use.more2"),
                        c.text("consent.use.more3"),
                    ),
                )
            )
            add(
                ConsentSection(
                    c.text("consent.protection.title"),
                    c.text("consent.protection.body"),
                    texts("consent.protection.more", 4),
                )
            )
            add(
                ConsentSection(
                    c.text("consent.retention.title"),
                    c.text("consent.retention.body", brand),
                    listOf(
                        c.text("consent.retention.more1", brand, brand),
                        c.text("consent.retention.more2"),
                    ),
                )
            )
            add(
                ConsentSection(
                    c.text("consent.rights.title"),
                    c.text("consent.rights.body"),
                    listOf(
                        c.text("consent.rights.more1", brand),
                        c.text("consent.rights.more2"),
                        c.text("consent.rights.more3", brand),
                    ),
                )
            )
        }
    }

    fun agreedData(s: HostedSession, c: Copy): String {
        val scopes = FlowRouting.consentScopes(s)
        val items =
            listOf(c.text("consent.data.identity")) +
                (if ("biometric" in scopes) listOf(c.text("consent.data.biometric"))
                else emptyList()) +
                (if ("background" in scopes) listOf(c.text("consent.data.background"))
                else emptyList())
        return if (items.size == 1) c.text("consent.data.one", items[0])
        else c.text("consent.data.many", items.dropLast(1).joinToString(", "), items.last())
    }
}

internal fun docLabel(type: String, c: Copy) =
    when (type) {
        DocType.governmentId,
        DocType.licenseFront,
        DocType.licenseBack,
        DocType.selfie,
        DocType.proofOfAddress -> c.text("doc.$type")
        else -> type
    }
