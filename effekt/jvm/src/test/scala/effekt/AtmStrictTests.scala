package effekt

import effekt.atm.{Checker, Core, DirectBackend, Parser, Syntax, TypeEquality}

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
  private def checked(source: String): Checker.CheckedProgram = {
    val p = strict(source).fold(e => fail(e.message), identity)
    Core.elaborate(p)
    p
  }
  private def run(source: String, expected: String): Unit = {
    val p = checked(source)
    assertEquals(AtmReference.run(p.source), expected)
    for optimized <- List(false, true) do
      assertEquals(DirectBackend.run(p, optimized).fold(fail(_), identity), expected)
  }
  private def rejected(source: String, reason: String): Unit = {
    val parsed = Parser.parse(source).fold(e => fail(e.message), identity)
    val result = Checker.checkStrict(parsed)
    assert(result.isLeft, "strict mode accepted an invalid annotated operation")
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

  private def annotatedOperation(result: String): String = s"""def main(): Bool = {
      try {
        def call(u: Unit) at [h]$result = { h.ask[refl; []]() }
        call()
      } with h: Bool {
        def ask(u: Unit): Int / (Int => Bool) = { val n = resume(42); n == 42 }
        return x => { x }
      }
    }"""

  for (rule, result) <- List("A_DoAnn" -> "", "A_DoAnnF" -> ": Int / (Int => Bool)") do {
    test(s"$rule accepts an explicit ATC in strict mode") {
      run(annotatedOperation(result), "true")
    }
    test(s"$rule checks evidence endpoints and ATC depth") {
      rejected(annotatedOperation(result).replace("[refl; []]", "[ev(h); []]"), "incorrect region endpoints")
      rejected(annotatedOperation(result).replace("[refl; []]", "[refl; Int / (Int => [])]"), "ATC depth differs")
    }
  }

  test("an operation without an ATC still needs an inherited answer") {
    for annotation <- List("", "[refl;]") do
      rejected(annotatedOperation("").replace("[refl; []]", annotation), "needs an inherited final answer type")
  }

  private def symbolicOperation(result: String): String = s"""def main(): Bool = {
    try {
      val call = fun [R: Reg, D: Dist, E: Ev(R, D, outer), K: ATC(D)] {
        fun () at [R]$result { outer.ask[E; K]() }
      };
      try {
        call[inner, 1, ev(inner), Int / (Int => [])]()
      } with inner: Int / (Int => Bool) {
        def unused(): Unit / (Int / (Int => Bool) => Int / (Int => Bool)) = { resume() }
        return x => { x }
      }
    } with outer: Bool {
      def ask(): Int / (Int => Bool) = { val n = resume(42); n == 42 }
      return x => { x }
    }
  }"""

  for (rule, result) <- List("A_DoAnn" -> "", "A_DoAnnF" -> ": Int / (K[Int] => K[Bool])") do {
    test(s"$rule checks symbolic evidence and ATCs and executes at depth one") {
      run(symbolicOperation(result), "true")
    }
    test(s"$rule rejects invalid symbolic annotations") {
      rejected(symbolicOperation(result).replace("Ev(R, D, outer)", "Ev(outer, D, R)"), "incorrect region endpoints")
      rejected(symbolicOperation(result).replace("K: ATC(D)", "K: ATC(D + 1)"), "ATC depth differs")
      rejected(symbolicOperation(result).replace("outer.ask[E; K]", "outer.ask[Missing; K]"), "Unknown or non-evidence")
    }
  }

  test("A_DoAnn checks explicit handler-edge evidence without an inherited answer") {
    run("""def main(): Bool = {
      try {
        try {
          def call() at [inner] = { outer.ask[ev(inner); Int / (Int => [])]() }
          call()
        } with inner: Int / (Int => Bool) {
          def unused(): Unit / (Int / (Int => Bool) => Int / (Int => Bool)) = { resume() }
          return x => { x }
        }
      } with outer: Bool {
        def ask(): Int / (Int => Bool) = { val n = resume(42); n == 42 }
        return x => { x }
      }
    }""", "true")
  }

  test("A_DoAnnF widens the final answer by subtyping and preserves the initial answer") {
    import Syntax.*
    val p = checked("""def main(): Int = {
      try { h.ask[refl; []]() } with h: Int {
        def ask(): Int / (Int => {n: Int | n == 42}) = { 42 }
        return x => { x }
      }
    }""")
    val operation = p.body.children.head
    val inferred = operation.operationTyping.get.inferred
    assertEquals(operation.tpe, Computation(Type.Int, Effect.Change(Computation(Type.Int), Computation(Type.Int))))
    inferred.effect match {
      case Effect.Change(initial, answer) =>
        assertEquals(initial, Computation(Type.Int))
        assert(!TypeEquality.same(answer, Computation(Type.Int)), "the test must exercise a proper subtype")
      case _ => fail("annotated operation has no answer transition")
    }
    assertEquals(DirectBackend.run(p, optimized = false).fold(fail(_), identity), "42")
  }

  test("A_DoAnnF rejects a final answer that is not a subtype of the inherited answer") {
    rejected(annotatedOperation(": Int / (Int => Int)"), "Answer mismatch")
  }

  for (rule, result) <- List("A_DoAnn" -> "", "A_DoAnnF" -> ": {n: Int | n == 42} / (Int => Int)") do {
    test(s"$rule substitutes operation type and value arguments") {
      val source = s"""interface Echo {
        def echo[A](x: Int): {n: Int | n == x} / (A => A)
      }
      def main(): Int = {
        try {
          def call() at [h]$result = { h.echo[refl; []][Int](42) }
          call()
        } with h: Echo {
          def echo(x) = resume(x)
          return x => { x }
        }: Int
      }"""
      run(source, "42")
      rejected(source.replace("[Int](42)", "[Int](true)"), "Expected Int")
      rejected(source.replace("[Int](42)", "[Bool](42)"), "Answer mismatch")
    }
  }
}
