package scalax.collection

import scala.annotation.unchecked.{uncheckedVariance => uV}
import scala.collection.AbstractIterable
import scala.util.chaining._

import scalax.collection.GraphEdge._
import scalax.collection.generic.{GraphCompanion, GraphCoreCompanion}
import scalax.collection.config.GraphConfig
import scalax.collection.mutable.Builder
import scala.collection.compat._

/** A template trait for graphs.
  *
  * This trait provides the common structure and operations of immutable graphs independently
  * of their representation.
  *
  * If `E` inherits `DiHyperEdgeLike` the graph is directed, otherwise it is undirected or mixed.
  *
  * @tparam N    the user type of the nodes (vertices) in this graph.
  * @tparam E    the higher kinded type of the edges (links) in this graph.
  * @tparam This the higher kinded type of the graph itself.
  * @define REIMPLFACTORY Note that this method must be reimplemented in each module
  *         having its own factory methods such as `constrained` does.
  * @define CONTGRAPH The `Graph` instance that contains `this`
  * @author Peter Empen
  */
trait GraphLike[N, E <: EdgeLike[N], +This[X, Y <: EdgeLike[X]] <: GraphLike[X, Y, This] with Graph[X, Y]]
    extends GraphBase[N, E, This]
    /* TODO
      with GraphTraversal[N, E]
      with GraphDegree[N, E]
     */ {
  thisGraph: // This[N, E] => see https://youtrack.jetbrains.com/issue/SCL-13199
  This[N, E] with GraphLike[N, E, This] with Graph[N, E] =>

  protected type ThisGraph = thisGraph.type

  def empty: This[N, E]
  protected[this] def newBuilder: mutable.Builder[N, E, This]

  def isDirected: Boolean = edges.size > 0 && edges.hasOnlyDiEdges
  def isHyper: Boolean    = edges.size > 0 && edges.hasAnyHyperEdge
  def isMixed: Boolean    = edges.size > 0 && edges.hasMixedEdges
  def isMulti: Boolean    = edges.size > 0 && edges.hasAnyMultiEdge

  /** The companion object of `This`. */
  val companion: GraphCompanion[This]
  protected type Config <: GraphConfig
  implicit def config: companion.Config with Config

  /** Ensures sorted nodes/edges unless this `Graph` has more than 100 elements.
    * See also `asSortedString` and `toSortedString`.
    */
  override def toString: String = if (elementCount <= 100) toSortedString()() else super.toString

  /** Sorts all nodes of this graph by `ordNode` followed by all edges sorted by `ordEdge`
    * and concatinates their string representation `nodeSeparator` and `edgeSeparator`
    * respectively.
    *
    * @param nodeSeparator to separate nodes by.
    * @param edgeSeparator to separate edges by.
    * @param nodesEdgesSeparator to separate nodes from edges by.
    * @param withNodesEdgesPrefix whether the node and edge set should be prefixed.
    * @param ordNode the node ordering defaulting to `defaultNodeOrdering`.
    * @param ordEdge the edge ordering defaulting to `defaultEdgeOrdering`.
    */
  def asSortedString(
      nodeSeparator: String = GraphBase.defaultSeparator,
      edgeSeparator: String = GraphBase.defaultSeparator,
      nodesEdgesSeparator: String = GraphBase.defaultSeparator,
      withNodesEdgesPrefix: Boolean = false
  )(implicit ordNode: NodeOrdering = defaultNodeOrdering, ordEdge: EdgeOrdering = defaultEdgeOrdering) = {
    val ns =
      if (withNodesEdgesPrefix) nodes.toSortedString(nodeSeparator)(ordNode)
      else nodes.asSortedString(nodeSeparator)(ordNode)
    val es =
      if (withNodesEdgesPrefix) edges.toSortedString(edgeSeparator)(ordEdge)
      else edges.asSortedString(edgeSeparator)(ordEdge)
    ns + (if (ns.length > 0 && es.length > 0) nodesEdgesSeparator
          else "") +
    es
  }

  /** Same as `asSortedString` but additionally prefixed and parenthesized by `stringPrefix`.
    */
  def toSortedString(
      nodeSeparator: String = GraphBase.defaultSeparator,
      edgeSeparator: String = GraphBase.defaultSeparator,
      nodesEdgesSeparator: String = GraphBase.defaultSeparator,
      withNodesEdgesPrefix: Boolean = false
  )(implicit ordNode: NodeOrdering = defaultNodeOrdering, ordEdge: EdgeOrdering = defaultEdgeOrdering) =
    stringPrefix +
      "(" + asSortedString(nodeSeparator, edgeSeparator, nodesEdgesSeparator, withNodesEdgesPrefix)(ordNode, ordEdge) +
      ")"

  /** `Graph` instances are equal if their nodes and edges turned
    * to outer nodes and outer edges are equal. Any `TraversableOnce`
    * instance may also be equal to this graph if its set representation
    * contains equalling outer nodes and outer edges. Thus the following
    * expressions hold:
    * {{{
    * Graph(1~2, 3) == List(1~2, 3)
    * Graph(1~2, 3) == List(1, 2, 2, 3, 2~1)
    * }}}
    * The first test is `false` because of the failing nodes `1` and `2`.
    * The second is true because of duplicate elimination and undirected edge equivalence.
    */
  override def equals(that: Any): Boolean = that match {
    case that: Graph[N, E] =>
      (this eq that) ||
        (this.order == that.order) &&
        (this.size == that.size) && {
          val thatNodes = that.nodes.toOuter
          try this.nodes forall (thisN => thatNodes(thisN.outer))
          catch { case _: ClassCastException => false }
        } && {
          val thatEdges = that.edges.toOuter
          try this.edges forall (thisE => thatEdges(thisE.outer))
          catch { case _: ClassCastException => false }
        }
    case that: IterableOnce[_] =>
      val thatSet = that.iterator.to(Set)
      (this.elementCount == thatSet.size) && {
        val thatNodes = thatSet.asInstanceOf[Set[N]]
        try this.nodes forall (thisN => thatNodes(thisN.outer))
        catch { case _: ClassCastException => false }
      } && {
        val thatEdges = thatSet.asInstanceOf[Set[E]]
        try this.edges forall (thisE => thatEdges(thisE.outer))
        catch { case _: ClassCastException => false }
      }
    case _ =>
      false
  }

  type NodeT <: InnerNode
  trait InnerNode extends super.InnerNode { // TODO with TraverserInnerNode {
    this: NodeT =>

    /** $CONTGRAPH inner edge. */
    final def containingGraph: ThisGraph = thisGraph
  }

  protected trait NodeBase extends super.NodeBase with InnerNode {
    this: NodeT =>
    final def isContaining[N, E <: EdgeLike[N]](g: GraphBase[N, E, This] @uV): Boolean =
      g eq containingGraph
  }

  type NodeSetT <: NodeSet
  trait NodeSet extends super.NodeSet {
    protected def copy: NodeSetT

    final override def -(node: NodeT): NodeSetT =
      if (this contains node) { val c = copy; c minus node; c }
      else this.asInstanceOf[NodeSetT]

    /** removes `node` from this node set leaving the edge set unchanged.
      *
      * @param node the node to be removed from the node set.
      */
    protected def minus(node: NodeT): Unit

    /** removes `node` either rippling or gently.
      *
      * @param node the node to be subtracted
      * @param rippleDelete if `true`, `node` will be deleted with its incident edges;
      *        otherwise `node` will be only deleted if it has no incident edges or
      *        all its incident edges are hooks.
      * @param minusNode implementation of node removal without considering incident edges.
      * @param minusEdges implementation of removal of all incident edges.
      * @return `true` if `node` has been removed.
      */
    final protected[collection] def subtract(
        node: NodeT,
        rippleDelete: Boolean,
        minusNode: (NodeT) => Unit,
        minusEdges: (NodeT) => Unit
    ): Boolean = {
      def minusNodeTrue = { minusNode(node); true }
      def minusAllTrue  = { minusEdges(node); minusNodeTrue }
      if (contains(node))
        if (node.edges.isEmpty) minusNodeTrue
        else if (rippleDelete) minusAllTrue
        else if (node.hasOnlyHooks) minusAllTrue
        else handleNotGentlyRemovable
      else false
    }
    protected def handleNotGentlyRemovable = false
  }

  trait InnerEdge extends super.InnerEdge {
    this: EdgeT =>

    /** $CONTGRAPH inner edge. */
    final def containingGraph: ThisGraph = thisGraph
  }

  type EdgeT = InnerEdge

  @transient protected[collection] object Inner {
    import scala.{SerialVersionUID => S}

    // format: off
    @S(-901) case class HyperEdge       (ends: Iterable[NodeT], outer: E) extends Abstract.HyperEdge       (ends) with EdgeT
    @S(-902) case class OrderedHyperEdge(ends: Iterable[NodeT], outer: E) extends Abstract.OrderedHyperEdge(ends) with EdgeT

    @S(-903) case class DiHyperEdge       (override val sources: Iterable[NodeT], override val targets: Iterable[NodeT], outer: E) extends Abstract.DiHyperEdge       (sources, targets) with EdgeT
    @S(-904) case class OrderedDiHyperEdge(override val sources: Iterable[NodeT], override val targets: Iterable[NodeT], outer: E) extends Abstract.OrderedDiHyperEdge(sources, targets) with EdgeT

    @S(-905) case class UnDiEdge(node_1: NodeT, node_2: NodeT, outer: E) extends Abstract.UnDiEdge(node_1, node_2) with EdgeT
    @S(-906) case class DiEdge  (source: NodeT, target: NodeT, outer: E) extends Abstract.DiEdge  (source, target) with EdgeT
    // format: on
  }

  final override protected def newHyperEdge(outer: E, nodes: Iterable[NodeT]): EdgeT = outer match {
    case _: AnyHyperEdge[N] with OrderedEndpoints => Inner.OrderedHyperEdge(nodes, outer)
    case _: AnyHyperEdge[N]                       => Inner.HyperEdge(nodes, outer)
    case e                                        => throw new MatchError(s"Unexpected HyperEdge $e")
  }
  protected def newDiHyperEdge(outer: E, sources: Iterable[NodeT], targets: Iterable[NodeT]): EdgeT = outer match {
    case _: AnyDiHyperEdge[N] with OrderedEndpoints => Inner.OrderedDiHyperEdge(sources, targets, outer)
    case _: AnyDiHyperEdge[N]                       => Inner.DiHyperEdge(sources, targets, outer)
    case e                                          => throw new MatchError(s"Unexpected DiHyperEdge $e")
  }
  protected def newEdge(outer: E, node_1: NodeT, node_2: NodeT): EdgeT = outer match {
    case _: AnyDiEdge[N]   => Inner.DiEdge(node_1, node_2, outer)
    case _: AnyUnDiEdge[N] => Inner.UnDiEdge(node_1, node_2, outer)
    case e                 => throw new MatchError(s"Unexpected Edge $e")
  }

  type EdgeSetT <: EdgeSet
  trait EdgeSet extends super.EdgeSet {
    def hasOnlyDiEdges: Boolean
    def hasOnlyUnDiEdges: Boolean
    def hasMixedEdges: Boolean
    def hasAnyHyperEdge: Boolean
    def hasAnyMultiEdge: Boolean
  }

  final def contains(node: N): Boolean = nodes contains newNode(node)
  final def contains(edge: E): Boolean = edges contains newHyperEdge(edge, Nil)

  def iterator: Iterator[InnerElem] = nodes.iterator ++ edges.iterator

  def toIterable: Iterable[InnerElem] = new AbstractIterable[InnerElem] {
    def iterator: Iterator[InnerElem] = thisGraph.iterator
  }

  def outerIterator: Iterator[OuterElem] =
    nodes.iterator.map[OuterElem](n => OuterNode(n.outer)) ++
      edges.iterator.map[OuterElem](e => OuterEdge(e.outer))

  def toOuterIterable: Iterable[OuterElem] = new AbstractIterable[OuterElem] {
    def iterator: Iterator[OuterElem] = outerIterator
  }

  @inline final def find(node: N): Option[NodeT] = nodes find node
  @inline final def find(edge: E): Option[EdgeT] = edges find edge

  @inline final def get(node: N): NodeT = nodes get node
  @inline final def get(edge: E): EdgeT = edges.find(edge).get

  def filter(fNode: NodePredicate = anyNode, fEdge: EdgePredicate = anyEdge): This[N, E] = {
    import scala.collection.Set

    def build(nodes: Set[NodeT]): This[N, E] = {
      val b = companion.newBuilder[N, E]
      nodes foreach { case innerN @ InnerNode(outerN) =>
        b addOne outerN
        innerN.edges foreach { case innerE @ InnerEdge(outerE) =>
          if (fEdge(innerE) && (innerE.ends forall nodes.contains)) b += outerE
        }
      }
      b.result
    }
    (fNode, fEdge) match {
      case (`anyNode`, `anyEdge`) => this
      case (`anyNode`, _)         => build(nodes)
      case (fN, _)                => build(nodes filter fN)
    }
  }

  final def map[NN, EC[X] <: EdgeLike[X]](
      fNode: NodeT => NN
  )(implicit w1: E <:< GenericMapper, w2: EC[N] =:= E, fallbackMapper: EdgeCompanion[EC]): This[NN, EC[NN]] =
    mapNodes(fNode)(
      (m: PartialEdgeMapper[N, _], ns: (NN, NN)) => {
        def toExistingType                                   = m.map.andThen(_.asInstanceOf[EC[NN]])
        def toWidenedType: PartialFunction[(NN, NN), EC[NN]] = { case (n1, n2) => fallbackMapper(n1, n2) }
        toExistingType applyOrElse (ns, toWidenedType)
      },
      (m: PartialDiHyperEdgeMapper[N, _], s: Iterable[NN], t: Iterable[NN]) => null.asInstanceOf[EC[NN]], // TODO
      (m: PartialHyperEdgeMapper[N, _], ns: Iterable[NN]) => null.asInstanceOf[EC[NN]]                    // TODO
    )

  def mapBounded[NN <: N, EC[X] <: EdgeLike[X]](
      fNode: NodeT => NN
  )(implicit w1: E <:< PartialMapper, w2: EC[N] =:= E): This[NN, EC[NN]] =
    mapNodes(fNode)(
      (m: PartialEdgeMapper[N, _], ns: (NN, NN)) => m.map(ns).asInstanceOf[EC[NN]],
      (m: PartialDiHyperEdgeMapper[N, _], s: Iterable[NN], t: Iterable[NN]) => null.asInstanceOf[EC[NN]],
      (m: PartialHyperEdgeMapper[N, _], ns: Iterable[NN]) => null.asInstanceOf[EC[NN]]
    )

  private def mapNodes[NN, EC[X] <: EdgeLike[X]](fNode: NodeT => NN)(
      mapTypedEdge: (PartialEdgeMapper[N, _], (NN, NN)) => EC[NN],
      mapTypedDiHyper: (PartialDiHyperEdgeMapper[N, _], Iterable[NN], Iterable[NN]) => EC[NN],
      mapTypedHyper: (PartialHyperEdgeMapper[N, _], Iterable[NN]) => EC[NN]
  ): This[NN, EC[NN]] =
    mapNodesInBuilder[NN, EC[NN]](fNode) pipe { case (nMap, builder) =>
      edges foreach {
        case InnerEdge(outer @ AnyEdge(n1: N @unchecked, n2: N @unchecked)) =>
          (nMap(n1), nMap(n2)) pipe { case nns @ (nn1, nn2) =>
            outer match {
              case m: GenericEdgeMapper[N, EC @unchecked]     => builder += m.map(nn1, nn2)
              case m: PartialEdgeMapper[N, EC[NN] @unchecked] => builder += mapTypedEdge(m, nns)
            }
          }
        case _ => ??? // TODO
      }
      builder.result
    }

  private def mapNodesInBuilder[NN, EE <: EdgeLike[NN]](fNode: NodeT => NN): (MMap[N, NN], Builder[NN, EE, This]) = {
    val nMap = MMap.empty[N, NN]
    val b    = companion.newBuilder[NN, EE](config)
    nodes foreach { n =>
      val nn = fNode(n)
      nMap put (n.outer, nn)
      b addOne nn
    }
    (nMap, b)
  }

  final def map[NN, EC[X] <: AnyEdge[X]](fNode: NodeT => NN, fEdge: (NN, NN) => EC[NN]): This[NN, EC[NN]] =
    mapBounded(fNode, fEdge)

  final def mapBounded[NN, EC <: AnyEdge[NN]](fNode: NodeT => NN, fEdge: (NN, NN) => EC): This[NN, EC] =
    mapNodesInBuilder[NN, EC](fNode) pipe { case (nMap, builder) =>
      edges foreach {
        case InnerEdge(outer @ AnyEdge(n1: N @unchecked, n2: N @unchecked)) =>
          (nMap(n1), nMap(n2)) pipe { case nns @ (nn1, nn2) =>
            builder += fEdge(nn1, nn2)
          }
        case _ => ??? // TODO
      }
      builder.result
    }

  final protected def partition(elems: Iterable[OuterElem]): (MSet[N], MSet[E]) = {
    val size = elems.size
    def builder[A] = {
      val b = MSet.newBuilder[A]
      b.sizeHint(size)
      b
    }
    val (nB, eB) = (builder[N], builder[E])
    elems foreach {
      case OuterNode(n) => nB += n
      case OuterEdge(e) => eB += e
    }
    (nB.result(), eB.result())
  }
}

