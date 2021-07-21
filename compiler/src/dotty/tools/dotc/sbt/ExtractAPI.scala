package dotty.tools.dotc
package sbt

import ExtractDependencies.internalError
import ast.{Positioned, Trees, tpd, untpd}
import core._
import core.Decorators._
import Annotations._
import Contexts._
import Flags._
import Phases._
import Trees._
import Types._
import Symbols._
import Names._
import NameOps._
import NameKinds.DefaultGetterName
import typer.Inliner
import transform.ValueClasses
import transform.SymUtils._
import dotty.tools.io.File
import java.io.PrintWriter

import xsbti.api.DefinitionType

import scala.collection.mutable

/** This phase sends a representation of the API of classes to sbt via callbacks.
 *
 *  This is used by sbt for incremental recompilation.
 *
 *  See the documentation of `ExtractAPICollector`, `ExtractDependencies`,
 *  `ExtractDependenciesCollector` and
 *  http://www.scala-sbt.org/1.x/docs/Understanding-Recompilation.html for more
 *  information on incremental recompilation.
 *
 *  The following flags affect this phase:
 *   -Yforce-sbt-phases
 *   -Ydump-sbt-inc
 *
 *  @see ExtractDependencies
 */
class ExtractAPI extends Phase {
  override def phaseName: String = "sbt-api"

  override def isRunnable(using Context): Boolean = {
    def forceRun = ctx.settings.YdumpSbtInc.value || ctx.settings.YforceSbtPhases.value
    super.isRunnable && (ctx.sbtCallback != null || forceRun)
  }

  // Check no needed. Does not transform trees
  override def isCheckable: Boolean = false

  // SuperAccessors need to be part of the API (see the scripted test
  // `trait-super` for an example where this matters), this is only the case
  // after `PostTyper` (unlike `ExtractDependencies`, the simplication to trees
  // done by `PostTyper` do not affect this phase because it only cares about
  // definitions, and `PostTyper` does not change definitions).
  override def runsAfter: Set[String] = Set(transform.PostTyper.name)

  override def run(using Context): Unit = {
    val unit = ctx.compilationUnit
    val sourceFile = unit.source.file
    if (ctx.sbtCallback != null)
      ctx.sbtCallback.startSource(sourceFile.file)

    val apiTraverser = new ExtractAPICollector
    val classes = apiTraverser.apiSource(unit.tpdTree)
    val mainClasses = apiTraverser.mainClasses

    if (ctx.settings.YdumpSbtInc.value) {
      // Append to existing file that should have been created by ExtractDependencies
      val pw = new PrintWriter(File(sourceFile.jpath).changeExtension("inc").toFile
        .bufferedWriter(append = true), true)
      try {
        classes.foreach(source => pw.println(DefaultShowAPI(source)))
      } finally pw.close()
    }

    if ctx.sbtCallback != null &&
      !ctx.compilationUnit.suspendedAtInliningPhase // already registered before this unit was suspended
    then
      classes.foreach(ctx.sbtCallback.api(sourceFile.file, _))
      mainClasses.foreach(ctx.sbtCallback.mainClass(sourceFile.file, _))
  }
}

/** Extracts full (including private members) API representation out of Symbols and Types.
 *
 *  The exact representation used for each type is not important: the only thing
 *  that matters is that a binary-incompatible or source-incompatible change to
 *  the API (for example, changing the signature of a method, or adding a parent
 *  to a class) should result in a change to the API representation so that sbt
 *  can recompile files that depend on this API.
 *
 *  Note that we only records types as they are defined and never "as seen from"
 *  some other prefix because `Types#asSeenFrom` is a complex operation and
 *  doing it for every inherited member would be slow, and because the number
 *  of prefixes can be enormous in some cases:
 *
 *    class Outer {
 *      type T <: S
 *      type S
 *      class A extends Outer { /*...*/ }
 *      class B extends Outer { /*...*/ }
 *      class C extends Outer { /*...*/ }
 *      class D extends Outer { /*...*/ }
 *      class E extends Outer { /*...*/ }
 *    }
 *
 *  `S` might be refined in an arbitrary way inside `A` for example, this
 *  affects the type of `T` as seen from `Outer#A`, so we could record that, but
 *  the class `A` also contains itself as a member, so `Outer#A#A#A#...` is a
 *  valid prefix for `T`. Even if we avoid loops, we still have a combinatorial
 *  explosion of possible prefixes, like `Outer#A#B#C#D#E`.
 *
 *  It is much simpler to record `T` once where it is defined, but that means
 *  that the API representation of `T` may not change even though `T` as seen
 *  from some prefix has changed. This is why in `ExtractDependencies` we need
 *  to traverse used types to not miss dependencies, see the documentation of
 *  `ExtractDependencies#usedTypeTraverser`.
 *
 *  TODO: sbt does not store the full representation that we compute, instead it
 *  hashes parts of it to reduce memory usage, then to see if something changed,
 *  it compares the hashes instead of comparing the representations. We should
 *  investigate whether we can just directly compute hashes in this phase
 *  without going through an intermediate representation, see
 *  http://www.scala-sbt.org/0.13/docs/Understanding-Recompilation.html#Hashing+an+API+representation
 */
