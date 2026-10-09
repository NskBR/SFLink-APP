package com.wififiles.android

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

internal data class UpdateOffer(val version: String, val name: String, val url: String, val size: Long, val hash: String, val notes: String)
internal object UpdateRules {
    const val REPO = "NskBR/SFLink-APP"
    const val MAX_SIZE = 512L * 1024 * 1024
    fun version(raw: String): List<Long>? {
        val value = raw.removePrefix("v")
        if (!Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(value)) return null
        return value.split('.').map { it.toLongOrNull() ?: return null }
    }
    fun newer(latest: String, current: String): Boolean {
        val a = version(latest) ?: return false; val b = version(current) ?: return false
        for (i in 0..2) { if (a[i] != b[i]) return a[i] > b[i] }; return false
    }
    fun validAsset(name: String, url: String, size: Long, digest: String): Boolean {
        val parsed = runCatching { URL(url) }.getOrNull() ?: return false
        return parsed.protocol == "https" && parsed.host == "github.com" && parsed.port == -1 && parsed.userInfo == null && parsed.query == null && parsed.ref == null &&
            parsed.path.startsWith("/$REPO/releases/download/") && parsed.path.endsWith("/$name") &&
            Regex("SFLink_[0-9]+\\.[0-9]+\\.[0-9]+_android\\.apk").matches(name) && size in 1..MAX_SIZE && Regex("sha256:[a-fA-F0-9]{64}").matches(digest)
    }
}

internal class AppUpdater(context: Context) {
    private val context = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    var offer by mutableStateOf<UpdateOffer?>(null); private set
    var busy by mutableStateOf(false); private set
    var downloading by mutableStateOf(false); private set
    var ready by mutableStateOf(false); private set
    var progress by mutableStateOf(0f); private set
    var message by mutableStateOf(""); private set
    var error by mutableStateOf(""); private set
    @Volatile private var cancelled = false
    private var verifiedFile: File? = null

