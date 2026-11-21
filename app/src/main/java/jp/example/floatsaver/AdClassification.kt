package jp.example.floatsaver

import java.net.URI
import java.util.Locale

/** Heuristics only. NEVER automatically downloads or discards a candidate. */
object AdClassification {
    enum class Verdict(val label: String, val order: Int) {
        MAIN_LIKELY("本編候補（未確認）", 0),
        PLAYING("再生中（要確認）", 1),
        UNKNOWN("未判定", 2),
        AD_SUSPECT("広告の疑い", 3)
    }

    data class Signals(
        val url: String,
        val fromPlayingVideo: Boolean = false,
        val durationSeconds: Double? = null,
        val playbackSeconds: Double? = null,
        val adElement: Boolean = false,
        val observedPlayingSamples: Int = 0
    )

    /** Only actual playback progress in successive observations builds evidence.
     *  Advertisements remain suspect until three clean, advancing observations
     *  after the last advertisement marker.  Network requests alone cannot
     *  dismiss a player-level ad marker.
     */
    data class Evidence(
        val samples: Int = 0,
        val lastPosition: Double? = null,
        val adMarked: Boolean = false,
        val cleanSamplesAfterAd: Int = 0
    )

    fun observe(
        previous: Evidence,
        fromVideoElement: Boolean,
        playing: Boolean,
        elapsed: Double?,
        adMarked: Boolean
    ): Evidence {
        if (!fromVideoElement) return previous
        val position = elapsed?.takeIf { it.isFinite() && it >= 0 }
        val progressed = playing && position != null &&
            (previous.lastPosition == null || position >= previous.lastPosition + 0.5)
        val samples = when {
            !playing -> 0
            progressed -> (previous.samples + 1).coerceAtMost(4)
            else -> previous.samples
        }
        val clean = when {
            adMarked -> 0
            !playing -> 0
            progressed -> (previous.cleanSamplesAfterAd + 1).coerceAtMost(4)
            else -> previous.cleanSamplesAfterAd
        }
        val mark = adMarked || (previous.adMarked && clean < 3)
        return Evidence(samples, position ?: previous.lastPosition, mark, clean)
    }

    /** URL indicators are *hints*, not a blacklist; never reject on this basis. */
    fun adUrlHint(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val path = uri.path.orEmpty().lowercase(Locale.ROOT)
        // Only standalone path segments / filename tokens. No domain based restrictions,
        // and no naive substring search that would flag e.g. download or adventure.
        val tokens = setOf("ads", "advertisement", "preroll", "midroll", "postroll", "adserver", "adtag")
        val pathTokens = path.split('/', '.', '-', '_').filter { it.isNotEmpty() }
        if (pathTokens.any { it in tokens }) return true
        val queryKeys = uri.rawQuery.orEmpty().split('&').map { it.substringBefore('=').lowercase(Locale.ROOT) }
        return queryKeys.any { it in setOf("adtag", "ad_unit", "adunit", "adslot", "ad_slot", "adtype") }
    }

    fun classify(s: Signals): Verdict {
        // A marked ad or ad-resource URL must never be promoted merely because it's playing.
        if (s.adElement || adUrlHint(s.url)) return Verdict.AD_SUSPECT
        val seconds = s.playbackSeconds?.takeIf { it.isFinite() && it >= 0 } ?: 0.0
        val length = s.durationSeconds?.takeIf { it.isFinite() && it > 0 }
        // Long and actively playing content is a useful hint, not a guarantee.
        // Threshold intentionally favors fewer false positives over recognizing short clips.
        if (s.fromPlayingVideo && s.observedPlayingSamples >= 2 &&
            seconds >= 8.0 && length != null && length >= 180.0)
            return Verdict.MAIN_LIKELY
        if (s.fromPlayingVideo) return Verdict.PLAYING
        return Verdict.UNKNOWN
    }
}