private class ExtractAPICollector(using Context) extends ThunkHolder {
  import tpd._
  import xsbti.api

  /** This cache is necessary for correctness, see the comment about inherited
   *  members in `apiClassStructure`
   */
  private val classLikeCache = new mutable.HashMap[ClassSymbol, api.ClassLikeDef]
  /** This cache is optional, it avoids recomputing representations */
  private val typeCache = new mutable.HashMap[Type, api.Type]
  /** This cache is necessary to avoid unstable name hashing when `typeCache` is present,
   *  see the comment in the `RefinedType` case in `computeType`
   *  The cache key is (api of RefinedType#parent, api of RefinedType#refinedInfo).
    */
  private val refinedTypeCache = new mutable.HashMap[(api.Type, api.Definition), api.Structure]

  private val allNonLocalClassesInSrc = new mutable.HashSet[xsbti.api.ClassLike]
  private val _mainClasses = new mutable.HashSet[String]

  private object Constants {
    val emptyStringArray = Array[String]()
    val local            = api.ThisQualifier.create()
    val public           = api.Public.create()
    val privateLocal     = api.Private.create(local)
    val protectedLocal   = api.Protected.create(local)
    val unqualified      = api.Unqualified.create()
    val thisPath         = api.This.create()
    val emptyType        = api.EmptyType.create()
    val emptyModifiers   =
      new api.Modifiers(false, false, false, false, false,false, false, false)
  }

  /** Some Dotty types do not have a corresponding type in xsbti.api.* that
   *  represents them. Until this is fixed we can workaround this by using
   *  special annotations that can never appear in the source code to
   *  represent these types.
   *
   *  @param tp      An approximation of the type we're trying to represent
   *  @param marker  A special annotation to differentiate our type
   */
  private def withMarker(tp: api.Type, marker: api.Annotation) =
    api.Annotated.of(tp, Array(marker))
  private def marker(name: String) =
    api.Annotation.of(api.Constant.of(Constants.emptyType, name), Array())
  private val orMarker = marker("Or")
  private val byNameMarker = marker("ByName")
  private val matchMarker = marker("Match")
  private val superMarker = marker("Super")

  /** Extract the API representation of a source file */
  def apiSource(tree: Tree): Seq[api.ClassLike] = {
    def apiClasses(tree: Tree): Unit = tree match {
      case PackageDef(_, stats) =>
        stats.foreach(apiClasses)
      case tree: TypeDef =>
        apiClass(tree.symbol.asClass)
      case _ =>
    }

    apiClasses(tree)
    forceThunks()

    allNonLocalClassesInSrc.toSeq
  }

  def apiClass(sym: ClassSymbol): api.ClassLikeDef =
    classLikeCache.getOrElseUpdate(sym, computeClass(sym))

  def mainClasses: Set[String] = {
    forceThunks()
    _mainClasses.toSet
  }

