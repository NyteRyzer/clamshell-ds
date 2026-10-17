package com.clamshellds.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.clamshellds.data.RomInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/** Something that sits in a slot of the DSi-style carousel. */
sealed class MenuItem {
    class Game(val rom: RomInfo) : MenuItem()
    object Settings : MenuItem()
    object SystemMenu : MenuItem()
    object Empty : MenuItem()
}

/** Shared state between the top and bottom menu screens. */
class MenuState {
    var items: List<MenuItem> = listOf(MenuItem.Settings)
    var selected = 0
    var scroll = 0f              // carousel position in slots (animated towards `selected`)
    var username = "Player"
    var accent = DsiTheme.accent(11)
    var batteryPercent = 100
    var charging = false
    var scanning = false
    var statusMessage: String? = null
    val listeners = ArrayList<View>()

    val current: MenuItem get() = items.getOrElse(selected) { MenuItem.Empty }
    fun invalidateAll() = listeners.forEach { it.postInvalidateOnAnimation() }
}

/**
 * Base for views drawn in DS pixel units: a 256x192 virtual canvas scaled to fit,
 * centred, with the background filling the rest. 640x480 screens give a clean 2.5x.
 */
abstract class DsCanvasView(context: Context, protected val state: MenuState) : View(context) {
    protected var unit = 1f
    protected var ox = 0f
    protected var oy = 0f
    protected val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val iconPaint = Paint().apply { isFilterBitmap = false }

    init {
        state.listeners += this
        isFocusable = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        unit = minOf(w / 256f, h / 192f)
        ox = (w - 256 * unit) / 2f
        oy = (h - 192 * unit) / 2f
    }

    protected fun x(v: Float) = ox + v * unit
    protected fun y(v: Float) = oy + v * unit
    protected fun u(v: Float) = v * unit
    protected fun rect(l: Float, t: Float, r: Float, b: Float) = RectF(x(l), y(t), x(r), y(b))

    protected fun text(c: Canvas, s: String, cx: Float, by: Float, size: Float, color: Int,
                       align: Paint.Align = Paint.Align.CENTER, bold: Boolean = false) {
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.typeface = if (bold) DsiTheme.FONT_BOLD else DsiTheme.FONT
        paint.textSize = u(size)
        paint.color = color
        paint.textAlign = align
        c.drawText(s, x(cx), y(by), paint)
    }

    protected fun ellipsize(s: String, size: Float, maxWidth: Float): String {
        paint.textSize = u(size)
        if (paint.measureText(s) <= u(maxWidth)) return s
        var end = s.length
        while (end > 1 && paint.measureText(s.substring(0, end) + "…") > u(maxWidth)) end--
        return s.substring(0, end) + "…"
    }

    protected fun drawIcon(c: Canvas, item: MenuItem, l: Float, t: Float, size: Float) {
        val dst = rect(l, t, l + size, t + size)
        when (item) {
            is MenuItem.Game -> {
                val bmp: Bitmap? = item.rom.icon
                if (bmp != null) c.drawBitmap(bmp, null, dst, iconPaint)
                else drawCartridge(c, dst)
            }
            MenuItem.Settings -> drawWrench(c, dst)
            MenuItem.SystemMenu -> drawConsole(c, dst)
            MenuItem.Empty -> Unit
        }
    }

    private fun drawCartridge(c: Canvas, r: RectF) {
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(0x50, 0x50, 0x50)
        val w = r.width()
        c.drawRoundRect(RectF(r.left + w * 0.15f, r.top + w * 0.08f, r.right - w * 0.15f, r.bottom - w * 0.08f), w * 0.06f, w * 0.06f, paint)
        paint.color = Color.rgb(0xC8, 0xC8, 0xC8)
        c.drawRect(RectF(r.left + w * 0.25f, r.top + w * 0.2f, r.right - w * 0.25f, r.top + w * 0.6f), paint)
    }

