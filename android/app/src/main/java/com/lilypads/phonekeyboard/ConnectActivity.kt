package com.lilypads.phonekeyboard

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Start screen: finds PCs on the WiFi, pairs, or connects from a pasted link. */
class ConnectActivity : Activity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var list: LinearLayout
    private lateinit var info: TextView
    private lateinit var linkInput: EditText
    private lateinit var lastBtn: Button
    @Volatile private var scanning = false

    private fun dp(v: Int) = Ui.dp(this, v)
    private fun full() = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("pk", MODE_PRIVATE)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG

        val col = Ui.column(this).apply { setPadding(dp(18), dp(24), dp(18), dp(24)) }
        col.addView(Ui.label(this, "⌨️  WiFi Keyboard", 26f, Color.WHITE, bold = true))
        col.addView(Ui.label(this, "Start PhoneKeyboard on your Windows PC, then pick it below. " +
                "Phone and PC need to be on the same WiFi.", 14f, Ui.GREY).apply { setPadding(0, dp(6), 0, dp(10)) })

        lastBtn = Ui.button(this, "").apply { visibility = View.GONE }
        col.addView(lastBtn, full())

        col.addView(section("PCs on your WiFi"))
        info = Ui.label(this, "", 13f, Ui.GREY)
        col.addView(info)
        list = Ui.column(this)
        col.addView(list, full())
        col.addView(Ui.button(this, "🔄  Search again").apply { setOnClickListener { scan() } }, full())

        col.addView(section("Or paste the link shown on the PC"))
        linkInput = EditText(this).apply {
            hint = "http://192.168.1.10:8765/?t=…"
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF666670.toInt())
            textSize = 15f
            background = Ui.shape(this@ConnectActivity, Ui.CARD, 10)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        col.addView(linkInput, full())
        col.addView(Ui.button(this, "Connect").apply { setOnClickListener { connectFromLink() } }, full())
        col.addView(Ui.label(this, "Tip: long-press a PC to forget its pairing.", 12f, Ui.GREY)
            .apply { setPadding(0, dp(18), 0, 0) })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            fitsSystemWindows = true
            addView(col)
        })
    }

    override fun onResume() {
        super.onResume()
        refreshLast()
        scan()
    }

    private fun section(t: String) = Ui.label(this, t.uppercase(), 12f, Ui.GREY, bold = true)
        .apply { setPadding(0, dp(22), 0, dp(4)) }

    private fun refreshLast() {
        val h = prefs.getString("last_host", null)
        val t = if (h != null) prefs.getString("token_$h", null) else null
        if (h != null && t != null) {
            val n = prefs.getString("last_name", h) ?: h
            val p = prefs.getInt("last_port", 8765)
            lastBtn.text = "⚡  Reconnect to $n"
            Ui.setOn(lastBtn, true)
            lastBtn.visibility = View.VISIBLE
            lastBtn.setOnClickListener { launch(n, h, p, t) }
        } else {
            lastBtn.visibility = View.GONE
        }
    }

    private fun scan() {
        if (scanning) return
        scanning = true
        info.text = "Searching your WiFi…"
        list.removeAllViews()
        Thread {
            val pcs = try { Discovery.find() } catch (ignored: Exception) { emptyList() }
            runOnUiThread {
                scanning = false
                if (isFinishing) return@runOnUiThread
                info.text = if (pcs.isEmpty())
                    "No PCs found. Is PhoneKeyboard running on the PC? Same WiFi? Firewall allowed?"
                else "Tap your PC:"
                for (pc in pcs) {
                    val paired = prefs.getString("token_${pc.host}", null) != null
                    val b = Ui.button(this, "🖥  ${pc.name}   ·   ${pc.host}${if (paired) "   ✓" else ""}")
                    b.setOnClickListener { openPc(pc.name, pc.host, pc.port, null) }
                    b.setOnLongClickListener {
                        prefs.edit().remove("token_${pc.host}").apply()
                        toast("Forgot pairing with ${pc.name}")
                        refreshLast(); scan(); true
                    }
                    list.addView(b, full())
                }
            }
        }.start()
    }

    private fun connectFromLink() {
        var s = linkInput.text.toString().trim()
        if (s.isEmpty()) { toast("Paste the link from the PC window first"); return }
        if (!s.contains("://")) s = "http://$s"
        val u = Uri.parse(s)
        val host = u.host
        if (host.isNullOrEmpty()) { toast("That doesn't look like the right link"); return }
        val port = if (u.port > 0) u.port else 8765
        openPc(host, host, port, u.getQueryParameter("t"))
    }

    private fun openPc(name: String, host: String, port: Int, token: String?) {
        val saved = token ?: prefs.getString("token_$host", null)
        if (!saved.isNullOrEmpty()) { launch(name, host, port, saved); return }

        val dlg = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Pair with $name")
            .setMessage("Look at the PhoneKeyboard window on your PC, type  y  and press Enter.")
            .setNegativeButton("Cancel", null)
            .show()
        Thread {
            val r = Discovery.pair(host, deviceName())
            runOnUiThread {
                if (isFinishing || !dlg.isShowing) return@runOnUiThread
                dlg.dismiss()
                when {
                    r == null -> toast("No answer from the PC. Is PhoneKeyboard running there?")
                    !r.ok || r.token.isEmpty() -> toast("The PC said no 🙅")
                    else -> launch(name, host, r.port, r.token)
                }
            }
        }.start()
    }

    private fun launch(name: String, host: String, port: Int, token: String) {
        prefs.edit()
            .putString("token_$host", token)
            .putString("last_host", host)
            .putString("last_name", name)
            .putInt("last_port", port)
            .apply()
        startActivity(Intent(this, RemoteActivity::class.java)
            .putExtra("host", host).putExtra("port", port)
            .putExtra("token", token).putExtra("name", name))
    }

    private fun deviceName(): String {
        val m = Build.MODEL ?: "Android"
        val brand = Build.MANUFACTURER ?: ""
        val n = if (m.lowercase().startsWith(brand.lowercase())) m else "$brand $m"
        return n.trim().replaceFirstChar { it.uppercase() }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