  private def computeClass(sym: ClassSymbol): api.ClassLikeDef = {
    import xsbti.api.{DefinitionType => dt}
    val defType =
      if (sym.is(Trait)) dt.Trait
      else if (sym.is(ModuleClass)) {
        if (sym.is(PackageClass)) dt.PackageModule
        else dt.Module
      } else dt.ClassDef

    val selfType = apiType(sym.givenSelfType)

    val name = sym.fullName.stripModuleClassSuffix.toString
      // We strip module class suffix. Zinc relies on a class and its companion having the same name

    val tparams = sym.typeParams.map(apiTypeParameter).toArray

    val structure = apiClassStructure(sym)
    val acc = apiAccess(sym)
    val modifiers = apiModifiers(sym)
    val anns = apiAnnotations(sym).toArray
    val topLevel = sym.isTopLevelClass
    val childrenOfSealedClass = sym.sealedDescendants.sorted(classFirstSort).map(c =>
      if (c.isClass)
        apiType(c.typeRef)
      else
        apiType(c.termRef)
    ).toArray

    val cl = api.ClassLike.of(
      name, acc, modifiers, anns, defType, api.SafeLazy.strict(selfType), api.SafeLazy.strict(structure), Constants.emptyStringArray,
      childrenOfSealedClass, topLevel, tparams)

    allNonLocalClassesInSrc += cl

    if (sym.isStatic && !sym.is(Trait) && ctx.platform.hasMainMethod(sym)) {
       // If sym is an object, all main methods count, otherwise only @static ones count.
      _mainClasses += name
    }

    api.ClassLikeDef.of(name, acc, modifiers, anns, tparams, defType)
  }

  def apiClassStructure(csym: ClassSymbol): api.Structure = {
    val cinfo = csym.classInfo

    val bases = {
      val ancestorTypes0 =
        try linearizedAncestorTypes(cinfo)
        catch {
          case ex: TypeError =>
            // See neg/i1750a for an example where a cyclic error can arise.
            // The root cause in this example is an illegal "override" of an inner trait
            report.error(ex, csym.sourcePos)
            defn.ObjectType :: Nil
        }
      if (ValueClasses.isDerivedValueClass(csym)) {
        val underlying = ValueClasses.valueClassUnbox(csym).info.finalResultType
        // The underlying type of a value class should be part of the name hash
        // of the value class (see the test `value-class-underlying`), this is accomplished
        // by adding the underlying type to the list of parent types.
        underlying :: ancestorTypes0
      } else
        ancestorTypes0
    }

    val apiBases = bases.map(apiType)

    // Synthetic methods that are always present do not affect the API
    // and can therefore be ignored.
    def alwaysPresent(s: Symbol) = csym.is(ModuleClass) && s.isConstructor
    val decls = cinfo.decls.filter(!alwaysPresent(_))
    val apiDecls = apiDefinitions(decls)

    val declSet = decls.toSet
    // TODO: We shouldn't have to compute inherited members. Instead, `Structure`
    // should have a lazy `parentStructures` field.
    val inherited = cinfo.baseClasses
      .filter(bc => !bc.is(Scala2x))
      .flatMap(_.classInfo.decls.filter(s => !(s.is(Private) || declSet.contains(s))))
    // Inherited members need to be computed lazily because a class might contain
    // itself as an inherited member, like in `class A { class B extends A }`,
    // this works because of `classLikeCache`
    val apiInherited = lzy(apiDefinitions(inherited).toArray)

    api.Structure.of(api.SafeLazy.strict(apiBases.toArray), api.SafeLazy.strict(apiDecls.toArray), apiInherited)
  }

  def linearizedAncestorTypes(info: ClassInfo): List[Type] = {
    val ref = info.appliedRef
    // Note that the ordering of classes in `baseClasses` is important.
    info.baseClasses.tail.map(ref.baseType)
  }

  // The hash generated by sbt for definitions is supposed to be symmetric so
  // we shouldn't have to sort them, but it actually isn't symmetric for
  // definitions which are classes, therefore we need to sort classes to
  // ensure a stable hash.
  // Modules and classes come first and are sorted by name, all other
  // definitions come later and are not sorted.
  private object classFirstSort extends Ordering[Symbol] {
    override def compare(a: Symbol, b: Symbol) = {
      val aIsClass = a.isClass
      val bIsClass = b.isClass
      if (aIsClass == bIsClass) {
        if (aIsClass) {
          if (a.is(Module) == b.is(Module))
            a.fullName.toString.compareTo(b.fullName.toString)
          else if (a.is(Module))
            -1
          else
            1
        } else
          0
      } else if (aIsClass)
      -1
    else
      1
    }
  }