    private fun connection(value: String): HttpsURLConnection {
        var url = URL(value)
        repeat(6) {
            require(url.protocol == "https") { "Atualizações exigem HTTPS." }
            val connection = (url.openConnection() as HttpsURLConnection).apply {
                connectTimeout = 15000; readTimeout = 30000; instanceFollowRedirects = false
                setRequestProperty("User-Agent", "SFLink-updater"); setRequestProperty("Accept", "application/vnd.github+json")
            }
            val code = connection.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val location = connection.getHeaderField("Location"); connection.disconnect()
                require(location != null) { "Redirecionamento inválido." }; url = URL(url, location)
            } else return connection
        }
        error("Redirecionamentos demais.")
    }
    fun check() {
        if (busy || ready) return
        busy = true; error = ""; message = ""; offer = null
        executor.execute {
            val result = runCatching {
                val connection = connection("https://api.github.com/repos/${UpdateRules.REPO}/releases/latest")
                try {
                    if (connection.responseCode == 404) return@runCatching null
                    require(connection.responseCode == 200) { "GitHub respondeu HTTP ${connection.responseCode}. Tente novamente depois." }
                    val bytes = connection.inputStream.use { input ->
                        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                        while (true) { val count = input.read(buffer); if (count < 0) break; require(output.size() + count <= 2 * 1024 * 1024) { "Resposta de atualização grande demais." }; output.write(buffer, 0, count) }; output.toByteArray()
                    }
                    require(bytes.size <= 2 * 1024 * 1024) { "Resposta de atualização grande demais." }
                    val release = JSONObject(String(bytes, Charsets.UTF_8))
                    val version = release.getString("tag_name").removePrefix("v")
                    require(UpdateRules.version(version) != null) { "A release precisa usar uma versão como v0.2.1." }
                    if (release.optBoolean("draft") || release.optBoolean("prerelease") || !UpdateRules.newer(version, BuildConfig.VERSION_NAME)) return@runCatching null
                    val assets = release.getJSONArray("assets")
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i); val name = asset.optString("name"); val url = asset.optString("browser_download_url")
                        val size = asset.optLong("size"); val digest = asset.optString("digest")
                        if (name == "SFLink_${version}_android.apk" && UpdateRules.validAsset(name, url, size, digest)) return@runCatching UpdateOffer(version, name, url, size, digest.removePrefix("sha256:").lowercase(), release.optString("body"))
                    }
                    error("A release ainda não contém um APK Android com SHA-256 válido.")
                } finally { connection.disconnect() }
            }
            main.post { busy = false; result.onSuccess { offer = it; message = if (it == null) "Nenhuma atualização pública disponível." else "Versão ${it.version} disponível." }.onFailure { error = it.message ?: "Não foi possível verificar atualizações." } }
        }
    }
    fun cancel() { cancelled = true }
    fun download() {
        val approved = offer ?: return
        if (busy) return
        busy = true; downloading = true; cancelled = false; error = ""; message = ""; ready = false; progress = 0f; verifiedFile = null
        executor.execute {
            val root = File(context.cacheDir, "updates").apply { mkdirs() }
            val partial = File(root, "update.part")
            val destination = File(root, "update.apk")
            val result = runCatching {
                destination.delete()
                val connection = connection(approved.url)
                try {
                    require(connection.responseCode == 200) { "Não foi possível baixar o APK." }
                    val hash = MessageDigest.getInstance("SHA-256"); var count = 0L; var last = 0L
                    connection.inputStream.use { input -> partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            check(!cancelled) { "Download cancelado." }
                            val read = input.read(buffer); if (read < 0) break
                            count += read; require(count <= approved.size) { "O APK excedeu o tamanho publicado." }
                            hash.update(buffer, 0, read); output.write(buffer, 0, read)
                            if (System.currentTimeMillis() - last > 150) { val value = count.toFloat() / approved.size; main.post { progress = value }; last = System.currentTimeMillis() }
                        }
                        output.fd.sync()
                    } }
                    check(!cancelled) { "Download cancelado." }
                    require(count == approved.size && hash.digest().joinToString("") { "%02x".format(it) } == approved.hash) { "A verificação SHA-256 do APK falhou." }
                    verifyPackage(partial)
                    check(partial.renameTo(destination)) { "Não foi possível guardar o APK verificado." }
                    destination
                } finally { connection.disconnect() }
            }
            partial.delete()
            main.post { busy = false; downloading = false; result.onSuccess { verifiedFile = it; ready = true; progress = 1f; message = "APK verificado. Confirme a instalação no Android." }.onFailure { error = it.message ?: "Falha ao baixar atualização." } }
        }
    }
    @Suppress("DEPRECATION")
    private fun verifyPackage(file: File) {
        val manager = context.packageManager
        val archive = manager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES) ?: error("APK inválido.")
        val installed = manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        require(archive.packageName == context.packageName && archive.versionName == offer?.version && archive.longVersionCode > installed.longVersionCode) { "O APK não é uma atualização deste aplicativo." }
        val incoming = archive.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
        val existing = installed.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
        require(existing.isNotEmpty() && incoming == existing) { "A assinatura do APK difere da versão instalada. Não desinstale para atualizar; use um APK com a mesma assinatura." }
    }
    fun install(activity: android.app.Activity, transferring: Boolean) {
        if (busy) return
        if (transferring) { error = "Aguarde as transferências terminarem antes de instalar."; return }
        val file = verifiedFile ?: return
        val expected = offer ?: return
        busy = true; error = ""
        executor.execute {
            val result = runCatching {
                val hash = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { val read = input.read(buffer); if (read < 0) break; hash.update(buffer, 0, read) } }
                require(hash.digest().joinToString("") { "%02x".format(it) } == expected.hash) { "O APK foi alterado após o download." }
                verifyPackage(file)
            }
            main.post {
                busy = false
                if (activity.isFinishing || activity.isDestroyed) return@post
                result.onFailure { error = it.message ?: "APK inválido." }.onSuccess {
                    runCatching {
                        check(Session.state.operation.isBlank()) { "Aguarde as transferências terminarem antes de instalar." }
                        if (!context.packageManager.canRequestPackageInstalls()) {
                            message = "Autorize o SFLink nesta tela, volte e toque em Instalar novamente."
                            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                        } else {
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                            activity.stopService(Intent(activity, SharingService::class.java))
                            activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        }
                    }.onFailure { error = it.message ?: "Não foi possível abrir o instalador do Android." }
                }
            }
        }
    }
    fun close() { cancelled = true; executor.shutdown() }
}
