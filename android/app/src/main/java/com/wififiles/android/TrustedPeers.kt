package com.wififiles.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

data class TrustedPeer(val id: String, val name: String, val token: String)

class TrustedPeers(context: Context) {
    private val store = SecureStore(context)
    private var peers = store.read("peers")?.let { bytes ->
        val array = JSONArray(String(bytes))
        (0 until array.length()).map { i -> array.getJSONObject(i).let { TrustedPeer(it.getString("id"), it.getString("name"), it.getString("token")) } }
    }.orEmpty()
    @Synchronized fun list(): List<TrustedPeer> = peers.toList()
    @Synchronized fun remember(id: String, name: String): TrustedPeer {
        require(id.matches(Regex("[a-f0-9]{64}")))
        if (peers.size >= 20 && peers.none { it.id == id }) throw ApiFailure(409, "Limite de computadores lembrados. Esqueça um dispositivo primeiro.")
        val token = android.util.Base64.encodeToString(ByteArray(32).also(SecureRandom()::nextBytes), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        val peer = TrustedPeer(id, name.take(80), token)
        val next = peers.filterNot { it.id == id } + peer
        save(next); peers = next
        return peer
    }
    @Synchronized fun forget(id: String) { val next = peers.filterNot { it.id == id }; save(next); peers = next }
    @Synchronized fun authenticate(token: String): TrustedPeer? = peers.firstOrNull { MessageDigest.isEqual(it.token.toByteArray(), token.toByteArray()) }
    private fun save(next: List<TrustedPeer>) { store.write("peers", JSONArray().apply { next.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("token", it.token)) } }.toString().toByteArray()) }
}
