package com.lilypads.phonekeyboard

import android.annotation.SuppressLint
import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/** The remote: live typing box + Keys / Touchpad / Media tabs. */
class RemoteActivity : Activity() {
    private lateinit var conn: PcConnection
    private lateinit var prefs: SharedPreferences
    private lateinit var statusText: TextView
    private lateinit var dot: View
    private lateinit var input: EditText
    private lateinit var touchpad: TouchpadView

    private val main = Handler(Looper.getMainLooper())
    private val mods = linkedSetOf<String>()
    private val modButtons = mutableMapOf<String, Button>()
    private var host = ""
    private var last = ""
    private var live = true
    private var suppress = false
    private var volMode = 0          // 0 = PC volume, 1 = scroll, 2 = normal phone volume
    private var sens = 1.6f
    private var vibrator: Vibrator? = null
    private var sensorManager: SensorManager? = null
    private var airOn = false
    private var gax = 0f
    private var gay = 0f

    private fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("pk", MODE_PRIVATE)
        val h = intent.getStringExtra("host")
        if (h == null) { finish(); return }
        host = h
        val port = intent.getIntExtra("port", 8765)
        val token = intent.getStringExtra("token") ?: ""
        val name = intent.getStringExtra("name") ?: host
        volMode = prefs.getInt("volMode", 0)
        sens = prefs.getFloat("sens", 1.6f)
        vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator
        sensorManager = getSystemService(SENSOR_SERVICE) as? SensorManager

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG

