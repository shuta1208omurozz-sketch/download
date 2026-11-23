package jp.example.floatsaver

/** Pure helpers for conservative, direct-media classification and completeness checks. */
object DirectMediaHints {
    fun extensionForVideoMime(rawMime: String): String? = when (rawMime.lowercase().substringBefore(';').trim()) {
        "video/mp4", "video/x-m4v" -> "mp4"
        "video/webm" -> "webm"
        "video/x-matroska" -> "mkv"
        "video/quicktime" -> "mov"
        "video/3gpp", "video/3gpp2" -> "3gp"
        "video/mp2t" -> "ts"
        "video/x-flv" -> "flv"
        "video/x-msvideo", "video/avi" -> "avi"
        else -> null
    }

    /** Reject HTTP 206 unless its range really covers the entire declared entity. */
    fun isCompleteRange(header: String): Boolean {
        val parts = Regex("""bytes (\d+)-(\d+)/(\d+)""")
            .matchEntire(header.trim()) ?: return false
        val start = parts.groupValues[1].toLongOrNull() ?: return false
        val end = parts.groupValues[2].toLongOrNull() ?: return false
        val total = parts.groupValues[3].toLongOrNull() ?: return false
        return start == 0L && total > 0L && end == total - 1L
    }
}
