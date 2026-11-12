package effekt.atm

import Syntax.*

/** Encode a signature telescope using the existing quantified value types.
 * The final arrow is only a contract container, never an executable function.
 * This shares capture-avoiding substitution and alpha-equivalence with values.
 */
object OperationSignatures {
  def scheme(op: Signature): Type = op.typeParameters.foldRight[Type](
    Type.FixedFunction(op.argument, Computation(op.result,
      Effect.Change(op.initialAnswer, op.finalAnswer), op.returned), parameter = op.parameter)) {
    case ((n, ParameterKind.ValueType), b) => Type.TypePoly(n, b)
    case ((n, ParameterKind.AnswerEffect(k)), b) => Type.EffectPoly(n, b, k)
    case ((n, ParameterKind.Predicate(args)), b) => Type.PredicatePoly(n, args, b)
    case ((n, ParameterKind.Annotation(k)), b) => Type.KindPoly(n, k, b)
  }
  def signature(name: String, t: Type): Signature = t match {
    case Type.TypePoly(n, b) => prepend(name, n, ParameterKind.ValueType, b)
    case Type.EffectPoly(n, b, k) => prepend(name, n, ParameterKind.AnswerEffect(k), b)
    case Type.PredicatePoly(n, args, b) => prepend(name, n, ParameterKind.Predicate(args), b)
    case Type.KindPoly(n, k, b) => prepend(name, n, ParameterKind.Annotation(k), b)
    case Type.FixedFunction(a, Computation(r, Effect.Change(i, f), returned, None), _, _, p) =>
      Signature(name, a, r, i, f, p, returned)
    case _ => throw Diagnostic("Malformed operation signature telescope")
  }
  private def prepend(name: String, n: String, k: ParameterKind, b: Type): Signature = {
    val op = signature(name, b)
    op.copy(typeParameters = (n -> k) :: op.typeParameters)
  }
  def instantiate(t: Type, a: PolymorphicArgument): Type = (t, a) match {
    case (Type.TypePoly(n, b), PolymorphicArgument.ValueType(v)) => Contracts.instantiate(b, n, v)
    case (Type.EffectPoly(n, b, _), PolymorphicArgument.AnswerEffect(e)) => EffectSubstitution(b, n, e)
    case (Type.PredicatePoly(n, _, b), PolymorphicArgument.Predicate(ps, p)) => Contracts.instantiatePredicate(b, n, ps.map(_._1), p)
    case (Type.KindPoly(n, _, b), PolymorphicArgument.Kind(a)) => Annotations.substitution(n, a).typ(b)
    case _ => throw Diagnostic("Operation argument does not match its quantified kind")
  }
  def align(op: Signature, parameters: List[(String, ParameterKind)]): Signature = {
    if op.typeParameters.size != parameters.size then throw Diagnostic("Wrong number of operation type parameters")
    val body = parameters.foldLeft(scheme(op)) {
      case (t, (n, ParameterKind.ValueType)) => instantiate(t, PolymorphicArgument.ValueType(Type.Variable(n)))
      case (t, (n, ParameterKind.AnswerEffect(_))) => instantiate(t, PolymorphicArgument.AnswerEffect(Effect.Variable(n)))
      case (t, (n, ParameterKind.Annotation(k))) => instantiate(t, PolymorphicArgument.Kind(Annotations.variable(n, k)))
      case (Type.PredicatePoly(old, _, b), (n, ParameterKind.Predicate(_))) =>
        // Rename directly, without inventing lambda argument names that could
        // collide with the signature's generative logical variables.
        def rename(e: Logic.Expr): Logic.Expr = e match {
          case Logic.Expr.Predicate(p, xs) => Logic.Expr.Predicate(if p == old then n else p, xs.map(rename))
          case Logic.Expr.App(f, xs) => Logic.Expr.App(f, xs.map(rename))
          case Logic.Expr.Exists(x, s, p) => Logic.Expr.Exists(x, s, rename(p))
          case Logic.Expr.Forall(x, s, p) => Logic.Expr.Forall(x, s, rename(p))
          case other => other
        }
        Contracts.map(b)(identity, rename)
      case _ => throw Diagnostic("Operation parameter kind mismatch")
    }
    signature(op.name, body).copy(typeParameters = parameters)
  }
}
