package jp.example.floatsaver

import kotlin.math.min

/** Scale ALL content, not only the outside window frame. */
object PanelUiScaleMath {
    fun factor(widthPx: Int, heightPx: Int, density: Float): Float {
        val baseWidth = 320f * density.coerceAtLeast(0.1f)
        val baseHeight = 350f * density.coerceAtLeast(0.1f)
        return min(widthPx / baseWidth, heightPx / baseHeight).coerceIn(0.60f, 1.15f)
    }
}
