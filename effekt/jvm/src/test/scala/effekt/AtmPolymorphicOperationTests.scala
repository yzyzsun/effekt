package effekt

import effekt.atm.{Checker, Core, DirectBackend, Parser, Syntax}
import java.nio.file.{Files, Path}

class AtmPolymorphicOperationTests extends munit.FunSuite {
  private def checked(s: String): Checker.CheckedProgram =
    Parser.parse(s).flatMap(Checker.check).fold(e => fail(e.message), identity)
  private def rejected(s: String, reason: String): Unit = {
    val result = Parser.parse(s).flatMap(Checker.check)
    assert(result.isLeft, "Invalid polymorphic operation accepted")
    assert(result.swap.toOption.get.message.contains(reason), result.toString)
  }
  private val echo = """interface Echo { def echo[A](x: A): A }
    def main(): Int = {
      try {
        val b = h.echo[Bool](true);
        if (b) { h.echo[Int](42) } else { 0 }
      } with h: Echo { def echo(x) = resume(x) }
    }"""

  test("one generic clause checks once and serves distinct value types") {
    val p = checked(echo)
    Core.elaborate(p)
    assertEquals(AtmReference.run(p.source), "42")
    for optimized <- List(false, true) do
      assertEquals(DirectBackend.run(p, optimized).fold(fail(_), identity), "42")
  }
  test("unused invalid generic implementation is rejected") {
    rejected("""interface Echo { def echo[A](x: A): A }
      def main(): Int = try { 42 } with h: Echo { def echo(x) = resume(0) }
    """, "Expected")
  }
  test("operation type arguments are required") {
    rejected(echo.replace("echo[Int]", "echo"), "Wrong number of type arguments")
  }
  test("operation type arguments have checked arity") {
    rejected(echo.replace("echo[Int]", "echo[Int, Bool]"), "Wrong number of type arguments")
  }
  test("instantiated operation domains are checked") {
    rejected(echo.replace("echo[Int](42)", "echo[Bool](42)"), "Expected Bool")
  }
  test("handler binders may rename signature parameters") {
    Core.elaborate(checked(echo.replace("def echo(x)", "def echo[B](x: B): B")))
  }
  test("polymorphic operation result projection supports inferred handlers") {
    Core.elaborate(checked(echo.replace("main(): Int", "main()")))
  }
  test("handler binder kinds must match the signature") {
    rejected(echo.replace("def echo(x)", "def echo[B: Cty](x)"), "Type parameters")
  }
  test("computation parameters can occur in both answer endpoints") {
    val p = checked("""interface Ping {
      def ping[C: Cty](): Unit / (C => C)
    }
    def main(): Int = {
      try { h.ping[Int](); 42 } with h: Ping { def ping() = resume(()) }
    }""")
    Core.elaborate(p)
    for optimized <- List(false, true) do
      assertEquals(DirectBackend.run(p, optimized).fold(fail(_), identity), "42")
  }
  test("evidence and ATC annotations precede typelike arguments with either annotation omitted") {
    for annotations <- List("[refl; []]", "[refl;]", "[; []]",
      "[evidence refl; atc []]", "[evidence refl]", "[atc []]") do {
      val p = checked(echo.replace("h.echo[Int](42)", s"h.echo$annotations[Int](42)"))
      Core.elaborate(p)
      assertEquals(DirectBackend.run(p, optimized = false).fold(fail(_), identity), "42")
    }
  }
  test("annotated polymorphic calls validate evidence and context depths") {
    rejected(echo.replace("h.echo[Int](42)", "h.echo[ev(h); []][Int](42)"), "incorrect region endpoints")
    rejected(echo.replace("h.echo[Int](42)", "h.echo[refl; Int / (Int => [])][Int](42)"), "ATC depth differs")
  }
  test("legacy tag words may name operation typelike arguments") {
    Core.elaborate(checked("""interface Probe { def probe[E: Ev(top, 0, top)](): Int }
      def main(): Int = {
        evidence evidence = refl;
        try { h.probe[evidence]() } with h: Probe { def probe() = resume(42) }
      }"""))
  }

