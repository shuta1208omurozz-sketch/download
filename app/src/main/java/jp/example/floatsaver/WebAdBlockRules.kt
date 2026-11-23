package jp.example.floatsaver

import java.net.URI
import java.util.Locale

/**
 * Privacy-friendly, static ad-host filter for the in-app WebView only.
 * No external service, user tracking, traffic proxy or DNS modifications.
 * Matching entire hosts and subdomains avoids false positives like "adventure".
 */
object WebAdBlockRules {
    private val blockedDomains = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "adnxs.com", "adsrvr.org", "criteo.com", "criteo.net",
        "pubmatic.com", "rubiconproject.com", "openx.net",
        "taboola.com", "outbrain.com", "revcontent.com",
        "adform.net", "smartadserver.com", "sharethrough.com",
        "exoclick.com", "exosrv.com", "juicyads.com", "trafficjunky.net",
        "popads.net", "popcash.net", "propellerads.com", "onclickads.net",
        "onclckads.net", "clickadu.com", "hilltopads.net",
        "adsterra.com", "ad-maven.com", "bidvertiser.com"
    )

    fun shouldBlock(raw: String): Boolean {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return false
        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https")) return false
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return false
        return blockedDomains.any { domain -> host == domain || host.endsWith(".$domain") }
    }
}
