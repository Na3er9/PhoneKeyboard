package com.lilypads.phonekeyboard

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

/** Tiny view helpers so the whole UI is built in code (no XML layouts). */
object Ui {
    const val BG = 0xFF0F0F12.toInt()
    const val CARD = 0xFF1C1C22.toInt()
    const val PAD = 0xFF18181E.toInt()
    const val KEY = 0xFF23232B.toInt()
    const val KEY_DOWN = 0xFF3A3A46.toInt()
    const val BORDER = 0xFF33333D.toInt()
    const val GREEN = 0xFF33BB66.toInt()
    const val RED = 0xFFEE5555.toInt()
    const val GREY = 0xFF8A8A95.toInt()

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density + 0.5f).toInt()

    fun shape(c: Context, color: Int, radius: Int = 9, stroke: Int = BORDER): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(c, radius).toFloat()
            setStroke(dp(c, 1), stroke)
        }

    fun keyBg(c: Context, on: Boolean): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), shape(c, KEY_DOWN))
        addState(intArrayOf(), if (on) shape(c, GREEN, stroke = GREEN) else shape(c, KEY))
    }

    fun button(c: Context, label: String, sp: Float = 15f): Button = Button(c).apply {
        text = label
        isAllCaps = false
        textSize = sp
        setTextColor(Color.WHITE)
        background = keyBg(c, false)
        stateListAnimator = null
        minHeight = 0
        minimumHeight = dp(c, 46)
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(c, 2), dp(c, 10), dp(c, 2), dp(c, 10))
    }

    fun setOn(b: Button, on: Boolean) {
        b.background = keyBg(b.context, on)
        b.setTextColor(if (on) Color.BLACK else Color.WHITE)
    }

    fun label(c: Context, s: String, sp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(c).apply {
            text = s
            textSize = sp
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    fun row(c: Context, vararg views: View): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        for (v in views) {
            val m = dp(c, 3)
            addView(v, LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(m, m, m, m) })
        }
    }

    fun column(c: Context): LinearLayout = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
}