  def apiDefinitions(defs: List[Symbol]): List[api.ClassDefinition] = {
    defs.sorted(classFirstSort).map(apiDefinition)
  }

  def apiDefinition(sym: Symbol): api.ClassDefinition = {
    if (sym.isClass) {
      apiClass(sym.asClass)
    } else if (sym.isType) {
      apiTypeMember(sym.asType)
    } else if (sym.is(Mutable, butNot = Accessor)) {
      api.Var.of(sym.name.toString, apiAccess(sym), apiModifiers(sym),
        apiAnnotations(sym).toArray, apiType(sym.info))
    } else if (sym.isStableMember && !sym.isRealMethod) {
      api.Val.of(sym.name.toString, apiAccess(sym), apiModifiers(sym),
        apiAnnotations(sym).toArray, apiType(sym.info))
    } else {
      apiDef(sym.asTerm)
    }
  }

  def apiDef(sym: TermSymbol): api.Def = {
    def paramLists(t: Type, paramss: List[List[Symbol]]): List[api.ParameterList] = t match {
      case pt: TypeLambda =>
        paramLists(pt.resultType, paramss.drop(1))
      case mt @ MethodTpe(pnames, ptypes, restpe) =>
        assert(paramss.nonEmpty && paramss.head.hasSameLengthAs(pnames),
          i"mismatch for $sym, ${sym.info}, ${sym.paramSymss}")
        val apiParams = paramss.head.lazyZip(ptypes).map((param, ptype) =>
          api.MethodParameter.of(param.name.toString, apiType(ptype),
          param.is(HasDefault), api.ParameterModifier.Plain))
        api.ParameterList.of(apiParams.toArray, mt.isImplicitMethod)
          :: paramLists(restpe, paramss.tail)
      case _ =>
        Nil
    }

    val tparams = sym.info match {
      case pt: TypeLambda =>
        pt.paramNames.lazyZip(pt.paramInfos).map((pname, pbounds) =>
          apiTypeParameter(pname.toString, 0, pbounds.lo, pbounds.hi))
      case _ =>
        Nil
    }
    val vparamss = paramLists(sym.info, sym.paramSymss)
    val retTp = sym.info.finalResultType.widenExpr

    api.Def.of(sym.zincMangledName.toString, apiAccess(sym), apiModifiers(sym),
      apiAnnotations(sym).toArray, tparams.toArray, vparamss.toArray, apiType(retTp))
  }

  def apiTypeMember(sym: TypeSymbol): api.TypeMember = {
    val typeParams = Array[api.TypeParameter]()
    val name = sym.name.toString
    val access = apiAccess(sym)
    val modifiers = apiModifiers(sym)
    val as = apiAnnotations(sym)
    val tpe = sym.info

    if (sym.isAliasType)
      api.TypeAlias.of(name, access, modifiers, as.toArray, typeParams, apiType(tpe.bounds.hi))
    else {
      assert(sym.isAbstractType)
      api.TypeDeclaration.of(name, access, modifiers, as.toArray, typeParams, apiType(tpe.bounds.lo), apiType(tpe.bounds.hi))
    }
  }

  // Hack to represent dotty types which don't have an equivalent in xsbti
  def combineApiTypes(apiTps: api.Type*): api.Type = {
    api.Structure.of(api.SafeLazy.strict(apiTps.toArray),
      api.SafeLazy.strict(Array()), api.SafeLazy.strict(Array()))
  }

  def apiType(tp: Type): api.Type = {
    typeCache.getOrElseUpdate(tp, computeType(tp))
  }