    private fun drawWrench(c: Canvas, r: RectF) {
        val w = r.width()
        paint.shader = LinearGradient(r.left, r.top, r.right, r.bottom, Color.rgb(0x9A, 0x9A, 0x9A), Color.rgb(0x4A, 0x4A, 0x4A), Shader.TileMode.CLAMP)
        paint.style = Paint.Style.FILL
        c.drawRoundRect(RectF(r.left + w * 0.06f, r.top + w * 0.06f, r.right - w * 0.06f, r.bottom - w * 0.06f), w * 0.18f, w * 0.18f, paint)
        paint.shader = null
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = w * 0.12f
        paint.strokeCap = Paint.Cap.ROUND
        c.drawLine(r.left + w * 0.32f, r.bottom - w * 0.32f, r.right - w * 0.4f, r.top + w * 0.4f, paint)
        paint.strokeWidth = w * 0.09f
        c.drawArc(RectF(r.right - w * 0.52f, r.top + w * 0.2f, r.right - w * 0.2f, r.top + w * 0.52f), 120f, 270f, false, paint)
        paint.style = Paint.Style.FILL
        paint.strokeCap = Paint.Cap.BUTT
    }

    private fun drawConsole(c: Canvas, r: RectF) {
        val w = r.width()
        paint.shader = null
        paint.style = Paint.Style.FILL
        // lid
        paint.color = state.accent
        c.drawRoundRect(RectF(r.left + w * 0.12f, r.top + w * 0.08f, r.right - w * 0.12f, r.top + w * 0.5f), w * 0.07f, w * 0.07f, paint)
        // base
        paint.color = DsiTheme.darken(state.accent, 0.25f)
        c.drawRoundRect(RectF(r.left + w * 0.12f, r.top + w * 0.52f, r.right - w * 0.12f, r.bottom - w * 0.08f), w * 0.07f, w * 0.07f, paint)
        paint.color = Color.rgb(0x20, 0x20, 0x28)
        c.drawRect(RectF(r.left + w * 0.24f, r.top + w * 0.15f, r.right - w * 0.24f, r.top + w * 0.43f), paint)
        c.drawRect(RectF(r.left + w * 0.3f, r.top + w * 0.58f, r.right - w * 0.3f, r.bottom - w * 0.16f), paint)
    }
}

