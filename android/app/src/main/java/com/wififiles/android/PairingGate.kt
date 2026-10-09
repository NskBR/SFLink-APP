package com.wififiles.android

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class PairRequest(val peer: String, val verification: String, val latch: CountDownLatch = CountDownLatch(1), @Volatile var approved: Boolean = false, val clientId: String = "", val clientName: String = "", @Volatile var remember: Boolean = false)

class PairingGate(private val certificate: String, private val publish: (PairRequest?) -> Unit) {
    private val random = SecureRandom()
    var code: String = ""; private set
    private var expires = 0L
    private var attempts = 0
    private var pending: PairRequest? = null
    init { renew() }
    @Synchronized fun renew(): String {
        pending?.latch?.countDown(); pending = null; publish(null)
        code = "%06d".format(java.util.Locale.US, random.nextInt(1_000_000))
        expires = System.nanoTime() + TimeUnit.MINUTES.toNanos(5); attempts = 0
        return code
    }
    fun request(supplied: String, nonce: String, peer: String, clientId: String = "", clientName: String = ""): Boolean {
        return authorize(supplied, nonce, peer, clientId, clientName) != null
    }
    fun authorize(supplied: String, nonce: String, peer: String, clientId: String = "", clientName: String = ""): PairRequest? {
        val request: PairRequest
        synchronized(this) {
            if (System.nanoTime() > expires || attempts >= 5) throw ApiFailure(429, "Código expirado ou limite de tentativas. Gere outro código no Android.")
            if (pending != null) throw ApiFailure(409, "Já existe uma solicitação aguardando aprovação.")
            attempts++
            if (!MessageDigest.isEqual(code.toByteArray(), supplied.toByteArray())) throw ApiFailure(401, "Código incorreto.")
            if (!nonce.matches(Regex("[a-f0-9]{64}"))) throw ApiFailure(400, "Solicitação inválida.")
            val digest = MessageDigest.getInstance("SHA-256").digest((certificate + nonce).toByteArray())
            val verification = digest.take(8).joinToString("") { "%02X".format(java.util.Locale.US, it) }
            if (clientId.isNotEmpty() && !clientId.matches(Regex("[a-f0-9]{64}"))) throw ApiFailure(400, "Identidade do PC inválida.")
            request = PairRequest(peer, verification, clientId = clientId, clientName = clientName.take(80))
            pending = request; publish(request)
        }
        try {
            val allowed = request.latch.await(60, TimeUnit.SECONDS) && request.approved
            return if (allowed) request else null
        } finally {
            synchronized(this) { if (pending === request) { pending = null; publish(null) } }
        }
    }
    @Synchronized fun answer(request: PairRequest, allow: Boolean, remember: Boolean = false) {
        if (pending !== request) return
        request.approved = allow
        request.remember = allow && remember && request.clientId.isNotEmpty()
        if (allow) { expires = 0; attempts = 5 }
        request.latch.countDown()
    }
    @Synchronized fun close() { expires = 0; pending?.latch?.countDown(); pending = null; publish(null) }
}
