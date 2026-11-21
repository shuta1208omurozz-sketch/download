package jp.example.floatsaver

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque
import java.util.Locale

/** Opt-in and read-only Brave URL helper. No history, cookies, or traffic interception. */
class BravePageUrlService : AccessibilityService() {
    companion object {
        @Volatile private var lastUrl: String? = null
        @Volatile private var savedAt = 0L
        private val packages = setOf("com.brave.browser", "com.brave.browser_beta",
            "com.brave.browser_dev", "com.brave.browser_nightly")

        fun latestUrl(): String? = lastUrl?.takeIf {
            SystemClock.elapsedRealtime() - savedAt in 0L..180_000L
        }

        fun isEnabled(context: Context): Boolean {
            val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo.serviceInfo.packageName == context.packageName &&
                    it.resolveInfo.serviceInfo.name == BravePageUrlService::class.java.name }
        }

        private fun safeUrl(raw: String?): String? {
            val text = raw?.trim()?.takeIf { it.length in 4..4096 } ?: return null
            if (text.any { it.isWhitespace() } || text.contains("…") || text.contains("...")) return null
            val lower = text.lowercase(Locale.ROOT)
            val candidate = when {
                lower.startsWith("https://") || lower.startsWith("http://") -> text
                "://" in text || lower.startsWith("about:") || !text.substringBefore('/').contains('.') -> return null
                else -> "https://" + text
            }
            val uri = runCatching { Uri.parse(candidate) }.getOrNull() ?: return null
            if (uri.scheme !in listOf("http", "https") || uri.host?.contains('.') != true) return null
            return candidate
        }

        private fun addressBarUrl(root: AccessibilityNodeInfo): String? {
            val nodes = ArrayDeque<Pair<AccessibilityNodeInfo, Boolean>>()
            nodes.add(root to false)
            var count = 0
            while (nodes.isNotEmpty() && count++ < 250) {
                val (node, withinAddressBar) = nodes.removeFirst()
                val id = node.viewIdResourceName?.substringAfterLast('/')?.lowercase(Locale.ROOT).orEmpty()
                val addressBar = id.contains("url_bar") || id.contains("location_bar") ||
                    id.contains("omnibox") || id.contains("url_text")
                if (addressBar || withinAddressBar) {
                    safeUrl(node.text?.toString())?.let { return it }
                    if (addressBar) safeUrl(node.contentDescription?.toString())?.let { return it }
                }
                for (i in 0 until node.childCount.coerceAtMost(30)) {
                    node.getChild(i)?.let { nodes.add(it to (addressBar || withinAddressBar)) }
                }
            }
            return null
        }
    }

    private var lastInspection = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() !in packages) return
        if (event.eventType !in setOf(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_VIEW_FOCUSED,
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastInspection < 350L) return
        lastInspection = now
        val url = rootInActiveWindow?.let { addressBarUrl(it) } ?: return
        lastUrl = url
        savedAt = now
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        lastUrl = null
        savedAt = 0L
        super.onDestroy()
    }
}
