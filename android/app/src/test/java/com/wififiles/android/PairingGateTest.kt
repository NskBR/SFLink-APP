package com.wififiles.android

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class PairingGateTest {
    @Test fun rememberingRequiresExplicitConsentAndAClientIdentity() {
        lateinit var gate: PairingGate
        gate = PairingGate("certificate") { request -> if (request != null) gate.answer(request, true, true) }
        val withoutIdentity = gate.authorize(gate.code, "a".repeat(64), "PC")!!
        assertFalse(withoutIdentity.remember)
        gate.renew()
        val remembered = gate.authorize(gate.code, "b".repeat(64), "PC", "c".repeat(64), "Meu PC")!!
        assertTrue(remembered.remember)
        assertEquals("c".repeat(64), remembered.clientId)
        gate.renew()
        try { gate.authorize(gate.code, "b".repeat(64), "PC", "invalid", "Meu PC"); fail() } catch (e: ApiFailure) { assertEquals(400, e.status) }
    }
    @Test fun wrongCodesAreLimitedAndRenewalRestoresAccess() {
        val gate = PairingGate("certificate") {}
        val wrong = if (gate.code == "000000") "111111" else "000000"
        repeat(5) { try { gate.request(wrong, "a".repeat(64), "PC"); fail() } catch (e: ApiFailure) { assertEquals(401, e.status) } }
        try { gate.request(gate.code, "a".repeat(64), "PC"); fail() } catch (e: ApiFailure) { assertEquals(429, e.status) }
        assertTrue(gate.renew().matches(Regex("[0-9]{6}")))
        gate.close()
        try { gate.request(gate.code, "a".repeat(64), "PC"); fail() } catch (e: ApiFailure) { assertEquals(429, e.status) }
    }
    @Test fun authorizationRequiresAnExplicitAnswerAndConsumesTheCode() {
        val published = CountDownLatch(1)
        val request = AtomicReference<PairRequest>()
        val result = AtomicReference<Boolean>()
        val gate = PairingGate("certificate") { if (it != null) { request.set(it); published.countDown() } }
        val thread = Thread { result.set(gate.request(gate.code, "a".repeat(64), "PC")) }.apply { start() }
        assertTrue(published.await(2, TimeUnit.SECONDS))
        assertNull(result.get())
        assertEquals(16, request.get().verification.length)
        gate.answer(request.get(), true)
        thread.join(2000)
        assertEquals(true, result.get())
        try { gate.request(gate.code, "a".repeat(64), "PC"); fail() } catch (e: ApiFailure) { assertEquals(429, e.status) }
    }
    @Test fun refusalNeverGrantsAccess() {
        val published = CountDownLatch(1)
        val request = AtomicReference<PairRequest>()
        val result = AtomicReference<Boolean>()
        val gate = PairingGate("certificate") { if (it != null) { request.set(it); published.countDown() } }
        val thread = Thread { result.set(gate.request(gate.code, "b".repeat(64), "PC")) }.apply { start() }
        assertTrue(published.await(2, TimeUnit.SECONDS))
        gate.answer(request.get(), false)
        thread.join(2000)
        assertEquals(false, result.get())
    }
}
