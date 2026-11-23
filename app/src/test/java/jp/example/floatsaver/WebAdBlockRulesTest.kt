package jp.example.floatsaver

import org.junit.Assert.*
import org.junit.Test

class WebAdBlockRulesTest {
    @Test fun blocksKnownAdHostsAndTheirSubdomains() {
        assertTrue(WebAdBlockRules.shouldBlock("https://ad.doubleclick.net/pagead/ads"))
        assertTrue(WebAdBlockRules.shouldBlock("https://a.b.googlesyndication.com/pagead"))
        assertTrue(WebAdBlockRules.shouldBlock("https://exoclick.com/"))
        assertTrue(WebAdBlockRules.shouldBlock("https://cdn.popads.net/ads"))
    }

    @Test fun doesNotBlockNormalVideoOrLookalikeHosts() {
        assertFalse(WebAdBlockRules.shouldBlock("https://example.com/movie.mp4"))
        assertFalse(WebAdBlockRules.shouldBlock("https://adventure.example/watch"))
        assertFalse(WebAdBlockRules.shouldBlock("https://doubleclick.net.evil.example/video"))
        assertFalse(WebAdBlockRules.shouldBlock("https://mydoubleclick.net/video"))
        assertFalse(WebAdBlockRules.shouldBlock("https://video.example/page?ad=1"))
        assertFalse(WebAdBlockRules.shouldBlock("blob:https://ad.doubleclick.net/1"))
    }

    @Test fun rejectsInvalidInputsSafely() {
        assertFalse(WebAdBlockRules.shouldBlock(""))
        assertFalse(WebAdBlockRules.shouldBlock("javascript:alert(1)"))
        assertFalse(WebAdBlockRules.shouldBlock("https://"))
    }
}
