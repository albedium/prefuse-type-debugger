package scala.typedebugger
package ui
package controllers

import prefuse.{Constants, Display, Visualization}
import prefuse.data.{Graph, Table, Node, Tuple, Edge, Tree}
import prefuse.data.expression.{AbstractPredicate, Predicate, OrPredicate}
import prefuse.action.{ ItemAction }
import prefuse.action.layout.graph.{NodeLinkTreeLayout}
import prefuse.action.assignment.{ColorAction, FontAction}
import prefuse.action.{ ItemAction, ActionList, RepaintAction, Action}
import prefuse.visual.{VisualItem, NodeItem, EdgeItem}
import prefuse.util.{ColorLib}
import scala.collection.JavaConversions._

import scala.collection.mutable

trait PrefuseControllers {
  self: internal.CompilerInfo with UIUtils with internal.PrefuseStructure with internal.EventFiltering =>
    
  import PrefuseComponent._
  import PrefusePimping._
   
  import global.{Tree => STree, _}
  import EV._
  
  class AdvancedOptionsController extends AdvancedOptions {
    private val advFilter = new mutable.BitSet()
    
    def enableOption(v: Filtering.Value) { advFilter += v.id }
    def disableOption(v: Filtering.Value) { advFilter -= v.id}
    
    def isOptionEnabled(t: Tuple): Boolean = {
      val ev = asDataNode(t).ev
      FilteringOps.map.isDefinedAt(ev) && advFilter(FilteringOps.map(ev).id)
    }
    def isAdvancedOption(t: Tuple): Boolean = asDataNode(t).advanced
  }
  
  class PrefuseController(pTree: Tree, goals0: List[UINode[PrefuseEventNode]]) extends PrefuseComponent(pTree) {
      // methods that are global specific
    def nodeColorAction(nodes: String): ItemAction = new NodeColorAction(nodes)
    def extractPrefuseNode(t: Tuple): Node = asDataNode(t).pfuseNode
    def isGoal(t: Tuple): Boolean = asDataNode(t).goal
    def isNode(t: Tuple): Boolean = containsDataNode(t)
    def eventInfo(item: VisualItem): String = asDataNode(item).fullInfo
    def setGoalPath(item: VisualItem): Unit = {
      def setGoalPath0(node: Option[UINode[PrefuseEventNode]]): Unit = node match {
        case Some(n) if !n.goal =>
          n.goal = true
          setGoalPath0(n.parent)
        case _ =>   
      }
      setGoalPath0(Some(asDataNode(item)))
    }
    def visualizeFixedNodesAction(): Action = new VisualizeFixedNodes(PrefuseComponent.fixedNodes, PrefuseComponent.nonGoalNodes)
    def showFullTree = settings.fullTypechecking.value

    private var _initPredicate: ToExpandInfo = new InitialGoalPredicate()
    def initialGoalPredicate(): ToExpandInfo = _initPredicate

    private var _treeLayout: NodeLinkTreeLayout = new CustomNodeLinkTreeLayout(Visualization.FOCUS_ITEMS, orientation, 50, 0, 8)
    def customTreeLayout(): NodeLinkTreeLayout = _treeLayout

    
    private var _goals: List[UINode[PrefuseEventNode]] = goals0
    def updateGoals(gs: List[UINode[PrefuseEventNode]]) {
      debug("[prefuse] update initial goals to: " + gs)
      _goals = gs
      flushVisCache()
      showPrefuseDisplay()
    }
    
    val adv: AdvancedOptions = new AdvancedOptionsController()
    
    // customized predicates
    class InitialGoalPredicate() extends ToExpandInfo {
	    private def isInitialGoal(node: UINode[PrefuseEventNode]) =
	      if (_goals.contains(node)) {
	        node.ev match {
	          case _: HardErrorEvent => true
	          case e: ContextTypeError if e.errType == ErrorLevel.Hard => true
	          case _ => true
	        }
	      } else false
	      
	    def isGoalOrSibling(t: VisualItem) = t match {
	      case node: NodeItem if containsDataNode(node) =>
	        val eNode = asDataNode(node)
	        // Apart from expanding the error node
	        // expand also its siblings
	        if (!isInitialGoal(eNode)) (false, eNode.children.exists(isInitialGoal))
	        else (true, false)
	      case _ => (false, false)
	    }
	  }
      
