package jp.example.floatsaver

import org.junit.Assert.*
import org.junit.Test

class PanelUiScaleMathTest {
    @Test fun widthAndHeightBothControlScale() {
        assertEquals(1f, PanelUiScaleMath.factor(320, 350, 1f), 0.001f)
        assertEquals(0.75f, PanelUiScaleMath.factor(240, 350, 1f), 0.001f)
        assertEquals(0.8f, PanelUiScaleMath.factor(320, 280, 1f), 0.001f)
        assertEquals(0.60f, PanelUiScaleMath.factor(200, 160, 1f), 0.001f)
        assertEquals(0.75f, PanelUiScaleMath.factor(480, 700, 2f), 0.001f)
    }

    @Test fun neverTooSmallOrTooLarge() {
        assertEquals(0.60f, PanelUiScaleMath.factor(5, 5, 1f), 0.001f)
        assertEquals(1.15f, PanelUiScaleMath.factor(2000, 2000, 1f), 0.001f)
    }
}
