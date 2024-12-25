package com.github.takahirom.arbiter

import dadb.Dadb
import maestro.*
import maestro.drivers.AndroidDriver
import maestro.orchestra.*
import java.io.File
import java.util.concurrent.TimeoutException

interface Device {
  fun executeCommands(commands: List<MaestroCommand>)
  fun viewTreeString(): String
}

class MaestroDevice(
  private val maestro: Maestro,
  private val screenshotsDir: File = File("screenshots")
) : Device {
  private val orchestra = Orchestra(
    maestro = maestro,
    screenshotsDir = screenshotsDir
  )
  override fun executeCommands(commands: List<MaestroCommand>) {
    orchestra.executeCommands(commands)
  }

  override fun viewTreeString(): String {
    return maestro.viewHierarchy(false).toOptimizedString()
  }



  data class OptimizationResult(
    val node: TreeNode?,
    val promotedChildren: List<TreeNode>
  )

  fun TreeNode.optimizeTree(
    isRoot: Boolean = false,
    meaningfulAttributes: Set<String> = setOf("text", "content description", "hintText"),
    viewHierarchy: ViewHierarchy
  ): OptimizationResult {
    // Optimize children
    val childResults = children
      .filter {
        it.attributes["resource-id"] != "status_bar_container"
      }
      .map { it.optimizeTree(false, meaningfulAttributes, viewHierarchy) }
    val optimizedChildren = childResults.flatMap {
      it.node?.let { node -> listOf(node) } ?: it.promotedChildren
    }
    val hasContent = attributes.keys.any { it in meaningfulAttributes }
      && (children.isNotEmpty() || viewHierarchy.isVisible(this))
    val singleChild = optimizedChildren.singleOrNull()

    return when {
      (hasContent) || isRoot -> {
        // Keep the node with optimized children
        OptimizationResult(
          node = this.copy(children = optimizedChildren),
          promotedChildren = emptyList()
        )
      }

      optimizedChildren.isEmpty() -> {
        // Remove the node
        OptimizationResult(
          node = null,
          promotedChildren = emptyList()
        )
      }
      // If the node has only one child, promote it
      singleChild != null -> {
        OptimizationResult(
          node = singleChild,
          promotedChildren = emptyList()
        )
      }

      else -> {
        // Promote children
        OptimizationResult(
          node = null,
          promotedChildren = optimizedChildren
        )
      }
    }
  }

  fun TreeNode.optimizedToString(depth: Int, enableDepth: Boolean = false): String {
    /**
     * data class TreeNode(
     *     val attributes: MutableMap<String, String> = mutableMapOf(),
     *     val children: List<TreeNode> = emptyList(),
     *     val clickable: Boolean? = null,
     *     val enabled: Boolean? = null,
     *     val focused: Boolean? = null,
     *     val checked: Boolean? = null,
     *     val selected: Boolean? = null,
     * )
     * if it is not true, it shouldn't be included in the output
     */
    val blank = " ".repeat(depth)
    fun StringBuilder.appendString(str: String) {
      if (enableDepth) {
        append(blank)
        appendLine(str)
      } else {
        append(str)
      }
    }
    return buildString {
      appendString("Node(")
//    appendString("attributes=$attributes, ")
      if (attributes.isNotEmpty()) {
//      appendString("attr={")
        attributes.forEach { (key, value) ->
          if (key == "class") {
            appendString("class=${value.substringAfterLast('.')}, ")
          } else if (key == "resource-id" && value.isNotBlank()) {
            appendString("id=${value.substringAfterLast('/')}, ")
          } else if (key == "clickable") {
            appendString("clickable=$value, ")
          } else if (key == "enabled") {
            if (value == "false") {
              appendString("enabled=$value, ")
            }
          } else if (value.isNotBlank() && value != "null" && value != "false") {
            appendString("$key=$value, ")
          }
        }
//      appendString("}, ")
      }
      if (clickable != null && clickable!!) {
        appendString("clickable=$clickable, ")
      }
      if (enabled != null && !enabled!!) {
        appendString("enabled=$enabled, ")
      }
      if (focused != null && focused!!) {
        appendString("focused=$focused, ")
      }
      if (checked != null && checked!!) {
        appendString("checked=$checked, ")
      }
      if (selected != null && selected!!) {
        appendString("selected=$selected, ")
      }
      if (children.isNotEmpty()) {
        appendString("children(${children.size})=[")
        children.forEachIndexed { index, child ->
          append(child.optimizedToString(depth + 1))
          if (index < children.size - 1) {
            appendString(", ")
          }
        }
        appendString("], ")
      }
      appendString(")")
    }
  }

  fun ViewHierarchy.toOptimizedString(
    meaningfulAttributes: Set<String> = setOf("text", "content description", "hintText")
  ): String {
    val root = root
    val result = root.optimizeTree(
      isRoot = true,
      viewHierarchy = this,
      meaningfulAttributes = meaningfulAttributes
    )
    val optimizedTree = result.node ?: result.promotedChildren.firstOrNull()
    println("Before optimization (length): ${this.toString().length}")
    println("After optimization (length): ${optimizedTree?.optimizedToString(depth = 0)?.length}")
    return optimizedTree?.optimizedToString(depth = 0) ?: ""
  }
}

val maestroInstance: Maestro by lazy {
  var dadb = Dadb.discover()!!
  val maestro = retry {
    val driver = AndroidDriver(
      dadb
    )
    try {
      dadb.close()
      dadb = Dadb.discover()!!
      Maestro.android(
        driver
      )
    } catch (e: Exception) {
      driver.close()
      dadb.close()
      throw e
    }
  }
  maestro
}

private fun <T> retry(times: Int = 5, block: () -> T): T {
  repeat(times) {
    try {
      return block()
    } catch (e: Exception) {
      println("Failed to run agent: $e")
      e.printStackTrace()
    }
  }

  throw TimeoutException("Failed to run agent")
}



fun closeMaestro() {
  (maestroInstance as Maestro).close()
}