	  class CustomNodeLinkTreeLayout(visGroup: String, orientation: Int, dspace: Double,
	    bspace: Double, tspace: Double)
	    extends NodeLinkTreeLayout(tree) {
	    
	    def initialGoal: Option[UINode[PrefuseEventNode]] = {
	      _goals match {
	        case head::_ => Some(head)
	        case _       => None
	      }
	    }
	    
	    object GoalNode extends AbstractPredicate {
	      override def getBoolean(t: Tuple): Boolean = t match {
	        case nodeItem: NodeItem if containsDataNode(nodeItem) =>
	          asDataNode(t).goal && nodeItem.isVisible
	        case _ => false
	      }
	    }
	    
	    // Anchor the layout root at the first error
	    // or show the synthetic root
	    // whenever we expand the type tree we update the root
	    override def getLayoutRoot() = {
	      val allVisibleGoals = m_vis.items_[NodeItem](visGroup, GoalNode)
	      val allPNodeVisibleGoals = allVisibleGoals.map(t => (asDataNode(t).pfuseNode, t)).toMap
	      
	      initialGoal match {
	        case Some(head) =>
	          // Need to find respective VisualItem for node so that
	          // we can match prefuse node stored in PrefuseEventNode
	          var eNode = head 
	          while (eNode.parent.isDefined && allPNodeVisibleGoals.contains(eNode.parent.get.pfuseNode)) {
	            eNode = eNode.parent.get
	          }
	          //debug("[layout root] HEAD: " + head)
	          //debug("[layout root] all visible goals: " + allVisibleGoals.toList.map(_.toString))
	          if (!allPNodeVisibleGoals.contains(eNode.pfuseNode)) {
	            // we are dealing with a first (root) node
	            // so try to find it manually
	            val first = m_vis.items_[NodeItem](tree, new VisualItemSearchPred(head.pfuseNode))
	            if (first.hasNext) first.next else super.getLayoutRoot()
	          } else {
	            allPNodeVisibleGoals(eNode.pfuseNode) // get corresponding visualitem
	          }
	        case None =>
	          super.getLayoutRoot()
	      }
	    }
	    
	    override def getGraph(): Graph = {
	      m_vis.getGroup(visGroup).asInstanceOf[Graph]
	    }
	  }
  }
  
  object NodeColorAction {
    private final val DEFAULT_COLOR: Int = ColorLib.rgba(255,255,255,0)
    private val phasesMap: Map[Phase, (Int, Int, Int)] = 
      Map((global.currentRun.namerPhase, (192, 255, 193)), (global.currentRun.typerPhase, (238, 230, 133)))
  }

  class NodeColorAction(group: String) extends ColorAction(group, VisualItem.FILLCOLOR) {
    import NodeColorAction._
    private def retrieveEvent(item: VisualItem): Option[Event] =
      if (containsDataNode(item)) Some(asDataNode(item).ev)
      else None

    // TODO: Refactor that
    override def getColor(item: VisualItem): Int = {
      val event = retrieveEvent(item)
      event match {
        case _ if ( m_vis.isInGroup(item, PrefuseComponent.clickedNode)) =>
          ColorLib.rgb(198, 229, 250) // Make it always visible
        case Some(ev: HardErrorEvent) =>
          ColorLib.rgba(255, 0, 0, 150)
        case Some(ev: ContextTypeError) if ev.errType == ErrorLevel.Hard =>
          ColorLib.rgba(255, 0, 0, 150)
        case Some(ev: TyperOmittedStatement) =>
          ColorLib.rgba(204, 204, 204, 50)
        case Some(ev: SoftErrorEvent) =>
          ColorLib.rgba(255, 0, 0, 50)
        case Some(ev: ContextTypeError) if ev.errType == ErrorLevel.Soft =>
          ColorLib.rgba(255, 0, 0, 50)
        case Some(ev: LubEvent) =>
          ColorLib.rgba(238, 102, 34, 100)
        case Some(ev: TypesEvent) =>
          ColorLib.rgba(238, 102, 34, 100) // TODO change to a different one
        case _ =>
          // search currently not supported
          if ( m_vis.isInGroup(item, Visualization.SEARCH_ITEMS) )
            ColorLib.rgb(255,190,190)
          //else if ( m_vis.isInGroup(item, TreeDisplay.clickedNode))
          //  ColorLib.rgb(198, 229, 250)
          else if ( m_vis.isInGroup(item, fixedNodes) )
            ColorLib.rgb(204, 255, 51)
          else if ( m_vis.isInGroup(item, Visualization.FOCUS_ITEMS) )
            ColorLib.rgb(198,229,229)
            // provide different colors for phases and types of events
          else if ( item.getDOI() > -1 )
            ColorLib.rgb(164,193,193)
          else
            event match {
              case Some(ev: AdaptEvent) =>
                ColorLib.rgba(150, 200, 100, 100)
              case Some(ev: InferEvent) =>
                ColorLib.rgba(201, 150, 100, 100)
              case Some(ev) =>
                if (phasesMap.contains(ev.phase)) {
                  val c = phasesMap(ev.phase)
                  ColorLib.rgb(c._1, c._2, c._3)
                } else
                  DEFAULT_COLOR                   
              case None =>
                DEFAULT_COLOR
            }
      }
    }
      
  }
    
  // Add all intermediate nodes that lead to the already visible nodes
  // to the nonGoalGroup (i.e. not goals, but still visible)
  class VisualizeFixedNodes(fixedGroup: String, nonGoalGroup: String) extends Action {
    lazy val nonGoals = m_vis.getFocusGroup(nonGoalGroup)
    
    override def run(frac: Double) {
      val target = m_vis.getFocusGroup(fixedGroup)
      target.foreach(addLinkPath)
    }
    
    def addLinkPath(starting: NodeItem) {
      var n = asDataNode(starting)
      while (!n.goal && n.parent.isDefined) {
        nonGoals.addTuple(n.pfuseNode)
        n = n.parent.get
      }
    }
  }
}