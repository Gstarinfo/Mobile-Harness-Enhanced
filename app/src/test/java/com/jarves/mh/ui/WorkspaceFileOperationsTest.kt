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
}
