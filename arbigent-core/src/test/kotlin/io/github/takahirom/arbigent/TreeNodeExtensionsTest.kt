package io.github.takahirom.arbigent

import maestro.TreeNode
import org.junit.Test
import kotlin.test.assertEquals

class TreeNodeExtensionsTest {
    @Test
    fun `findAllAiHints should collect hint from single node`() {
        val node = TreeNode(
            attributes = mutableMapOf("accessibilityText" to "[[aihint:Test hint]]"),
            children = emptyList(),
            clickable = false,
            enabled = true,
            focused = false,
            checked = false,
            selected = false
        )
        assertEquals(listOf("Test hint"), node.findAllAiHints())
    }

    @Test
    fun `findAllAiHints should collect hints from nested nodes`() {
        val child = TreeNode(
            attributes = mutableMapOf("accessibilityText" to "[[aihint:Child hint]]"),
            children = emptyList(),
            clickable = false,
            enabled = true,
            focused = false,
            checked = false,
            selected = false
        )
        val parent = TreeNode(
            attributes = mutableMapOf("accessibilityText" to "[[aihint:Parent hint]]"),
            children = listOf(child),
            clickable = false,
            enabled = true,
            focused = false,
            checked = false,
            selected = false
        )
        assertEquals(listOf("Parent hint", "Child hint"), parent.findAllAiHints())
    }

    @Test
    fun `findAllAiHints should ignore non-hint text`() {
        val node = TreeNode(
            attributes = mutableMapOf("accessibilityText" to "Regular text"),
            children = emptyList(),
            clickable = false,
            enabled = true,
            focused = false,
            checked = false,
            selected = false
        )
        assertEquals(emptyList(), node.findAllAiHints())
    }

    @Test
    fun `findAllAiHints should handle missing accessibilityText`() {
        val node = TreeNode(
            attributes = mutableMapOf(),
            children = emptyList(),
            clickable = false,
            enabled = true,
            focused = false,
            checked = false,
            selected = false
        )
        assertEquals(emptyList(), node.findAllAiHints())
    }

    @Test
    fun `findAllAiHints should collect multiple hints from deep tree`() {
        val grandchild = TreeNode(
            attributes = mutableMapOf("accessibilityText" to "[[aihint:Grandchild hint]]"),
            children = emptyList(),
            clickable = false,
            enabled = true,
            focused = false,
            checked = false,
            selected = false
        )
        val child1 = TreeNode(
            attributes = mutableMapOf("accessibilityText" to "[[aihint:Child1 hint]]"),
            children = listOf(grandchild),
            clickable = false,
            enabled = true,
            focused = false,
            checked = false,
            selected = false
        )
        val child2 = TreeNode(
            attributes = mutableMapOf("accessibilityText" to "Regular text"),
            children = emptyList(),
            clickable = false,
            enabled = true,
            focused = false,
            checked = false,
            selected = false
        )
        val root = TreeNode(
            attributes = mutableMapOf("accessibilityText" to "[[aihint:Root hint]]"),
            children = listOf(child1, child2),
            clickable = false,
            enabled = true,
            focused = false,
            checked = false,
            selected = false
        )
        assertEquals(listOf("Root hint", "Child1 hint", "Grandchild hint"), root.findAllAiHints())
    }

    @Test
    fun `findAllAiHints should extract hint with existing contentDescription`() {
        val node = TreeNode(
            attributes = mutableMapOf("accessibilityText" to "Play button [[aihint:Video player, buffering]]"),
            children = emptyList(),
            clickable = false,
            enabled = true,
            focused = false,
            checked = false,
            selected = false
        )
        assertEquals(listOf("Video player, buffering"), node.findAllAiHints())
    }

    @Test
    fun `findAllAiHints should handle JSON content`() {
        val node = TreeNode(
            attributes = mutableMapOf("accessibilityText" to """[[aihint:{"screen":"player","state":"buffering"}]]"""),
            children = emptyList(),
            clickable = false,
            enabled = true,
            focused = false,
            checked = false,
            selected = false
        )
        assertEquals(listOf("""{"screen":"player","state":"buffering"}"""), node.findAllAiHints())
    }

    @Test
    fun `findAllAiHints should extract only first hint when multiple hints in single node`() {
        val node = TreeNode(
            attributes = mutableMapOf("accessibilityText" to "[[aihint:First hint]] Some text [[aihint:Second hint]]"),
            children = emptyList(),
            clickable = false,
            enabled = true,
            focused = false,
            checked = false,
            selected = false
        )
        // Only the first hint is extracted (one hint per node is the expected usage)
        assertEquals(listOf("First hint"), node.findAllAiHints())
    }

    @Test
    fun `findAllAiHints should return empty list for unclosed hint`() {
        val node = TreeNode(
            attributes = mutableMapOf("accessibilityText" to "[[aihint:Missing closing bracket"),
            children = emptyList(),
            clickable = false,
            enabled = true,
            focused = false,
            checked = false,
            selected = false
        )
        assertEquals(emptyList(), node.findAllAiHints())
    }

    @Test
    fun `findAllAiHints should return empty list for missing opening bracket`() {
        val node = TreeNode(
            attributes = mutableMapOf("accessibilityText" to "Missing opening bracket]]"),
            children = emptyList(),
            clickable = false,
            enabled = true,
            focused = false,
            checked = false,
            selected = false
        )
        assertEquals(emptyList(), node.findAllAiHints())
    }

    @Test
    fun `findAllAiHints should handle empty hint content`() {
        val node = TreeNode(
            attributes = mutableMapOf("accessibilityText" to "[[aihint:]]"),
            children = emptyList(),
            clickable = false,
            enabled = true,
            focused = false,
            checked = false,
            selected = false
        )
        assertEquals(listOf(""), node.findAllAiHints())
    }

    @Test
    fun `findAllAiHints should return empty list for prefix only`() {
        val node = TreeNode(
            attributes = mutableMapOf("accessibilityText" to "[[aihint:"),
            children = emptyList(),
            clickable = false,
            enabled = true,
            focused = false,
            checked = false,
            selected = false
        )
        assertEquals(emptyList(), node.findAllAiHints())
    }
}
