package com.wififiles.android

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class FileStorageTest {
    @Test fun rejectsTraversalAndProtectedDirectories() {
        val root = Files.createTempDirectory("wifi-test").toFile()
        try {
            val storage = FileStorage(root)
            for (path in listOf("../escape", "/../escape", "/Android/data/secret", "/a\\b", "/.wifi-upload-private")) {
                try { storage.resolve(path); fail(path) } catch (_: ApiFailure) { }
            }
            assertEquals(root.canonicalFile, storage.resolve("/"))
        } finally { root.deleteRecursively() }
    }
    @Test fun rejectsNamesReservedByWindows() {
        for (name in listOf("CON.mp3", "../escape", "a:b", "trailing.", "NUL")) {
            try { FileStorage.validateName(name); fail(name) } catch (_: ApiFailure) { }
        }
        FileStorage.validateName("Minha música.mp3")
    }
    @Test fun refusesRenamingOverAnExistingFile() {
        val root = Files.createTempDirectory("wifi-test").toFile()
        try {
            val storage = FileStorage(root)
            root.resolve("first.txt").writeText("first")
            root.resolve("second.txt").writeText("preserve")
            try { storage.action("rename", "/first.txt", "second.txt"); fail("Overwrite accepted") } catch (error: ApiFailure) { assertEquals(409, error.status) }
            assertEquals("preserve", root.resolve("second.txt").readText())
            storage.action("rename", "/first.txt", "renamed.txt")
            assertEquals("first", root.resolve("renamed.txt").readText())
        } finally { root.deleteRecursively() }
    }
    @Test fun movesFileWithoutReplacingDestination() {
        val root = Files.createTempDirectory("wifi-test").toFile()
        try {
            val storage = FileStorage(root)
            val source = root.resolve("Download").apply { mkdir() }.resolve("Minha música.mp3")
            val target = root.resolve("Music").apply { mkdir() }.resolve(source.name)
            source.writeText("original")
            target.writeText("preserve")

            assertEquals(409, assertThrows(ApiFailure::class.java) {
                storage.move("/Download/${source.name}", "/Music")
            }.status)
            assertEquals("original", source.readText())
            assertEquals("preserve", target.readText())
            assertEquals(403, assertThrows(ApiFailure::class.java) {
                storage.move("/Download/${source.name}", "/Android")
            }.status)

            assertTrue(target.delete())
            storage.move("/Download/${source.name}", "/Music")
            assertFalse(source.exists())
            assertEquals("original", target.readText())
        } finally { root.deleteRecursively() }
    }
}
