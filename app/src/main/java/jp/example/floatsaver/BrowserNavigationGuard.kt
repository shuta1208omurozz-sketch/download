package jp.example.floatsaver

import java.net.URI
import java.util.Locale

/**
 * Prevent unexpected cross-host top-level advertising navigations while blocking
 * is enabled. Does not intercept media subresources, embedded iframes or download URLs.
 * Explicitly opened player pages may navigate within their own host.
 */
object BrowserNavigationGuard {
    private fun host(url: String): String? {
        val parsed = runCatching { URI(url) }.getOrNull() ?: return null
        if (parsed.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https")) return null
        return parsed.host?.lowercase(Locale.ROOT)?.trimEnd('.')
    }

    private fun sameHost(a: String, b: String): Boolean {
        val first = a.removePrefix("www.")
        val second = b.removePrefix("www.")
        return first == second
    }

    fun shouldBlock(
        originalUrl: String,
        destinationUrl: String,
        blockingEnabled: Boolean,
        approvedPlayerUrl: String? = null,
        userGesture: Boolean = false
    ): Boolean {
        if (!blockingEnabled) return false
        val original = host(originalUrl) ?: return false
        val destination = host(destinationUrl) ?: return false
        if (sameHost(original, destination)) return false
        val approved = approvedPlayerUrl?.let(::host)
        if (approved != null && sameHost(approved, destination)) return false
        // A link intentionally tapped by the user may lead to a legitimate
        // login or embedded video provider; do not silently block every other host.
        // Known ad domains still get stopped by the request filter.
        if (userGesture && !WebAdBlockRules.shouldBlock(destinationUrl)) return false
        return true
    }
}
