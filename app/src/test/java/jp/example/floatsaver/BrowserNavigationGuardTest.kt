package jp.example.floatsaver

import org.junit.Assert.*
import org.junit.Test

class BrowserNavigationGuardTest {
    @Test fun suppressesCrossSiteTopLevelNavigationWhenEnabled() {
        assertTrue(BrowserNavigationGuard.shouldBlock(
            "https://videos.example/watch/1", "https://ad.other.example/click", true))
        assertFalse(BrowserNavigationGuard.shouldBlock(
            "https://videos.example/watch/1", "https://videos.example/watch/2", true))
        assertFalse(BrowserNavigationGuard.shouldBlock(
            "https://www.videos.example/watch", "https://videos.example/watch", true))
        assertFalse(BrowserNavigationGuard.shouldBlock(
            "https://videos.example/watch", "https://elsewhere.example/legit", false))
    }

    @Test fun allowsExplicitlyOpenedPlayerHostButNotItsAdRedirect() {
        assertFalse(BrowserNavigationGuard.shouldBlock(
            "https://videos.example/watch",
            "https://player.example/watch",
            true, "https://player.example/embed/123"))
        assertTrue(BrowserNavigationGuard.shouldBlock(
            "https://videos.example/watch",
            "https://ads.example/click",
            true, "https://player.example/embed/123"))
    }

    @Test fun intentionalNavigationToDifferentHostIsAllowed() {
        assertFalse(BrowserNavigationGuard.shouldBlock(
            "https://videos.example/watch", "https://login-provider.example/signin",
            true, userGesture = true))
        assertTrue(BrowserNavigationGuard.shouldBlock(
            "https://videos.example/watch", "https://ads.popads.net/click",
            true, userGesture = true))
        assertTrue(BrowserNavigationGuard.shouldBlock(
            "https://videos.example/watch", "https://ads.example/pop",
            true, userGesture = false))
    }

    @Test fun doesNotBlockWithoutKnownPage() {
        assertFalse(BrowserNavigationGuard.shouldBlock(
            "", "https://somewhere.example/page", true))
    }
}
