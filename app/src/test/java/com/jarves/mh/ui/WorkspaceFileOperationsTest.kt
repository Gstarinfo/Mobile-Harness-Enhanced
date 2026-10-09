package com.jarves.mh.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class WorkspaceFileOperationsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `resolveWorkspaceFile resolves valid nested path`() {
        val root = tempFolder.newFolder("workspace")
        val subDir = File(root, "src").apply { mkdirs() }
        val testFile = File(subDir, "Main.kt").apply { writeText("println(1)") }

        val resolved = resolveWorkspaceFile(root, "src/Main.kt")
        assertEquals(testFile.canonicalPath, resolved.canonicalPath)
        assertTrue(resolved.isFile)
    }

    @Test
    fun `resolveWorkspaceFile prevents escaping workspace root via parent directory traversal`() {
        val root = tempFolder.newFolder("workspace")
        tempFolder.newFile("secret.txt").writeText("secret")

        try {
            resolveWorkspaceFile(root, "../secret.txt")
            fail("Expected IllegalArgumentException for path traversal escaping workspace")
        } catch (e: IllegalArgumentException) {
            assertEquals("Path outside project workspace", e.message)
        }
    }

    @Test
    fun `resolveWorkspaceFile prevents escaping via deeply nested parent segments`() {
        val root = tempFolder.newFolder("workspace")
        val nested = File(root, "a/b/c").apply { mkdirs() }

        try {
            resolveWorkspaceFile(root, "a/b/c/../../../../etc/passwd")
            fail("Expected IllegalArgumentException for deeply nested traversal")
        } catch (e: IllegalArgumentException) {
            assertEquals("Path outside project workspace", e.message)
        }
    }

    @Test
    fun `export single file streams full contents without truncation`() {
        val root = tempFolder.newFolder("workspace")
        val file = File(root, "sample.txt")
        val fileContent = "A".repeat(10_000)
        file.writeText(fileContent)

        val resolved = resolveWorkspaceFile(root, "sample.txt")
        assertTrue(resolved.isFile)

        val output = ByteArrayOutputStream()
        output.use { out ->
            resolved.inputStream().use { input ->
                input.copyTo(out)
            }
        }

        assertEquals(fileContent, output.toString(Charsets.UTF_8.name()))
    }

    @Test
    fun `saving workspace file updates file content`() {
        val root = tempFolder.newFolder("workspace")
        val target = resolveWorkspaceFile(root, "hello.py")
        assertFalse(target.exists())

        target.parentFile?.mkdirs()
        target.writeText("print('hello world')")

        assertTrue(target.exists())
        assertEquals("print('hello world')", target.readText())
    }

    @Test
    fun `deleting workspace file removes the file`() {
        val root = tempFolder.newFolder("workspace")
        val target = resolveWorkspaceFile(root, "to_delete.txt")
        target.writeText("delete me")
        assertTrue(target.exists())

        target.delete()
        assertFalse(target.exists())
    }

    @Test
    fun `export binary file streams full binary contents with exact byte match`() {
        val root = tempFolder.newFolder("workspace")
        val apkDir = File(root, "app/build/outputs/apk/debug").apply { mkdirs() }
        val apkFile = File(apkDir, "app-debug.apk")

        // Generate 128KB of non-text binary data with null bytes, 0xFF, random patterns
        val binaryData = ByteArray(128 * 1024) { (it xor (it shr 8)).toByte() }
        apkFile.writeBytes(binaryData)

        val resolved = resolveWorkspaceFile(root, "app/build/outputs/apk/debug/app-debug.apk")
        assertTrue(resolved.isFile)
        assertEquals(binaryData.size.toLong(), resolved.length())

        val output = ByteArrayOutputStream()
        output.use { out ->
            resolved.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                var read: Int
                var written = 0L
                while (input.read(buffer).also { read = it } != -1) {
                    out.write(buffer, 0, read)
                    written += read
                }
                out.flush()
                assertEquals(resolved.length(), written)
            }
        }

        assertTrue(binaryData.contentEquals(output.toByteArray()))
    }

    @Test
    fun `mimeTypeForFileName resolves correct mime types`() {
        assertEquals("application/vnd.android.package-archive", mimeTypeForFileName("app-debug.apk"))
        assertEquals("application/zip", mimeTypeForFileName("project.zip"))
        assertEquals("application/json", mimeTypeForFileName("config.json"))
        assertEquals("image/png", mimeTypeForFileName("icon.png"))
        assertEquals("text/plain", mimeTypeForFileName("Main.kt"))
        assertEquals("text/plain", mimeTypeForFileName("script.py"))
    }

    @Test
    fun `formatFileSize formats sizes correctly`() {
        assertEquals("500 B", formatFileSize(500L))
        assertEquals("1.5 KB", formatFileSize(1536L))
        assertEquals("10.0 MB", formatFileSize(10 * 1024 * 1024L))
        assertEquals("1.50 GB", formatFileSize((1.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun `determineOpenWithConfig configures APK for external installer`() {
        val config = determineOpenWithConfig("app-debug.apk")
        assertEquals("application/vnd.android.package-archive", config.mimeType)
        assertTrue(config.isApk)
        assertFalse(config.allowWrite)
    }

    @Test
    fun `determineOpenWithConfig configures source code and files for external editing`() {
        val ktConfig = determineOpenWithConfig("MainActivity.kt")
        assertEquals("text/plain", ktConfig.mimeType)
        assertFalse(ktConfig.isApk)
        assertTrue(ktConfig.allowWrite)

        val jsonConfig = determineOpenWithConfig("settings.json")
        assertEquals("application/json", jsonConfig.mimeType)
        assertFalse(jsonConfig.isApk)
        assertTrue(jsonConfig.allowWrite)
    }
}
