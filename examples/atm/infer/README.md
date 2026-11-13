# Evidence and ATC inference examples

Two sets of examples live here.

- **[boundary/](boundary)** shows where inference stops: one small program per
  case, and for every rejected program the smallest annotated version that is
  accepted. Start here.
- **The six files in this directory** are the nested-handler suite used for the
  paper's inference table. They are described [further down](#nested-handler-suite).

Throughout, "evidence" is the subregion evidence passed to an operation or a
function call, and "ATC" is the answer-type context of an operation. Answer
types of handlers and operations are annotations in every example unless a row
says otherwise.

## What is inferred and what is not

### Inferred

| Situation | Example | What you write |
| --- | --- | --- |
| An operation performed directly in a handler body, on any enclosing handler | [active-three-counts](active-three-counts.effekt) | Nothing. Evidence composes the handler edges; the ATC peels one answer layer per handler crossed (distances 0, 1, 2 here) |
| A call to an ordinary function from any region below it | [nested-residuals](nested-residuals.effekt), [evidence-functions](../evidence-functions.effekt) | Nothing. The call evidence is deferred inside the caller and discharged at its own call sites; a function's region is the lowest one its body needs |
| An operation performed in a helper declared at the handler's region, including a recursive one | [exact-region-helper](boundary/exact-region-helper.effekt) | The region, `at [h]`. Evidence and ATC follow at distance 0 |
| The evidence of an operation whose ATC is written | [function-outer-operation-annotated](boundary/function-outer-operation-annotated.effekt) | The ATC only, as in `h.ask[K]()` |

### Inferred in default mode only

Default mode proposes these annotations and then checks them. Strict mode
(`--check-strict`) follows the printed algorithm, which has no such step, and
rejects both programs.

| Situation | Example | Limit |
| --- | --- | --- |
| The answer type of a handler with no expected type | [handler-answer-preserved](boundary/handler-answer-preserved.effekt) | Only when the handler does not change the answer type |
| The answer types of an operation clause | [clause-answer-preserved](boundary/clause-answer-preserved.effekt) | Only when the clause keeps the handler's answer type |

### Not inferred

Each program on the left is rejected with the diagnostic shown. The program on
the right differs only by the annotation named in the last column, and is
accepted in strict mode as well.

| What is missing | Rejected | Diagnostic | Accepted | What you write |
| --- | --- | --- | --- | --- |
| ATC of an operation inside an ordinary function | [function-outer-operation](boundary/function-outer-operation.effekt) | `ATC inference requires a concrete evidence distance` | [function-outer-operation-annotated](boundary/function-outer-operation-annotated.effekt) | An ATC parameter `[K: ATC(dist(path(h)))]`, used as `h.ask[K]()` |
| ATC argument at a call of such a function | [call-context-argument](boundary/call-context-argument.effekt) | `Function expects 1 explicit ATC arguments` | same as above | The ATC at each call, e.g. `helper[Int / (Int => [])]()` |
| ATC of an operation inside a recursive helper | [recursive-helper](boundary/recursive-helper.effekt) | `ATC inference requires a concrete evidence distance` | [recursive-helper-annotated](boundary/recursive-helper-annotated.effekt) | The region, `at [h]` |
| ATC of an operation on a capability parameter | [capability-parameter](boundary/capability-parameter.effekt) | `ATC inference requires a concrete evidence distance` | [capability-parameter-annotated](boundary/capability-parameter-annotated.effekt) | The region, `at [c]` |
| ATC of an operation where no answer type is inherited | [missing-inherited-answer](boundary/missing-inherited-answer.effekt) | `needs an inherited final answer type` | [missing-inherited-answer-annotated](boundary/missing-inherited-answer-annotated.effekt) | The function's result type, or an explicit invocation ATC as described below |
| Evidence that must pass through a function body | [region-annotation-in-function](boundary/region-annotation-in-function.effekt) | `required region … cannot discharge` | [region-annotation-in-function-annotated](boundary/region-annotation-in-function-annotated.effekt) | Less: drop the region annotation so the function gets its minimum region |
| A common type for branches returning functions at different regions | [branch-function-regions](boundary/branch-function-regions.effekt) | `Guarded join is undefined` | [branch-function-regions-annotated](boundary/branch-function-regions-annotated.effekt) | An eta-expansion of one function at the other's region |
| Answer type of a handler that changes the answer | [handler-answer](boundary/handler-answer.effekt) | `operation requires answer Bool / pure, found Int / pure` | [handler-answer-annotated](boundary/handler-answer-annotated.effekt) | The handler's answer type, `h: Bool` |
| Answer types of a clause that changes the answer | [clause-answer](boundary/clause-answer.effekt) | `Expected Bool, found Int` | [clause-answer-annotated](boundary/clause-answer-annotated.effekt) | The clause's answer types, `Int / (Int => Bool)` |

