package com.wififiles.android

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Comparator

class ApiFailure(val status: Int, message: String) : Exception(message)

class FileStorage(private val root: File) {
    private val canonicalRoot = root.canonicalFile
    val mutationLock = Any()
    fun resolve(virtual: String, allowRoot: Boolean = true): File {
        if (!virtual.startsWith('/') || virtual.contains('\\') || virtual.contains('\u0000')) throw ApiFailure(400, "Caminho inválido.")
        val parts = virtual.split('/').filter { it.isNotEmpty() }
        if (parts.any { it == "." || it == ".." || it.startsWith(".wifi-upload-") }) throw ApiFailure(403, "Pasta não autorizada.")
        if (parts.firstOrNull()?.equals("Android", ignoreCase = true) == true) throw ApiFailure(403, "Esta pasta é protegida pelo Android.")
        val file = File(canonicalRoot, parts.joinToString("/")).canonicalFile
        if (file != canonicalRoot && !file.path.startsWith(canonicalRoot.path + File.separator)) throw ApiFailure(403, "Pasta não autorizada.")
        if (!allowRoot && file == canonicalRoot) throw ApiFailure(403, "Não é possível modificar a raiz.")
        return file
    }
    fun virtual(file: File): String = "/" + file.canonicalFile.relativeTo(canonicalRoot).invariantSeparatorsPath.takeUnless { it == "." }.orEmpty()
    fun list(path: String): JSONObject {
        val folder = resolve(path)
        if (!folder.isDirectory) throw ApiFailure(404, "Pasta não encontrada.")
        val children = folder.listFiles() ?: throw ApiFailure(403, "Não foi possível ler esta pasta.")
        val entries = JSONArray()
        children.filter { !it.name.startsWith(".wifi-upload-") && !(folder == canonicalRoot && it.name.equals("Android", true)) }
            .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() }).forEach { child ->
                try {
                    val safe = resolve(virtual(child))
                    entries.put(JSONObject().put("name", child.name).put("path", virtual(safe)).put("isDir", safe.isDirectory)
                        .put("size", if (safe.isDirectory) 0 else safe.length()).put("modified", safe.lastModified() / 1000))
                } catch (_: Exception) { /* Restricted links and private Android areas are omitted. */ }
            }
        return JSONObject().put("path", virtual(folder)).put("parent", if (folder == canonicalRoot) JSONObject.NULL else virtual(folder.parentFile!!)).put("entries", entries)
    }
    fun action(action: String, path: String, name: String?) = synchronized(mutationLock) {
        when (action) {
            "mkdir" -> {
                validateName(name ?: throw ApiFailure(400, "Informe um nome."))
                val parent = resolve(path)
                if (!parent.isDirectory) throw ApiFailure(404, "Pasta não encontrada.")
                val target = resolve(virtual(File(parent, name)))
                if (target.exists()) throw ApiFailure(409, "Já existe um item com esse nome.")
                if (!target.mkdir()) throw ApiFailure(403, "Não foi possível criar a pasta.")
            }
            "rename" -> {
                validateName(name ?: throw ApiFailure(400, "Informe um nome."))
                val source = resolve(path, false)
                if (!source.exists()) throw ApiFailure(404, "Item não encontrado.")
                val destination = resolve(virtual(File(source.parentFile, name)), false)
                if (destination.exists()) throw ApiFailure(409, "Já existe um item com esse nome.")
                Files.move(source.toPath(), destination.toPath())
            }
            "delete" -> {
                val source = resolve(path, false)
                if (!source.exists()) throw ApiFailure(404, "Item não encontrado.")
                // Files.walk does not follow symbolic links.
                Files.walk(source.toPath()).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
            }
            else -> throw ApiFailure(400, "Operação inválida.")
        }
    }
    companion object {
        fun validateName(name: String) {
            if (name.isEmpty() || name.length > 240 || name != name.trim() || name.endsWith('.') || name == "." || name == ".." || name.any { it.code < 32 || it in "<>:\"/\\|?*" } || name.startsWith(".wifi-upload-")) throw ApiFailure(400, "Nome inválido.")
            val stem = name.substringBefore('.').uppercase()
            if (stem in setOf("CON", "PRN", "AUX", "NUL") || stem.matches(Regex("(COM|LPT)[1-9]"))) throw ApiFailure(400, "Nome reservado.")
        }
    }
}
