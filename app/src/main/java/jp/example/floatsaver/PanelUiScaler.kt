package jp.example.floatsaver

import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Record a single immutable baseline for every inflated UI view. Repeated
 * resizing always scales from the baseline, never from already scaled sizes.
 * The resize handles are outside this root and remain easy to grab.
 */
class PanelUiScaler(root: View) {
    private data class Base(
        val view: View,
        val width: Int, val height: Int,
        val margins: IntArray?,
        val padding: IntArray,
        val minWidth: Int, val minHeight: Int,
        val textPx: Float?
    )
    private val bases = ArrayList<Base>()

    init { collect(root) }

    private fun collect(view: View) {
        val lp = view.layoutParams
        val margins = (lp as? ViewGroup.MarginLayoutParams)?.let {
            intArrayOf(it.leftMargin, it.topMargin, it.rightMargin, it.bottomMargin)
        }
        bases += Base(
            view, lp?.width ?: -2, lp?.height ?: -2, margins,
            intArrayOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom),
            view.minimumWidth, view.minimumHeight,
            (view as? TextView)?.textSize
        )
        if (view is ViewGroup) for (i in 0 until view.childCount) collect(view.getChildAt(i))
    }

    fun apply(scale: Float) {
        val f = scale.coerceIn(0.60f, 1.15f)
        fun px(v: Int) = if (v <= 0) v else (v * f).roundToInt().coerceAtLeast(1)
        for (base in bases) {
            val view = base.view
            val lp = view.layoutParams ?: continue
            if (base.width > 0) lp.width = px(base.width)
            if (base.height > 0) lp.height = px(base.height)
            val margins = base.margins
            if (lp is ViewGroup.MarginLayoutParams && margins != null) {
                lp.setMargins(px(margins[0]), px(margins[1]), px(margins[2]), px(margins[3]))
            }
            view.minimumWidth = px(base.minWidth).coerceAtLeast(0)
            view.minimumHeight = px(base.minHeight).coerceAtLeast(0)
            view.setPadding(px(base.padding[0]), px(base.padding[1]),
                px(base.padding[2]), px(base.padding[3]))
            if (view is TextView && base.textPx != null) {
                val density = view.resources.displayMetrics.scaledDensity
                val minimum = if (view is Button) 9.5f else 9f
                view.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    maxOf(base.textPx * f, minimum * density))
                // Default Android button font padding/min-width was previously
                // larger than the resizable panel; reduce it together with text.
                if (view is Button) view.minWidth = 0
            }
            view.layoutParams = lp
        }
    }
}