/** DSi top screen: status bar with name, date, clock, battery; banner of the selected title. */
class DsiTopView(context: Context, state: MenuState) : DsCanvasView(context, state) {
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("M/d", Locale.getDefault())
    private val ticker = object : Runnable {
        override fun run() { invalidate(); postDelayed(this, 500) }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(ticker) }
    override fun onDetachedFromWindow() { removeCallbacks(ticker); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        c.drawColor(DsiTheme.BG_TOP)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = u(0.5f).coerceAtLeast(1f)
        paint.color = DsiTheme.GRID
        var gy = 20f
        while (gy < 192f) { c.drawLine(0f, y(gy), width.toFloat(), y(gy), paint); gy += 8f }
        paint.style = Paint.Style.FILL

        // status bar
        paint.shader = LinearGradient(0f, y(0f), 0f, y(16f), DsiTheme.BAR, DsiTheme.BAR_DARK, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, width.toFloat(), y(16f), paint)
        paint.shader = null
        text(c, state.username, 6f, 12f, 10f, Color.WHITE, Paint.Align.LEFT, bold = true)

        val now = Date()
        val blink = (SystemClock.uptimeMillis() / 500) % 2 == 0L
        val time = timeFmt.format(now).let { if (blink) it else it.replace(':', ' ') }
        text(c, dateFmt.format(now), 170f, 12f, 10f, Color.WHITE, Paint.Align.RIGHT)
        text(c, time, 208f, 12f, 10f, Color.WHITE, Paint.Align.RIGHT, bold = true)
        drawBattery(c, 216f, 4f)

        // banner card
        val item = state.current
        val card = rect(18f, 42f, 238f, 150f)
        paint.color = 0x22000000
        c.drawRoundRect(RectF(card.left + u(2f), card.top + u(2f), card.right + u(2f), card.bottom + u(2f)), u(8f), u(8f), paint)
        paint.color = DsiTheme.CARD
        c.drawRoundRect(card, u(8f), u(8f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = u(1.5f)
        paint.color = state.accent
        c.drawRoundRect(card, u(8f), u(8f), paint)
        paint.style = Paint.Style.FILL

        val (lines, hint) = when (item) {
            is MenuItem.Game -> item.rom.titleLines.ifEmpty { listOf(item.rom.file.nameWithoutExtension) } to "Press A or tap the icon to start"
            MenuItem.Settings -> listOf("System Settings", "Display, controls, DSi files", "and your profile") to "Press A to open"
            MenuItem.SystemMenu -> listOf("System Menu", "Boot the console's own menu", "(uses your BIOS/NAND dumps)") to "Press A to power on"
            MenuItem.Empty -> (if (state.scanning) listOf("Looking for games…") else listOf("No software here")) to
                "Put .nds files in ClamshellDS/roms"
        }

        if (item != MenuItem.Empty) {
            paint.color = DsiTheme.EMPTY_SLOT
            c.drawRoundRect(rect(28f, 62f, 96f, 130f), u(4f), u(4f), paint)
            drawIcon(c, item, 30f, 64f, 64f)
        }
        val textLeft = if (item != MenuItem.Empty) 104f else 128f
        val align = if (item != MenuItem.Empty) Paint.Align.LEFT else Paint.Align.CENTER
        val maxW = if (item != MenuItem.Empty) 128f else 200f
        val shown = lines.take(3)
        val startY = 96f - (shown.size - 1) * 8f + 4f
        shown.forEachIndexed { i, line ->
            text(c, ellipsize(line, if (i == 0) 12f else 10f, maxW), textLeft, startY + i * 16f,
                if (i == 0) 12f else 10f, if (i == 0) DsiTheme.TEXT else DsiTheme.TEXT_LIGHT, align, bold = i == 0)
        }

        text(c, hint, 128f, 168f, 9f, DsiTheme.TEXT_LIGHT)
        state.statusMessage?.let { text(c, it, 128f, 184f, 8f, DsiTheme.darken(state.accent, 0.2f)) }
    }

    private fun drawBattery(c: Canvas, l: Float, t: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = u(1f)
        paint.color = Color.WHITE
        c.drawRoundRect(rect(l, t, l + 30f, t + 9f), u(1.5f), u(1.5f), paint)
        paint.style = Paint.Style.FILL
        c.drawRect(rect(l + 30f, t + 2.5f, l + 32f, t + 6.5f), paint)
        val bars = when {
            state.batteryPercent > 75 -> 4
            state.batteryPercent > 50 -> 3
            state.batteryPercent > 25 -> 2
            state.batteryPercent > 8 -> 1
            else -> 0
        }
        paint.color = if (bars <= 1 && !state.charging) Color.rgb(0xF0, 0x40, 0x30) else Color.rgb(0x60, 0xE0, 0x60)
        for (i in 0 until bars) c.drawRect(rect(l + 2f + i * 7f, t + 2f, l + 7.5f + i * 7f, t + 7f), paint)
        if (state.charging) text(c, "⚡", l - 3f, t + 8f, 8f, Color.rgb(0xFF, 0xE0, 0x40), Paint.Align.RIGHT)
    }
}

/** DSi bottom screen: horizontal icon carousel with a draggable scroll bar. */
class DsiMenuView(
    context: Context,
    state: MenuState,
    private val onMove: () -> Unit,
    private val onActivate: (Int) -> Unit,
) : DsCanvasView(context, state) {

    private val spacing = 44f
    private var animating = false
    private var lastFrame = 0L

    // touch
    private var downX = 0f
    private var downY = 0f
    private var downScroll = 0f
    private var dragging = false
    private var draggingBar = false

    fun select(index: Int, animate: Boolean = true) {
        val n = state.items.size
        if (n == 0) return
        val i = index.coerceIn(0, n - 1)
        if (i != state.selected) onMove()
        state.selected = i
        if (!animate) state.scroll = i.toFloat()
        startAnim()
        state.invalidateAll()
    }

    private fun startAnim() {
        if (!animating) { animating = true; lastFrame = SystemClock.uptimeMillis() }
        postInvalidateOnAnimation()
    }

    override fun onDraw(c: Canvas) {
        // animate scroll towards the selected slot
        val now = SystemClock.uptimeMillis()
        if (animating && !dragging) {
            val dt = ((now - lastFrame).coerceIn(1, 50)) / 1000f
            val target = state.selected.toFloat()
            state.scroll += (target - state.scroll) * (1f - Math.pow(0.0005, dt.toDouble()).toFloat())
            if (abs(target - state.scroll) < 0.002f) { state.scroll = target; animating = false }
        }
        lastFrame = now

        c.drawColor(DsiTheme.BG_BOTTOM)
        // dotted grid
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = DsiTheme.GRID
        var gy = 26f
        while (gy < 168f) {
            var gx = 4f
            while (gx < 256f) { c.drawCircle(x(gx), y(gy), u(0.8f), paint); gx += 8f }
            gy += 8f
        }

        // top bar
        paint.shader = LinearGradient(0f, y(0f), 0f, y(18f), Color.WHITE, Color.rgb(0xD8, 0xD8, 0xD8), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, width.toFloat(), y(18f), paint)
        paint.shader = null
        paint.color = DsiTheme.CARD_BORDER
        c.drawRect(0f, y(18f), width.toFloat(), y(18.6f), paint)
        val title = when (val it = state.current) {
            is MenuItem.Game -> it.rom.shortTitle
            MenuItem.Settings -> "System Settings"
            MenuItem.SystemMenu -> "System Menu"
            MenuItem.Empty -> ""
        }
        text(c, ellipsize(title, 10f, 200f), 128f, 13f, 10f, DsiTheme.TEXT, bold = true)

        // carousel
        val cy = 88f
        val first = floor(state.scroll - 4).toInt()
        for (i in first..first + 8) {
            val item = state.items.getOrNull(i) ?: if (i >= 0) MenuItem.Empty else continue
            val cx = 128f + (i - state.scroll) * spacing
            if (cx < -30f || cx > 286f) continue
            val dist = abs(i - state.scroll).coerceAtMost(1f)
            val size = 32f + (1f - dist) * 12f
            val l = cx - size / 2f
            val t = cy - size / 2f
            paint.color = if (item == MenuItem.Empty) DsiTheme.EMPTY_SLOT else Color.WHITE
            c.drawRoundRect(rect(l - 3f, t - 3f, l + size + 3f, t + size + 3f), u(4f), u(4f), paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = u(0.8f)
            paint.color = DsiTheme.CARD_BORDER
            c.drawRoundRect(rect(l - 3f, t - 3f, l + size + 3f, t + size + 3f), u(4f), u(4f), paint)
            paint.style = Paint.Style.FILL
            drawIcon(c, item, l, t, size)
        }

        // selection frame (pulses like the DSi cursor)
        val pulse = (sin(now / 180.0) * 1.2).toFloat()
        val fs = 44f / 2f + 6f + pulse
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = u(2.5f)
        paint.color = state.accent
        c.drawRoundRect(rect(128f - fs, cy - fs, 128f + fs, cy + fs), u(6f), u(6f), paint)
        paint.style = Paint.Style.FILL
        val tri = Path().apply {
            moveTo(x(128f - 5f), y(cy - fs - 7f)); lineTo(x(128f + 5f), y(cy - fs - 7f)); lineTo(x(128f), y(cy - fs - 2f)); close()
        }
        c.drawPath(tri, paint)

        // START pill
        if (state.current != MenuItem.Empty) {
            val pill = rect(100f, 126f, 156f, 142f)
            paint.shader = LinearGradient(0f, pill.top, 0f, pill.bottom, DsiTheme.lighten(state.accent, 0.25f), state.accent, Shader.TileMode.CLAMP)
            c.drawRoundRect(pill, u(8f), u(8f), paint)
            paint.shader = null
            text(c, if (state.current is MenuItem.Game) "START" else "OPEN", 128f, 138f, 9f, Color.WHITE, bold = true)
        }

        // scroll bar
        val n = state.items.size.coerceAtLeast(1)
        val track = rect(16f, 166f, 240f, 182f)
        paint.color = Color.WHITE
        c.drawRoundRect(track, u(4f), u(4f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = u(0.8f)
        paint.color = DsiTheme.CARD_BORDER
        c.drawRoundRect(track, u(4f), u(4f), paint)
        paint.style = Paint.Style.FILL
        val slotW = 216f / n
        for (i in 0 until n) {
            val sx = 20f + slotW * i + slotW / 2f
            paint.color = if (state.items[i] == MenuItem.Empty) DsiTheme.EMPTY_SLOT else DsiTheme.TEXT_LIGHT
            c.drawRect(rect(sx - (slotW * 0.3f).coerceAtMost(2f), 171f, sx + (slotW * 0.3f).coerceAtMost(2f), 177f), paint)
        }
        val hx = 20f + slotW * state.scroll + slotW / 2f
        paint.color = state.accent
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = u(1.5f)
        c.drawRoundRect(rect(hx - 8f, 168f, hx + 8f, 180f), u(3f), u(3f), paint)
        paint.style = Paint.Style.FILL

        // pulse needs continuous frames; cheap at this size
        postInvalidateOnAnimation()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val vx = (e.x - ox) / unit
        val vy = (e.y - oy) / unit
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = vx; downY = vy; downScroll = state.scroll
                dragging = false
                draggingBar = vy >= 160f
                if (draggingBar) scrollBarTo(vx)
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingBar) scrollBarTo(vx)
                else if (dragging || abs(vx - downX) > 6f) {
                    dragging = true
                    val n = state.items.size
                    state.scroll = (downScroll - (vx - downX) / spacing).coerceIn(-0.4f, n - 0.6f)
                    val nearest = state.scroll.roundToInt().coerceIn(0, n - 1)
                    if (nearest != state.selected) { state.selected = nearest; onMove() }
                    state.invalidateAll()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (draggingBar) {
                    draggingBar = false
                    select(state.scroll.roundToInt())
                } else if (dragging) {
                    dragging = false
                    select(state.scroll.roundToInt())
                } else {
                    // tap: on the centre icon or START pill launches, a side icon selects
                    val slot = (state.scroll + (vx - 128f) / spacing).roundToInt()
                    val onPill = vy in 124f..144f && vx in 98f..158f
                    val onCarousel = vy in 56f..120f
                    if (onPill || (onCarousel && slot == state.selected)) onActivate(state.selected)
                    else if (onCarousel && slot in state.items.indices) select(slot)
                }
                startAnim()
            }
            MotionEvent.ACTION_CANCEL -> { dragging = false; draggingBar = false; startAnim() }
        }
        return true
    }

    private fun scrollBarTo(vx: Float) {
        val n = state.items.size.coerceAtLeast(1)
        val pos = ((vx - 20f) / (216f / n) - 0.5f).coerceIn(0f, (n - 1).toFloat())
        state.scroll = pos
        val nearest = pos.roundToInt()
        if (nearest != state.selected) { state.selected = nearest; onMove() }
        state.invalidateAll()
    }
}
