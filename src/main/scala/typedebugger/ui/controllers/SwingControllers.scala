package scala.typedebugger
package ui
package controllers

import prefuse.Visualization
import prefuse.data.{Graph, Table, Node, Tuple, Edge, Tree}
import prefuse.visual.{VisualItem, NodeItem, EdgeItem}
import prefuse.controls.{ControlAdapter, FocusControl, PanControl, WheelZoomControl,
                         ZoomControl, ZoomToFitControl}
import prefuse.data.expression.{AbstractPredicate, Predicate, OrPredicate}

import java.awt.Color
import java.awt.event._
import javax.swing.text.DefaultHighlighter.DefaultHighlightPainter
import javax.swing.text.Highlighter
import javax.swing.event.{CaretListener, CaretEvent}

import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.tools.nsc.io
import scala.tools.nsc.util.{SourceFile, BatchSourceFile}

trait SwingControllers {
  self: internal.CompilerInfo with UIUtils with internal.PrefuseStructure
    with internal.EventFiltering with PrefuseControllers =>
    
  import global.{Tree => STree, _}
  import EV._
  import UIConfig.{nodesLabel => label}
  
  import PrefuseComponent.toVisualNode
  
  import PrefusePimping._
  
  def debugUI(msg: => String) = global.debug(msg, "ui")
  