  private def computeType(tp: Type): api.Type = {
    // TODO: Never dealias. We currently have to dealias because
    // sbt main class discovery relies on the signature of the main
    // method being fully dealiased. See https://github.com/sbt/zinc/issues/102
    val tp2 = if (!tp.isLambdaSub) tp.dealiasKeepAnnots else tp
    tp2 match {
      case NoPrefix | NoType =>
        Constants.emptyType
      case tp: NamedType =>
        val sym = tp.symbol
        // A type can sometimes be represented by multiple different NamedTypes
        // (they will be `=:=` to each other, but not `==`), and the compiler
        // may choose to use any of these representation, there is no stability
        // guarantee. We avoid this instability by always normalizing the
        // prefix: if it's a package, if we didn't do this sbt might conclude
        // that some API changed when it didn't, leading to overcompilation
        // (recompiling more things than what is needed for incremental
        // compilation to be correct).
        val prefix = if (sym.maybeOwner.is(Package)) // { type T } here T does not have an owner
          sym.owner.thisType
        else
          tp.prefix
        api.Projection.of(apiType(prefix), sym.name.toString)
      case AppliedType(tycon, args) =>
        def processArg(arg: Type): api.Type = arg match {
          case arg @ TypeBounds(lo, hi) => // Handle wildcard parameters
            if (lo.isDirectRef(defn.NothingClass) && hi.isDirectRef(defn.AnyClass))
              Constants.emptyType
            else {
              val name = "_"
              val ref = api.ParameterRef.of(name)
              api.Existential.of(ref,
                Array(apiTypeParameter(name, 0, lo, hi)))
            }
          case _ =>
            apiType(arg)
        }

        val apiTycon = apiType(tycon)
        val apiArgs = args.map(processArg)
        api.Parameterized.of(apiTycon, apiArgs.toArray)
      case tl: TypeLambda =>
        val apiTparams = tl.typeParams.map(apiTypeParameter)
        val apiRes = apiType(tl.resType)
        api.Polymorphic.of(apiRes, apiTparams.toArray)
      case rt: RefinedType =>
        val name = rt.refinedName.toString
        val parent = apiType(rt.parent)

        def typeRefinement(name: String, tp: TypeBounds): api.TypeMember = tp match {
          case TypeAlias(alias) =>
            api.TypeAlias.of(name,
              Constants.public, Constants.emptyModifiers, Array(), Array(), apiType(alias))
          case TypeBounds(lo, hi) =>
            api.TypeDeclaration.of(name,
              Constants.public, Constants.emptyModifiers, Array(), Array(), apiType(lo), apiType(hi))
        }
        val decl = rt.refinedInfo match {
          case rinfo: TypeBounds =>
            typeRefinement(name, rinfo)
          case _ =>
            report.debuglog(i"sbt-api: skipped structural refinement in $rt")
            null
        }

        // Aggressive caching for RefinedTypes: `typeCache` is enough as long as two
        // RefinedType are `==`, but this is only the case when their `refinedInfo`
        // are `==` and this is not always the case, consider:
        //
        //     val foo: { type Bla = a.b.T }
        //     val bar: { type Bla = a.b.T }
        //
        // The sbt API representations of `foo` and `bar` (let's call them `apiFoo`
        // and `apiBar`) will both be instances of `Structure`. If `typeCache` was
        // the only cache, then in some cases we would have `apiFoo eq apiBar` and
        // in other cases we would just have `apiFoo == apiBar` (this happens
        // because the dotty representation of `a.b.T` is unstable, see the comment
        // in the `NamedType` case above).
        //
        // The fact that we may or may not have `apiFoo eq apiBar` is more than
        // an optimisation issue: it will determine whether the sbt name hash for
        // `Bla` contains one or two entries (because sbt `NameHashing` will not
        // traverse both `apiFoo` and `apiBar` if they are `eq`), therefore the
        // name hash of `Bla` will be unstable, unless we make sure that
        // `apiFoo == apiBar` always imply `apiFoo eq apiBar`. This is what
        // `refinedTypeCache` is for.
        refinedTypeCache.getOrElseUpdate((parent, decl), {
          val adecl: Array[api.ClassDefinition] = if (decl == null) Array() else Array(decl)
          api.Structure.of(api.SafeLazy.strict(Array(parent)), api.SafeLazy.strict(adecl), api.SafeLazy.strict(Array()))
        })
      case tp: RecType =>
        apiType(tp.parent)
      case RecThis(recType) =>
        // `tp` must be present inside `recType`, so calling `apiType` on
        // `recType` would lead to an infinite recursion, we avoid this by
        //  computing the representation of `recType` lazily.
        apiLazy(recType)
      case tp: AndType =>
        combineApiTypes(apiType(tp.tp1), apiType(tp.tp2))
      case tp: OrType =>
        val s = combineApiTypes(apiType(tp.tp1), apiType(tp.tp2))
        withMarker(s, orMarker)
      case ExprType(resultType) =>
        withMarker(apiType(resultType), byNameMarker)
      case MatchType(bound, scrut, cases) =>
        val s = combineApiTypes(apiType(bound) :: apiType(scrut) :: cases.map(apiType): _*)
        withMarker(s, matchMarker)
      case ConstantType(constant) =>
        api.Constant.of(apiType(constant.tpe), constant.stringValue)
      case AnnotatedType(tpe, annot) =>
        api.Annotated.of(apiType(tpe), Array(apiAnnotation(annot)))
      case tp: ThisType =>
        apiThis(tp.cls)
      case tp: ParamRef =>
        // TODO: Distinguishing parameters based on their names alone is not enough,
        // the binder is also needed (at least for type lambdas).
        api.ParameterRef.of(tp.paramName.toString)
      case tp: LazyRef =>
        apiType(tp.ref)
      case tp: TypeVar =>
        apiType(tp.underlying)
      case SuperType(thistpe, supertpe) =>
        val s = combineApiTypes(apiType(thistpe), apiType(supertpe))
        withMarker(s, superMarker)
      case _ => {
        internalError(i"Unhandled type $tp of class ${tp.getClass}")
        Constants.emptyType
      }
    }
  }

