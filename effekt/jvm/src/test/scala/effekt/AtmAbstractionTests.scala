package effekt

import effekt.atm.{Annotations, Checker, Core, DirectBackend, Evidence, Parser, Syntax}
import Syntax.*
import Evidence.*

/** Source A_Abs arrows retain a minimum requirement and a caller-independent C. */
class AtmAbstractionTests extends munit.FunSuite {
  private def checked(source: String): Checker.CheckedProgram =
    Parser.parse(source).flatMap(Checker.check).fold(e => fail(e.message), identity)
  private def run(source: String): Core.Program = {
    val p = checked(source)
    assertEquals(AtmReference.run(p.source), "42")
    for optimized <- List(false, true) do
      assertEquals(DirectBackend.run(p, optimized).fold(fail(_), identity), "42")
    Core.elaborate(p)
  }
  private def terms(t: Core.Term): List[Core.Term] = t :: (t.node match {
    case Core.Node.Let(_, a, b) => terms(a) ++ terms(b)
    case Core.Node.Handle(_, _, b, _, r, ops) => terms(b) ++ terms(r) ++ ops.flatMap(o => terms(o.body))
    case Core.Node.Ascribe(b) => terms(b)
    case _ => Nil
  })

  test("A_AbsSyn keeps rho = top and supplies different evidence at deeper calls") {
    val p = run("""def main(): Int = {
      val f = fun (x: Int) { x };
      val first = f(42);
      try {
        val second = f(first);
        try { f(second) } with inner: Int {
          def unused(): Unit / (Int => Int) = resume(())
          return x => x
        }
      } with outer: Int {
        def unused(): Unit / (Int => Int) = resume(())
        return x => x
      }
    }""")
    val all = terms(p.body)
    val f = all.collectFirst { case Core.Term(Core.Node.Return(v), _, _) if v.node.isInstanceOf[Core.ValueNode.Lambda] => v }.get
    val arrow = f.tpe.asInstanceOf[Type.Function]
    assertEquals(arrow.region, Some(Top))
    assertEquals(arrow.caller, None)
    assert(!Syntax.show(arrow).contains("forall caller"))
    val calls = all.collect { case Core.Term(Core.Node.Apply(Core.Value(Core.ValueNode.Variable("f"), tpe), _, List(ev), _), _, _) =>
      assertEquals(tpe, arrow)
      assertEquals(ev.constraint.to, Top)
      ev.distance.concrete
    }
    assertEquals(calls, List(Some(0), Some(1), Some(2)))
  }

  test("A_AbsChk requirements and A_AbsSyn's captured meet permit deeper callers") {
    val p = run("""def main(): Int = {
      def outer(u: Unit): Unit = ()
      try {
        val inner: ((Unit) => Unit) at {h} = fun (u) { () };
        val both = fun (u: Unit) { outer(); inner() };
        try { both(); 42 } with nested: Int {
          def unused(): Unit / (Int => Int) = resume(())
          return x => x
        }
      } with h: Int {
        def unused(): Unit / (Int => Int) = resume(())
        return x => x
      }
    }""")
    val all = terms(p.body)
    val h = all.collectFirst { case Core.Term(Core.Node.Handle(r, _, _, _, _, _), _, _) => r }.get
    val functions = all.collect { case Core.Term(Core.Node.Let(n, binding, _), _, _)
        if Set("inner", "both")(n) => n -> binding.tpe.value.asInstanceOf[Type.Function] }.toMap
    assertEquals(functions.keySet, Set("inner", "both"))
    functions.values.foreach { f => assertEquals(f.region, Some(h)); assertEquals(f.caller, None) }
    val call = all.collectFirst { case Core.Term(Core.Node.Apply(Core.Value(Core.ValueNode.Variable("both"), _), _, List(e), _), _, _) => e }.get
    assertEquals(call.constraint.to, h)
    assertEquals(call.distance.concrete, Some(1))
  }

  test("a result annotation does not pin a nested lambda to the enclosing body region") {
    // rho is the minimum captured requirement (top here), so the outer
    // codomain does not mention the outer body region.
    val p = run("""def main(): Int = {
      val make = fun (u: Unit) { fun (x: Int): Int { x } };
      val f = make();
      f(42)
    }""")
    val make = terms(p.body).collectFirst { case Core.Term(Core.Node.Let("make", binding, _), _, _) =>
      binding.tpe.value.asInstanceOf[Type.Function] }.get
    assertEquals(make.region, Some(Top))
    assertEquals(make.result.value.asInstanceOf[Type.Function].region, Some(Top))
  }

