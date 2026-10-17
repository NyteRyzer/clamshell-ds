package com.clamshellds.ui

import android.graphics.Color
import android.graphics.Typeface

/** Colours modelled on the DSi system menu. The accent follows the user's favourite colour. */
object DsiTheme {
    /** The 16 DS/DSi profile colours, in firmware order. */
    val FAVORITE_COLORS = intArrayOf(
        0xFF61829A.toInt(), // gray
        0xFFBA4900.toInt(), // brown
        0xFFFB0018.toInt(), // red
        0xFFFB8AFB.toInt(), // pink
        0xFFFB9200.toInt(), // orange
        0xFFF3E300.toInt(), // yellow
        0xFFAAFB00.toInt(), // lime
        0xFF00FB00.toInt(), // green
        0xFF00A238.toInt(), // dark green
        0xFF49DB8A.toInt(), // sea green
        0xFF30BAF3.toInt(), // turquoise
        0xFF0059F3.toInt(), // blue
        0xFF000092.toInt(), // dark blue
        0xFF8A00D3.toInt(), // purple
        0xFFD300EB.toInt(), // violet
        0xFFFB0092.toInt(), // magenta
    )
    val FAVORITE_NAMES = listOf(
        "Gray", "Brown", "Red", "Pink", "Orange", "Yellow", "Lime", "Green",
        "Dark Green", "Sea Green", "Turquoise", "Blue", "Dark Blue", "Purple", "Violet", "Magenta",
    )

    val BG_TOP = Color.rgb(0xF4, 0xF4, 0xF4)
    val BG_BOTTOM = Color.rgb(0xE6, 0xE6, 0xE6)
    val GRID = Color.rgb(0xDC, 0xDC, 0xDC)
    val BAR = Color.rgb(0x8C, 0x8C, 0x8C)
    val BAR_DARK = Color.rgb(0x5A, 0x5A, 0x5A)
    val TEXT = Color.rgb(0x40, 0x40, 0x40)
    val TEXT_LIGHT = Color.rgb(0x90, 0x90, 0x90)
    val CARD = Color.WHITE
    val CARD_BORDER = Color.rgb(0xB8, 0xB8, 0xB8)
    val EMPTY_SLOT = Color.rgb(0xD2, 0xD2, 0xD2)

    val FONT: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val FONT_BOLD: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    val FONT_ROUNDED: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun accent(index: Int): Int = FAVORITE_COLORS[index.coerceIn(0, 15)]

    fun lighten(color: Int, amount: Float): Int {
        val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
        return Color.rgb(
            (r + (255 - r) * amount).toInt(),
            (g + (255 - g) * amount).toInt(),
            (b + (255 - b) * amount).toInt(),
        )
    }

    fun darken(color: Int, amount: Float): Int = Color.rgb(
        (Color.red(color) * (1 - amount)).toInt(),
        (Color.green(color) * (1 - amount)).toInt(),
        (Color.blue(color) * (1 - amount)).toInt(),
    )
}
