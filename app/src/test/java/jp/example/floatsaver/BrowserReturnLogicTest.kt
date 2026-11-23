package jp.example.floatsaver

import org.junit.Assert.*
import org.junit.Test

class BrowserReturnLogicTest {
    private val root = "https://movies.example/watch?token=123"

    @Test fun skipsEntireAdRedirectChain() {
        val history = listOf(root, "https://ad.example/pop", "https://ad2.example/offer")
        assertEquals(-2, BrowserReturnLogic.findRootOffset(root, history, 2))
    }

    @Test fun returnsNullIfRootWasReplaced() {
        val history = listOf("https://ad.example/pop", "https://ad2.example/offer")
        assertNull(BrowserReturnLogic.findRootOffset(root, history, 1))
    }

    @Test fun preservesSignedQueryAndIgnoresFragment() {
        assertFalse(BrowserReturnLogic.isDifferentPage(root, root + "#play"))
        assertTrue(BrowserReturnLogic.isDifferentPage(root, "https://movies.example/watch?token=456"))
        val history = listOf(root + "#play", "https://ad.example/pop")
        assertEquals(-1, BrowserReturnLogic.findRootOffset(root, history, 1))
    }
}
