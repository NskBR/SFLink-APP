package com.wififiles.android

import android.os.Build
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/** Discovery reveals only a public identity. Access still requires pinned HTTPS and a token. */
class DiscoveryServer(private val id: String) {
    private val socket = DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress("0.0.0.0", 8445)) }
    @Volatile private var active = true
    private val thread = Thread({
        val bytes = ByteArray(2048)
        while (active) {
            try {
                val packet = DatagramPacket(bytes, bytes.size); socket.receive(packet)
                if (!packet.address.isSiteLocalAddress && !packet.address.isLoopbackAddress) continue
                val query = JSONObject(String(packet.data, 0, packet.length, Charsets.UTF_8))
                val nonce = query.optString("nonce")
                if (query.optString("type") != "wifi-files-discover-v1" || !nonce.matches(Regex("[a-f0-9]{32}"))) continue
                val reply = JSONObject().put("type", "wifi-files-device-v1").put("nonce", nonce).put("id", id).put("port", 8443)
                    .put("name", "${Build.MANUFACTURER} ${Build.MODEL}").toString().toByteArray()
                socket.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
            } catch (_: Exception) { if (!active) break }
        }
    }, "wifi-discovery").apply { isDaemon = true; start() }
    fun stop() { active = false; socket.close(); thread.interrupt() }
}
