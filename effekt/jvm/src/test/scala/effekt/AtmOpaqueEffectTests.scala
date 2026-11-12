package effekt

import effekt.atm.{Checker, Core, DirectBackend, Parser, Syntax}
import effekt.atm.Syntax.*

class AtmOpaqueEffectTests extends munit.FunSuite {
  private def checked(s: String): Checker.CheckedProgram =
    Parser.parse(s).flatMap(Checker.check).fold(e => fail(e.message), identity)
  private def rejected(s: String, reason: String): Unit = {
    val result = Parser.parse(s).flatMap(Checker.check)
    assert(result.isLeft, "Invalid abstract effect accepted")
    assert(result.swap.toOption.get.message.contains(reason), result.toString)
  }

  private def program(body: String, result: String = "Int / F"): String = s"""
    def main(): Int = {
      val map = fun [F: Eff] {
        fun (f: (Unit) =>[top] Int / F) at [top]: $result { $body }
      };
      map[pure](fun () at [top]: Int { 41 })
    }
  """

  test("T_App checks an abstract effect without a final-answer projection") {
    Core.elaborate(checked(program("f()")))
  }
  test("T_LetEVar preserves one abstract effect with a pure suffix") {
    val p = checked(program("val n = f(); n + 1"))
    for optimized <- List(false, true) do
      assertEquals(DirectBackend.run(p, optimized).fold(fail(_), identity), "42")
  }
  test("T_LetPure may prefix an abstract computation") {
    Core.elaborate(checked(program("val n = 1; val y = f(); y + n")))
  }
  test("an abstract effect survives branches") {
    Core.elaborate(checked(program("if (true) { f() } else { f() }")))
  }
  test("an abstract effect survives exhaustive matches") {
    val s = "type Choice { One(); Two() }\n" + program("One() match { case One() => f(); case Two() => f() }")
    Core.elaborate(checked(s))
  }
  test("the same abstract effect cannot be composed with itself") {
    rejected(program("val n = f(); f()"), "impure computation requires an inherited final answer")
  }
  test("a pure return cannot fabricate an arbitrary effect") {
    rejected(program("42"), "Expected")
  }
  test("an abstract effect cannot be discarded by a pure result annotation") {
    rejected(program("f()", "Int"), "impure computation requires an inherited final answer")
  }
  test("branches cannot hide an abstract effect in a pure alternative") {
    rejected(program("if (true) { f() } else { 42 }"), "Guarded join")
  }
  test("distinct abstract effects are not interchangeable") {
    rejected("""def main(): Int = {
      val bad = fun [F: Eff, G: Eff] {
        fun (f: (Unit) =>[top] Int / F) at [top]: Int / G { f() }
      };
      42
    }""", "Expected")
  }
  test("strict mode keeps the printed algorithm's opaque-effect boundary") {
    val result = Parser.parse(program("f()")).flatMap(Checker.checkStrict)
    assert(result.isLeft)
    assert(result.swap.toOption.get.message.contains("final-answer projection is undefined"), result.toString)
  }
  test("Core independently rejects a return with an invented abstract effect") {
    val p = Core.elaborate(checked("def main(): Int = 42"))
    val body = p.body.copy(tpe = Computation(Type.Int, Effect.Variable("F")))
    val error = intercept[Syntax.Diagnostic](Core.validate(body, p.datatypes))
    assert(error.message.contains("pure return cannot invent"), error.message)
  }
}