  def apiLazy(tp: => Type): api.Type = {
    // TODO: The sbt api needs a convenient way to make a lazy type.
    // For now, we repurpose Structure for this.
    val apiTp = lzy(Array(apiType(tp)))
    api.Structure.of(apiTp, api.SafeLazy.strict(Array()), api.SafeLazy.strict(Array()))
  }

  def apiThis(sym: Symbol): api.Singleton = {
    val pathComponents = sym.ownersIterator.takeWhile(!_.isEffectiveRoot)
      .map(s => api.Id.of(s.name.toString))
    api.Singleton.of(api.Path.of(pathComponents.toArray.reverse ++ Array(Constants.thisPath)))
  }

  def apiTypeParameter(tparam: ParamInfo): api.TypeParameter =
    apiTypeParameter(tparam.paramName.toString, tparam.paramVarianceSign,
      tparam.paramInfo.bounds.lo, tparam.paramInfo.bounds.hi)

  def apiTypeParameter(name: String, variance: Int, lo: Type, hi: Type): api.TypeParameter =
    api.TypeParameter.of(name, Array(), Array(), apiVariance(variance),
      apiType(lo), apiType(hi))

  def apiVariance(v: Int): api.Variance = {
    import api.Variance._
    if (v < 0) Contravariant
    else if (v > 0) Covariant
    else Invariant
  }

  def apiAccess(sym: Symbol): api.Access = {
    // Symbols which are private[foo] do not have the flag Private set,
    // but their `privateWithin` exists, see `Parsers#ParserCommon#normalize`.
    if (!sym.isOneOf(Protected | Private) && !sym.privateWithin.exists)
      Constants.public
    else if (sym.isAllOf(PrivateLocal))
      Constants.privateLocal
    else if (sym.isAllOf(ProtectedLocal))
      Constants.protectedLocal
    else {
      val qualifier =
        if (sym.privateWithin eq NoSymbol)
          Constants.unqualified
        else
          api.IdQualifier.of(sym.privateWithin.fullName.toString)
      if (sym.is(Protected))
        api.Protected.of(qualifier)
      else
        api.Private.of(qualifier)
    }
  }

  def apiModifiers(sym: Symbol): api.Modifiers = {
    val absOver = sym.is(AbsOverride)
    val abs = absOver || sym.isOneOf(Trait | Abstract | Deferred)
    val over = absOver || sym.is(Override)
    new api.Modifiers(abs, over, sym.is(Final), sym.is(Sealed),
      sym.isOneOf(GivenOrImplicit), sym.is(Lazy), sym.is(Macro), sym.isSuperAccessor)
  }

