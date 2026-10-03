package com.lilypads.phonekeyboard

import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/** Finds PCs running PhoneKeyboard (UDP broadcast) and pairs with them. Call off the main thread. */
object Discovery {
    private const val PORT = 8766

    data class Pc(val name: String, val host: String, val port: Int)
    data class PairReply(val ok: Boolean, val token: String, val port: Int)

    fun find(timeoutMs: Long = 1500): List<Pc> {
        val found = LinkedHashMap<String, Pc>()
        DatagramSocket().use { s ->
            s.broadcast = true
            s.soTimeout = 250
            val msg = "PK_DISCOVER".toByteArray()
            repeat(2) { // UDP can drop packets, ask twice
                for (addr in broadcastAddresses()) {
                    try { s.send(DatagramPacket(msg, msg.size, addr, PORT)) } catch (ignored: Exception) { }
                }
            }
            val end = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(2048)
            while (System.currentTimeMillis() < end) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    val j = JSONObject(String(p.data, 0, p.length, Charsets.UTF_8))
                    val host = p.address.hostAddress
                    if (host != null) found[host] = Pc(j.optString("name", host), host, j.optInt("port", 8765))
                } catch (ignored: SocketTimeoutException) {
                } catch (ignored: Exception) {
                }
            }
        }
        return found.values.toList()
    }

    /** Asks the PC to approve this phone. Returns null if the PC never answered. */
    fun pair(host: String, deviceName: String): PairReply? = try {
        DatagramSocket().use { s ->
            s.soTimeout = 65_000
            val msg = "PK_PAIR $deviceName".toByteArray(Charsets.UTF_8)
            s.send(DatagramPacket(msg, msg.size, InetAddress.getByName(host), PORT))
            val buf = ByteArray(2048)
            val p = DatagramPacket(buf, buf.size)
            s.receive(p)
            val j = JSONObject(String(p.data, 0, p.length, Charsets.UTF_8))
            PairReply(j.optBoolean("ok", false), j.optString("token", ""), j.optInt("port", 8765))
        }
    } catch (ignored: Exception) {
        null
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val list = mutableListOf(InetAddress.getByName("255.255.255.255"))
        try {
            val nis = NetworkInterface.getNetworkInterfaces()
            if (nis != null) for (ni in nis.toList()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) ia.broadcast?.let { list.add(it) }
            }
        } catch (ignored: Exception) { }
        return list.distinct()
    }
}