The first four rows are one limitation seen from different sides. An ordinary
function may be called from any region below its own, so its body is checked at
a fresh region whose distance to an enclosing handler is not a number. The ATC
is defined only for a concrete distance. The two ways out are to state the
function's region (`at [h]`), which makes the distance 0, or to abstract over
the ATC and supply it at each call.

For the missing-inherited-answer example, an alternative repair is
`def helper() at [h] = { h.ask[refl; []]() }`. Strict mode accepts this by
`A_DoAnn`: the supplied context determines both answers without an inherited
final answer. When an answer is inherited, `A_DoAnnF` additionally checks that
the contextual final answer is its subtype. `AtmStrictTests` covers both rules,
including symbolic contexts, nested handlers and invalid annotations.

Two further programs of this kind predate this directory:
[callback-under-handler](../callback-under-handler.effekt) and
[multiple-capabilities](../multiple-capabilities.effekt), with its annotated
form in [annotations/](../annotations/multiple-capabilities.effekt).

`AtmInferBoundaryTests` checks every row: the rejected program fails with the
diagnostic above in both modes, and the accepted one checks in strict mode and
runs to the same value in the reference interpreter and both Chez modes.

```sh
sbt -batch 'effektJVM/testOnly effekt.AtmInferBoundaryTests'
sbt 'effektJVM/runMain effekt.atm.Main --check examples/atm/infer/boundary/recursive-helper.effekt'
sbt 'effektJVM/runMain effekt.atm.Main --check-strict examples/atm/infer/boundary/handler-answer-preserved.effekt'
```

## Nested-handler suite

This suite contains five successful programs and one inference limitation. All
use annotated answer types and omit explicit evidence and ATC arguments. The five
successful programs exercise operation evidence distances and ATC depths **0, 1, 2**
and evaluate to `true` in the source interpreter and both optimized and unoptimized
Lifted/Chez execution. These are runtime witnesses, not refinement proofs of the
Boolean returned by `main`.

| Program | Purpose |
| --- | --- |
| [active-three-counts.effekt](active-three-counts.effekt) | Three active handlers, with evidence/ATC depths 0, 1, and 2 and observable clause results. |
| [active-three-multishot.effekt](active-three-multishot.effekt) | Two outer resumptions restore the two intervening handlers. |
| [nested-residuals.effekt](nested-residuals.effekt) | Abstract helper-call evidence composes with three local handler edges; callers instantiate distances 0 and 1. |
| [mixed-answer-layers.effekt](mixed-answer-layers.effekt) | An outer operation infers a Bool layer around an Int layer, preserving both initial answers. |
| [shadowed-capabilities.effekt](shadowed-capabilities.effekt) | Aliases retain the identities of outer handlers despite repeated source name h. |
| [polymorphic-outer-call.effekt](polymorphic-outer-call.effekt) | **Inference limitation:** a callback captures an outer capability; its abstract caller region gives residual evidence with symbolic distance, so ATC inference is undefined. |

For `polymorphic-outer-call`, evidence reconstruction succeeds **with a residual
obligation**; the checker then reports `Unsupported` at ATC inference. It does
not produce a complete elaboration or run the program. The supplement marks
Evidence with a qualified checkmark and ATCs with a cross. `Checker.inspect`
records the witness/residual and failed ATC attempt directly; it does not infer
these statuses from a diagnostic string or bypass the failure.

`AtmInferTests` checks exact evidence endpoints/composition, complete ATCs and
initial answers, residual abstraction/discharge, and alias identity. It also
constructs two negative variants: an incompatible ATC hole type and too few
answer layers. Those two variants are tests, not additional benchmark files. The symbolic-distance
limitation is the sixth file and has its own table row.

```sh
sbt -batch 'effektJVM/testOnly effekt.AtmInferTests'
sbt 'effektJVM/runMain effekt.atm.Main --elaborate examples/atm/infer/nested-residuals.effekt'
python3 docs/atm/benchmark_arm.py inspect --suite infer
python3 docs/atm/benchmark_arm.py render --suite infer --omit-timing
# After reviewing the document, collect timing last.
python3 docs/atm/benchmark_arm.py time --suite infer --warmups 2 --repetitions 5
python3 docs/atm/benchmark_arm.py render --suite infer
```

The [manifest](../../../docs/atm/infer-manifest.json) pins the source hashes.
Inspection and timing reports are in `docs/atm/results/infer-*.json`.
The supplement has a dedicated inference table; its ARM table remains separate.

Recorded typechecking medians: **0.14–1.50 ms**, including time to report
the unsupported example. Each uses five measured runs after two full-suite
warm-up rounds. See the [raw samples](../../../docs/atm/results/infer-timing.json).