        conn = PcConnection(host, port, token) { ok, msg, auth -> onStatus(ok, msg, auth) }
        setContentView(buildUi(name))
        conn.connect()
    }

    override fun onResume() {
        super.onResume()
        if (airOn) registerGyro()
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(gyro)
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager?.unregisterListener(gyro)
        main.removeCallbacksAndMessages(null)
        if (::conn.isInitialized) conn.close()
    }

    // ------------------------------------------------------------------ UI

    private fun buildUi(name: String): View {
        val root = Ui.column(this).apply {
            setBackgroundColor(Ui.BG)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            fitsSystemWindows = true
        }

        // header
        dot = View(this).apply {
            background = GradientDrawable().apply { setShape(GradientDrawable.OVAL); setColor(Ui.RED) }
        }
        statusText = Ui.label(this, "connecting…", 12f, 0xFFAAAAAA.toInt())
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Ui.label(context, "⌨️  $name", 16f, Color.WHITE, bold = true), LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(dot, LinearLayout.LayoutParams(dp(9), dp(9)).apply { rightMargin = dp(6) })
            addView(statusText)
        }
        root.addView(header, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })

        // typing box
        input = EditText(this).apply {
            hint = "Tap & type, it goes straight to the PC"
            setHintTextColor(0xFF777780.toInt())
            setTextColor(Color.WHITE)
            textSize = 17f
            background = Ui.shape(context, Ui.CARD, 12)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        input.addTextChangedListener(watcher)
        input.setOnKeyListener { _, code, e -> onInputKey(code, e) }

        val modeBtn = Ui.button(this, "Live", 13f)
        Ui.setOn(modeBtn, true)
        val sendBtn = Ui.button(this, "Send ➤", 13f).apply { visibility = View.GONE }
        modeBtn.setOnClickListener {
            live = !live
            modeBtn.text = if (live) "Live" else "Compose"
            Ui.setOn(modeBtn, live)
            sendBtn.visibility = if (live) View.GONE else View.VISIBLE
            input.hint = if (live) "Tap & type, it goes straight to the PC" else "Write it all, then hit Send ➤"
            resetInput()
        }
        sendBtn.setOnClickListener {
            val t = input.text.toString()
            if (t.isNotEmpty()) { conn.text(t); buzz() }
            resetInput()
        }
        val side = Ui.column(this).apply {
            addView(modeBtn, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(sendBtn, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
        }
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(input, LinearLayout.LayoutParams(0, dp(72), 1f))
            addView(side, LinearLayout.LayoutParams(dp(86), WRAP).apply { leftMargin = dp(6) })
        }
        root.addView(inputRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })

        // tabs + panes
        val panes = listOf<View>(scroll(keysPane()), padPane(), scroll(mediaPane()))
        val frame = FrameLayout(this)
        for (p in panes) frame.addView(p, FrameLayout.LayoutParams(MATCH, MATCH))
        val tabButtons = mutableListOf<Button>()
        fun select(i: Int) {
            panes.forEachIndexed { j, p -> p.visibility = if (i == j) View.VISIBLE else View.GONE }
            tabButtons.forEachIndexed { j, b -> Ui.setOn(b, i == j) }
            prefs.edit().putInt("tab", i).apply()
        }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Keys", "Touchpad", "Media / F-keys").forEachIndexed { i, t ->
            val b = Ui.button(this, t, 13f)
            b.setOnClickListener { buzz(); select(i) }
            tabButtons.add(b)
            tabs.addView(b, LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        select(prefs.getInt("tab", 0).coerceIn(0, 2))
        root.addView(tabs, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(6) })
        root.addView(frame, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return root
    }

    private fun scroll(v: View) = ScrollView(this).apply { isFillViewport = true; addView(v) }

    private val ctrl = listOf("ctrl")

    private fun keysPane(): LinearLayout = Ui.column(this).apply {
        addView(Ui.row(context, modBtn("Ctrl", "ctrl"), modBtn("Shift", "shift"), modBtn("Alt", "alt"), modBtn("Win", "win")))
        addView(Ui.row(context, keyBtn("Esc", "esc"), keyBtn("Tab", "tab"), keyBtn("⌫", "backspace", repeat = true),
            keyBtn("Del", "delete", repeat = true), keyBtn("⏎", "enter")))
        addView(Ui.row(context, keyBtn("Home", "home"), keyBtn("▲", "up", repeat = true), keyBtn("End", "end"),
            keyBtn("PgUp", "pgup", repeat = true)))
        addView(Ui.row(context, keyBtn("◀", "left", repeat = true), keyBtn("▼", "down", repeat = true),
            keyBtn("▶", "right", repeat = true), keyBtn("PgDn", "pgdn", repeat = true)))
        addView(Ui.row(context, keyBtn("Copy", "c", ctrl, sp = 13f), keyBtn("Paste", "v", ctrl, sp = 13f),
            keyBtn("Cut", "x", ctrl, sp = 13f), keyBtn("Undo", "z", ctrl, true, 13f), keyBtn("All", "a", ctrl, sp = 13f)))
        addView(Ui.row(context, keyBtn("Alt+Tab", "tab", listOf("alt"), sp = 12f), keyBtn("Start", "win", sp = 12f),
            keyBtn("Desktop", "d", listOf("win"), sp = 12f), keyBtn("Alt+F4", "f4", listOf("alt"), sp = 12f),
            keyBtn("Save", "s", ctrl, sp = 12f)))
        addView(Ui.label(context, "Tap a modifier, then type a letter or tap a key (e.g. Ctrl → s). " +
                "Arrows & ⌫ repeat while held.", 11f, Ui.GREY).apply { setPadding(dp(4), dp(8), dp(4), 0) })
    }

    private fun padPane(): LinearLayout {
        touchpad = TouchpadView(this, { conn }, { sens }, { buzz() })
        val dragBtn = Ui.button(this, "Drag 🔒", 13f)
        dragBtn.setOnClickListener {
            touchpad.dragLock = !touchpad.dragLock
            Ui.setOn(dragBtn, touchpad.dragLock)
            if (touchpad.dragLock) conn.down("left") else conn.up("left")
            buzz()
        }
        val airBtn = Ui.button(this, "🎯  Air mouse (tilt the phone)", 13f)
        airBtn.setOnClickListener { setAir(!airOn); Ui.setOn(airBtn, airOn); buzz() }
        val speed = SeekBar(this).apply {
            max = 35
            progress = ((sens - 0.5f) * 10f).toInt().coerceIn(0, 35)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    sens = 0.5f + p / 10f
                    prefs.edit().putFloat("sens", sens).apply()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        val speedRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), 0)
            addView(Ui.label(context, "Speed", 12f, Ui.GREY))
            addView(speed, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        return Ui.column(this).apply {
            addView(touchpad, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(6)) })
            addView(Ui.row(context, clickBtn("Left", "left"), clickBtn("Middle", "middle"), clickBtn("Right", "right"), dragBtn))
            addView(Ui.row(context, airBtn))
            addView(speedRow)
        }
    }

    private fun mediaPane(): LinearLayout = Ui.column(this).apply {
        addView(Ui.row(context, keyBtn("⏮", "prev"), keyBtn("⏯", "playpause"), keyBtn("⏭", "next")))
        addView(Ui.row(context, keyBtn("🔉", "voldown", repeat = true), keyBtn("🔇", "mute"), keyBtn("🔊", "volup", repeat = true)))
        addView(Ui.row(context, *(1..6).map { keyBtn("F$it", "f$it", sp = 12f) }.toTypedArray()))
        addView(Ui.row(context, *(7..12).map { keyBtn("F$it", "f$it", sp = 12f) }.toTypedArray()))
        addView(Ui.row(context, keyBtn("PrtSc", "printscreen", sp = 12f), keyBtn("Snip", "s", listOf("win", "shift"), sp = 12f),
            keyBtn("TaskMgr", "esc", listOf("ctrl", "shift"), sp = 12f), keyBtn("Lock", "l", listOf("win"), sp = 12f)))
        val volBtn = Ui.button(this@RemoteActivity, volLabel(), 13f)
        volBtn.setOnClickListener {
            volMode = (volMode + 1) % 3
            prefs.edit().putInt("volMode", volMode).apply()
            volBtn.text = volLabel()
            buzz()
        }
        addView(Ui.row(context, volBtn))
    }

    private fun volLabel() = "Phone volume buttons → " + listOf("PC volume", "scroll", "phone (normal)")[volMode]

    private fun modBtn(label: String, m: String): Button = Ui.button(this, label).also { b ->
        modButtons[m] = b
        b.setOnClickListener {
            buzz()
            if (!mods.remove(m)) mods.add(m)
            Ui.setOn(b, m in mods)
        }
    }

    private fun clearMods() {
        mods.clear()
        modButtons.values.forEach { Ui.setOn(it, false) }
    }

    private fun clickBtn(label: String, button: String): Button = Ui.button(this, label, 13f).apply {
        setOnClickListener { buzz(); conn.click(button) }
    }

    /** A key button. fixedMods = always-used modifiers (shortcuts); otherwise uses the sticky Ctrl/Shift/Alt/Win. */
    @SuppressLint("ClickableViewAccessibility")
    private fun keyBtn(label: String, key: String, fixedMods: List<String>? = null,
                       repeat: Boolean = false, sp: Float = 15f): Button {
        val b = Ui.button(this, label, sp)
        var rep: Runnable? = null
        b.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    buzz()
                    val m = fixedMods ?: mods.toList()
                    conn.key(key, m)
                    if (fixedMods == null) clearMods()
                    if (repeat) {
                        val r = object : Runnable {
                            override fun run() { conn.key(key, m); main.postDelayed(this, 45) }
                        }
                        rep = r
                        main.postDelayed(r, 380)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    rep?.let { main.removeCallbacks(it) }
                    rep = null
                }
            }
            true
        }
        return b
    }

    // ------------------------------------------------------------------ typing

    private fun resetInput() {
        suppress = true
        input.setText("")
        suppress = false
        last = ""
    }

    /** Diff-based: works with every IME (Gboard, Samsung, SwiftKey), swipe typing, autocorrect, any language. */
    private val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable) {
            if (suppress || !live) return
            val v = s.toString()
            var i = 0
            val n = minOf(last.length, v.length)
            while (i < n && last[i] == v[i]) i++
            if (i > 0 && Character.isHighSurrogate(last[i - 1])) i--   // don't split emoji
            val removed = last.substring(i)
            val added = v.substring(i)
            last = v
            val del = removed.codePointCount(0, removed.length)
            if (del > 0) conn.backspace(del)
            if (added.isNotEmpty()) {
                if (mods.isNotEmpty()) {
                    val m = mods.toList()
                    for (ch in added) conn.key(when (ch) { '\n' -> "enter"; ' ' -> "space"; else -> ch.lowercaseChar().toString() }, m)
                    clearMods()
                } else {
                    conn.text(added)
                }
            }
            if (v.endsWith("\n") || v.length > 200) main.post { resetInput() }
        }
    }

    /** Keys pressed while the box is empty (backspace, arrows from a hardware keyboard, etc). */
    private fun onInputKey(code: Int, e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN || !live || input.text.isNotEmpty()) return false
        val k = when (code) {
            KeyEvent.KEYCODE_DEL -> { conn.backspace(1); return true }
            KeyEvent.KEYCODE_FORWARD_DEL -> "delete"
            KeyEvent.KEYCODE_DPAD_LEFT -> "left"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
            KeyEvent.KEYCODE_DPAD_UP -> "up"
            KeyEvent.KEYCODE_DPAD_DOWN -> "down"
            KeyEvent.KEYCODE_TAB -> "tab"
            KeyEvent.KEYCODE_ESCAPE -> "esc"
            KeyEvent.KEYCODE_MOVE_HOME -> "home"
            KeyEvent.KEYCODE_MOVE_END -> "end"
            else -> return false
        }
        conn.key(k, mods.toList())
        clearMods()
        return true
    }

    /** Phone volume buttons -> PC volume or scroll wheel. */
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val up = e.keyCode == KeyEvent.KEYCODE_VOLUME_UP
        val down = e.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if ((up || down) && volMode != 2) {
            if (e.action == KeyEvent.ACTION_DOWN) {
                if (volMode == 0) conn.key(if (up) "volup" else "voldown")
                else conn.scroll(if (up) 120 else -120)
            }
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    // ------------------------------------------------------------------ air mouse

    private val gyro = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val k = sens * 22f
            val gx = e.values[0]
            val gz = e.values[2]
            if (abs(gz) > 0.03f) gax += -gz * k
            if (abs(gx) > 0.03f) gay += -gx * k
            val mx = gax.toInt()
            val my = gay.toInt()
            if (mx != 0 || my != 0) { conn.move(mx, my); gax -= mx; gay -= my }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun registerGyro(): Boolean {
        val sm = sensorManager ?: return false
        val g = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: return false
        return sm.registerListener(gyro, g, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun setAir(on: Boolean) {
        if (on) {
            airOn = registerGyro()
            if (!airOn) Toast.makeText(this, "This phone has no gyroscope 😕", Toast.LENGTH_SHORT).show()
            else Toast.makeText(this, "Hold the phone flat like a remote and turn/tilt it", Toast.LENGTH_SHORT).show()
        } else {
            airOn = false
            sensorManager?.unregisterListener(gyro)
        }
    }

    // ------------------------------------------------------------------ misc

    private fun onStatus(ok: Boolean, msg: String, auth: Boolean) {
        (dot.background as? GradientDrawable)?.setColor(if (ok) Ui.GREEN else Ui.RED)
        statusText.text = msg
        if (auth) {
            prefs.edit().remove("token_$host").apply()
            statusText.text = "rejected"
            Toast.makeText(this, "The PC doesn't know this phone anymore. Go back and pair again.", Toast.LENGTH_LONG).show()
        }
    }

    private fun buzz() {
        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(8, VibrationEffect.DEFAULT_AMPLITUDE))
        else {
            @Suppress("DEPRECATION")
            v.vibrate(8)
        }
    }
}