  def apiAnnotations(s: Symbol): List[api.Annotation] = {
    val annots = new mutable.ListBuffer[api.Annotation]
    val inlineBody = Inliner.bodyToInline(s)
    if (!inlineBody.isEmpty) {
      // If the body of an inline def changes, all the reverse dependencies of
      // this method need to be recompiled. sbt has no way of tracking method
      // bodies, so we include the hash of the body of the method as part of the
      // signature we send to sbt.
      //
      // FIXME: The API of a class we send to Zinc includes the signatures of
      // inherited methods, which means that we repeatedly compute the hash of
      // an inline def in every class that extends its owner. To avoid this we
      // could store the hash as an annotation when pickling an inline def
      // and retrieve it here instead of computing it on the fly.
      val inlineBodyHash = treeHash(inlineBody)
      annots += marker(inlineBodyHash.toString)
    }

    // In the Scala2 ExtractAPI phase we only extract annotations that extend
    // StaticAnnotation, but in Dotty we currently pickle all annotations so we
    // extract everything (except annotations missing from the classpath which
    // we simply skip over, and inline body annotations which are handled above).
    s.annotations.foreach { annot =>
      val sym = annot.symbol
      if sym.exists && sym != defn.BodyAnnot then
        annots += apiAnnotation(annot)
    }

    annots.toList
  }

  /** Produce a hash for a tree that is as stable as possible:
   *  it should stay the same across compiler runs, compiler instances,
   *  JVMs, etc.
   */
  def treeHash(tree: Tree): Int =
    import scala.util.hashing.MurmurHash3

    def positionedHash(p: ast.Positioned, initHash: Int): Int =
      p match
        case p: WithLazyField[?] =>
          p.forceIfLazy
        case _ =>
      // FIXME: If `p` is a tree we should probably take its type into account
      // when hashing it, but producing a stable hash for a type is not trivial
      // since the same type might have multiple representations, for method
      // signatures this is already handled by `computeType` and the machinery
      // in Zinc that generates hashes from that, if we can reliably produce
      // stable hashes for types ourselves then we could bypass all that and
      // send Zinc hashes directly.
      val h = MurmurHash3.mix(initHash, p.productPrefix.hashCode)
      iteratorHash(p.productIterator, h)
    end positionedHash

    def iteratorHash(it: Iterator[Any], initHash: Int): Int =
      import core.Constants._
      var h = initHash
      while it.hasNext do
        it.next() match
          case p: Positioned =>
            h = positionedHash(p, h)
          case xs: List[?] =>
            h = iteratorHash(xs.iterator, h)
          case c: Constant =>
            h = MurmurHash3.mix(h, c.tag)
            c.tag match
              case NullTag =>
                // No value to hash, the tag is enough.
              case ClazzTag =>
                // Go through `apiType` to get a value with a stable hash, it'd
                // be better to use Murmur here too instead of relying on
                // `hashCode`, but that would essentially mean duplicating
                // https://github.com/sbt/zinc/blob/develop/internal/zinc-apiinfo/src/main/scala/xsbt/api/HashAPI.scala
                // and at that point we might as well do type hashing on our own
                // representation.
                val apiValue = apiType(c.typeValue)
                h = MurmurHash3.mix(h, apiValue.hashCode)
              case _ =>
                h = MurmurHash3.mix(h, c.value.hashCode)
          case n: Name =>
            // The hashCode of the name itself is not stable across compiler instances
            h = MurmurHash3.mix(h, n.toString.hashCode)
          case elem =>
            internalError(
              i"Don't know how to produce a stable hash for `$elem` of unknown class ${elem.getClass}",
              tree.sourcePos)

            h = MurmurHash3.mix(h, elem.toString.hashCode)
      h
    end iteratorHash

    val seed = 4 // https://xkcd.com/221
    val h = positionedHash(tree, seed)
    MurmurHash3.finalizeHash(h, 0)
  end treeHash

  def apiAnnotation(annot: Annotation): api.Annotation = {
    // Like with inline defs, the whole body of the annotation and not just its
    // type is part of its API so we need to store its hash, but Zinc wants us
    // to extract the annotation type and its arguments, so we use a dummy
    // annotation argument to store the hash of the tree. We still need to
    // extract the annotation type in the way Zinc expects because sbt uses this
    // information to find tests to run (for example junit tests are
    // annotated @org.junit.Test).
    api.Annotation.of(
      apiType(annot.tree.tpe), // Used by sbt to find tests to run
      Array(api.AnnotationArgument.of("TREE_HASH", treeHash(annot.tree).toString)))
  }
}
