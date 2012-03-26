package scala.typedebugger
package internal

import prefuse.data.Node
import scala.collection.mutable
import mutable.{ ListBuffer }

trait PrefuseStructure extends IStructure {
  self: CompilerInfo with processing.StringOps =>
    
  import global._
  import EV._
  
  val COLUMN_PREFUSENODE_CLASS = (new PrefuseEventNode(null, null, null)).getClass
  
  trait UINodeLike[T, Container[X]] extends BaseTreeNodeLike[T, Container] {
    val pfuseNode: Node
    def fullInfo: String
    var goal: Boolean
    def advanced: Boolean
  }
  
  trait UINode[T] extends UINodeLike[T, UINode]

  class PrefuseEventNode(val ev: Event,
                         val parent: Option[UINode[PrefuseEventNode]],
                         val pfuseNode: Node) extends UINode[PrefuseEventNode] {
    val children = ListBuffer[UINode[PrefuseEventNode]]()
    var goal = false // for caching purposes so that we don't have to constantly
                     // check neighbors
    
    // TODO refactor to a separate place that handles all the UI stuff
    override def toString =
      if (ev != null) {
        ev match {
          case tpchecker:TyperTyped =>
            Explanations(tpchecker)
          case _ =>
            EventDescriptors(ev).basicInfo
        }
      } else "Typecheck full tree" // root
        
    // TODO this should go
    def fullInfo =
      if (ev != null)
        ev match {
          case evTyped: TyperTyped =>
            //TODO This still needs adjustment
            val tpe = if (evTyped.tree.tpe != null) evTyped.tree.tpe else if (evTyped.tree.symbol != null) evTyped.tree.symbol.tpe else null
            /*(evTyped formattedString PrefuseEventNode.fmtFull) + "\n" + 
            "Type of tree: [ " + tpe + " ] expected: [ " + evTyped.pt + " ]" + 
            "\nDebugging info: " + evTyped.tree.getClass +
            "\nEvent toString: " + evTyped.eventString*/
            evTyped.expl + "\n\n" +
            "Typechecking tree: \n " +
            snapshotAnyString(evTyped.tree)(evTyped.time) + "\n\n" +
            (if (settings.debugTD.value && evTyped.tree != null) "Tree class " + evTyped.tree.getClass + " with sym " + evTyped.tree.symbol else "") +
            "\nExpected type: " + (if (evTyped.pt == WildcardType) "None" else snapshotAnyString(evTyped.pt)(evTyped.time)) +
            (if (tpe != null) "\nType of tree set to: " + snapshotAnyString(tpe)(evTyped.time) else " Tree not yet typed")
            //"\nTree class " + evTyped.tree.getClass + " pos " + evTyped.tree.pos

          case _ =>
            EventDescriptors(ev).fullInfo
            //ev formattedString Formatting.fmtFull
        }
      else "Typecheck full tree" // root
    
    // Determine initial state for the node
    // This can change by enabling options explicitly
    lazy val advanced: Boolean = advanced0
        
    private def advanced0: Boolean = ev match {
      case _: ConvertConstrBody =>
        true

      case e: ValidateParentClass =>
        e.parent.symbol != null && (e.parent.symbol == definitions.ScalaObjectClass ||
                                    e.parent.symbol == definitions.ObjectClass)

      case e: TyperTyped =>
        val res = e.tree match {
          // Should make it more specific, i.e. search for synthetics 
          case ddef: DefDef if ddef.name == nme.CONSTRUCTOR || (ddef.symbol != null && ddef.symbol.isSynthetic) =>
            true
          case _ =>
            false
        }
        if (!res) {
          e.expl match {
            case e@TypeTemplateStatement(stat) =>
              if (stat.symbol != null) stat.symbol.isSynthetic || stat.symbol.isGetter
              else false
            case _ =>
              false
          }
        } else true
        
      case e: ProtoTypeArgsDoTypedApply =>
        true
        
      case e: ImplicitsEligibility =>
        true

      case e: CheckTypesCompatibility =>
        true
        
      case e: SubTypeCheck =>
        true
        
      case e: Subtyping =>
        true
        
      case e: OverloadedSymDoTypedApply =>
        true
        
      case e: ImprovesAlternativesCheck =>
        true
        
      case _ =>
        false
      
    }
  }
}