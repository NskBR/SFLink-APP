package com.wififiles.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** App-private files, authenticated encryption, key held by Android Keystore. */
class SecureStore(context: Context) {
    companion object { private val keyLock = Any() }
    private val directory = File(context.noBackupFilesDir, "trusted").apply { mkdirs() }
    private fun key(): SecretKey = synchronized(keyLock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("wifi-files-storage", null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("wifi-files-storage", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
    @Synchronized fun read(name: String): ByteArray? {
        val file = AtomicFile(File(directory, name))
        if (!file.baseFile.exists() && !File(directory, "$name.bak").exists()) return null
        val bytes = file.readFully()
        require(bytes.size in 29..65536) { "Dados salvos inválidos." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(name.toByteArray())
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size))
    }
    @Synchronized fun write(name: String, bytes: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key()); cipher.updateAAD(name.toByteArray())
        val encrypted = cipher.iv + cipher.doFinal(bytes)
        val file = AtomicFile(File(directory, name))
        val stream = file.startWrite()
        try { stream.write(encrypted); file.finishWrite(stream) } catch (error: Exception) { file.failWrite(stream); throw error }
    }
}
