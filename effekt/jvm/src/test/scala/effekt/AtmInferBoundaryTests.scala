package effekt

import effekt.atm.{Checker, DirectBackend, Parser}
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** examples/atm/infer/boundary: what is inferred, what is not, and the
 * smallest annotation that repairs each rejected program.
 */
class AtmInferBoundaryTests extends munit.FunSuite {
  private val directory = Path.of("examples/atm/infer/boundary")
  private def source(name: String): String = Files.readString(directory.resolve(name + ".effekt"))

  /** Accepted in strict mode too: nothing is inserted on the user's behalf. */
  private val inferred = List("exact-region-helper" -> "true")
  /** Accepted only in default mode, which proposes the missing annotation. */
  private val proposed = List("handler-answer-preserved" -> "42", "clause-answer-preserved" -> "42")
  /** Rejected program, the diagnostic that names the limitation, its repair and the repair's result. */
  private val limits = List(
    ("function-outer-operation", "symbolic distance", "function-outer-operation-annotated", "true"),
    ("call-context-argument", "expects 1 explicit ATC arguments", "function-outer-operation-annotated", "true"),
    ("recursive-helper", "symbolic distance", "recursive-helper-annotated", "true"),
    ("capability-parameter", "symbolic distance", "capability-parameter-annotated", "true"),
    ("missing-inherited-answer", "needs an inherited final answer type", "missing-inherited-answer-annotated", "true"),
    ("region-annotation-in-function", "cannot discharge", "region-annotation-in-function-annotated", "42"),
    ("branch-function-regions", "Guarded join is undefined", "branch-function-regions-annotated", "true"),
    ("handler-answer", "operation requires answer Bool", "handler-answer-annotated", "true"),
    ("clause-answer", "Expected Bool, found Int", "clause-answer-annotated", "true"))

  private def accepted(name: String, expected: String, strict: Boolean): Unit = {
    val parsed = Parser.parse(source(name)).fold(e => fail(e.message), identity)
    val p = Checker.check(parsed).fold(e => fail(s"$name: ${e.message}"), identity)
    assertEquals(AtmReference.run(p.source), expected)
    for optimized <- List(false, true) do
      assertEquals(DirectBackend.run(p, optimized).fold(fail(_), identity), expected)
    assertEquals(Checker.checkStrict(parsed).isRight, strict, s"$name in strict mode")
  }

  test("the directory contains exactly the documented examples") {
    val documented = inferred.map(_._1) ++ proposed.map(_._1) ++ limits.flatMap(l => List(l._1, l._3))
    val present = Files.list(directory).iterator().asScala.map(_.getFileName.toString)
      .filter(_.endsWith(".effekt")).map(_.stripSuffix(".effekt")).toSet
    assertEquals(present, documented.toSet)
  }

  for (name, expected) <- inferred do test(s"$name: inferred, also in strict mode") {
    accepted(name, expected, strict = true)
  }

  for (name, expected) <- proposed do test(s"$name: proposed in default mode, rejected in strict mode") {
    accepted(name, expected, strict = false)
  }

  for (name, reason, repair, expected) <- limits do test(s"$name: not inferred; $repair repairs it") {
    val result = Parser.parse(source(name)).flatMap(Checker.check)
    assert(result.isLeft, s"$name was accepted")
    assert(result.swap.toOption.get.message.contains(reason), result.toString)
    assert(Parser.parse(source(name)).flatMap(Checker.checkStrict).isLeft, s"$name was accepted in strict mode")
    accepted(repair, expected, strict = true)
  }
}
