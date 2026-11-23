package jp.example.floatsaver

import org.junit.Assert.*
import org.junit.Test

class DirectMediaHintsTest {
    @Test fun extensionlessMimeVideoIsRecognized() {
        assertEquals("mp4", DirectMediaHints.extensionForVideoMime("video/mp4"))
        assertEquals("webm", DirectMediaHints.extensionForVideoMime("VIDEO/WEBM; codecs=vp9"))
        assertEquals("ts", DirectMediaHints.extensionForVideoMime("video/mp2t"))
        assertNull(DirectMediaHints.extensionForVideoMime("text/html"))
        assertNull(DirectMediaHints.extensionForVideoMime("application/octet-stream"))
        assertNull(DirectMediaHints.extensionForVideoMime("application/vnd.apple.mpegurl"))
    }

    @Test fun incompletePartialVideoMustNotBePublished() {
        assertTrue(DirectMediaHints.isCompleteRange("bytes 0-999/1000"))
        assertTrue(DirectMediaHints.isCompleteRange("bytes 0-0/1"))
        assertFalse(DirectMediaHints.isCompleteRange("bytes 0-99/1000"))
        assertFalse(DirectMediaHints.isCompleteRange("bytes 100-999/1000"))
        assertFalse(DirectMediaHints.isCompleteRange("bytes 0-99/*"))
        assertFalse(DirectMediaHints.isCompleteRange("garbage"))
        assertFalse(DirectMediaHints.isCompleteRange("bytes 0-1/0"))
    }
}
