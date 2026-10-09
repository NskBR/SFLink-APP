package com.wififiles.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

@RunWith(AndroidJUnit4::class)
class RememberedConnectionTest {
    @Test fun rememberRestartTransferAndRevoke() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val first = PairingFactory.create(context)
        val trust = TrustedPeers(context)
        val id = "d".repeat(64)
        trust.forget(id)
        lateinit var gate: PairingGate
        gate = PairingGate(first.certificate) { request -> if (request != null) gate.answer(request, true, true) }
        var server = FileServer(context, first, gate, trust, {}, { _, _, _ -> }, {})
        var thread = Thread { server.run() }.apply { start() }
        val ssl = pinned(first.certificate)
        try {
            val pair = request(ssl, "POST", "/v1/pair", "", JSONObject().put("code", gate.code).put("nonce", "a".repeat(64)).put("clientId", id).put("clientName", "PC de teste").toString().toByteArray())
            assertEquals(200, pair.first)
            val authorization = JSONObject(String(pair.second))
            assertTrue(authorization.getBoolean("remembered"))
            val token = authorization.getString("token")
            assertEquals(id, TrustedPeers(context).authenticate(token)?.id)
            assertEquals(200, request(ssl, "GET", "/v1/device", token).first)
            server.stop(); thread.join(3000)
            val second = PairingFactory.create(context)
            assertEquals(first.id, second.id)
            assertEquals(first.certificate, second.certificate)
            assertNotEquals(first.token, second.token)
            val reopened = TrustedPeers(context)
            server = FileServer(context, second, PairingGate(second.certificate) {}, reopened, {}, { _, _, _ -> }, {})
            thread = Thread { server.run() }.apply { start() }
            // The old pinned identity and remembered token work with the newly issued TLS leaf.
            assertEquals(200, request(ssl, "GET", "/v1/device", token).first)
            assertEquals(401, request(ssl, "GET", "/v1/device", first.token).first)
            assertEquals(401, request(ssl, "GET", "/v1/device", "invalid-token").first)
            val file = "/Download/remember-test-${System.nanoTime()}.bin"
            val bytes = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
            val route = "/v1/content?overwrite=false&path=" + java.net.URLEncoder.encode(file, "UTF-8")
            try {
                assertEquals(201, request(ssl, "PUT", route, token, bytes).first)
                assertArrayEquals(bytes, request(ssl, "GET", route, token).second)
                assertEquals(409, request(ssl, "PUT", route, token, byteArrayOf(1)).first)
            } finally { request(ssl, "POST", "/v1/actions", token, JSONObject().put("action", "delete").put("path", file).toString().toByteArray()) }
            reopened.forget(id); server.revokeConnections()
            assertEquals(401, request(ssl, "GET", "/v1/device", token).first)
            assertNull(TrustedPeers(context).authenticate(token))
        } finally { server.stop(); thread.join(3000); trust.forget(id) }
    }
    private fun pinned(pem: String): SSLContext {
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(pem.byteInputStream())
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("identity", cert) }
        val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        return SSLContext.getInstance("TLS").apply { init(null, managers.trustManagers, null) }
    }
    private fun request(ssl: SSLContext, method: String, path: String, token: String, body: ByteArray? = null): Pair<Int, ByteArray> {
        val connection = URL("https://127.0.0.1:8443$path").openConnection() as HttpsURLConnection
        connection.sslSocketFactory = ssl.socketFactory; connection.requestMethod = method; connection.connectTimeout = 4000; connection.readTimeout = 15000
        connection.setRequestProperty("Connection", "close")
        if (token.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) { connection.doOutput = true; connection.setFixedLengthStreamingMode(body.size); connection.outputStream.use { it.write(body) } }
        try { val status = connection.responseCode; val bytes = (if (status < 400) connection.inputStream else connection.errorStream)?.use { it.readBytes() } ?: byteArrayOf(); return status to bytes }
        finally { connection.disconnect() }
    }
}