  test("effect, distance, dependent ATC, region, and evidence parameters") {
    val p = checked("""interface Probe {
      def probe[F: Eff, D: Dist, K: ATC(D), R: Reg, E: Ev(R, D, top)](): Int / (K[Int / F] => Int)
    }
    def main(): Int = {
      try { h.probe[refl; []][pure][0][[]][top][refl(top)]() } with h: Probe {
        def probe() = 42
      }
    }""")
    Core.elaborate(p)
  }
  test("dependent parameter kinds are checked after earlier arguments") {
    rejected("""interface Probe {
      def probe[D: Dist, K: ATC(D)](): Int / (K[Int] => Int)
    }
    def main(): Int = {
      try { h.probe[1, []]() } with h: Probe { def probe() = 42 }
    }""", "kind mismatch")
  }
  test("predicate parameters scope over operation argument and result refinements") {
    val p = checked("""interface Echo {
      def echo[P: Pred(Int)](x: {n: Int | P(n)}): {n: Int | P(n)}
    }
    def main(): Int = {
      try { h.echo[(n: Int) => n == 42](42) } with h: Echo { def echo(x) = resume(x) }
    }""")
    Core.elaborate(p)
    for optimized <- List(false, true) do
      assertEquals(DirectBackend.run(p, optimized).fold(fail(_), identity), "42")
  }
  test("generic capabilities pass through structural consumers") {
    val p = checked("""interface Echo { def echo[A](x: A): A }
    def main(): Int = {
      val use = fun (c: { def echo[B](B): B / (Int => Int); }) {
        fun () at [c]: Int / (Int => Int) { c.echo[Int](42) }
      };
      try { use(h)() } with h: Echo { def echo(x) = resume(x) }: Int
    }""")
    for optimized <- List(false, true) do
      assertEquals(DirectBackend.run(p, optimized).fold(fail(_), identity), "42")
  }
  test("higher-order generic operation parameters retain their method-bound types") {
    val p = checked("""interface Apply {
      def apply[A, B](x: A, f: (A) =>[top] B): B
    }
    def main(): Int = {
      try { h.apply[Bool, Int](true, fun (x) { if (x) { 42 } else { 0 } }) }
      with h: Apply { def apply(x, f) = { val y = f(x); resume(y) } }
    }""")
    for optimized <- List(false, true) do
      assertEquals(DirectBackend.run(p, optimized).fold(fail(_), identity), "42")
  }
  test("operation region parameters scope over checked lambdas and Core") {
    val p = checked("""interface Factory { def make[R: Reg](): (Unit) =>[R] Int }
    def main(): Int = {
      try { val f = h.make[h](); f() }
      with h: Factory { def make() = resume(fun (u) { 42 }) }
    }""")
    for optimized <- List(false, true) do
      assertEquals(DirectBackend.run(p, optimized).fold(fail(_), identity), "42")
  }
  test("operation evidence parameters bind actual runtime evidence in the clause") {
    val p = checked("""interface Probe {
      def probe[R: Reg, D: Dist, E: Ev(R, D, top)](): (Unit) =>[R] Int
    }
    def main(): Int = {
      def id(x: Int): Int = x
      try { val g = h.probe[h, 1, ev(h)](); g() } with h: Probe {
        def probe() = resume(fun () { id(42) })
      }: Int
    }""")
    for optimized <- List(false, true) do
      assertEquals(DirectBackend.run(p, optimized).fold(fail(_), identity), "42")
  }
  test("interface parameters and method parameters have separate scopes") {
    Core.elaborate(checked("""interface Echo[A] { def echo[A](x: A): A }
    def main(): Int = {
      try { h.echo[Int](42) } with h: Echo[Bool] { def echo(x) = resume(x) }
    }"""))
  }
  test("unused malformed operation kinds fail at declaration") {
    rejected("""interface Bad { def op[K: ATC(D), D: Dist](): Int }
      def main(): Int = 42""", "Unknown")
  }
  test("an operation binder shadows differently kinded outer parameters") {
    rejected("""def main(): Unit = {
      val f = fun [F: Eff] { fun () {
        try { () } with h {
          def op[F](): Unit / (Int / F => Int / F) = ()
        }: Unit
      } };
      ()
    }""", "Unknown answer-effect variable F")
  }
  test("Core independently rejects a changed operation type argument") {
    val core = Core.elaborate(checked("""interface Echo { def echo[A](x: A): A }
      def main(): Int = try { h.echo[Int](42) } with h: Echo { def echo(x) = resume(x) }
    """))
    val Core.Node.Handle(r, e, body, x, ret, ops) = core.body.node: @unchecked
    val Core.Node.Perform(c, op, a, ev, typing) = body.node: @unchecked
    val bad = body.copy(node = Core.Node.Perform(c, op, a, ev,
      typing.copy(arguments = List(Syntax.PolymorphicArgument.ValueType(Syntax.Type.Bool)))))
    val root = core.body.copy(node = Core.Node.Handle(r, e, bad, x, ret, ops))
    val error = intercept[Syntax.Diagnostic](Core.validate(root, core.datatypes))
    assert(error.message.contains("instantiated signature"), error.message)
  }
  test("full interface answer contracts cannot be changed by implementations") {
    rejected("""interface Ping { def ping[A](): Unit / (Int => Int) }
      def main(): Int = {
        try { 42 } with h: Ping { def ping(): Unit / (Bool => Int) = 42 }
      }""", "Answer contract")
  }

  for (name, expected) <- List("modular-file" -> "42", "effect-file" -> "42",
    "modular-nested" -> "42", "effect-nested" -> "true") do
    test(s"Section 5.1 repaired $name checks abstractly and executes") {
      val source = Files.readString(Path.of(s"examples/atm/section5-1-$name.effekt"))
      val p = checked(source)
      Core.elaborate(p)
      assertEquals(AtmReference.run(p.source), expected)
      for optimized <- List(false, true) do
        assertEquals(DirectBackend.run(p, optimized).fold(fail(_), identity), expected)
    }
  test("a signature cannot apply the invocation's answer context a second time") {
    val source = Files.readString(Path.of("examples/atm/section5-1-modular-file.effekt"))
      .replace("def close[F: Eff](): Unit / (ClosedAnswer / F => OpenAnswer / F)",
        "def close[F: Eff, D: Dist, K: ATC(D)](): Unit / (K[ClosedAnswer / F] => K[OpenAnswer / F])")
      .replace("x.close[E; K][F]()", "x.close[E; K][F, D, K]()")
    rejected(source, "Answer mismatch")
  }
  test("deferring the abstract effect inside a pure state wrapper remains invalid") {
    val source = Files.readString(Path.of("examples/atm/section5-1-effect-file.effekt"))
      .replace("{ val next = resume(42); fun (s) { next(token(Open)) } }",
        "fun (s) { val next = resume(42); next(token(Open)) }")
    rejected(source, "impure computation requires an inherited final answer")
  }
}
