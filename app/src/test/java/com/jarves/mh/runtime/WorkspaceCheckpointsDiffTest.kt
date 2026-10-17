package com.jarves.mh.runtime

import com.jarves.mh.model.DiffLineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceCheckpointsDiffTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val checkpoints by lazy {
        WorkspaceCheckpoints(tempFolder.newFolder("checkpoints"))
    }

    @Test
    fun `buildDiffLines orders deletions before additions on line replacements`() {
        val before = "val x = 1\n".toByteArray()
        val after = "val x = 2\n".toByteArray()

        val diff = checkpoints.buildDiffLines(before, after)

        assertEquals(2, diff.size)
        assertEquals(DiffLineType.DELETION, diff[0].type)
        assertEquals("val x = 1", diff[0].text)
        assertEquals(1, diff[0].oldLine)
        assertNull(diff[0].newLine)

        assertEquals(DiffLineType.ADDITION, diff[1].type)
        assertEquals("val x = 2", diff[1].text)
        assertNull(diff[1].oldLine)
        assertEquals(1, diff[1].newLine)
    }

    @Test
    fun `buildDiffLines normalizes CRLF and LF so pure line ending differences produce no diff`() {
        val before = "line 1\r\nline 2\r\n".toByteArray()
        val after = "line 1\nline 2\n".toByteArray()

        val diff = checkpoints.buildDiffLines(before, after)
        // With normalization, content lines are identical, so all lines are CONTEXT and collapsed
        assertEquals(0, diff.count { it.type == DiffLineType.ADDITION || it.type == DiffLineType.DELETION })
    }

    @Test
    fun `identical files return empty diff`() {
        val textBytes = "hello world\n".toByteArray()
        assertEquals(emptyList<Any>(), checkpoints.buildDiffLines(textBytes, textBytes))

        val binaryBytes = byteArrayOf(0, 1, 2, 3)
        assertEquals(emptyList<Any>(), checkpoints.buildDiffLines(binaryBytes, binaryBytes))
    }

    @Test
    fun `single unchanged line between changes is not collapsed into placeholder`() {
        // Line 3 is changed, line 5 is changed. Line 4 is a 1-line gap.
        val before = (1..7).joinToString("\n") { "line $it" }.toByteArray()
        val after = (1..7).map { if (it == 3 || it == 5) "MODIFIED $it" else "line $it" }.joinToString("\n").toByteArray()

        val diff = checkpoints.buildDiffLines(before, after)
        // Ensure no placeholder was generated for the 1-line gap
        assertTrue(diff.none { it.text.contains("1 unchanged lines") })
    }

    @Test
    fun `large unchanged section collapses properly`() {
        val beforeLines = (1..100).map { "line $it" }
        val afterLines = beforeLines.map { if (it == "line 50") "line 50 changed" else it }

        val diff = checkpoints.buildDiffLines(beforeLines.joinToString("\n").toByteArray(), afterLines.joinToString("\n").toByteArray())
        val infoLines = diff.filter { it.type == DiffLineType.INFO }
        assertTrue(infoLines.isNotEmpty())
        assertTrue(infoLines.any { it.text.contains("unchanged lines") })
    }
}