  test("an annotated function may leave the handler in which it is defined") {
    run("""def main(): Int = {
      val f = try {
        def g(x: Int): Int = { x }
        g
      } with h: ((Int) => Int) {
        def unused(u: Unit): Unit / (((Int) => Int) => ((Int) => Int)) = { resume() }
        return x => { x }
      };
      f(42)
    }""")
  }

  test("fresh caller occurrences in answer endpoints and nested domains are rejected") {
    for resultType <- List(
      "Int / (((Unit) =>[R] Int) => ((Unit) =>[R] Int))",
      "((forall E: Ev(R, 0, R). (Int)) =>[top] Int)") do {
      val result = Parser.parse(s"def main(): Int = { val bad = fun (u: Unit)[R: Reg]: $resultType { 0 }; 42 }")
        .flatMap(Checker.check)
      assert(result.isLeft)
      assert(result.swap.toOption.get.message.contains("alpha notin fv(C)"), result.toString)
    }
  }

  test("a caller-independent nested inferred function remains valid") {
    run("""def main(): Int = {
      val make = fun (u: Unit) { fun (x: Int) { x } };
      val f = make();
      try { f(42) } with h: Int {
        def unused(): Unit / (Int => Int) = resume(())
        return x => x
      }
    }""")
  }

  test("A_AbsChk obtains a stable nested arrow from the expected codomain") {
    run("""def main(): Int = {
      val make: ((Unit) => ((Int) => Int)) = fun (u) { fun (x) { x } };
      val f = make();
      try { f(42) } with h: Int {
        def unused(): Unit / (Int => Int) = resume(())
        return x => x
      }
    }""")
  }

  test("explicit region quantification may occur in an ordinary codomain") {
    run("""def main(): Int = {
      val make = fun [R: Reg] (u: Unit) { fun [E: Ev(R, 0, R)] { 42 } };
      val first = make[top]()[refl(top)];
      try { make[h]()[refl(h)] } with h: Int {
        def unused(): Unit / (Int => Int) = resume(())
        return x => x
      }
    }""")
  }

  test("a returned explicit region binder may shadow the body's caller alias") {
    run("""def main(): Int = {
      val make = fun (u: Unit)[R: Reg] {
        fun [R: Reg] { fun [E: Ev(R, 0, R)] { 42 } }
      };
      make()[top][refl(top)]
    }""")
  }

  test("explicit call ATCs do not permit a caller-dependent result") {
    val result = Parser.parse("""def main(): Int = {
      val bad = fun ()[R: Reg, K: ATC(dist(path(top)))]:
        forall E: Ev(R, 0, R). (Int) { fun [E: Ev(R, 0, R)] { 42 } };
      42
    }""").flatMap(Checker.check)
    assert(result.isLeft)
    assert(result.swap.toOption.get.message.contains("alpha notin fv(C)"), result.toString)
  }

  test("Core rejects a caller-dependent codomain with or without forged binder metadata") {
    val int = Computation(Type.Int)
    val local = Type.FixedFunction(Type.Int, int, region = Some("alpha"))
    val inner = Core.Value(Core.ValueNode.FixedLambda("x", "alpha",
      Core.Term(Core.Node.Return(Core.Value(Core.ValueNode.Integer(42), Type.Int)), int, "alpha")), local)
    val body = Core.Term(Core.Node.Return(inner), Computation(local), "alpha")
    val abstraction = FunctionAbstraction("alpha", Binding("call", Constraint("alpha", Top), Distance.Variable("d")))
    for caller <- List(None, Some(Caller("alpha", "d")),
        Some(Caller("alpha", "d", List("K" -> Annotations.Kind.Context(Distance.Variable("d")))))) do {
      val function = Type.Function(Type.Unit, Computation(local), region = Some(Top), caller = caller)
      val value = Core.Value(Core.ValueNode.Lambda("u", abstraction, body), function)
      val error = intercept[Diagnostic](Core.validate(Core.Term(Core.Node.Return(value), Computation(function), Top)))
      assert(error.message.contains("alpha notin fv(C)"), error.message)
    }
  }
}
