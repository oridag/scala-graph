package scalax.collection.constrained
package constraints

import scalax.collection.GraphPredef._

/** The empty constraint treating any addition or subtraction as valid.
  */
class NoneConstraint[N, E <: EdgeLike[N], G <: Graph[N, E]](override val self: G) extends Constraint[N, E, G](self) {
  import PreCheckFollowUp.Complete
  override def preAdd(node: N)                                = PreCheckResult(Complete)
  override def preAdd(edge: E)                                = PreCheckResult(Complete)
  override def preSubtract(node: self.NodeT, forced: Boolean) = PreCheckResult(Complete)
  override def preSubtract(edge: self.EdgeT, forced: Boolean) = PreCheckResult(Complete)
}
object NoneConstraint extends ConstraintCompanion[NoneConstraint] {
  def apply[N, E <: EdgeLike[N], G <: Graph[N, E]](self: G) = new NoneConstraint[N, E, G](self)
}
