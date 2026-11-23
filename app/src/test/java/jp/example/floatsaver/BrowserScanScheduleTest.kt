package jp.example.floatsaver

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserScanScheduleTest {
    @Test fun fastUntilMediaFound() {
        assertEquals(1200L, BrowserScanSchedule.delayMillis(0, false))
        assertEquals(950L, BrowserScanSchedule.delayMillis(2, true))
        assertEquals(2400L, BrowserScanSchedule.delayMillis(3, false))
    }
}
