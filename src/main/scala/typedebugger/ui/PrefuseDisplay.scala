package scala.typedebugger
package ui

import prefuse.{Constants, Display, Visualization}
import prefuse.data.{Graph, Table, Node, Tuple, Edge, Tree}
import prefuse.data.tuple.{TupleSet, DefaultTupleSet}
import prefuse.data.expression.{AbstractPredicate, Predicate, OrPredicate}
import prefuse.visual.expression.{InGroupPredicate, VisiblePredicate}
import prefuse.action.{ ItemAction, ActionList, RepaintAction, Action}
import prefuse.action.assignment.{ColorAction, FontAction}
import prefuse.action.animate.{ColorAnimator, LocationAnimator, QualityControlAnimator, VisibilityAnimator}
import prefuse.action.layout.graph.{NodeLinkTreeLayout}
import prefuse.action.layout.CollapsedSubtreeLayout
import prefuse.activity.SlowInSlowOutPacer
import prefuse.visual.{VisualItem, NodeItem, EdgeItem}
import prefuse.util.{ColorLib, FontLib, GraphicsLib}
import prefuse.controls.{ControlAdapter, FocusControl, PanControl, WheelZoomControl,
                         ZoomControl, ZoomToFitControl}
import prefuse.visual.sort.TreeDepthItemSorter
import prefuse.util.PrefuseLib


import java.awt.Color
import java.awt.event.{ActionEvent, MouseEvent}
import java.awt.geom.{Point2D, Rectangle2D}
import javax.swing.{Action => swingAction, _}
import javax.swing.event.TreeModelListener

import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.tools.nsc.io

import prefuse.render._



object PrefuseDisplay {
  val typeDebuggerOrientation = Constants.ORIENT_BOTTOM_TOP
  
  val backgroundColor = Color.WHITE
  val foregroundColor = Color.BLACK
  
  def toVisualNode(node: Node, vis: Visualization, group: String): NodeItem = vis.getVisualItem(group, node).asInstanceOf[NodeItem]
  def toVisualEdge(edge: Edge, vis: Visualization, group: String): EdgeItem = vis.getVisualItem(group, edge).asInstanceOf[EdgeItem]
}

import UIConfig.{nodesLabel => label} // todo: remove

