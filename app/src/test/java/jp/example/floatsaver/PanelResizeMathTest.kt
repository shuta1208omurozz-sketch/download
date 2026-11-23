package jp.example.floatsaver

import org.junit.Assert.*
import org.junit.Test

class PanelResizeMathTest {
    private val start = PanelResizeMath.Geometry(300, 240, 0f, 0f)

    @Test fun leftBottomDragAnchorsTopRight() {
        val result = PanelResizeMath.resized(start, -60f, 40f, -1, true,
            210, 500, 160, 500, 600, 600, 12)
        assertEquals(360, result.width)
        assertEquals(280, result.height)
        assertEquals(-30f, result.offsetX, 0.001f)
        assertEquals(20f, result.offsetY, 0.001f)
        assertEquals(start.width / 2f, result.offsetX + result.width / 2f, 0.001f)
        assertEquals(-start.height / 2f, result.offsetY - result.height / 2f, 0.001f)
    }

    @Test fun rightBottomDragAnchorsTopLeft() {
        val result = PanelResizeMath.resized(start, 60f, 40f, 1, true,
            210, 500, 160, 500, 600, 600, 12)
        assertEquals(360, result.width)
        assertEquals(280, result.height)
        assertEquals(30f, result.offsetX, 0.001f)
        assertEquals(20f, result.offsetY, 0.001f)
        assertEquals(-start.width / 2f, result.offsetX - result.width / 2f, 0.001f)
    }

    @Test fun dragClampsAtMinSizeAndWithinScreen() {
        val result = PanelResizeMath.resized(start, 500f, 500f, -1, true,
            215, 500, 160, 500, 340, 330, 12)
        assertEquals(215, result.width)
        assertEquals(306, result.height)
        assertTrue(kotlin.math.abs(result.offsetX) <= (340f - result.width) / 2f - 12f)
        assertTrue(kotlin.math.abs(result.offsetY) <= (330f - result.height) / 2f - 12f)
    }

    @Test fun horizontalOnlyDoesNotAlterHeight() {
        val result = PanelResizeMath.resized(start, -42f, 78f, 1, false,
            210, 500, 160, 500, 600, 600, 12)
        assertEquals(258, result.width)
        assertEquals(240, result.height)
        assertEquals(0f, result.offsetY, 0.001f)
    }
}
