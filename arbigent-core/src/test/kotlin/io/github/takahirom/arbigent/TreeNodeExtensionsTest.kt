package io.github.takahirom.arbigent

import maestro.TreeNode
import org.junit.Test
import kotlin.test.assertEquals

class TreeNodeExtensionsTest {
    @Test
    fun `findAllArbigentHints should collect hint from single node`() {
        val node = TreeNode(
            attributes = mutableMapOf("accessibilityText" to "ArbigentHint:Test hint"),
            children = emptyList(),
            clickable = false,
            enabled = true,
            focused = false,
            checked = false,
            selected = false
        )
        assertEquals(listOf("Test hint"), node.findAllArbigentHints())
    }

    @Test
    fun `findAllArbigentHints should collect hints from nested nodes`() {
        val child = TreeNode(
            attributes = mutableMapOf("accessibilityText" to "ArbigentHint:Child hint"),
            children = emptyList(),
            clickable = false,
            enabled = true,
            focused = false,
            checked = false,
            selected = false
        )
        val parent = TreeNode(
            attributes = mutableMapOf("accessibilityText" to "ArbigentHint:Parent hint"),
            children = listOf(child),
            clickable = false,
            enabled = true,
            focused = false,
            checked = false,
            selected = false
        )
        assertEquals(listOf("Parent hint", "Child hint"), parent.findAllArbigentHints())
    }

    @Test
    fun `findAllArbigentHints should ignore non-hint text`() {
        val node = TreeNode(
            attributes = mutableMapOf("accessibilityText" to "Regular text"),
            children = emptyList(),
            clickable = false,
            enabled = true,
            focused = false,
            checked = false,
            selected = false
        )
        assertEquals(emptyList(), node.findAllArbigentHints())
    }

    @Test
    fun `findAllArbigentHints should handle missing accessibilityText`() {
        val node = TreeNode(
            attributes = mutableMapOf(),
            children = emptyList(),
            clickable = false,
            enabled = true,
            focused = false,
            checked = false,
            selected = false
        )
        assertEquals(emptyList(), node.findAllArbigentHints())
    }

    @Test
    fun `findAllArbigentHints should collect multiple hints from deep tree`() {
        val grandchild = TreeNode(
            attributes = mutableMapOf("accessibilityText" to "ArbigentHint:Grandchild hint"),
            children = emptyList(),
            clickable = false,
            enabled = true,
            focused = false,
            checked = false,
            selected = false
        )
        val child1 = TreeNode(
            attributes = mutableMapOf("accessibilityText" to "ArbigentHint:Child1 hint"),
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
            attributes = mutableMapOf("accessibilityText" to "ArbigentHint:Root hint"),
            children = listOf(child1, child2),
            clickable = false,
            enabled = true,
            focused = false,
            checked = false,
            selected = false
        )
        assertEquals(listOf("Root hint", "Child1 hint", "Grandchild hint"), root.findAllArbigentHints())
    }
}
