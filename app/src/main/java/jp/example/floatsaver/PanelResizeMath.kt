package jp.example.floatsaver

import kotlin.math.roundToInt

/** Layout-independent resize geometry. The opposite corner remains anchored. */
object PanelResizeMath {
    data class Geometry(val width: Int, val height: Int, val offsetX: Float, val offsetY: Float)

    fun resized(
        start: Geometry, dx: Float, dy: Float,
        horizontalDirection: Int, vertical: Boolean,
        minWidth: Int, maxWidth: Int, minHeight: Int, maxHeight: Int,
        parentWidth: Int, parentHeight: Int, margin: Int
    ): Geometry {
        require(horizontalDirection in -1..1)
        val availableWidth = (parentWidth - 2 * margin).coerceAtLeast(1)
        val availableHeight = (parentHeight - 2 * margin).coerceAtLeast(1)
        val upperWidth = minOf(maxWidth, availableWidth).coerceAtLeast(1)
        val upperHeight = minOf(maxHeight, availableHeight).coerceAtLeast(1)
        val newWidth = (start.width + horizontalDirection * dx.roundToInt())
            .coerceIn(minWidth.coerceAtMost(upperWidth), upperWidth)
        val newHeight = (start.height + if (vertical) dy.roundToInt() else 0)
            .coerceIn(minHeight.coerceAtMost(upperHeight), upperHeight)
        val newOffsetX = start.offsetX +
            horizontalDirection * (newWidth - start.width) / 2f
        val newOffsetY = start.offsetY +
            (newHeight - start.height) / 2f
        val maxOffsetX = ((parentWidth - newWidth) / 2f - margin).coerceAtLeast(0f)
        val maxOffsetY = ((parentHeight - newHeight) / 2f - margin).coerceAtLeast(0f)
        return Geometry(
            newWidth, newHeight,
            newOffsetX.coerceIn(-maxOffsetX, maxOffsetX),
            newOffsetY.coerceIn(-maxOffsetY, maxOffsetY)
        )
    }
}
