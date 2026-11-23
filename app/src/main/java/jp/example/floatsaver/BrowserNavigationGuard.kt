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
        approvedPlayerUrl: String? = null
    ): Boolean {
        if (!blockingEnabled) return false
        val original = host(originalUrl) ?: return false
        val destination = host(destinationUrl) ?: return false
        if (sameHost(original, destination)) return false
        val approved = approvedPlayerUrl?.let(::host)
        return approved == null || !sameHost(approved, destination)
    }
}
