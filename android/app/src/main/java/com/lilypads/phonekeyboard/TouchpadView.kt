package com.lilypads.phonekeyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 1 finger: move  ·  tap: left click  ·  2 fingers: scroll  ·  2-finger tap: right click
 * double-tap & hold: drag
 */
@SuppressLint("ViewConstructor")
class TouchpadView(
    context: Context,
    private val conn: () -> PcConnection,
    private val sens: () -> Float,
    private val buzz: () -> Unit
) : View(context) {

    var dragLock = false

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF5A5A66.toInt()
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics)
    }
    private val hint = listOf(
        "1 finger: move  ·  tap: click",
        "2 fingers: scroll  ·  2-finger tap: right-click",
        "double-tap & hold: drag"
    )

    private var startT = 0L
    private var moved = 0f
    private var maxPtr = 0
    private var lx = 0f
    private var ly = 0f
    private var ax = 0f
    private var ay = 0f
    private var sx = 0f
    private var sy = 0f
    private var lastTap = 0L
    private var dragging = false

    init {
        background = Ui.shape(context, Ui.PAD, 14)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val lh = paint.textSize * 1.6f
        var y = height / 2f - lh * (hint.size - 1) / 2f
        for (line in hint) { canvas.drawText(line, width / 2f, y, paint); y += lh }
    }

    private fun recenter(e: MotionEvent, skip: Int = -1) {
        var x = 0f; var y = 0f; var n = 0
        for (i in 0 until e.pointerCount) {
            if (i == skip) continue
            x += e.getX(i); y += e.getY(i); n++
        }
        if (n > 0) { lx = x / n; ly = y / n }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val now = SystemClock.uptimeMillis()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                startT = now; moved = 0f; maxPtr = 1
                if (now - lastTap < 280 && !dragLock) { dragging = true; conn().down("left") }
                recenter(e)
            }
            MotionEvent.ACTION_POINTER_DOWN -> { maxPtr = max(maxPtr, e.pointerCount); recenter(e) }
            MotionEvent.ACTION_POINTER_UP -> recenter(e, e.actionIndex)
            MotionEvent.ACTION_MOVE -> {
                val oldX = lx; val oldY = ly
                recenter(e)
                val dx = (lx - oldX) / density
                val dy = (ly - oldY) / density
                moved += abs(dx) + abs(dy)
                if (e.pointerCount >= 2) {
                    sy += dy * 5f          // natural scrolling, like Windows touchpads
                    sx -= dx * 5f
                    val wy = sy.toInt(); val wx = sx.toInt()
                    if (wy != 0 || wx != 0) { conn().scroll(wy, wx); sy -= wy; sx -= wx }
                } else {
                    val accel = sens() * (1f + min(hypot(dx, dy) / 12f, 2.5f))
                    ax += dx * accel; ay += dy * accel
                    val mx = ax.toInt(); val my = ay.toInt()
                    if (mx != 0 || my != 0) { conn().move(mx, my); ax -= mx; ay -= my }
                }
            }
            MotionEvent.ACTION_UP -> {
                val tap = now - startT < 220 && moved < 6f
                if (dragging) {
                    conn().up("left"); dragging = false; lastTap = 0
                } else if (tap) {
                    buzz()
                    val right = maxPtr >= 2
                    conn().click(if (right) "right" else "left")
                    lastTap = if (right) 0 else now
                }
                maxPtr = 0
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) { conn().up("left"); dragging = false }
                maxPtr = 0
            }
        }
        return true
    }
}