  class SwingController(srcs: List[io.AbstractFile])
    extends SwingFrame("Type debugger 0.0.3", settings.advancedDebug.value, srcs) {

    var lastAccessed: Option[NodeItem] = None
    val advController = new AdvController() 
    
    val highlightContr = new HighlighterAndGeneralInfo()
    val keyHandler = new KeyPressListener()
    
    private def codeHighlighter = sCodeViewer.getHighlighter()
    sCodeViewer.addCaretListener(new SelectionListener())

    
    def initAllDisplays(tree: io.AbstractFile => Tree, allGoals: List[UINode[PrefuseEventNode]]) {
      val groupedGoals: Map[io.AbstractFile, List[UINode[PrefuseEventNode]]] = allGoals groupBy { (node: UINode[PrefuseEventNode]) =>
        node.ev.file match {
          case Some(absFile) => absFile
          case None          => scala.tools.nsc.util.NoFile
        }
      }
      val vis = new TypeDebuggerVisualization()
      srcs.zipWithIndex foreach { case (src, idx) =>
        val component = new PrefuseController(idx, src, tree(src), vis,
                                              groupedGoals.getOrElse(src, Nil), advController)
        component.init()
        debug("Initialised prefuse tree component for " + src)
        
        component.addControlListener(highlightContr)
        component.addControlListener(new ClickedNodeListener())    
        component.addControlListener(new StickyNode())
        component.addControlListener(HiddenEvents)

        //component.addControlListener(new LinkNode())
        //component.addControlListener(keyHandler)
        tabDisplayFiles.add(src.name, component)
        if (idx < 9)
          tabDisplayFiles.setMnemonicAt(idx, 48 + idx)
      }
      
      if (srcs.isEmpty)
        println("[Warning] No files specified for debugging.")
      else {
        loadSourceFile(srcs.head)
        currentDisplay.reRenderView()
      }
    }
    
    def processKeyEvent(k: KeyEvent, component: PrefuseComponent) {
      keyHandler.keyPressed(k)
    }
    
    // specific adapters, actions
    class SelectionListener() extends CaretListener {
      
      private def orderedPos(e: CaretEvent): (Int, Int) = 
        if (e.getDot > e.getMark) (e.getMark, e.getDot)
        else (e.getDot, e.getMark)
      
      def caretUpdate(e: CaretEvent) {
        debug("Caret update: " + e.getDot + " .... " + e.getMark)
        // locate tree
        srcs match {
          case List(oneSource) =>
            val sFile = new BatchSourceFile(oneSource)
            val (start, end) = orderedPos(e)
            val position = global.rangePos(sFile, start, start, end)
            debug("Selection position: " + position)
            val t = global.locate(position)
            currentDisplay.grabFocus() // fix focus for key navigation
            debug("Overlapping tree: " + t + "\n with pos " + t.pos + " treeOfClass " + t.getClass)
            if (t != EmptyTree) {
              highlightContr.clearHighlight(true)
              val ref = highlightContr.highlight(position, highlightContr.TargetDebuggingHighlighter)
              highlightContr.activeSelection = ref
              targetedCompile(position)
            } else
              debug("invalid selection")

          case _ => // not supported yet

        }
      }
    }
    
    class HighlighterAndGeneralInfo() extends ControlAdapter {
      var activeSelection: AnyRef =  null
      
      override def itemClicked(item: VisualItem, e: MouseEvent) = 
        if (containsDataNode(item) && e.isAltDown())
            fullEventInfo(asDataNode(item).ev)

      override def itemEntered(item: VisualItem, e: MouseEvent) {
        if (containsDataNode(item)) {
          clearHighlight()
          val node = asDataNode(item)
          node.ev match {
            case e:TreeEvent if e.tree.pos.isRange =>
              val prettyTree = asString(e.tree)
              ASTViewer.setText(prettyTree)
              highlight(e.tree.pos, TreeMainHighlighter)
              
            case e: SymEvent if e.sym.pos.isRange =>
              highlight(e.sym.pos, TreeMainHighlighter)
              
            case _ =>
              debug("No precise position for " + node + ". Trying parents.")
              // Fallback try to find the closest parent that has positions set
              def canHighlight(n: UINode[PrefuseEventNode]) = n.ev match {
                case e: TreeEvent if e.tree.pos.isRange => true
                case e: SymEvent if e.sym.pos.isRange => true
                case _ => false
              }
              
              var nodeWithPos: Option[UINode[PrefuseEventNode]] = node.parent
              while (nodeWithPos.isDefined && !canHighlight(nodeWithPos.get)) {
                nodeWithPos = nodeWithPos.get.parent
              }
              
              // TODO refactor
              if (nodeWithPos.isDefined) {
                nodeWithPos.get.ev match {
                  case e: TreeEvent => highlight(e.tree.pos, TreeMainHighlighter)
                  case e: SymEvent  => highlight(e.sym.pos, TreeMainHighlighter)
                  case _            => ()
                }
              }
          }
          
          node.ev match {
            case e: SymbolReferencesEvent =>
              e.references.foreach((ref:Symbol) => if (ref != null) highlight(ref.pos, TreeReferenceHighlighter))
            case e: TreeReferencesEvent =>
              e.references.foreach((ref:STree) => highlight(ref.pos, TreeReferenceHighlighter))
            case _ =>
          }
        }
      }
      
	    override def itemExited(item: VisualItem, e: MouseEvent) {
	      ASTViewer.setText(null)
	      clearHighlight()
	    }
      
      private[SwingControllers] def highlight(pos: Position, colorSelection: DefaultHighlightPainter): AnyRef = 
        if (pos.isRange) codeHighlighter.addHighlight(pos.start, pos.end, colorSelection)
        else null
  
      private[SwingControllers] def clearHighlight(force: Boolean = false) = {
        codeHighlighter.getHighlights foreach (h =>
          if (force || h != activeSelection) codeHighlighter.removeHighlight(h)
        )
      }
     
      object TreeMainHighlighter extends DefaultHighlightPainter(Color.red)
      object TreeReferenceHighlighter extends DefaultHighlightPainter(Color.green)
      object TargetDebuggingHighlighter extends DefaultHighlightPainter(new Color(204, 204, 204, 80))
    }
    
    object HiddenEvents extends ControlAdapter {
      private final val PREFIX = "Filtered debugging: "
      override def itemEntered(item: VisualItem, e: MouseEvent) {
        item match {
          case node: NodeItem =>
            // display information about hidden events (if any)
            val children = node.children_[NodeItem].flatMap { ch =>
              if (!ch.isVisible && asDataNode(ch).advanced && !advController.isOptionEnabled(ch))
                Some(FilteringOps.map(asDataNode(ch).ev))
              else None
            }.toList
            if (children.nonEmpty)
              statusBar.setText(PREFIX + children.distinct.mkString(","))
          case _ =>
        }
      }
      
      override def itemExited(item: VisualItem, e: MouseEvent) {
        statusBar.setText("")
      }
    }
    
    // 'Stick' node that was clicked with Shift & Ctrl.
    // It will be visible even if it is not on a path to a goal(errors etc).
    class StickyNode extends ControlAdapter {
      override def itemClicked(item0: VisualItem, e: MouseEvent) {
        if (!e.isControlDown() || !e.isShiftDown())
          return

        item0 match {
          case item: NodeItem =>
            val display = currentDisplay
            val vis = item.getVisualization
            val fGroup = vis.getFocusGroup(display.stickyNodes)
            if (fGroup.containsTuple(item)) {
              fGroup.removeTuple(item)
              vis.getFocusGroup(display.toRemoveNodes).addTuple(asDataNode(item).pfuseNode)
              cleanup(item.getSourceTuple.asInstanceOf[Node], vis, display)
            } else {
              fGroup.addTuple(item)
            }
            lastAccessed = Some(item)
          case _ =>   
        }
      }
	    
	    def cleanup(starting: Node, vis: Visualization, display: PrefuseComponent) {
	      var node = starting
	      val tsNonGoal = vis.getFocusGroup(display.nonGoalNodes)
	      val tsRemove = vis.getFocusGroup(display.toRemoveNodes)
	      val tsGoal = vis.getFocusGroup(display.openGoalNodes)
	      while (!(tsGoal containsTuple node) && node.getParent != null) {
	        tsNonGoal.removeTuple(node)
	        tsRemove.addTuple(node)
	        node = node.getParent
	      }
	    }
	  }
	  
	  class KeyPressListener extends ControlAdapter {
	    val keyFilter = List(KeyEvent.VK_DOWN, KeyEvent.VK_UP, KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT,
	                         KeyEvent.VK_E, KeyEvent.VK_C, KeyEvent.VK_ENTER, KeyEvent.VK_ESCAPE)
	    def display = currentDisplay

	    override def itemKeyPressed(item: VisualItem, k: KeyEvent) = keyPressed(k)
	    
	    override def keyPressed(k: KeyEvent): Unit = {
	      val keyCode = k.getKeyCode 
	      if (!(keyFilter contains k.getKeyCode))
	        return

	      lastAccessed match {
  	      case Some(last) =>
  	        debugUI("Key pressed. Last accessed event " + last)	
    	      val vis = last.getVisualization
    	
    	      display.tooltipController.clearTooltip()
    	      keyCode match {
    	        case KeyEvent.VK_DOWN  =>
    	          // expand down (if necessary)
    	          if (last.getParent != null)
    	            navigate(last.getParent.asInstanceOf[NodeItem], vis)
    	          
    	        case KeyEvent.VK_LEFT  =>
    	          // expand left neighbour (if possible)
    	          val prevSibling = last.getPreviousSibling()
    	          if (prevSibling != null)
    	            navigate(prevSibling.asInstanceOf[NodeItem], vis)
    	          else {
    	            // go around
    	            val parent = last.getParent
    	            if (parent != null) {
    	              val lastSibling = parent.getLastChild()
    	              if (lastSibling != last)
    	                navigate(lastSibling.asInstanceOf[NodeItem], vis)
    	            }
    	          }
    	          
    	        case KeyEvent.VK_RIGHT =>
    	          // expand right neighbour (if possible)
    	          val nextSibling = last.getNextSibling()
    	          if (nextSibling != null)
    	            navigate(nextSibling.asInstanceOf[NodeItem], vis)
    	          else {
    	            // go around
                  val parent = last.getParent
                  if (parent != null) {
                    val firstSibling = parent.getFirstChild()
                    if (firstSibling != last)
                      navigate(firstSibling.asInstanceOf[NodeItem], vis)
                  }
    	          }
    	
    	        case KeyEvent.VK_UP    =>
    	          if (last.getChildCount() > 0)
    	            navigate(last.getFirstChild().asInstanceOf[NodeItem], vis)
    
    	        case KeyEvent.VK_E     => // expand
    	          expand(last)
    
    	        case KeyEvent.VK_C     => // collapse
    	          collapse(last)
    
    	        case other =>
    	          controlKeyPressed(other, last)
  
            }
  
    	    case None =>
    	      keyCode match {
    	        case KeyEvent.VK_DOWN =>
                val lowest = display.getBottomNode
                debugUI("Key pressed. Initial 'lowest' node: " + lowest)
    	          if (lowest != null)
    	            navigate(lowest, display.getVisualization)
    	        case _                => // enable others
    	      }
        }
	    }
	    
	    def controlKeyPressed(k: Int, node: NodeItem) = k match {
	      case KeyEvent.VK_ENTER =>
	        val pNode = asDataNode(node).pfuseNode
	        // FIXME
	        node.getVisualization.items(new VisualItemSearchPred(pNode)).toList match {
	          case List(single: VisualItem) =>
	            display.tooltipController.showItemTooltip(single)
	          case _ =>
	        }
	        
	      case KeyEvent.VK_ESCAPE =>
	        display.tooltipController.clearTooltip()
	      
	      case _ =>
	        
	    }
	    
	    def navigate(target: NodeItem, vis: Visualization) {
	      debug("navigate to: " + target, "ui")
	      ClickedNodeListener.addClickedItem(target, display)
	      highlightContr.itemEntered(target, null)
        // need to schedule the action by hand since keycontrol doesn't do it
        display.reRenderView()
	    }
	    
	    def expand(last: NodeItem) = {
	      val nodes = advancedNodes(!_.isVisible, last) 
	      if (nodes.nonEmpty) {
	        nodes.distinct foreach advController.enableOption
	        display.reRenderView()
	      }
	    }
	    
	    def collapse(last: NodeItem) = {
        val nodes = advancedNodes(_.isVisible, last) 
        if (nodes.nonEmpty) {
          nodes.distinct foreach advController.disableOption
          display.reRenderView()
        }
      }
	    
	    private def advancedNodes(visibleState: NodeItem => Boolean, last: NodeItem) = {
	      last.children_[NodeItem] flatMap { ch =>
	        if (visibleState(ch) && asDataNode(ch).advanced)
	          Some(FilteringOps.map(asDataNode(ch).ev))
	        else None
	      } toList
	    }
	  }
	  
    // Find node which is somehow linked (tree or symbol reference) to the
	  // one that was just clicked (with Ctrl).
	  // Use case: clicking on a node to see at what point it's type was set.
/*	  class LinkNode extends ControlAdapter {
	    
	    import PrefuseComponent.{linkGroupNodes => linkGroup,
	                             treeNodes, nonGoalNodes, openGoalNodes}
	    
	    class FindNode(id: Int) extends AbstractPredicate {
	      override def getBoolean(t: Tuple): Boolean = t match {
	        case node: NodeItem if containsDataNode(node) =>
	          val ev = asDataNode(node).ev
	          ev != null && ev.id == id
	        case _ =>
	          false
	      }
	    }
	    
	    def addLinkPath(starting: NodeItem, vis: Visualization) {
	      var n = asDataNode(starting)
	      val tsNonGoal = vis.getFocusGroup(nonGoalNodes)
	      while (!n.goal && n.parent.isDefined) {
	        tsNonGoal.addTuple(n.pfuseNode)
	        n = n.parent.get
	      }
	      
	      if (n.goal) {
	        val tsGoal = vis.getFocusGroup(openGoalNodes)
	        while (!tsGoal.containsTuple(n.pfuseNode)) {
	          tsGoal.addTuple(n.pfuseNode)
	          // better check 
	          n = n.children.find(_.goal).get
	        }
	      }
	    }
	    
	    override def itemClicked(item: VisualItem, e: MouseEvent) {
	      if (!e.isControlDown() || e.isShiftDown())
	        return
	        
	      val vis = item.getVisualization
	      item match {
	        case node: NodeItem =>
     	      val eNode = asDataNode(node)
	          eNode.ev match {
	            case e@IdentTyper(tree0) =>
			          debug("[Link] IdentTyper event " + tree0.symbol)
			          val refId = tree0.symbol.previousHistoryEvent(e.id)
			          if (refId != NoEvent.id) {
			              // Find corresponding event and node in the tree
			              //println("Found info in the history: " + refId)
			              val ts2= vis.items_[NodeItem](treeNodes, new FindNode(refId))
			              val tsTarget = vis.getFocusGroup(linkGroup)
			              // will ts2 return NodeItem or Node
			              ts2.foreach(n => {
			                tsTarget.addTuple(n)
			                // need to find common root with the currently visible tree
			                // go until you find goal
			                addLinkPath(n, vis)               
			              })
			          }
			        case _ =>
			          // Do nothing for the moment
			          // we need to find other cases, where
			          // we might want to link
			      }
	        case _ =>
	      }
	    }
	  }*/
	  
    // Handle action on the node of the graph.
    // Expand the node that was just clicked. Also cleanup all the intermediate nodes leading to it.
    class ClickedNodeListener() extends ControlAdapter {
      import ClickedNodeListener._
      override def itemClicked(item0: VisualItem, e: MouseEvent) {
        if (e.isControlDown() || e.isShiftDown())
          return
          
        item0 match {
          case item: NodeItem =>
            addClickedItem(item, currentDisplay)
          case _ =>
        }
      }      
    }
  
	  object ClickedNodeListener {
	    def addClickedItem(node: NodeItem, comp: PrefuseComponent) {
	      // Add or remove from focus group
	      debug("Add clicked item " + node)
	      val vis = node.getVisualization
	      val ts1 = vis.getFocusGroup(comp.openGoalNodes)
	      val clicked = vis.getFocusGroup(comp.clickedNode)
	
	      CleanupAction.clean(node, comp)
	      clicked.clear()
	      clicked.addTuple(node)
	      
	      if (ts1.containsTuple(node.getSourceTuple)) {
	        if (node.getParent != null) {
	          node.getParent match {
	            case item: NodeItem =>
	              ts1.addTuple(item.getSourceTuple)
	            case parent: Node =>
	              ts1.addTuple(parent)
	            case _ =>
	              throw new Exception("Prefuse bug!")
	          }
	        }
	      } else {
	        var item: Node = node.getSourceTuple().asInstanceOf[Node] // ts1 contains SourceTuples not VisualItems, meh
          // re-add nodes that should be in ts2
          // this means adding all non-goals on the path from this node upwards upto a goal node
          // (the latter is guaranteed to be reached since we wouldn't be able to reach item0 otherwise)
          // This is cleaner than complicating the logic for the removal
	        val ts2 = vis.getFocusGroup(comp.nonGoalNodes)
          while (item != null && !ts1.containsTuple(item)) {
            ts2.addTuple(item)
            item = item.getParent()
          }
	      }
	      lastAccessed = Some(node)        
	    }
	  }
	  
	  object CleanupAction {
	    def clean(item0: VisualItem, comp: PrefuseComponent) {
	      if (!containsDataNode(item0))
	        return
	
	      val vis = item0.getVisualization
	      //val List(ts1, ts2, ts3, tsRemove) =
	      val ts1 = vis.getFocusGroup(comp.openGoalNodes)
	      val ts2 = vis.getFocusGroup(comp.nonGoalNodes)
	      val tsRemove = vis.getFocusGroup(comp.toRemoveNodes)
	        //List(openGoalNodes, nonGoalNodes, linkGroupNodes, toRemoveNodes).map(vis.getFocusGroup(_))
	      
	      // Remove all the link nodes
	      //ts3.foreach(tsRemove.addTuple)
	      //ts3.clear()

        // collapse all non-goals (parts need to be re-created if necessary)
        ts2.foreach(tsRemove.addTuple)
        ts2.clear()

        if (ts1.containsTuple(item0.getSourceTuple)) {
	        // Collapse all the subgoals above
          val parent0 = item0.getSourceTuple.asInstanceOf[Node].getParent // casting unfortunately necessary
          val parent = parent0 match {
            case visItem: NodeItem if visItem != null => visItem.getSourceTuple.asInstanceOf[Node]
            case parent0: Node     => parent0
            case _                 =>
              if (parent0 != null) throw new Exception("Prefuse bug!") else null
          }
          if (parent != null) {
            val cached = comp.nodesAlwaysVisible
            var item = parent
            val ts1 = vis.getFocusGroup(comp.openGoalNodes)
            while (item.getParent != null && ts1.containsTuple(item.getParent)) {            
              item = item.getParent
              // keep the minimal subset always expanded
              if (!cached.contains(toVisualNode(item, vis, comp.dataGroupName))) {
                ts1.removeTuple(item)
                tsRemove.addTuple(item)
              }
            }
	        }
	      } else {
	        // necessary non-goal nodes should be re-added. See addClickedItem handler for non-goal
	      }
	    }
	  }
	  
	  // only debugging information
    private def fullEventInfo(ev: Event) {
	    if (settings.debugTD.value == "ui" && ev != null) {
	      println("----------------")
	      println("ITEM [" + ev.id + "] CLICKED: " + ev.getClass)
	      ev match {
	        case e0: TreeEvent => println("TREE POS: " + e0.tree.pos)
	        case e0: SymEvent  => println("SYM POS: " + e0.sym.pos)
	        case _ =>
	      }
	      ev match {
	        case e0: SymbolReferencesEvent => println("References symbol: " + e0.references.map(_.pos))
	        case e0: TreeReferencesEvent   => println("References tree: " + e0.references.map(_.pos))
	        case _ =>
	      }
	      
	      if (ev.isInstanceOf[DoneBlock])
	        println("DONE BLOCK: " + ev.asInstanceOf[DoneBlock].originEvent)
	      if (ev.isInstanceOf[TyperTyped]) {
	        val nTyperTyped = ev.asInstanceOf[TyperTyped]
	        val expl = nTyperTyped.expl
	        println("[TYPER-TYPED] : " + expl + " " + nTyperTyped.tree.getClass + " ||" +
	          expl.getClass)
	      }  
	      println("----------------")
	    }
	  }
  }
  
  class AdvController extends AdvancedOptionsController {
    private val advFilter = new mutable.BitSet()
    
    def enableOption(v: Filtering.Value) { advFilter += v.id }
    def disableOption(v: Filtering.Value) { advFilter -= v.id}
    
    def isOptionEnabled(t: Tuple): Boolean = {
      val ev = asDataNode(t).ev
      FilteringOps.map.isDefinedAt(ev) && advFilter(FilteringOps.map(ev).id)
    }
    def isAdvancedOption(t: Tuple): Boolean = asDataNode(t).advanced
  }
}