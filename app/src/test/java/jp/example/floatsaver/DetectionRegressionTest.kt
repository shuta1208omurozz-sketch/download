package jp.example.floatsaver

import org.junit.Assert.*
import org.junit.Test

class DetectionRegressionTest {
    @Test fun recognizesDirectVideoFormats() {
        assertEquals("mp4", DetectionRules.directVideoExtension("https://cdn.example/video.mp4?token=abc"))
        assertEquals("webm", DetectionRules.directVideoExtension("https://cdn.example/video.WEBM"))
        assertEquals("3gp", DetectionRules.directVideoExtension("https://cdn.example/video.3gp"))
        assertNull(DetectionRules.directVideoExtension("https://www.example/watch?format=mp4"))
        assertNull(DetectionRules.directVideoExtension("blob:https://www.example/id"))
    }

    @Test fun acceptsStreamManifestsButNotSegments() {
        assertEquals("配信", DetectionRules.kind("https://cdn.example/live/master.m3u8"))
        assertEquals("配信", DetectionRules.kind("https://cdn.example/live/manifest.mpd"))
        assertNull(DetectionRules.kind("https://cdn.example/live/segment.m4s"))
        assertEquals("配信", DetectionRules.networkHint("https://cdn.example/hls/manifest", null))
    }

    @Test fun keepsUnrelatedWebpagesOutOfMediaCandidates() {
        assertNull(DetectionRules.kind("https://video.example/watch"))
        assertNull(DetectionRules.kind("https://example.com/preview.jpg"))
        assertNull(DetectionRules.networkHint("https://example.com/article", null))
    }

    @Test fun recognizesKnownMimeHintsWithoutDomainAllowlist() {
        assertEquals("動画", DetectionRules.networkHint("https://random.example/resource", "video/mp4"))
        assertEquals("配信", DetectionRules.networkHint("https://random.example/resource", "application/dash+xml"))
    }

    @Test fun adHeuristicsDoNotBlacklistOrdinaryWords() {
        assertTrue(AdClassification.adUrlHint("https://cdn.example/ads/preroll.mp4"))
        assertFalse(AdClassification.adUrlHint("https://cdn.example/download.mp4"))
        assertFalse(AdClassification.adUrlHint("https://cdn.example/adventure.mp4"))
        assertEquals(AdClassification.Verdict.UNKNOWN,
            AdClassification.classify(AdClassification.Signals("https://cdn.example/short.mp4")))
    }

    @Test fun requiresRepeatedPlaybackEvidence() {
        val video = "https://cdn.example/movie.mp4"
        assertEquals(AdClassification.Verdict.PLAYING,
            AdClassification.classify(AdClassification.Signals(video, true, 240.0, 20.0, false, 1)))
        assertEquals(AdClassification.Verdict.MAIN_LIKELY,
            AdClassification.classify(AdClassification.Signals(video, true, 240.0, 20.0, false, 2)))
    }

    @Test fun neverAutomaticallyPromotesMarkedAds() {
        assertEquals(AdClassification.Verdict.AD_SUSPECT,
            AdClassification.classify(AdClassification.Signals(
                "https://cdn.example/movie.mp4", true, 240.0, 15.0, true, 3)))
    }

    @Test fun errorMessagesDoNotExposeSignedUrls() {
        val result = ErrorHints.describe(Exception("HTTP 403 https://secret.example/path?token=private"))
        assertTrue(result.contains("403"))
        assertFalse(result.contains("private"))
        assertFalse(result.contains("secret.example"))
    }
}
