package jp.example.floatsaver

/** Pure browser return rules. Ads may erase WebView's actual back history. */
object BrowserReturnLogic {
    fun isDifferentPage(original: String, current: String): Boolean =
        original.isNotBlank() &&
            original.substringBefore('#') != current.substringBefore('#')

    /** Offset from the current history index to the last exact original page. */
    fun findRootOffset(original: String, history: List<String>, currentIndex: Int): Int? {
        if (original.isBlank() || currentIndex !in history.indices) return null
        for (i in currentIndex - 1 downTo 0) {
            if (!isDifferentPage(original, history[i])) return i - currentIndex
        }
        return null
    }
}
