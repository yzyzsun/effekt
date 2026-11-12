package effekt.atm

import Syntax.*
import Annotations.{Kind, Mapper}
import Evidence.Distance

/** Opening temporary annotation binders and explicit call-ATC metadata.
 * Ordinary source functions have no caller binder and keep the same codomain
 * at every call. Their implicit telescope lives in FunctionAbstraction only.
 */
object FunctionTypes {
  def opening(caller: Option[Caller], callRegion: String, callDistance: Distance): Mapper = new Mapper {
    override def region(n: String): String = caller match {
      case Some(c) if n == c.region => callRegion
      case _ => n
    }
    override def distance(d: Distance): Distance = (caller, d) match {
      case (Some(c), Distance.Variable(n)) if n == c.distance => callDistance
      case _ => super.distance(d)
    }
  }
  def result(f: Type.Function, region: String, distance: Distance): Computation =
    opening(f.caller, region, distance).computation(f.result)
  def contexts(f: Type.Function, region: String, distance: Distance): List[(String, Kind)] =
    f.caller.toList.flatMap(_.contexts).map((n, k) => (n, opening(f.caller, region, distance).kind(k)))
}
