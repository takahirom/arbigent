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
    // If the jsEngine is already initialized, we don't need to reinitialize it
    val shouldJsReinit = if (orchestra::class.java.getDeclaredField("jsEngine").apply {
        isAccessible = true
      }.get(orchestra) != null) {
      false
    } else {
      true
    }
    orchestra.executeCommands(commands, shouldReinitJsEngine = shouldJsReinit)
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
        it.attributes["resource-id"]?.contains("status_bar_container").let {
            if(it != null) !it
            else true
        }
      }
      .map { it.optimizeTree(false, meaningfulAttributes, viewHierarchy) }
    val optimizedChildren = childResults.flatMap {
      it.node?.let { node -> listOf(node) } ?: it.promotedChildren
    }
    val hasContent = children.isNotEmpty()
            || (attributes.keys.any { it in meaningfulAttributes })
    if(!hasContent) {
        println("Node has no content: $this viewHierarchy.isVisible(this):${viewHierarchy.isVisible(this)}")
    }
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
      val className = attributes["class"]?.substringAfterLast('.') ?: "Node"
      appendString("$className(")
//    appendString("attributes=$attributes, ")
      if (attributes.isNotEmpty()) {
//      appendString("attr={")
        attributes.forEach { (key, value) ->
          if (key == "class") {
            // Skip class name
          } else if (key == "resource-id" && value.isNotBlank()) {
            appendString("id=${value.substringAfterLast('/')}, ")
          } else if (key == "clickable") {
            // Skip clickable
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
      val clickable = clickable ?: attributes["clickable"]
      if (clickable != null) {
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
    val optimizedToString = optimizedTree?.optimizedToString(depth = 0)
    println("After optimization (length): ${optimizedToString?.length}")
    return optimizedToString ?: ""
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
