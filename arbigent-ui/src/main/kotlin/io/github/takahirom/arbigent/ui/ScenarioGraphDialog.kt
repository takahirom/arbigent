package io.github.takahirom.arbigent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.dk.kuiver.model.KuiverEdge
import com.dk.kuiver.model.KuiverNode
import com.dk.kuiver.model.NodeDimensions
import com.dk.kuiver.model.buildKuiver
import com.dk.kuiver.model.layout.LayoutConfig
import com.dk.kuiver.model.layout.LayoutDirection
import com.dk.kuiver.renderer.KuiverViewer
import com.dk.kuiver.renderer.KuiverViewerConfig
import com.dk.kuiver.rememberKuiverViewerState
import com.dk.kuiver.ui.EdgeContent
import androidx.compose.animation.core.snap
import io.github.takahirom.arbigent.ArbigentScenarioGraph
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.typography

private fun colorFromHex(hex: String): Color =
  Color(0xFF000000L or hex.removePrefix("#").toLong(16))

private val scenarioNodeColor = Color(0xFFF5F5F5)
private val scenarioBorderColor = Color(0xFF9E9E9E)
private val reusableNodeColor = colorFromHex(ArbigentScenarioGraph.REUSABLE_FILL_HEX)
private val reusableBorderColor = colorFromHex(ArbigentScenarioGraph.REUSABLE_STROKE_HEX)
private val dependencyEdgeColor = Color(0xFF616161)
private val callEdgeColor = reusableBorderColor

/** Per-[ArbigentScenarioGraph.NodeKind] rendering, resolved in one place. */
private data class NodeStyle(
  val titlePrefix: String,
  val background: Color,
  val borderWidth: Dp,
  val borderColor: Color,
)

private val ArbigentScenarioGraph.NodeKind.style: NodeStyle
  get() = when (this) {
    ArbigentScenarioGraph.NodeKind.Scenario ->
      NodeStyle(titlePrefix = "", background = scenarioNodeColor, borderWidth = 1.dp, borderColor = scenarioBorderColor)
    ArbigentScenarioGraph.NodeKind.ReusableCall ->
      NodeStyle(titlePrefix = "↻ ", background = reusableNodeColor, borderWidth = 2.dp, borderColor = reusableBorderColor)
  }

/**
 * Zoomable/pannable view of the scenario graph: dependency edges between scenarios and
 * reusable calls expanded per call site (same model as `arbigent graph` in the CLI).
 * Snapshot of the project at open time; reopen to refresh.
 */
@Composable
fun ScenarioGraphDialog(
  appStateHolder: ArbigentAppStateHolder,
  onCloseRequest: () -> Unit,
) {
  val graph = remember { appStateHolder.scenarioGraph() }
  TestCompatibleDialog(
    onCloseRequest = onCloseRequest,
    title = "Scenario Graph",
    width = 900.dp,
    height = 640.dp,
    content = {
      Column(modifier = Modifier.padding(8.dp).fillMaxSize().testTag("scenario_graph_dialog")) {
        ScenarioGraphViewer(
          graph = graph,
          modifier = Modifier.weight(1f).fillMaxWidth()
        )
        Row(
          modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = "Drag to pan, Ctrl+scroll to zoom. Solid arrows: dependency, dashed: reusable call.",
            color = JewelTheme.globalColors.text.info,
          )
          Row {
            val clipboardManager = LocalClipboardManager.current
            OutlinedButton(
              onClick = { clipboardManager.setText(AnnotatedString(graph.toMermaid())) },
              modifier = Modifier.padding(end = 8.dp).testTag("copy_mermaid_button")
            ) {
              Text("Copy Mermaid")
            }
            DefaultButton(
              onClick = onCloseRequest,
              modifier = Modifier.testTag("close_scenario_graph_button")
            ) {
              Text("Close")
            }
          }
        }
      }
    }
  )
}

