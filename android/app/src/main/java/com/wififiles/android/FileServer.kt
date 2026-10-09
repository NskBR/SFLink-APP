package com.wififiles.android

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.StatFs
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

class FileServer(private val context: Context, val pairing: Pairing, private val gate: PairingGate, private val trusted: TrustedPeers, private val onPeer: (String) -> Unit, private val onProgress: (String, Long, Long) -> Unit, private val onComplete: (String) -> Unit) {
    private class Reply(stream: java.io.OutputStream) : BufferedOutputStream(stream, 128 * 1024) { var started = false }
    private val storage = FileStorage(Environment.getExternalStorageDirectory())
    private val socket = (pairing.tls.serverSocketFactory.createServerSocket() as SSLServerSocket).apply {
        reuseAddress = true
        enabledProtocols = supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
        bind(InetSocketAddress("0.0.0.0", 8443))
    }
    private val workers = ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, ArrayBlockingQueue(8))
    private val clients = java.util.concurrent.ConcurrentHashMap.newKeySet<SSLSocket>()
    @Volatile private var running = true
    fun run() {
        while (running) {
            val client = try { socket.accept() as SSLSocket } catch (error: Exception) { if (!running) break else throw error }
            if (!client.inetAddress.isLoopbackAddress && !client.inetAddress.isSiteLocalAddress) { client.close(); continue }
            clients.add(client)
            try { workers.execute { handle(client) } } catch (_: Exception) { clients.remove(client); client.close() }
        }
    }
    fun stop() { running = false; socket.close(); clients.forEach { runCatching { it.close() } }; workers.shutdownNow() }
    fun revokeConnections() { clients.forEach { runCatching { it.close() } } }
    private fun handle(client: SSLSocket) {
        client.use {
            try {
                client.soTimeout = 20_000
                client.startHandshake()
                val input = BufferedInputStream(client.inputStream, 128 * 1024)
                val output = Reply(client.outputStream)
                try { serve(client.inetAddress, input, output) }
                catch (error: ApiFailure) { if (output.started) throw error else respond(output, error.status, JSONObject().put("error", error.message).toString().toByteArray()) }
                catch (error: Exception) { if (output.started) throw error else respond(output, 500, "{\"error\":\"Não foi possível concluir a operação.\"}".toByteArray()) }
                output.flush()
            } catch (_: Exception) { /* Disconnect and cancellation close the request and clean temporary files. */ }
            finally { clients.remove(client) }
        }
    }
    private fun line(input: BufferedInputStream): String {
        val bytes = java.io.ByteArrayOutputStream()
        while (bytes.size() < 8192) {
            val value = input.read()
            if (value < 0) throw EOFException()
            if (value == 10) return bytes.toString("UTF-8").removeSuffix("\r")
            bytes.write(value)
        }
        throw ApiFailure(400, "Requisição inválida.")
    }
    private fun serve(peer: InetAddress, input: BufferedInputStream, output: BufferedOutputStream) {
        if (!Environment.isExternalStorageManager()) throw ApiFailure(403, "Autorize o acesso aos arquivos no Android.")
        val request = line(input).split(' ')
        if (request.size != 3 || request[2] != "HTTP/1.1") throw ApiFailure(400, "Requisição inválida.")
        val headers = mutableMapOf<String, String>()
        var count = 0
        while (true) {
            val line = line(input)
            if (line.isEmpty()) break
            if (++count > 40 || ':' !in line) throw ApiFailure(400, "Cabeçalhos inválidos.")
            val key = line.substringBefore(':').lowercase()
            if (headers.containsKey(key)) throw ApiFailure(400, "Cabeçalhos repetidos.")
            headers[key] = line.substringAfter(':').trim()
        }
        if (request[0] == "POST" && request[1] == "/v1/pair") {
            if (headers.containsKey("transfer-encoding")) throw ApiFailure(400, "Dados inválidos.")
            val length = headers["content-length"]?.toIntOrNull()?.takeIf { it in 1..1024 } ?: throw ApiFailure(400, "Dados inválidos.")
            val buffer = java.io.ByteArrayOutputStream()
            copy(input, buffer, length.toLong()) {}
            val data = JSONObject(buffer.toString("UTF-8"))
            val clientId = data.optString("clientId")
            val clientName = data.optString("clientName").ifBlank { peer.hostAddress ?: "PC" }
            val approval = gate.authorize(data.optString("code"), data.optString("nonce"), peer.hostAddress ?: "PC", clientId, clientName) ?: throw ApiFailure(403, "Conexão recusada ou aprovação expirada.")
            val remembered = approval.remember && approval.clientId.isNotEmpty()
            val token = if (remembered) trusted.remember(clientId, clientName).token else pairing.token
            Session.update { it.copy(trusted = trusted.list()) }
            json(output, JSONObject().put("token", token).put("remembered", remembered).put("id", pairing.id))
            return
        }
        val supplied = headers["authorization"].orEmpty().removePrefix("Bearer ")
        val rememberedPeer = trusted.authenticate(supplied)
        if (rememberedPeer == null && !MessageDigest.isEqual(supplied.toByteArray(), pairing.token.toByteArray())) throw ApiFailure(401, "Conexão não autorizada.")
        if (headers.containsKey("transfer-encoding")) throw ApiFailure(400, "Envie o tamanho do arquivo.")
        onPeer(rememberedPeer?.name ?: (peer.hostAddress ?: "PC"))
        val endpoint = request[1].substringBefore('?')
        val query = request[1].substringAfter('?', "").split('&').filter(String::isNotBlank).associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
        }
        val path = query["path"] ?: "/"
        when (request[0] to endpoint) {
            "GET" to "/v1/device" -> {
                val stats = StatFs(Environment.getExternalStorageDirectory().path)
                json(output, JSONObject().put("name", "${Build.MANUFACTURER} ${Build.MODEL}").put("root", "/").put("freeBytes", stats.availableBytes).put("totalBytes", stats.totalBytes))
            }
            "GET" to "/v1/files" -> json(output, storage.list(path))
            "GET" to "/v1/content" -> {
                val file = storage.resolve(path, false)
                if (!file.isFile) throw ApiFailure(404, "Arquivo não encontrado.")
                val length = file.length()
                FileInputStream(file).use { source ->
                    head(output, 200, length, "application/octet-stream")
                    copy(source, output, length) { done -> onProgress("Enviando ${file.name}", done, length) }
                }
                onComplete("Enviado: ${file.name}")
            }
            "PUT" to "/v1/content" -> {
                if (query["overwrite"] != "false") throw ApiFailure(400, "Sobrescrita não permitida.")
                val length = headers["content-length"]?.toLongOrNull()?.takeIf { it >= 0 } ?: throw ApiFailure(400, "Tamanho inválido.")
                val target = storage.resolve(path, false)
                FileStorage.validateName(target.name)
                if (!target.parentFile!!.isDirectory) throw ApiFailure(404, "Pasta não encontrada.")
                if (target.exists()) throw ApiFailure(409, "Arquivo já existente.")
                if (length > StatFs(target.parentFile!!.path).availableBytes - 4L * 1024 * 1024) throw ApiFailure(507, "Espaço insuficiente.")
                val temporary = File(target.parentFile, ".wifi-upload-${UUID.randomUUID()}.part")
                try {
                    FileOutputStream(temporary).use { destination ->
                        copy(input, destination, length) { done -> onProgress("Recebendo ${target.name}", done, length) }
                        destination.fd.sync()
                    }
                    synchronized(storage.mutationLock) {
                        if (target.exists()) throw ApiFailure(409, "Arquivo já existente.")
                        Files.move(temporary.toPath(), target.toPath())
                    }
                    scan(target)
                    respond(output, 201, byteArrayOf())
                    onComplete("Recebido: ${target.name}")
                } finally { temporary.delete() }
            }
            "POST" to "/v1/actions" -> {
                val length = headers["content-length"]?.toIntOrNull()?.takeIf { it in 1..8192 } ?: throw ApiFailure(400, "Dados inválidos.")
                val buffer = java.io.ByteArrayOutputStream()
                copy(input, buffer, length.toLong()) {}
                val action = JSONObject(buffer.toString("UTF-8"))
                val actionPath = action.getString("path")
                storage.action(action.getString("action"), actionPath, if (action.isNull("name")) null else action.getString("name"))
                val changed = if (action.getString("action") == "rename") File(storage.resolve(actionPath).parentFile, action.getString("name")) else storage.resolve(actionPath)
                scan(changed)
                respond(output, 204, byteArrayOf())
                onComplete(when (action.getString("action")) { "mkdir" -> "Pasta criada: ${action.optString("name")}"; "rename" -> "Renomeado: ${action.optString("name")}"; else -> "Item excluído" })
            }
            else -> throw ApiFailure(404, "Operação não encontrada.")
        }
    }
    private fun copy(input: java.io.InputStream, output: java.io.OutputStream, length: Long, progress: (Long) -> Unit) {
        val buffer = ByteArray(128 * 1024)
        var transferred = 0L
        var last = 0L
        while (transferred < length) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), length - transferred).toInt())
            if (read < 0) throw EOFException("Transferência interrompida.")
            output.write(buffer, 0, read)
            transferred += read
            val now = System.currentTimeMillis()
            if (now - last > 120 || transferred == length) { progress(transferred); last = now }
        }
        output.flush()
    }
    private fun scan(file: File) { MediaScannerConnection.scanFile(context, arrayOf(file.path), null, null) }
    private fun json(output: BufferedOutputStream, data: JSONObject) = respond(output, 200, data.toString().toByteArray())
    private fun respond(output: BufferedOutputStream, status: Int, data: ByteArray) { head(output, status, data.size.toLong(), "application/json; charset=utf-8"); output.write(data) }
    private fun head(output: BufferedOutputStream, status: Int, length: Long, type: String) {
        if (output is Reply) output.started = true
        val label = mapOf(200 to "OK", 201 to "Created", 204 to "No Content", 400 to "Bad Request", 401 to "Unauthorized", 403 to "Forbidden", 404 to "Not Found", 409 to "Conflict", 500 to "Internal Server Error", 507 to "Insufficient Storage")[status] ?: "Error"
        output.write("HTTP/1.1 $status $label\r\nContent-Type: $type\r\nContent-Length: $length\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n".toByteArray())
    }
}
