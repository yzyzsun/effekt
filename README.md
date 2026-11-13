# Answer-Type Modification for Lexical Effect Handlers

This artifact implements an experimental ATM frontend on top of Effekt v0.3.0.
It checks `.effekt` programs using regions, evidence, and answer-type contexts,
elaborates them to typed Core, and executes them through an adapted Lifted
representation and the Chez Scheme backend. The native Effekt compiler is also
included because the regression suite uses its JavaScript backend for
differential checks.

The submission archive contains source code, examples, tests, runtime libraries,
and a JVM-only build. Kiama sources are included directly; the extracted archive
does not require Git or submodule initialization. Run all commands below from
the directory containing this README.

## Requirements

| Tool | Purpose | Version used for validation |
| --- | --- | --- |
| JDK | Build and run the compiler | OpenJDK 27 |
| sbt | Build tool | 1.9.7, pinned in `project/build.properties` |
| Z3 | Refinement checking | 5.1.0 |
| Chez Scheme | Execute compiled programs | 10.4.1 |
| Node.js | JavaScript differential tests | 26.10.0 |

The build downloads Scala 3.3.1 and its Maven dependencies. The first build
requires Internet access unless those dependencies are already cached. The
validated environment is macOS on ARM64; other platforms have not been checked
for this submission. No performance measurements are reproduced by these tests.

Make `java`, `sbt`, `z3`, `chez`, and `node` available on `PATH`. If Chez is named
`scheme` on your system, set `EFFEKT_ATM_CHEZ=scheme`. Similarly,
`EFFEKT_ATM_Z3` can select another Z3 executable. Node is needed for the full
test suite, but not for the default ATM execution route.

## Build and quick start

```sh
sbt -batch 'effektJVM/compile'
sbt -batch 'effektJVM/runMain effekt.atm.Main --check examples/atm/int-to-bool.effekt'
sbt -batch 'effektJVM/runMain effekt.atm.Main --run examples/atm/int-to-bool.effekt'
```

The check prints `Bool / pure`; execution prints `false`, in addition to sbt's
own progress output. This example changes an integer answer to a Boolean answer.

## Run the regression suite

```sh
sbt -batch 'effektJVM/testOnly effekt.Atm*Tests'
```

Expected result: **573 tests passed, 0 failed**. Allow several minutes for the
suite, plus time for the first build. The tests cover parsing, typechecking,
negative cases, Core validation, lowering, and execution. Applicable examples
are compared with a reference evaluator and the native JavaScript backend;
polymorphic operation schemes use the direct Chez backend because the legacy
JavaScript emitter does not support those schemes.

For a smaller check of operation polymorphism and annotation syntax:

```sh
sbt -batch 'effektJVM/testOnly effekt.AtmParserTests effekt.AtmPolymorphicOperationTests effekt.AtmExplicitAnnotationTests'
```

Expected result: **93 tests passed, 0 failed**.

## Examples and compiler output

Replace the example path in the quick-start command to run another program:

| Example under `examples/atm/` | Feature | Execution output |
| --- | --- | --- |
| `int-to-bool.effekt` | Answer-type modification | `false` |
| `multishot.effekt` | Multiple resumption calls | `true` |
| `file-reusable.effekt` | Reusable file-protocol function | `42` |
| `polymorphic-operations.effekt` | Operation-level type and computation parameters | `42` |
| `section5-1-effect-nested.effekt` | Effect-polymorphic variant under an intervening handler | `true` |
| `section5-1-modular-nested.effekt` | Modular helper with weaker, effect-only operation contracts | `42` |

The Section 5.1 variants in this table do **not** validate the paper's original
context-polymorphic signatures. File operations in these examples are mocked
rather than operating on real files.

Other useful commands are:

```sh
sbt -batch 'effektJVM/runMain effekt.atm.Main --elaborate examples/atm/int-to-bool.effekt'
sbt -batch 'effektJVM/runMain effekt.atm.Main --lifted examples/atm/int-to-bool.effekt'
sbt -batch 'effektJVM/runMain effekt.atm.Main --check-json examples/atm/int-to-bool.effekt'
```

`--elaborate` displays the typed Core; `--lifted` displays the backend input.
`--check-json` reports acceptance or a diagnostic as JSON. `--check-strict`
checks the algorithm's rules, including annotated operation calls: explicit
evidence and an answer-type context can determine both answers without an
inherited final answer (`A_DoAnn`); with an inherited answer, `A_DoAnnF` checks
final-answer subtyping. Strict mode still rejects cases requiring an undefined
final-answer projection. Ordinary `--check` additionally supports explicit
declarative checking of opaque effects.

The archive includes 114 `.effekt` examples, including intentional negative and
unsupported cases. In particular, `arm/` contains 52 ARM ports. Some `-UNSAT`
programs should be rejected. The `distribution-UNSAT.effekt` port is accepted:
the upstream input has the same satisfiable assertion as its SAT counterpart.
The `infer/boundary/` directory records inference limitations. Use the test
suite's expectations rather than expecting every example to succeed.

## Archive contents and licenses

| Path in the submission archive | Contents |
| --- | --- |
| `effekt/` | Compiler sources and ATM regression tests |
| `kiama/` | Vendored compiler support sources and their license |
| `libraries/` | Standard libraries and Chez-Lift runtime |
| `examples/atm/` | Example programs and negative cases |
| `tests/fixtures/` | Source inputs required by regression tests |
| `build.sbt`, `project/build.properties` | Minimal JVM build |

Internal documentation, repository history, editor state, build caches, and
generated binaries are excluded from the submission archive. The repository
checkout also contains development material that is not part of the archive.

Effekt is distributed under the MIT license in `LICENSE`. Kiama is distributed
under the Mozilla Public License 2.0 in `kiama/LICENSE`. Upstream copyright and
license notices are retained in the source distribution.