/** The main trait for immutable graphs bundling the functionality of traits concerned with
  * specific aspects.
  *
  * @tparam N the type of the nodes (vertices) in this graph.
  * @tparam E the kind of the edges in this graph.
  * @author Peter Empen
  */
trait Graph[N, E <: EdgeLike[N]] extends GraphLike[N, E, Graph] {
  override def empty: Graph[N, E] = Graph.empty[N, E]
}

/** The main companion object for immutable graphs.
  *
  * @author Peter Empen
  */
object Graph extends GraphCoreCompanion[Graph] {

  override def newBuilder[N, E <: EdgeLike[N]](implicit config: Config) =
    scalax.collection.immutable.Graph.newBuilder[N, E](config)

  def empty[N, E <: EdgeLike[N]](implicit config: Config = defaultConfig): Graph[N, E] =
    scalax.collection.immutable.Graph.empty[N, E](config)

  def from[N, E <: EdgeLike[N]](nodes: Iterable[N], edges: Iterable[E])(implicit
      config: Config = defaultConfig
  ): Graph[N, E] =
    scalax.collection.immutable.Graph.from[N, E](nodes, edges)(config)

  def from[N, E[X] <: EdgeLike[X]](edges: Iterable[E[N]]) =
    scalax.collection.immutable.Graph.from[N, E[N]](Nil, edges)(defaultConfig)
}
