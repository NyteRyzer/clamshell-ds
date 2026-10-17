package com.clamshellds.ui

import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup

/**
 * Lays out DS screens at 4:3, letterboxed on black.
 * With one child it fills the display (dual-screen devices: one DS screen per panel).
 * With two children it stacks them (tall displays) or puts them side by side (wide displays).
 */
class ScreenPairLayout(context: Context, var mode: Int = MODE_AUTO) : ViewGroup(context) {
    init { setBackgroundColor(Color.BLACK) }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val rects = computeRects(w, h)
        for (i in 0 until childCount) {
            val r = rects.getOrNull(i) ?: continue
            getChildAt(i).measure(
                MeasureSpec.makeMeasureSpec(r[2] - r[0], MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(r[3] - r[1], MeasureSpec.EXACTLY),
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val rects = computeRects(r - l, b - t)
        for (i in 0 until childCount) {
            val rc = rects.getOrNull(i) ?: continue
            getChildAt(i).layout(rc[0], rc[1], rc[2], rc[3])
        }
    }

    /** Child rectangles as [left, top, right, bottom]. */
    private fun computeRects(w: Int, h: Int): List<IntArray> {
        val visible = (0 until childCount).filter { getChildAt(it).visibility != View.GONE }
        if (visible.size <= 1 || w == 0 || h == 0) return listOf(fit(0, 0, w, h))
        val sideBySide = when (mode) {
            MODE_STACKED -> false
            MODE_SIDE -> true
            else -> w.toFloat() / h > 4f / 3f * 1.2f
        }
        return if (sideBySide) {
            listOf(fit(0, 0, w / 2, h), fit(w / 2, 0, w, h))
        } else {
            listOf(fit(0, 0, w, h / 2), fit(0, h / 2, w, h))
        }
    }

    private fun fit(l: Int, t: Int, r: Int, b: Int): IntArray {
        val aw = r - l
        val ah = b - t
        var cw = aw
        var ch = cw * 3 / 4
        if (ch > ah) { ch = ah; cw = ch * 4 / 3 }
        val x = l + (aw - cw) / 2
        val y = t + (ah - ch) / 2
        return intArrayOf(x, y, x + cw, y + ch)
    }

    companion object {
        const val MODE_AUTO = 0
        const val MODE_STACKED = 1
        const val MODE_SIDE = 2
    }
}
