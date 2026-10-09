package com.wififiles.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Base64
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.json.JSONObject
import java.math.BigInteger
import java.net.Inet4Address
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

data class Pairing(val addresses: List<String>, val token: String, val certificate: String, val fingerprint: String, val tls: SSLContext, val id: String) {
    fun document(address: String = addresses.firstOrNull() ?: "127.0.0.1"): String = JSONObject()
        .put("version", 1).put("name", "${Build.MANUFACTURER} ${Build.MODEL}")
        .put("address", "https://$address:8443/").put("token", token).put("certificatePem", certificate).toString(2)
}

object PairingFactory {
    fun addresses(context: Context): List<String> {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        return manager.allNetworks.flatMap { network ->
            val capabilities = manager.getNetworkCapabilities(network)
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true && capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) != true) emptyList()
            else manager.getLinkProperties(network)?.linkAddresses.orEmpty().mapNotNull {
                (it.address as? Inet4Address)?.hostAddress?.takeIf { address -> address != "127.0.0.1" }
            }
        }.distinct()
    }
    fun create(context: Context): Pairing {
        val random = SecureRandom()
        val addresses = addresses(context)
        val secure = SecureStore(context)
        val identityPassword = "wifi-files-encrypted-identity".toCharArray()
        val identity = KeyStore.getInstance("PKCS12")
        val stored = secure.read("identity")
        if (stored != null) identity.load(stored.inputStream(), identityPassword) else {
            identity.load(null, null)
            val rootKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, random) }.generateKeyPair()
            val rootSubject = X500Name("CN=SFLink Identity ${java.util.UUID.randomUUID()}")
            val rootNow = System.currentTimeMillis()
            val rootBuilder = JcaX509v3CertificateBuilder(rootSubject, BigInteger(128, random).abs().add(BigInteger.ONE), Date(rootNow - 300_000), Date(rootNow + 10L * 365 * 24 * 60 * 60 * 1000), rootSubject, rootKey.public)
            rootBuilder.addExtension(Extension.basicConstraints, true, BasicConstraints(0))
            rootBuilder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign))
            val rootSigner = JcaContentSignerBuilder("SHA256withRSA").setProvider(BouncyCastleProvider()).build(rootKey.private)
            val root = JcaX509CertificateConverter().setProvider(BouncyCastleProvider()).getCertificate(rootBuilder.build(rootSigner))
            identity.setKeyEntry("identity", rootKey.private, identityPassword, arrayOf(root))
            val bytes = java.io.ByteArrayOutputStream(); identity.store(bytes, identityPassword); secure.write("identity", bytes.toByteArray())
        }
        val root = identity.getCertificate("identity") as java.security.cert.X509Certificate
        val rootPrivate = identity.getKey("identity", identityPassword) as java.security.PrivateKey
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, random) }.generateKeyPair()
        val subject = X500Name("CN=SFLink Android")
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(X500Name(root.subjectX500Principal.name), BigInteger(128, random).abs().add(BigInteger.ONE), Date(now - 300_000), Date(now + 7L * 24 * 60 * 60 * 1000), subject, key.public)
        builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames((addresses + "127.0.0.1").distinct().map { GeneralName(GeneralName.iPAddress, it) }.toTypedArray()))
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment))
        val provider = BouncyCastleProvider()
        val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider(provider).build(rootPrivate)
        val cert = JcaX509CertificateConverter().setProvider(provider).getCertificate(builder.build(signer))
        val password = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }.toCharArray()
        val store = KeyStore.getInstance("PKCS12").apply { load(null, null); setKeyEntry("session", key.private, password, arrayOf(cert, root)) }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
        val tls = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, random) }
        password.fill('\u0000')
        val pem = "-----BEGIN CERTIFICATE-----\n" + Base64.encodeToString(root.encoded, Base64.NO_WRAP).chunked(64).joinToString("\n") + "\n-----END CERTIFICATE-----\n"
        val token = Base64.encodeToString(ByteArray(32).also(random::nextBytes), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(root.encoded).joinToString(":") { "%02X".format(it) }
        val id = MessageDigest.getInstance("SHA-256").digest(pem.toByteArray()).joinToString("") { "%02x".format(it) }
        return Pairing(addresses, token, pem, fingerprint, tls, id)
    }
}
