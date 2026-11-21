package jp.example.floatsaver

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Pure Kotlin, conservative hints; no domain allowlist and no network interception. */
object DetectionRules {
    private val videoExtensions = setOf("mp4", "webm", "m4v", "mov", "mkv", "avi")
    private val streamExtensions = setOf("m3u8", "mpd")
    private val queryKeys = setOf("mime", "type", "content-type", "response-content-type", "format", "ext", "filename", "file")

    /** Returns GIF / 動画 / 配信, or null. Does not treat individual HLS fragments as full videos. */
    fun kind(value: String): String? {
        val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https") || uri.host.isNullOrBlank()) return null
        val suffix = uri.path.orEmpty().substringAfterLast('.', "").lowercase(Locale.ROOT)
        when (suffix) {
            "gif" -> return "GIF"
            in videoExtensions -> return "動画"
            in streamExtensions -> return "配信"
        }
        val params = uri.rawQuery.orEmpty().take(2000).split('&')
        for (entry in params) {
            val key = entry.substringBefore('=').lowercase(Locale.ROOT)
            if (key !in queryKeys) continue
            val valuePart = runCatching {
                URLDecoder.decode(entry.substringAfter('=', ""), StandardCharsets.UTF_8.name())
            }.getOrDefault("").lowercase(Locale.ROOT).substringBefore('#')
            val clean = valuePart.substringBefore('?').substringAfterLast('/')
            if (clean == "gif" || clean.endsWith(".gif") || valuePart.startsWith("image/gif")) return "GIF"
            if (clean in streamExtensions || streamExtensions.any { clean.endsWith(".$it") } ||
                valuePart.contains("application/vnd.apple.mpegurl") || valuePart.contains("application/dash+xml")) return "配信"
            if (clean in videoExtensions || videoExtensions.any { clean.endsWith(".$it") } ||
                valuePart.startsWith("video/")) return "動画"
        }
        return null
    }

    fun safeHost(value: String): String = runCatching { URI(value).host.orEmpty() }.getOrDefault("")
}
