package jp.example.floatsaver

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Pure Kotlin, conservative hints; no domain allowlist and no network interception. */
object DetectionRules {
    private val videoExtensions = setOf("mp4", "webm", "m4v", "mov", "mkv", "avi", "3gp", "flv", "ts")
    private val streamExtensions = setOf("m3u8", "m3u", "mpd")
    private val queryKeys = setOf("mime", "type", "content-type", "response-content-type", "format", "ext", "filename", "file")

    /** File extensions in the URL path only, never a 'format' query on a webpage. */
    fun directVideoExtension(value: String): String? {
        val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https")) return null
        if (uri.host.isNullOrBlank()) return null
        val extension = uri.path.orEmpty().substringAfterLast('.', "").lowercase(Locale.ROOT)
        return extension.takeIf { it in videoExtensions }
    }

    /** Lightly infer extensionless *network* requests; never call these confirmed videos. */
    fun networkHint(value: String, accept: String?): String? {
        kind(value)?.let { return it }
        val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https")) return null
        if (uri.host.isNullOrBlank()) return null
        val mime = accept.orEmpty().lowercase(Locale.ROOT)
        if (mime.contains("application/vnd.apple.mpegurl") ||
            mime.contains("application/x-mpegurl") ||
            mime.contains("application/dash+xml")) return "配信"
        if (mime.contains("video/") && !mime.contains("text/html")) return "動画"
        val path = uri.path.orEmpty().lowercase(Locale.ROOT).trimEnd('/')
        if (path.endsWith("/hls/manifest") || path.endsWith("/dash/manifest") ||
            path.endsWith("/hls/playlist") || path.endsWith("/dash/playlist")) return "配信"
        return null
    }

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