@Composable
private fun ScenarioGraphViewer(
  graph: ArbigentScenarioGraph,
  modifier: Modifier = Modifier,
) {
  if (graph.nodes.isEmpty()) {
    Box(modifier, contentAlignment = Alignment.Center) {
      Text("No scenarios to show yet.")
    }
    return
  }
  val nodesByKey = remember(graph) { graph.nodes.associateBy { it.key } }
  val edgeKindByKeys = remember(graph) {
    graph.edges.associate { (it.fromKey to it.toKey) to it.kind }
  }
  val kuiver = remember(graph) {
    buildKuiver {
      // Explicit dimensions keep Kuiver out of its SubcomposeLayout auto-measurement,
      // which never lets ComposeUiTest reach an idle state; GraphNode renders at
      // exactly this size with ellipsized text.
      graph.nodes.forEach { node ->
        addNode(KuiverNode(id = node.key, dimensions = node.dimensions()))
      }
      graph.edges.forEach { edge -> addEdge(KuiverEdge(fromId = edge.fromKey, toId = edge.toKey)) }
    }
  }
  val viewerState = rememberKuiverViewerState(
    initialKuiver = kuiver,
    layoutConfig = LayoutConfig.Hierarchical(direction = LayoutDirection.HORIZONTAL),
  )
  KuiverViewer(
    state = viewerState,
    modifier = modifier.background(JewelTheme.globalColors.panelBackground),
    // Instant transforms: no bouncy springs, and nothing left animating for tests to wait on.
    config = KuiverViewerConfig(
      scaleAnimationSpec = snap(),
      offsetAnimationSpec = snap(),
      nodeAnimationSpec = snap(),
      edgeAnimationSpec = snap(),
      fontLoadingDelayMs = 0,
    ),
    nodeContent = { kuiverNode ->
      val node = nodesByKey[kuiverNode.id] ?: return@KuiverViewer
      GraphNode(node, kuiverNode.dimensions)
    },
    edgeContent = { kuiverEdge, from, to ->
      val kind = edgeKindByKeys[kuiverEdge.fromId to kuiverEdge.toId]
      EdgeContent(
        from = from,
        to = to,
        color = if (kind == ArbigentScenarioGraph.EdgeKind.Call) callEdgeColor else dependencyEdgeColor,
        dashed = kind == ArbigentScenarioGraph.EdgeKind.Call,
        strokeWidth = 2f,
      )
    }
  )
}

/**
 * Estimated size for a graph node. Kuiver's layout needs sizes up front (see the note at
 * the buildKuiver call); [GraphNode] renders at exactly this size and ellipsizes overflow.
 */
private fun ArbigentScenarioGraph.Node.dimensions(): NodeDimensions {
  val textLength = maxOf(title.length + kind.style.titlePrefix.length, subtitle.length)
  val width = (textLength * 7 + 24).coerceIn(80, 260)
  val height = if (subtitle.isEmpty()) 34 else 52
  return NodeDimensions(width.dp, height.dp)
}

@Composable
private fun GraphNode(node: ArbigentScenarioGraph.Node, dimensions: NodeDimensions?) {
  val style = node.kind.style
  Column(
    verticalArrangement = Arrangement.Center,
    modifier = Modifier
      .then(
        if (dimensions != null) Modifier.size(dimensions.width, dimensions.height) else Modifier
      )
      .background(
        color = style.background,
        shape = RoundedCornerShape(4.dp)
      )
      .border(
        width = style.borderWidth,
        color = style.borderColor,
        shape = RoundedCornerShape(4.dp)
      )
      .padding(horizontal = 8.dp, vertical = 4.dp)
      .testTag("graph_node_${node.key}")
  ) {
    Text(
      text = style.titlePrefix + node.title,
      color = Color.Black,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    if (node.subtitle.isNotEmpty()) {
      Text(
        text = node.subtitle,
        style = JewelTheme.typography.small,
        color = Color(0xFF5F6368),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}
