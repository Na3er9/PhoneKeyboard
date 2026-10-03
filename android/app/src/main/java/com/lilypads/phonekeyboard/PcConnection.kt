package com.lilypads.phonekeyboard

import android.os.Handler
import android.os.Looper
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WebSocket link to the PC server (same protocol as the browser version).
 * Auto-reconnects with backoff. Status callbacks always arrive on the main thread.
 */
class PcConnection(
    private val host: String,
    private val port: Int,
    private val token: String,
    private val onStatus: (connected: Boolean, message: String, authFailed: Boolean) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .build()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var socket: WebSocket? = null
    @Volatile private var open = false
    @Volatile private var closed = false
    @Volatile private var backoff = 500L

    fun connect() {
        if (closed) return
        val url = HttpUrl.Builder()
            .scheme("http").host(host).port(port)
            .addPathSegment("ws").addQueryParameter("t", token)
            .build()
        status(false, "connecting…")
        socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                open = true
                backoff = 500L
                status(true, "connected")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                open = false
                if (!closed) { status(false, "reconnecting…"); retry() }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                open = false
                if (closed) return
                if (response?.code == 403) { status(false, "PC rejected this phone", true); return }
                status(false, "reconnecting…")
                retry()
            }
        })
    }

    private fun retry() {
        val wait = backoff
        backoff = (backoff * 2).coerceAtMost(5000L)
        main.postDelayed({ connect() }, wait)
    }

    private fun status(ok: Boolean, msg: String, auth: Boolean = false) {
        main.post { onStatus(ok, msg, auth) }
    }

    private fun obj(type: String) = JSONObject().put("type", type)

    private fun send(o: JSONObject) {
        if (open) socket?.send(o.toString())
    }

    fun text(s: String) = send(obj("text").put("text", s))
    fun backspace(n: Int) = send(obj("backspace").put("count", n))
    fun key(k: String, mods: List<String> = emptyList()) = send(obj("key").put("key", k).put("mods", JSONArray(mods)))
    fun move(dx: Int, dy: Int) = send(obj("move").put("dx", dx).put("dy", dy))
    fun scroll(dy: Int, dx: Int = 0) = send(obj("scroll").put("dy", dy).put("dx", dx))
    fun click(button: String, double: Boolean = false) = send(obj("click").put("button", button).put("double", double))
    fun down(button: String) = send(obj("down").put("button", button))
    fun up(button: String) = send(obj("up").put("button", button))

    fun close() {
        closed = true
        main.removeCallbacksAndMessages(null)
        socket?.close(1000, "bye")
        client.dispatcher.executorService.shutdown()
    }
}
