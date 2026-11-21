import jp.example.floatsaver.DetectionRules
import jp.example.floatsaver.ErrorHints
import jp.example.floatsaver.AdClassification

fun main() {
    val yes = mapOf(
        "https://a.example/video.mp4?token=abc" to "動画",
        "https://a.example/video.m3u8" to "配信",
        "https://a.example/g?response-content-type=image%2Fgif" to "GIF",
        "https://a.example/watch?format=webm" to "動画",
        "https://a.example/?filename=sample.gif" to "GIF",
        "https://a.example/?mime=video%2Fmp4" to "動画",
        "https://a.example/VIDEO.MP4#preview" to "動画",
        "https://a.example/live/stream.mpd" to "配信",
        "https://a.example/?file=clip%2Fmovie.webm" to "動画",
        "https://a.example/?response-content-type=image%2Fgif%3Bcharset%3Dbinary" to "GIF"
    )
    yes.forEach { (url, expected) -> check(DetectionRules.kind(url) == expected) { url } }
    val no = listOf("https://a.example/page", "https://a.example/cover.jpg", "https://a.example/video.m4s", "blob:https://example.org/xyz", "file:///movie.mp4", "https://a.example/segment.m4s", "https://a.example/playlist.txt")
    no.forEach { check(DetectionRules.kind(it) == null) { it } }
    check(DetectionRules.directVideoExtension("https://cdn.example/clip.mp4?token=a") == "mp4")
    check(DetectionRules.directVideoExtension("https://site.example/watch?format=mp4") == null)
    check(DetectionRules.networkHint("https://site.example/hls/manifest", null) == "配信")
    check(DetectionRules.networkHint("https://site.example/dash/manifest", null) == "配信")
    check(DetectionRules.networkHint("https://site.example/page", "video/mp4") == "動画")
    check(DetectionRules.networkHint("https://site.example/page", null) == null)
    check(DetectionRules.kind("https://site.example/a.3gp") == "動画")
    check(DetectionRules.kind("https://site.example/stream.m3u") == "配信")
    check(ErrorHints.describe(Exception("HTTP 403 https://site/?token=private")).contains("403"))
    check(!ErrorHints.describe(Exception("HTTP 403 https://site/?token=private")).contains("private"))
    val video = "https://cdn.example.org/film/main.mp4"
    val ad = "https://cdn.example.org/ads/preroll/promo.mp4"
    val main = AdClassification.Verdict.MAIN_LIKELY
    val playing = AdClassification.Verdict.PLAYING
    val unknown = AdClassification.Verdict.UNKNOWN
    val suspected = AdClassification.Verdict.AD_SUSPECT
    check(AdClassification.adUrlHint(ad))
    check(!AdClassification.adUrlHint(video))
    check(!AdClassification.adUrlHint("https://ads.example.org/adventure.mp4")) // no domain blacklist
    check(!AdClassification.adUrlHint("https://cdn.example.org/download.mp4"))
    check(AdClassification.adUrlHint("https://cdn.example.org/clip.mp4?adunit=123"))
    check(AdClassification.classify(AdClassification.Signals(video)) == unknown)
    check(AdClassification.classify(AdClassification.Signals(video, true, 240.0, 15.0)) == playing)
    check(AdClassification.classify(AdClassification.Signals(video, true, 240.0, 15.0, false, 2)) == main)
    check(AdClassification.classify(AdClassification.Signals(video, true, 12.0, 8.0)) == playing)
    check(AdClassification.classify(AdClassification.Signals(video, true, 120.0, 15.0)) == playing)
    check(AdClassification.classify(AdClassification.Signals(video, true, null, 8.0)) == playing)
    check(AdClassification.classify(AdClassification.Signals(video, false, 120.0, 15.0)) == unknown)
    check(AdClassification.classify(AdClassification.Signals(ad, true, 120.0, 15.0)) == suspected)
    check(AdClassification.classify(AdClassification.Signals(video, true, 200.0, 50.0, true)) == suspected)
    check(AdClassification.classify(AdClassification.Signals("https://cdn.example.org/ads/animation.gif")) == suspected)
    check(AdClassification.classify(AdClassification.Signals("https://cdn.example.org/clip.mp4?token=adventure")) == unknown)
    // Source-agnostic: a short clip is not automatically treated as an ad.
    var evidence = AdClassification.Evidence()
    evidence = AdClassification.observe(evidence, true, true, 4.0, true)
    check(evidence.adMarked && evidence.samples == 1)
    evidence = AdClassification.observe(evidence, false, false, null, false)
    check(evidence.adMarked) // network observations cannot clear player ad state
    evidence = AdClassification.observe(evidence, true, true, 8.0, false)
    check(evidence.adMarked) // keep suspect during first clean scan
    evidence = AdClassification.observe(evidence, true, true, 12.0, false)
    check(evidence.adMarked) // second clean scan still suspect
    evidence = AdClassification.observe(evidence, true, true, 16.0, false)
    check(!evidence.adMarked) // third clean scan releases the stale ad label
    check(AdClassification.classify(AdClassification.Signals(video, true, 30.0, 16.0, false, 3)) == playing)
    check(AdClassification.classify(AdClassification.Signals(video, true, 240.0, 16.0, true, 3)) == suspected)
    check(AdClassification.observe(evidence, true, false, 16.0, false).samples == 0)
    println("${yes.size + no.size + 2 + 16 + 8} detection/error/ad-classification checks passed")
}
