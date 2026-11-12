package effekt

import effekt.atm.{Checker, Parser}

/** Strict mode is the printed algorithm without inserted annotations. */
class AtmStrictTests extends munit.FunSuite {
  private def strict(source: String) = Parser.parse(source).flatMap(Checker.checkStrict)
  private def default(source: String) = Parser.parse(source).flatMap(Checker.check)
  private def onlyDefault(source: String, reason: String): Unit = {
    assert(default(source).isRight, default(source).toString)
    val result = strict(source)
    assert(result.isLeft, "strict mode accepted an inserted annotation")
    assert(result.swap.toOption.get.message.contains(reason), result.toString)
  }

  test("a fully annotated nested handler is accepted in strict mode") {
    val result = strict("""def main(): Bool = {
      try {
        try { () } with inner: Int / (Int => Bool) {
          def tick(u: Unit): Unit / (Int / (Int => Bool) => Int / (Int => Bool)) = { resume() }
          return x => { outer.ask() }
        }
      } with outer: Bool {
        def ask(u: Unit): Int / (Int => Bool) = { val n = resume(42); n == 42 }
        return x => { x }
      }
    }""")
    assert(result.isRight, result.toString)
  }

  test("strict mode does not guess a handler's answer type") {
    onlyDefault("""def main(): Int = {
      val n = try { h.ask() } with h {
        def ask(u: Unit): Int / (Int => Int) = { resume(41) }
        return x => { x }
      };
      n + 1
    }""", "A_Handle is a checking rule")
  }

  test("strict mode does not default an operation clause's answer types") {
    onlyDefault("""def main(): Int = {
      try { h.ask() } with h: Int {
        def ask(u: Unit): Int = { resume(42) }
        return x => { x }
      }
    }""", "needs its answer types")
  }

  test("strict mode does not eta-expand a named function at another region") {
    onlyDefault("""def main(): Int = {
      try {
        def apply(f: (Int) => Int): Int = { f(42) }
        def inc(x: Int): Int = { x }
        apply(inc)
      } with h: Int {
        def unused(u: Unit): Unit / (Int => Int) = { resume() }
        return x => { x }
      }
    }""", "Expected")
  }

  test("strict mode does not let an explicit ATC replace the inherited answer") {
    onlyDefault("""def main(): Bool = {
      try {
        def call(u: Unit) at [h] = { h.ask[refl; []]() }
        call()
      } with h: Bool {
        def ask(u: Unit): Int / (Int => Bool) = { val n = resume(42); n == 42 }
        return x => { x }
      }
    }""", "needs an inherited final answer type")
  }
}
