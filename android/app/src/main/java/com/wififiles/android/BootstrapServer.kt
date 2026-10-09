package com.wififiles.android

import java.net.ServerSocket
import java.net.InetSocketAddress
import org.json.JSONObject

// Only a public certificate is exposed here. Codes, approval and files use HTTPS.
class BootstrapServer(certificate: String) {
    private val server = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress("0.0.0.0", 8444)) }
    private val body = JSONObject().put("certificatePem", certificate).toString().toByteArray()
    @Volatile private var active = true
    private val thread = Thread({
        while (active) {
            val client = try { server.accept() } catch (_: Exception) { break }
            client.use {
                try {
                    if (!it.inetAddress.isSiteLocalAddress && !it.inetAddress.isLoopbackAddress) return@use
                    it.soTimeout = 2000
                    val input = it.getInputStream()
                    val line = java.io.ByteArrayOutputStream()
                    while (line.size() < 1024) { val b = input.read(); if (b < 0 || b == 10) break; line.write(b) }
                    if (line.toString("UTF-8").trim() != "GET /v1/certificate HTTP/1.1") return@use
                    // Consume the whole request before closing the TCP socket. Closing with
                    // unread headers can reset the connection and truncate the JSON on Wi-Fi.
                    var headerBytes = 0
                    var headerCount = 0
                    while (true) {
                        val header = java.io.ByteArrayOutputStream()
                        while (true) {
                            val b = input.read()
                            if (b < 0 || ++headerBytes > 16384) throw java.io.IOException("Invalid headers")
                            if (b == 10) break
                            header.write(b)
                        }
                        val value = header.toString("UTF-8").removeSuffix("\r")
                        if (value.isEmpty()) break
                        if (++headerCount > 40 || ':' !in value) throw java.io.IOException("Invalid headers")
                        if (value.startsWith("Transfer-Encoding:", true) || (value.startsWith("Content-Length:", true) && value.substringAfter(':').trim() != "0")) throw java.io.IOException("Unexpected request body")
                    }
                    val output = it.getOutputStream()
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n".toByteArray())
                    output.write(body); output.flush()
                } catch (_: Exception) { }
            }
        }
    }, "wifi-certificate").apply { isDaemon = true; start() }
    fun stop() { active = false; server.close(); thread.interrupt() }
}
