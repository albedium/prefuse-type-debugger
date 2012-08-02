package scala.typedebugger
package stringops

import scala.tools.nsc.symtab
import scala.collection.mutable
import scala.ref.WeakReference

import util. { StringFormatter, Formatting }

trait StringOps extends AnyRef
                with TyperStringOps
                with AdaptStringOps
                with InferStringOps
                with ImplicitsStringOps
                with NamerStringOps
                with TypesStringOps
                with util.DebuggerUtils {
  self: internal.CompilerInfo with internal.SyntheticEvents with internal.PrefuseStructure =>
    
  import global.{Tree => STree, _}
  import EV._
  
  //implicit val snapshotOpenTypes: List[Type] = Nil
  
  object Explanations extends AnyRef
                      with TyperExplanations
                      with NamerExplanations
                      with AdaptExplanations {
    def apply(ev: TyperTyped): String = ev.expl match {
      case DefaultExplanation =>
        ev formattedString Formatting.fmt
      case tEV: TyperExplanation =>
        explainTyper(tEV)(ev.time)
      case nEV: NamerExplanation =>
        explainNamer(nEV)(ev.time)
      case aEV: AdaptExplanation =>
        explainAdapt(aEV)
      case iEV: InferExplanation =>
        "" //ev formattedString PrefuseEventNode.fmt
      case _ =>
        ev formattedString Formatting.fmt
    }
  }

  object EventDescriptors extends AnyRef
                with ErrorEvents
                with TyperEventsOps
                with AdaptEventsOps
                with InferEventsOps
                with ImplicitsEventsOps
                with NamerEventsOps
                with TypesEventsOps
                with SyntheticStringOps
                with GeneralStringOps
                with Descriptors {

    private[this] val cache = new mutable.WeakHashMap[Int, WeakReference[Descriptor]]()
    
    def clearCache() {
      cache.clear()
    }
    
    private def update(e: Event, descr: Descriptor): Descriptor = {
      cache(e.id) = new WeakReference(descr)
      descr
    }
    
    def apply(e: Event, node: UINode[PrefuseEventNode]): Descriptor = cache.get(e.id) match {
      case Some(ref) =>
        ref.get match {
          case Some(v) => v
          case None    => update(e, generate(e, node))
        }
      case None =>
        update(e, generate(e, node))
    }
    
    def generate(e: Event, node: UINode[PrefuseEventNode]): Descriptor = e match {
      case eEV: ContextTypeError =>
        explainError(eEV)
      case ev: TyperEvent        =>
        explainTyperEvent(ev)
      case ev: DoTypedApplyEvent =>
        explainTyperEvent(ev)
      case ev: AdaptToEvent      =>
        explainTyperEvent(ev)
      case ev: AdaptEvent        =>
        explainAdaptEvent(ev)
      case ev: InferEvent        =>
        explainInferEvent(ev)
      case ev: ImplicitEvent     =>
        explainImplicitsEvent(ev)
      case ev: NamerEvent        =>
        explainNamerEvent(ev, node)
      case ev: LubEvent          =>
        explainLubGlbEvent(ev)
      case ev: TypesEvent        =>
        explainTypesEvent(ev, node)
      case ev: SyntheticEvent    =>
        explainSyntheticEvent(ev, node)
      case _                     =>
        explainGeneralEvent(e)
    }
  }
  
  private def printDebug(ev: Explanation) {
    debug("No explanation for " + ev.getClass, "ui")
  }
  
  // TODO incorporate into our string converter when
  // we move snapshotAnyString directly to prefuse
  def safeTypePrint(tp: Type, pre: String = "", post: String = "", truncate: Boolean = true, slice: Boolean = false)(implicit time: Clock): String =
    if (tp != null && tp != NoType) {
      val stringRep0 = snapshotAnyString(tp)
      // strip kind information
      val ExactType = """\[[^:]*: (.*)\]""".r
      val MethodTypeRegex = """\[MethodType: (.*)\]""".r
      val ErrorType = """\[ErrorType: (.*)\]""".r
      val WildcardType = """\[WildcardType: (.*)\]""".r
      val stringRep = stringRep0 match {
        //case MethodTypeRegex(tpe) => "(" + tpe // TODO fix
        case WildcardType(_)      => "?"
        case ErrorType(_)         => ""
        case ExactType(tpe)       => tpe
        case _                    => stringRep0
      }
      val msg =
        if (truncate && (stringRep.length > Formatting.maxTypeLength))
          if (slice) stringRep.slice(0, Formatting.maxTypeLength - 3) + "..." else ""// || tp.isErroneous)) ""
        else
          stringRep
      if (msg != "") pre + msg + post else ""
    } else if (tp eq NoType) "NoType"
    else ""
      
  def truncateStringRep(v1: String, v2: String, join: String, pre: String) = {
    val totalLength = v1.length + v2.length + join.length
    if (totalLength > Formatting.maxTypeLength || v1 == "" || v2 == "") ""
    else pre + v1 + join + v2
  }
  
  def snapshotAnyString(x: Any)(implicit c: Clock): String = x match {
    case list: List[_] => list.map(snapshotAnyString).mkString
    case t: STree      => anyString(treeAt(t))
    case tp: Type      => anyString(TypeSnapshot.mapOver(tp))
    case sym: Symbol   => anyString(SymbolSnapshot.mapOver(sym)) 
    case _             => anyString(x)
  }
  
  // not efficient if we are getting the snapshot of x as well in the event 
  def combinedSnapshotAnyString[T, U](x: T)(y: T => U)(implicit c: Clock): String = {
    x match {
      case t: STree =>
        // bug with [T <: Tree] and expecting T?
        val t1 = treeAt(t).asInstanceOf[T]
        snapshotAnyString(y(t1))
      case tp: Type => 
        val tp1 = TypeSnapshot.mapOver(tp).asInstanceOf[T]
        snapshotAnyString(y(tp1))
      case sym: Symbol =>
        val sym1 = SymbolSnapshot.mapOver(sym).asInstanceOf[T]
        snapshotAnyString(y(sym1))
      case _ => snapshotAnyString(y(x))
    }
  }
  
  // customizable length
  def visibleName(name: Name): Option[String] =
    if (name.toString.length < 15) Some(name.toString) 
    else None
    
  // todo: mixing with string interpolation would be the best 
  def visibleNameInTemplate(name: Name, templ: String => String): String =
    visibleName(name) match {
      case Some(str) => templ(str)
      case None      => templ("")
    }
  
  def simpleName(name: Name): String = visibleNameInTemplate(name, (s: String) => s)
  
  
  // TODO refactor
  trait TyperExplanations {
    def explainTyper(ev: Explanation with TyperExplanation)(implicit time: Clock): String = ev match {
      case _: TypeUnit =>
        "Typecheck unit"
        
      case _: TypePackageQualifier =>
        "Typecheck package qualifier"
        
      case _: TypePackageStatement =>
        "Typecheck package member"
    
      case e@TypeTemplateStatement(stat) =>
         val parent = e.templateInfo match {
           case ClassTemplate => "class"
           case ModuleTemplate => "object"
           case TraitTemplate => "trait"
         }
         stat match {
           case DefDef(_, nme.CONSTRUCTOR, _, _, _, _) =>
             "Typecheck " + parent + " constructor"
           case stat: MemberDef =>
             val mem = if (stat.symbol != NoSymbol && stat.symbol != null ) { ": '" + stat.symbol + "'" } else ""
             "Typecheck " + parent + " member" + mem
           case _ =>
             "Typecheck expression in " + parent
         }
        //"Typecheck member in template"

      case TypeExplicitTreeReturnType(_, tpeTree) =>
        //"Typecheck return type " + snapshotAnyString(tpe) + " of the function"
        if (tpeTree.tpe.resultType.typeSymbol == definitions.UnitClass) "Typecheck Unit return type"
        else "Typecheck explicit return type"

      case _: TypeDefConstr => 
        "Typecheck body of the constructor"

      case _: TypeDefBody =>
        "Typecheck body of the definition"

      // Apply
      case _: TypeFunctionApply =>
        "Typecheck function"
        //"Typecheck function in the application"

      case _: TypeArgsApply =>
        "Typecheck arguments"
        //"Typecheck arguments in the application"
        //override def provide(a: Tree): Explanation = TypeArgApply(a)

      case _: TypeArgApply =>
        "Typecheck argument"
        //"Typecheck single argument for application"

      // Apply overloaded
      case _: TypeArgsInOverloadedApply => 
        "Typecheck arguments \n in overloaded application"
        // override def provide(a: Tree): Explanation = TypeArgInOverloadedApply(a)

      case _: TypeArgInOverloadedApply => 
        "Typecheck argument \n without expected type \n in overloaded application"

      //Eta
      case _: TypeEtaExpandedTreeWithWildcard => 
        "Typecheck eta-expanded tree\n without the expected type involved"

      case _: TypeEtaExpandedTreeWithPt => 
        "Typecheck newly eta-expanded tree\n with the expected type"

      //TypeApply
      case _: TypeTypeConstructorTypeApply => 
        "Typecheck function\n in type application"

      case _: TypeHigherKindedTypeApplyWithExpectedKind =>
        "Typecheck higher-kinded type with expected kind"

      case _: TypeHigherKindedTypeApplyOverloaded =>
        "Typecheck higher-kinded overloaded polymorphic type"

      case _: TypeHigherKindedTypeForAppliedType =>
        "Typecheck higher-kinded type in applied type"

      //TypeFunction
      case _: TypeFunctionParameter =>
        "Typecheck function parameter"

      case _: TypeFunBody =>
        "Typecheck function body"

      //Do typed apply, try typed apply
      case _: TypeArgStandalone =>
        "Typecheck argument\n without expected type context"
        //"Typecheck arguments without taking into account formal parameter types, for " +
        //"further adaption of the qualifier"
        //override def provide(a: Tree): Explanation = TypeArgStandalone(a)
        
      case _: TypeArgForCorrectArgsNum =>
        "Typecheck single argument \n (number of parameters agrees with parameters)"
          
      case expl: TypeArgWithLenientPt =>
        if (expl.pt == WildcardType)
          "Typecheck argument \nwithout expected type"
        else
          "Typecheck argument \nwith lenient target type" + safeTypePrint(expl.pt, pre="\n")

      //Block
      case _: TypeStatementInBlock =>
        "Typecheck statement"
        //"Normal typecheck for statemenet in block"
        //override def underlying = underlying0

      case _: TypeLastStatementInBlock =>
        "Typecheck last statement"
        //"Typecheck of the final statement in block"
        //override def underlying = underlying0

      //Select
      case _: TypeQualifierInSelect =>
        "Typecheck qualifier"
        //"Typecheck qualifier for select statemenent"

      case _: TypeSuperQualifier =>
        "Typecheck super-dot qualifier"
        //"Typecheck qualifier in super call"

      case _: TypeAdaptedQualifer =>
        "Typecheck qualifier adapted to member"
        //"Typecheck qualifier adapted to member"

      //Super
      case _: TypeQualifierInSuper =>
        "Typecheck qualifier in super-dot"

      //AdaptToMember
      case _: TypeAppliedImplicitView =>
        //"Typecheck qualifier with applied implicit view"
        "Typecheck application of inferred view\n that adapts qualifier"

      //ValDef
      case expl: TypeValType =>
        "Typecheck value's type" + safeTypePrint(expl.vdef.symbol.tpe, "\n")

      //Type constructor
      case _: TypeTypeConstructorInNew =>
        "Typecheck type constructor in a new" // TODO

      //Parent typing
      case _: TypeInitialSuperType =>
        "Typecheck first parent\n as initial supertype"
        //"Type first of the parents as the initial supertype"

      case TypeParentMixin(mixin) =>
        "Typecheck mixin\n" + anyString(mixin)
        //"Type of the mixed in parents"
 
      case _: TypeCurrentTraitSuperTpt =>
        "Typecheck current\n super-type"
        //"Typer current supertype that is trait\n" +
        //"to replace it with it's supertpe" 

      case _: TypeFirstConstructor =>
        "Typecheck transformed \n primary constructor"//TODO
        //"Type transformed first constructor"

      //Typed
      case _: TypeExplicitTypeAnnotation =>
        "Typecheck type annotation"
        //"Type explicit type annotation"  

      case _: TypeAnnotatedExpr =>
        "Typecheck type-annotated expression"
        //"Type type-annotated expression"

      // Bounds checking
      case TypeLowerBound(bound) =>
        bound match {
                  case Select(_, tpnme.Nothing) =>
                    "Typecheck lower bound:Nothing"
                  case _ =>
                    "Typecheck lower bound"
                }
        //"Type lower of the type bounds"

      case TypeHigherBound(bound) =>
        bound match {
                  case Select(_, tpnme.Any) =>
                    "Typecheck higher bound:Any"
                  case _ =>
                    "Typecheck higher bound"
                }
        //"Type higher of the type bounds"

      case _: TypeQualifierWithApply =>
                "Typecheck qualifier with \n 'apply()' member"

      //type parameters
      case _: TypeClassTypeParameter =>
        "Typecheck class type-parameter"

      case _: TypeHigherOrderTypeParameter =>
        "Typecheck higher-order type parameter"

      case _: TypeDefTypeParameter =>
        "Typecheck definition \n type parameter" 
        //"Type def type parameter"

      case TypeDefParameter(param) =>
        if (param.symbol.hasFlag(symtab.Flags.IMPLICIT)) "Typecheck implicit parameter" else "Typecheck parameter"
        //"Type def parameter"

      // refinement
      case _: TypeRefinementStatement =>
        "Typecheck refinement statement"

      case _: TypeExistentialTypeStatement =>
        "Typecheck existential type-clause"

      // Use case?
      case _: TypeUseCaseStatement =>
        "Typecheck use-case statement"
        //"Type statement in the use case"
      
      case _: TypeCasePattern =>
        "Typecheck case pattern"
        
      case _: TypeCaseGuard   =>
        "Typecheck case guard"
      
      case _: TypeCaseBody    =>
        "Typecheck case body"
        
      case _ =>
        printDebug(ev)
        "Typecheck ?"
    }
  }
  
  trait NamerExplanations {
    def explainNamer(ev: Explanation with NamerExplanation)(implicit time: Clock): String = ev match {
      case _: MethodReturnType =>
        "Typecheck return type of the method"
      
      case _: MethodReturnTypeAgain =>
        "Typecheck return type of the method (again) in Namer"

      case _: InferredMethodReturnType =>
        "Infer and typecheck result type of the method"

      case ValExplicitType(_, sym) =>
        "Typecheck explicit type of " + (if (sym.isMutable) "variable's" else "value's") + " signature"

      case _: TypeAbstractTpeBounds =>
        "Typecheck bounds for the abstract type definition"

      case TypeValDefBody(vdef, pt) =>
        "Typecheck body of the " + (if (vdef.symbol == null) "value/variable" else if (vdef.symbol.isValue) "value" else "variable") +
        (if (!pt) " to infer its type" +safeTypePrint(vdef.symbol.tpe, "\n(inferred type is ", ")") else "")

      case _: TypeMethodDefBody =>
        "Typecheck body of the method"
        
      case _ =>
        printDebug(ev)
        "Typecheck ?"
    }
  }
  
  trait AdaptExplanations {
    def explainAdapt(ev: Explanation with AdaptExplanation): String = ev match {
      case _: TypeQualifierWithApply =>
        "Typecheck adapted qualifier with .apply()"
    
      case _: MethodEtaExpansion =>
        "Eta-expansion adaptation"
    
      case NotASubtypeInferView(treetp, pt) =>
        // TODO fix printing
        "Infer view to satisfy \n" + anyString(treetp) + " <: " + anyString(pt)

      case _: FirstTryTypeTreeWithAppliedImplicitArgs => 
        "Typecheck tree with applied \n (inferred) implicit arguments"
    
      case _: FallbackImplicitArgsTypeClean =>
        "Fallback\n Applying implicit arguments failed.\n " +
      	"Type and adapt original tree without expected type"
      case _: TypeTreeWithAppliedImplicitArgs => 
        "Typecheck tree with applied \n (inferred) implicit arguments"

      case _: TypeImplicitViewApplication =>
        "Typecheck application of found implicit view\nto the initial expression"
      
      case _: TypeApplyAdapt =>
        "Typecheck expression with an adapted 'apply' member"
      
      case _ =>
        printDebug(ev)
        "Typecheck ?"

    }
  }
  
  trait SyntheticStringOps {
    self: Descriptors =>
    def explainSyntheticEvent(ev: SyntheticEvent, uiNode: UINode[PrefuseEventNode]) = ev match {
      case GroupEligibleImplicits(src) =>
        new Descriptor {
          def basicInfo = "What are the eligible " + sourceRep(src) + "?"
          def fullInfo  = "" // explain more for each kind
        }
      case GroupCheckBoundsOfTArgs()   =>
        new Descriptor {
          def basicInfo = "Do bounds of all inferred type arguments\nagree with formal type parameters?"
          def fullInfo  = ""
        }
      case GroupCheckConstrInstantiationsBounds() =>
        new Descriptor {
          def basicInfo = "Do instantations of type variable constraints\nagree with their bounds?"
          def fullInfo  = ""
        }
      case MethTypeArgsResTpeCompatibleWithPt() =>
        new Descriptor {
          def basicInfo = "Is the result type of the Method Type\ncompatible with the expected type\n(may contain undefined type arguments)?"
          def fullInfo  = ""
        }
      case ExprTypeTpeCompatibleWithPt() =>
        new Descriptor {
          def basicInfo = "Is the current type of the expression\ncompatible with the expected type\n(may contain undefined type arguments)?"
          def fullInfo  = ""
        }
      case TryToSolveTVars() =>
        new Descriptor {
          def basicInfo = "Given results from compatibility tests with expected type,\ncan we try to resolve any type variables?"
          def fullInfo  = ""
        }
      case _                           =>
        new Descriptor {
          def basicInfo = ev formattedString Formatting.fmt
          def fullInfo  = ev formattedString Formatting.fmtFull
        }
    }
    
    def sourceRep(s: ImplicitInfoSource): String = s match {
      case MemberS        => "member implicits"
      case LocalS         => "local implicits"
      case ImportS        => "imported implicits"
      case PackageObjectS => "package object implicits"
      case UnknownS       => "implicits" 
    }
  }
  
  trait GeneralStringOps {
    self: Descriptors =>
    def explainGeneralEvent(ev: Event) = ev match {
      case _: TyperApplyPhase =>
        new Descriptor {
          def basicInfo = "Debug typechecking"
          def fullInfo  = ""
        }
      case _ =>
        new Descriptor {
          def basicInfo = ev formattedString Formatting.fmt
          def fullInfo  = ev formattedString Formatting.fmtFull
        }
    }
  }
  
  // TODO, should be able to provide more details message
  trait ErrorEvents {
    self: Descriptors =>
    def explainError(ev: Event with ContextTypeError)= ev match {
      case ContextAmbiguousTypeErrorEvent(err, level) =>
        new Descriptor {
          def basicInfo = level match {
            case ErrorLevel.Hard => "Ambiguous type error"
            case ErrorLevel.Soft => "Recoverable ambiguous type error"
          }
          def fullInfo  = "Ambiguous type error:\n" + err.errMsg
        }
      
      case ContextTypeErrorEvent(err, level) =>
        new Descriptor {
          def basicInfo = level match {
            case ErrorLevel.Hard => "Type error"
            case ErrorLevel.Soft => err.errMsg + "\n" + "Recoverable type error"
          }
          def fullInfo  = "Type error:\n" + err.errMsg
        }
      
      case _ =>
        new DefaultDescriptor("error")
    }
  }
  
  trait Descriptors {
    abstract class Descriptor() {
      def basicInfo: String
      lazy val lazyBasicInfo = basicInfo
      def fullInfo: StringFormatter
      lazy val lazyFullInfo = fullInfo
      def info(kind: Boolean) = if (kind) lazyBasicInfo else lazyFullInfo
    }
    
    class DefaultDescriptor(what: String) extends Descriptor {
      def basicInfo: String = "(" + what + " | not implemented)"
      def fullInfo: StringFormatter = "(" + what + " | not implemented)"
    }
  }
}