abstract class PrefuseDisplay(source0: io.AbstractFile, t: Tree, vis: TypeDebuggerVisualization)(implicit idx: Int)
  extends Display(vis) with ui.PrefuseTooltips { self =>

  import PrefuseDisplay._
  import PrefusePimping._
  
  protected def nodeColorAction(nodes: String): ItemAction
  protected def showFullTree: Boolean
  protected def extractPrefuseNode(t: Tuple): Node
  protected def isNode(t: Tuple): Boolean
  protected def eventFullInfo(item: VisualItem): util.StringFormatter
  protected def eventShortInfo(item: VisualItem): String
  
  private var edgeRenderer: EdgeRenderer = _
  private var nodeRenderer: LabelRenderer = _
  private var leafNodeRenderer: LabelRenderer = _
  protected var orientation: Int = _
  private var treeLayout: NodeLinkTreeLayout = new CustomNodeLinkTreeLayout(orientation, 50, 0, 8)
  
  protected def debug(msg: => String): Unit
  def source = source0
  
  protected val lst = new CachedLeastSpanningTree()
  def nodesAlwaysVisible: List[NodeItem] = {
    lst.get()
  }
  
  def getBottomNode: NodeItem = {
    lst.getRoot()
  }
  
  private val hoverController = new HoverTooltip() 
  def tooltipController = hoverController
  
  protected def adv: AdvancedOptionsController
  private def isAdvEnabled(t: Tuple): Boolean = adv.isAdvancedOption(t) && adv.isOptionEnabled(t)
  
  protected def _goals(): List[NodeItem]
  protected def removeFromGoals(node: NodeItem): Unit
  
  private[this] var _lastItem: Option[NodeItem] = None
  def lastItem: Option[NodeItem] = _lastItem
  def lastItem_= (item: NodeItem): Unit = {
    _lastItem = Some(item)
  }
  
  lazy val dataGroupName = "tree" + idx
  
  // todo: move to separate maps
  lazy val stickyNodes = "tree" + idx + ".sticky"         // Nodes that are 'fixed' to be visible
  lazy val openGoalNodes = "tree" + idx + ".openGoals"
  lazy val nonGoalNodes = "tree" + idx + ".openNods"// + idx      // Intermediate nodes on the path to the goal nodes
  lazy val toRemoveNodes = "tree" + idx + ".removeNodes"// + idx  // Nodes to be removed on the refresh of UI
  //val linkGroupNodes = "tree" + idx + ".link"
  lazy val clickedNodes = "tree" + idx + ".clicked"// + idx
  lazy val visibleGroup = "tree" + idx + ".visible"// + idx
  
  protected def dataAwareAction(action: String): String = action + idx
  
  private[this] var displayed: Boolean = false

  def init () {
    setBackground(backgroundColor)
    setForeground(foregroundColor)
   
    m_vis.add(dataGroupName, t)
    debug("Number of potential nodes: " + t.tuples().size)
    
    val treeNodes = "tree" + idx + ".nodes"
    val treeEdges = "tree" + idx + ".edges"
  
      // Set default node/edge renderer and orientation
    nodeRenderer = new CustomLabelRenderer(label)
    nodeRenderer.setRenderType(AbstractShapeRenderer.RENDER_TYPE_FILL)
    nodeRenderer.setHorizontalAlignment(Constants.CENTER)
    nodeRenderer.setVerticalAlignment(Constants.CENTER)
    nodeRenderer.setVerticalPadding(10)
    nodeRenderer.setHorizontalPadding(20)
    nodeRenderer.setRoundedCorner(8,8)
    
    // nodes for leafs
    leafNodeRenderer = new CustomLabelRenderer(label)
    leafNodeRenderer.setRenderType(AbstractShapeRenderer.RENDER_TYPE_FILL)
    leafNodeRenderer.setHorizontalAlignment(Constants.CENTER)
    leafNodeRenderer.setVerticalAlignment(Constants.CENTER)
    leafNodeRenderer.setVerticalPadding(10)
    leafNodeRenderer.setHorizontalPadding(20)
    leafNodeRenderer.setRoundedCorner(40,40)
    
    
    edgeRenderer = new EdgeRenderer(Constants.EDGE_TYPE_LINE)
        
    val rf = new DefaultRendererFactory()//nodeRenderer)
    rf.add(new IsLeafNode(), leafNodeRenderer)
    rf.add(new AbstractPredicate{override def getBoolean(t: Tuple) = t match {
      case _: NodeItem => true
      case _           => false
    }}, nodeRenderer)
    rf.add(new InGroupPredicate(treeEdges), edgeRenderer)
    m_vis.setRendererFactory(rf)
    
    // colors
    val nodeColor:ItemAction = nodeColorAction(treeNodes)
    val textColor:ItemAction = new ColorAction(treeNodes,
                VisualItem.TEXTCOLOR, ColorLib.rgb(0,0,0))
    vis.putAction(PrefuseActions.textColor, textColor)
        
    val edgeColorAction = new ColorAction(treeEdges,
                VisualItem.STROKECOLOR, ColorLib.rgb(194, 194, 194))
    
    edgeColorAction.add(IsEdgeOnGoalPath,
      new ColorAction(treeEdges, VisualItem.STROKECOLOR, ColorLib.rgb(0,0,0)))
    val edgeColor:ItemAction = edgeColorAction
  
    // quick repaint
    val repaint0 = new ActionList()
    repaint0.add(nodeColor)
    repaint0.add(new RepaintAction())
    vis.putAction(PrefuseActions.repaint, repaint0)
        
    // full paint
    val fullPaint = new ActionList()
    fullPaint.add(nodeColor)
    vis.putAction(PrefuseActions.fullPaint, fullPaint)
        
    // animate paint change
    val animatePaint = new ActionList(400)
    animatePaint.add(new ColorAnimator(treeNodes))
    animatePaint.add(new RepaintAction())
    vis.putAction(PrefuseActions.animatePaint, animatePaint)
  
    // create the tree layout action
    treeLayout.setLayoutAnchor(new Point2D.Double(25,300))
    vis.putAction(PrefuseActions.treeLayout, treeLayout)
        
    // Animation that handles collapsing/opening nodes 
    // Need to adapt it so that not all non-goal nodes are expanded
    val subLayout = new CollapsedSubtreeLayout(dataGroupName, orientation)
    vis.putAction(PrefuseActions.subLayout, subLayout)
  
    val autoPan = new AutoPanAction()
  
    // create the filtering and layout
    val filter = new ActionList()
    // Includes degree-of-interest factor
    filter.add(new VisualizeStickyNodes())
    filter.add(new UnfocusOnItems)
    filter.add(new VisualizeNodesWithPred(new IsNodeOnGoalPath()))
    //filter.add(new VisualizeNodes(linkGroupNodes))
  
    filter.add(new ShowAllGoalsAndEdges())
  
    filter.add(new FontAction(treeNodes, FontLib.getFont("Tahoma", 16)))
    filter.add(treeLayout)
    filter.add(textColor)
    filter.add(nodeColor)
    filter.add(edgeColor)
    vis.putAction(PrefuseActions.filter, filter)
        
    // animated transition
    val animate = new ActionList(1000)
    animate.setPacingFunction(new SlowInSlowOutPacer())
    animate.add(autoPan)
    animate.add(new QualityControlAnimator())
    animate.add(new VisibilityAnimator(dataGroupName))
    animate.add(new LocationAnimator(treeNodes))
    animate.add(new ColorAnimator(treeNodes))
    animate.add(new RepaintAction())
    vis.putAction(PrefuseActions.animate, animate)
    vis.alwaysRunAfter(PrefuseActions.filter, PrefuseActions.animate)
        
    // create animator for orientation changes
    val orient = new ActionList(2000)
    orient.setPacingFunction(new SlowInSlowOutPacer())
    orient.add(autoPan)
    orient.add(new QualityControlAnimator())
    orient.add(new LocationAnimator(treeNodes))
    orient.add(new RepaintAction())
    vis.putAction(PrefuseActions.orient, orient)
  
    // We cache collapse tree because it has a minimal version of
    // the graph that contains all the errors and intermediate nodes leading to it.
    vis.putAction(PrefuseActions.initialGoals, new CollapseTree())
    
    // proper collapse/expansion on filtering selection
    vis.putAction(PrefuseActions.advancedOptions, new CollapseDisabled())
    vis.putAction(PrefuseActions.hideAll, new HideAll(List(visibleGroup, clickedNodes, openGoalNodes, nonGoalNodes)))
   
    val zoomToFit = new ZoomToFitControl()
    zoomToFit.setZoomOverItem(false)
    
    // initialize the display
    setSize(700,800)
    setItemSorter(new TreeDepthItemSorter())
    addControlListener(zoomToFit)
    addControlListener(new ZoomControl())
    addControlListener(new WheelZoomControl())
    addControlListener(new PanControl())
    addControlListener(tooltipController)
    addControlListener(TypeDebuggerFocusControl(1, PrefuseActions.filter))
  
    setOrientation(typeDebuggerOrientation)
    vis.addFocusGroup(stickyNodes, new DefaultTupleSet())
    vis.addFocusGroup(openGoalNodes, new DefaultTupleSet())
    vis.addFocusGroup(nonGoalNodes, new DefaultTupleSet())
    vis.addFocusGroup(toRemoveNodes, new DefaultTupleSet())
    //vis.addFocusGroup(linkGroupNodes, new DefaultTupleSet())
    vis.addFocusGroup(clickedNodes, new DefaultTupleSet())
    vis.addFocusGroup(visibleGroup, new DefaultTupleSet())
    //vis.getFocusGroup(visibleGroup).addTupleSetListener(new SearchFor(0))
  }
  
  def reRenderDisabledEvents() {
    vis.run(PrefuseActions.advancedOptions)
  }
  
  def reRenderView(force: Boolean = false) {
    debug("re-render view for " + source + ", initial goals only: " + (!displayed && !showFullTree))
    if (force || (!displayed && !showFullTree)) {
      vis.run(PrefuseActions.initialGoals)
      displayed = true
    }
    vis.run(PrefuseActions.filter)
  }
  
  def hideView() {
    vis.run(PrefuseActions.hideAll)
  }
  
  // used for debugging
  class SearchFor(id: Int) extends prefuse.data.event.TupleSetListener {
    def tupleSetChanged(ts: prefuse.data.tuple.TupleSet, added: Array[Tuple], removed: Array[Tuple]) {
      added.toList foreach { t =>
        if (t.getRow == id)
          debug("Added the searched-for tuple " + t)
      }
    }
  }

  private def setOrientation(orientation0: Int) {
    val rtl = vis.getAction(PrefuseActions.treeLayout).asInstanceOf[NodeLinkTreeLayout]
    val stl = vis.getAction(PrefuseActions.subLayout).asInstanceOf[CollapsedSubtreeLayout]
    orientation0 match {
      case Constants.ORIENT_LEFT_RIGHT =>
        nodeRenderer.setHorizontalAlignment(Constants.LEFT)
        leafNodeRenderer.setHorizontalAlignment(Constants.LEFT)
        edgeRenderer.setHorizontalAlignment1(Constants.RIGHT)
        edgeRenderer.setHorizontalAlignment2(Constants.LEFT)
        edgeRenderer.setVerticalAlignment1(Constants.CENTER)
        edgeRenderer.setVerticalAlignment2(Constants.CENTER)
      case Constants.ORIENT_RIGHT_LEFT =>
        nodeRenderer.setHorizontalAlignment(Constants.RIGHT)
        leafNodeRenderer.setHorizontalAlignment(Constants.RIGHT)
        edgeRenderer.setHorizontalAlignment1(Constants.LEFT)
        edgeRenderer.setHorizontalAlignment2(Constants.RIGHT)
        edgeRenderer.setVerticalAlignment1(Constants.CENTER)
        edgeRenderer.setVerticalAlignment2(Constants.CENTER)
      case Constants.ORIENT_TOP_BOTTOM =>
        nodeRenderer.setHorizontalAlignment(Constants.CENTER)
        leafNodeRenderer.setHorizontalAlignment(Constants.CENTER)
        edgeRenderer.setHorizontalAlignment1(Constants.CENTER)
        edgeRenderer.setHorizontalAlignment2(Constants.CENTER)
        edgeRenderer.setVerticalAlignment1(Constants.BOTTOM)
        edgeRenderer.setVerticalAlignment2(Constants.TOP)
      case Constants.ORIENT_BOTTOM_TOP =>
        nodeRenderer.setHorizontalAlignment(Constants.CENTER)
        leafNodeRenderer.setHorizontalAlignment(Constants.CENTER)
        edgeRenderer.setHorizontalAlignment1(Constants.CENTER)
        edgeRenderer.setHorizontalAlignment2(Constants.CENTER)
        edgeRenderer.setVerticalAlignment1(Constants.TOP)
        edgeRenderer.setVerticalAlignment2(Constants.BOTTOM)
      case _ =>
        throw new IllegalArgumentException(
            "Unrecognized orientation value: "+orientation0)
    }
    orientation = orientation0
    rtl.setOrientation(orientation0)
    stl.setOrientation(orientation0)
  }
  
  protected def flushVisCache() {
    lst.clear()
    m_vis.getFocusGroup(stickyNodes).clear()
    m_vis.getFocusGroup(openGoalNodes).clear()
    m_vis.getFocusGroup(nonGoalNodes).clear()
    m_vis.getFocusGroup(toRemoveNodes).clear()
    //m_vis.getFocusGroup(linkGroupNodes).clear()
    m_vis.getFocusGroup(clickedNodes).clear()
    m_vis.getFocusGroup(visibleGroup).clear()
    m_vis.getFocusGroup(Visualization.FOCUS_ITEMS).clear()
    displayed = false
  }



  // Some predefined actions (direct translation from the prefuse examples)
  class OrientAction(var orientation: Int) extends AbstractAction {
    def actionPerformed(evt: ActionEvent) {
      setOrientation(orientation)
      vis.cancel(PrefuseActions.orient)
      vis.run(PrefuseActions.treeLayout)
      vis.run(PrefuseActions.orient)
    }
  }
  
  class CustomLabelRenderer(name: String) extends LabelRenderer(name) {
    override def getText(item: VisualItem): String = {
      eventShortInfo(item)
    }
  }
  
  class AutoPanAction extends Action {
    private val m_start:Point2D = new Point2D.Double()
    private val m_end:Point2D   = new Point2D.Double()
    private val m_cur:Point2D   = new Point2D.Double()
    private var m_bias: Int  = 100
      
    def run(frac: Double) {
      val ts:TupleSet = m_vis.getFocusGroup(clickedNodes)
      if ( ts.getTupleCount() == 0 )
        return
          
      if ( frac == 0.0 ) {
        var xbias = 0
        var ybias = 0
        orientation match {
          case Constants.ORIENT_LEFT_RIGHT =>
            xbias = m_bias

          case Constants.ORIENT_RIGHT_LEFT =>
            xbias = -m_bias

          case Constants.ORIENT_TOP_BOTTOM =>
            ybias = m_bias

          case Constants.ORIENT_BOTTOM_TOP =>
            ybias = -m_bias
        }

        val vi:VisualItem = ts.next()
        m_cur.setLocation(getWidth()/2, getHeight()/2)
        getAbsoluteCoordinate(m_cur, m_start)
        m_end.setLocation(vi.getX()+xbias, vi.getY()+ybias)
      } else {
        m_cur.setLocation(m_start.getX() + frac*(m_end.getX()-m_start.getX()),
                         m_start.getY() + frac*(m_end.getY()-m_start.getY()))
        panToAbs(m_cur)
      }
    }
  }

  class HoverTooltip extends ControlAdapter {
    var activeTooltip: PrefuseTooltip = _
  
    override def itemExited(item: VisualItem, e: MouseEvent) {
      if(activeTooltip != null) activeTooltip.stopShowing()
    }
    
    override def itemPressed(item: VisualItem, e: MouseEvent) {
      clearTooltip()
    }
    
    override def itemReleased(item: VisualItem, e: MouseEvent) {
      if(item.isInstanceOf[NodeItem] && e.getButton == MouseEvent.BUTTON3)
        showNodeTooltip(item, e.getX(), e.getY())
    }
    
    def clearTooltip(): Unit = if(activeTooltip != null) activeTooltip.stopShowingImmediately()
    
    def showItemTooltip(item: VisualItem) {
      //Use fixed coordinates as item can randomly fail to give sensible values
      showNodeTooltip(item, 5, 5)
    }
    
    protected def showNodeTooltip(item: VisualItem, coordX: Int, coordY: Int) {
      val v = item.getVisualization()
      
      showTooltip(new NodeTooltip(eventFullInfo(item), 100, 100, self),
                  item, coordX, coordY)
    }
    
    private def showTooltip(ptt: PrefuseTooltip, item: VisualItem, coordX: Int, coordY: Int) {
      clearTooltip()
      if (ptt.init()) {
        activeTooltip = ptt
        activeTooltip.startShowing(coordX + 10, coordY + 5,
            (getWidth()/2) < coordX,
            (getHeight()/2) < coordY)
      }
    }
  }

  class EdgeColorAction(group: String) extends ColorAction(group, VisualItem.STROKECOLOR) {
    // TODO: events should have appropriate colors
    override def getColor(item: VisualItem): Int = {
      ColorLib.rgba(200,200,200,0)
    }
  }
  
  // Add all NodeItems/EdgeItems for which predicate resolves true 
  // to the visible section of the graph
  class VisualizeNodesWithPred(predicate: Predicate) extends Action {
    def run(frac: Double) {
      debug("[visualize nodes with predicate]")
      val ts = m_vis.getFocusGroup(visibleGroup)
      if (ts != null)
        for (item <- m_vis.items_[Tuple](predicate)) {
          ts.addTuple(item)
        }
    }
  }

  // cannot be an object, because of a bug in the compiler (#2296)
  // illegal runtime access on m_vis
  class UnfocusOnItems extends Action {
    def run(frac: Double) {
      val ts = m_vis.getFocusGroup(visibleGroup)
      val visItems = m_vis.items_[Node](toRemoveNodes).map(toVisualNode(_, m_vis, dataGroupName))
      debug("[unfocus]: " + visItems)
      for (item <- visItems) {
        PrefuseLib.updateVisible(item, false)
        item.setExpanded(false)
        item.childEdges_[VisualItem]().foreach(PrefuseLib.updateVisible(_, false))
        item.outNeighbors_[VisualItem]().foreach(PrefuseLib.updateVisible(_, false))
        if (item.getParentEdge() != null)
          PrefuseLib.updateVisible(toVisualEdge(item.getParentEdge(), m_vis, dataGroupName), false)
        ts.removeTuple(item)
      }
      m_vis.getFocusGroup(toRemoveNodes).clear()
    }
  }
  
  // Need to show all the goals, all edges between them, 
  // as well as immediate (1-distance) subgoals of each goal
  class ShowAllGoalsAndEdges() extends Action {
    
    def run(frac: Double) {
      val ts = m_vis.getFocusGroup(visibleGroup)
      
      if (ts.getTupleCount() == 0) {
        // in case of no visible nodes available
        // display only the synthetic root
        // add it to clickable nodes (for zooming purposes etc).
        val root = treeLayout.getLayoutRoot()
        ts.addTuple(root)
        val clickedTs = m_vis.getFocusGroup(clickedNodes)
        clickedTs.addTuple(root)
      }
      
      debug("[make visible] " + ts.tuples().toList)
      for (item <- ts.tuples()) {
        item match {
          case item: NodeItem =>
            PrefuseLib.updateVisible(item, true)
            item.setExpanded(true)
            item.childEdges_[EdgeItem]().foreach { edge =>
              val targetNode = edge.getTargetNode()
              if (!edge.isVisible && (!adv.isAdvancedOption(targetNode) || adv.isOptionEnabled(targetNode)))
                PrefuseLib.updateVisible(edge, true)
            }
            // neighbors should be added to separate group
            item.outNeighbors_[NodeItem]().foreach { neighbor =>
              if (!neighbor.isVisible && (!adv.isAdvancedOption(neighbor) || adv.isOptionEnabled(neighbor)))
                PrefuseLib.updateVisible(neighbor, true)
            }
            
            // If this is not a goal, then expand all the incoming edges as well
            val ts = m_vis.getFocusGroup(openGoalNodes)
            if (!(ts containsTuple item.getSourceTuple))
              item.inEdges_[VisualItem]().foreach(PrefuseLib.updateVisible(_, true))
          case vItem: EdgeItem =>
            PrefuseLib.updateVisible(vItem, true)
          case _ =>
        }
      }
    }
  }
  
  // Spanning tree that wraps aroound goals/errors
  class CachedLeastSpanningTree() {
    private[this] var cachedMinimumSet: Option[(List[NodeItem], NodeItem)] = None
    private[this] var cachedPathToTreeRoot: List[NodeItem] = Nil
    @inline def ensureInitialized() {
      cachedMinimumSet match {
        case Some(_) =>
        case None =>
          // cache was flushed/not_initialized, calculate from scratch
          cachedMinimumSet = Some(leastSpanningTree(_goals))
          cachedPathToTreeRoot = pathToTreeRoot(cachedMinimumSet.get._2)
      }
    }
    
    def get(): List[NodeItem] = {
      ensureInitialized()
      cachedMinimumSet.get._1
    }
    
    def getRoot(): NodeItem = {
      ensureInitialized()
      cachedMinimumSet.get._2
    }
    
    def getPathToRoot(): List[NodeItem] = {
      ensureInitialized()
      cachedPathToTreeRoot
    }
    
    def clear() {
      cachedMinimumSet = None
    }
    
    private def pathToTreeRoot(start: NodeItem): List[NodeItem] = {
      var buffer = new mutable.ListBuffer[NodeItem]()
      var node = start
      
      while (node != null) {
        buffer += node
        node = node.getParent.asInstanceOf[NodeItem]
      }
      buffer.toList
    }
    
    private def leastSpanningTree(nodes0: List[NodeItem]): (List[NodeItem], NodeItem) = nodes0 match {
      case Nil =>
        val root = toVisualNode(t.getRoot, m_vis, dataGroupName)
        (List(root), root)
      case List(single) =>
        (nodes0, single)
      case nodes =>
        val idx = mutable.HashMap.empty[Node, Int]
        var meetingPoint: Option[Node] = None
        var maybeRoot: Node = null
        
        
        def markPath(start: Node) {
          var visited = new mutable.ListBuffer[Node]()
          var current = start
          // 1) we reached root
          // 2) we reached node that was already on some root path
          // 3) we reached advanced node that is not enabled
          while (current != null && !idx.contains(current) && (!adv.isAdvancedOption(current) || adv.isOptionEnabled(current))) {
            visited += current
            current = current.getParent()
          }
          
          if (current != null) {
            if (idx.contains(current)) {
              // 2)
              visited foreach (v => idx += (v -> 1))
              idx(current) += 1
              meetingPoint match {
                case Some(n) =>
                  if (idx(n) < idx(current))
                    meetingPoint = Some(current)
                case None    =>
                  meetingPoint = Some(current)
              }
              
              // increase the counter for all the
              // nodes above the join until we reach root
              current = current.getParent()
              while (current != null) {
                idx(current) += 1
                current = current.getParent()
              }
            } else {
              // 3)
            }
          } else {
            // 1)
            maybeRoot = visited.last
            visited foreach (v => idx += (v -> 1))
          }
        }
        nodes foreach markPath
        
        def cleanupUpwards(root: Node) {
          var start = root.getParent
          while (start != null) {
            idx -= start
            start = start.getParent
          }
        }
        val root = meetingPoint match {
          case Some(n) =>
            cleanupUpwards(n)
            n
          case None    =>
            // some paths were invalidated
            maybeRoot
        }
        
        val res = idx.keys.toList map(toVisualNode(_, m_vis, dataGroupName))
        assert(root != null)

        (res, toVisualNode(root, m_vis, dataGroupName))
    }
  }
  
  class CustomNodeLinkTreeLayout(orientation: Int, dspace: Double, bspace: Double, tspace: Double)
    extends NodeLinkTreeLayout(dataGroupName) {//, orientation, dspace, bspace, tspace) {
    
    // For the initial layout we anchor the tree layout at our least spanning tree root.
    // This needs to dynamically change as we expand our tree in include further nodes.
    // Therefore we need to find last visible parent.
    override def getLayoutRoot() = {      
      var item:NodeItem = lst.getRoot()
      if (item == null) {
        super.getLayoutRoot()
      } else {
        val openGoals = m_vis.getFocusGroup(openGoalNodes)
        var temp = item.getParent.asInstanceOf[NodeItem]
        while (temp != null && (openGoals.containsTuple(temp.getSourceTuple))) {
          item = temp
          temp = temp.getParent.asInstanceOf[NodeItem]
        }
        item
      }
    }
    
    override def getGraph(): Graph = {
      m_vis.getGroup(visibleGroup).asInstanceOf[Graph]
    }
    
    // Methods below are a direct copy of the original behaviour with the exception
    // that we take into account visible/advanced nodes when calculating
    // the proper layout
    // @see prefuse.action.layout.graph.NodeLinkTreeLayout for details
    override def firstWalk(n: NodeItem, num: Int, depth: Int) {
      val np = getParams(n)
      np.number = num
      updateDepths(depth, n)
        
      val expanded = n.isExpanded()
      if ( Proxy.getChildCount(n) == 0 || !expanded ) {
        val l = Proxy.getPreviousSibling(n)
        np.prelim = if ( l == null ) 0 else (getParams(l).prelim + spacing(l,n,true))
      } else if ( expanded ) {
        val leftMost = Proxy.getFirstChild(n)
        val rightMost = Proxy.getLastChild(n)
        var defaultAncestor = leftMost
        var c = leftMost
        var i = 0
        while (c != null) {
          firstWalk(c, i, depth+1)
          defaultAncestor = apportion(c, defaultAncestor)
          i += 1
          c = Proxy.getNextSibling(c)
        }
            
        executeShifts(n)
            
        val midpoint = 0.5 *
           (getParams(leftMost).prelim + getParams(rightMost).prelim)
            
        val left = Proxy.getPreviousSibling(n)
        if ( left != null ) {
          np.prelim = getParams(left).prelim + spacing(left, n, true)
          np.mod = np.prelim - midpoint
        } else {
          np.prelim = midpoint
        }
      }
    }
    
    override def apportion(v: NodeItem, a: NodeItem): NodeItem = {        
      val w = Proxy.getPreviousSibling(v)
      if ( w != null ) {
        var vip, vim, vop, vom: NodeItem = null
        var sip, sim, sop, som: Double = 0

        vip = v; vop = v

        vim = w
        vom = Proxy.getFirstChild(vip.getParent().asInstanceOf[NodeItem])

        sip = getParams(vip).mod
        sop = getParams(vop).mod
        sim = getParams(vim).mod
        som = getParams(vom).mod
        
        var nr = nextRight(vim) // TODO: need to use our version
        var nl = nextLeft(vip)  // same here
        while ( nr != null && nl != null ) {
          vim = nr
          vip = nl
          vom = nextLeft(vom)
          vop = nextRight(vop)
          getParams(vop).ancestor = v
          val shift: Double = (getParams(vim).prelim + sim) - 
              (getParams(vip).prelim + sip) + spacing(vim,vip,false)
          if ( shift > 0 ) {
              moveSubtree(ancestor(vim,v,a), v, shift)
              sip += shift
              sop += shift
          }
          sim += getParams(vim).mod
          sip += getParams(vip).mod
          som += getParams(vom).mod
          sop += getParams(vop).mod
          
          nr = nextRight(vim)
          nl = nextLeft(vip)
        }
        if ( nr != null && nextRight(vop) == null ) {
          val vopp = getParams(vop)
          vopp.thread = nr
          vopp.mod += sim - sop
        }
        if ( nl != null && nextLeft(vom) == null ) {
          val vomp = getParams(vom)
          vomp.thread = nl
          vomp.mod += sip - som
          v
        } else a
      } else a
    }
    
    override def nextLeft(n: NodeItem): NodeItem = {
      var c: NodeItem = null
      if ( n.isExpanded() && Proxy.isValid(n) ) c = Proxy.getFirstChild(n)
      if (c != null) c else getParams(n).thread
    }
    
    override def nextRight(n: NodeItem): NodeItem = {
      var c: NodeItem = null
      if ( n.isExpanded() && Proxy.isValid(n) ) c = Proxy.getLastChild(n)
      if (c != null) c else getParams(n).thread
    }
    
    override def executeShifts(n: NodeItem) {
      var shift, change: Double = 0
      var c = Proxy.getLastChild(n)
      while (c != null) {
        val cp = getParams(c)
        cp.prelim += shift
        cp.mod += shift
        change += cp.change
        shift += cp.shift + change
        c = Proxy.getPreviousSibling(c)
      }
    }

    override def ancestor(vim: NodeItem, v: NodeItem, a: NodeItem): NodeItem = {
      val p:NodeItem = v.getParent().asInstanceOf[NodeItem]
      val vimp = getParams(vim)
      if (vimp.ancestor.getParent() == p) vimp.ancestor else a
    }
    
    override def secondWalk(n: NodeItem, p: NodeItem, m: Double, depth0: Int) {
      val np = getParams(n)
      var depth = depth0
      setBreadth(n, p, np.prelim + m)
      setDepth(n, p, m_depths(depth))
      
      if ( n.isExpanded() && Proxy.isValid(n) ) {
        depth += 1
        var c = Proxy.getFirstChild(n)
        while (c != null) {
          secondWalk(c, n, m + np.mod, depth)
          c = Proxy.getNextSibling(c)
        }
      }
      np.clear()
    }
    
    //end of custom layout, specialized NodeLinkTreeLayout for our needs
    
    // Provide main custom operation for navigating between nodes, 
    // that takes into account visibility requirements of the type debugger
    object Proxy {
      def isValid(node: NodeItem): Boolean = {
        node.isVisible && (!adv.isAdvancedOption(node) || adv.isOptionEnabled(node))
      }
      
      def getChildCount(underlying: NodeItem): Int =
        underlying.children_[NodeItem].toList.filter(isValid).size
      
      def getFirstChild(underlying: NodeItem): NodeItem = {
        val first = underlying.getFirstChild().asInstanceOf[NodeItem]
        if (first != null) {
          if (!isValid(first)) Proxy.getNextSibling(first) else first
        } else null
      }
      
      def getLastChild(underlying: NodeItem): NodeItem = {
        val last = underlying.getLastChild().asInstanceOf[NodeItem]
        if (last != null) {
          if (!isValid(last)) Proxy.getPreviousSibling(last) else last
        } else null
      }
      
      def getPreviousSibling(underlying: NodeItem): NodeItem = {
        var previous = underlying.getPreviousSibling().asInstanceOf[NodeItem]
        while (previous != null && !isValid(previous))
          previous = previous.getPreviousSibling().asInstanceOf[NodeItem]
        previous
      }
      
      def getNextSibling(underlying: NodeItem): NodeItem = {
        var next = underlying.getNextSibling().asInstanceOf[NodeItem]
        while (next != null && !isValid(next))
          next = next.getNextSibling().asInstanceOf[NodeItem]
        next
      }
    }
  }
  
  
  // Collapse the whole tree initially so that only goals are visible (and paths to them)
  class CollapseTree() extends Action {    
    private def allInitialGoals: TupleSet = m_vis.getFocusGroup(openGoalNodes)

    def run(frac: Double) {
      val visibleItems = m_vis.items_[VisualItem](Visualization.ALL_ITEMS)
      val ts = allInitialGoals
      val min = lst.get()
      for (item <- visibleItems) {
        if (min contains item) {
          // Goal
          val panTs = m_vis.getFocusGroup(clickedNodes)
          panTs.addTuple(item)
          if (isNode(item)) {
            ts.addTuple(item.getSourceTuple)
            // add its immediate parent as well
            val nodeItem = item.asInstanceOf[NodeItem]
            if (nodeItem.getParent != null)
              ts.addTuple(nodeItem.getParent.asInstanceOf[NodeItem].getSourceTuple)
          }
        } else {
          item match {
            case item0: NodeItem =>
              item0.setExpanded(false)
            case _ =>
          }
          PrefuseLib.updateVisible(item, false)
        }
      }
    }
  }
  
  class CollapseDisabled() extends Action {
    // todo similar to visualitemsearchpred
    object OnlyNodes extends AbstractPredicate {
      override def getBoolean(t: Tuple): Boolean = t match {
        case item: NodeItem if isNode(t) => true
        case _                           => false
      }
    }
    def run(frac: Double) {
      val nodes = m_vis.items_[NodeItem](Visualization.ALL_ITEMS, OnlyNodes)
      for (node <- nodes) {
        if (node.isVisible && adv.isAdvancedOption(node) && !adv.isOptionEnabled(node)) {
          PrefuseLib.updateVisible(node, false)
          node.setExpanded(false)
          node.inEdges_[EdgeItem]() foreach { edge =>
            PrefuseLib.updateVisible(edge, false)
          }
        }
      }
    }
  }
  
  // ------------------------
  // Some utility predicates
  // ------------------------
      
  // Predicate returning true for goal and nongoal groups
  class IsNodeOnGoalPath() extends AbstractPredicate {
    override def getBoolean(t: Tuple): Boolean = t match {
      case item: NodeItem =>
        val ts = m_vis.getFocusGroup(openGoalNodes)
        ts.containsTuple(item.getSourceTuple) || {
          val ts2 = m_vis.getFocusGroup(nonGoalNodes)
          ts2.containsTuple(item.getSourceTuple)
        }
      case _ =>
        false
    }
  }
  
  class IsLeafNode() extends AbstractPredicate {
    override def getBoolean(t: Tuple): Boolean = t match {
      case item: NodeItem =>
        item.getChildCount == 0
      case _              =>
        false
    }
  }
  
  // Predicate for checking if an edge is between the two goals
  object IsEdgeOnGoalPath extends AbstractPredicate {
    override def getBoolean(t: Tuple): Boolean = t match {
      case edge: EdgeItem if isNode(edge.getSourceNode) =>
        val ts = m_vis.getFocusGroup(openGoalNodes)
        ts.containsTuple(edge.getTargetNode.asInstanceOf[VisualItem].getSourceTuple) &&
        ts.containsTuple(edge.getSourceNode.asInstanceOf[VisualItem].getSourceTuple)
      case _ => false
    }
  }
  
  // Add all intermediate nodes that lead to the already visible nodes
  // to the nonGoalGroup (i.e. not goals, but still visible)
  class VisualizeStickyNodes() extends Action {    
    override def run(frac: Double) {
      val target = m_vis.getFocusGroup(stickyNodes)
      target.foreach(addLinkPath)
    }
    
    def addLinkPath(starting: NodeItem) {
      var n: NodeItem = starting
      val nonGoals = m_vis.getFocusGroup(nonGoalNodes)
      val cachedPath = lst.getPathToRoot()
      debug("[sticky nodes]: " + n)
      while (!(cachedPath contains n) && n.getParent != null) {
        nonGoals.addTuple(n.getSourceTuple)
        n = n.getParent.asInstanceOf[NodeItem]
      }
      // some opengoals might have been removed in the meantime
      // need to ensure that they are there
      val goals = m_vis.getFocusGroup(openGoalNodes)
      if (cachedPath contains n) {
        // traverse from the root of the lst up to n
        // and make sure that they are visible
        var node = lst.getRoot()
        while (node != n) {
          goals.addTuple(node.getSourceTuple)
          node = node.getParent.asInstanceOf[NodeItem]
        }
        goals.addTuple(node.getSourceTuple)
      } else {
        cachedPath foreach (goals.addTuple)
      }
    }
  }
  
  class VisualizeNodes(groupName: String) extends Action {
    def run(frac: Double) {
      val target = m_vis.getFocusGroup(visibleGroup)
      val ts = m_vis.getFocusGroup(groupName)
      ts.tuples().foreach(n => target.addTuple(n.asInstanceOf[Tuple]))
    }
  }
  
  class HideAll(groupNames: List[String]) extends Action {
    def run(frac: Double) {
      val toHide = groupNames.flatMap(name => m_vis.getFocusGroup(name).tuples().toList.map((name, _)))
      toHide.foreach {
        case (name, elem) if elem == null =>
          println("Found null element in " + name)
        case (_, elem: NodeItem) =>
          PrefuseLib.updateVisible(elem, false)
          elem.childEdges_[EdgeItem]().foreach(PrefuseLib.updateVisible(_, false))
          elem.outNeighbors_[NodeItem]().foreach(PrefuseLib.updateVisible(_, false))
          elem.inEdges_[VisualItem]().foreach(PrefuseLib.updateVisible(_, false))
        case (_, elem: EdgeItem) =>
          PrefuseLib.updateVisible(elem, false)
        case (name: String, node: Node) =>
          val elem = m_vis.getVisualItem(name, node).asInstanceOf[NodeItem]
          if (elem != null) {
            PrefuseLib.updateVisible(elem, false)
            elem.childEdges_[EdgeItem]().foreach(PrefuseLib.updateVisible(_, false))
            elem.outNeighbors_[NodeItem]().foreach(PrefuseLib.updateVisible(_, false))
            elem.inEdges_[VisualItem]().foreach(PrefuseLib.updateVisible(_, false))
          }
      }
    }
  }

}