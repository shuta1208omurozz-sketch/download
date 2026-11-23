package jp.example.floatsaver

/** Low-latency detection while waiting, without continuously scanning busy pages. */
object BrowserScanSchedule {
    fun delayMillis(candidateCount: Int, recentlyPlaying: Boolean): Long = when {
        recentlyPlaying -> 950L
        candidateCount <= 0 -> 1200L
        else -> 2400L
    }
}
