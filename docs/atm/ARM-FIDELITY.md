# Are the ARM benchmarks faithfully ported?

Audit date: **2026-11-13**. It supersedes the classification of 2026-11-06 and
confirms its counts.

**Short answer.** Every port checks the same universally quantified property
as its OCaml original, and 41 of the 52 ports run the same algorithm with the
same handler clauses. The other 11 are rewritten programs that compute the same
result for this benchmark. In all 52, the refinement types that the original
tool infers are written out by hand. So the suite shows that the checker can
*verify* these programs against supplied specifications. It does not show that
it can infer them, and it is not evidence about the original handler libraries
in general.

## What was compared

- **Originals:** the 52 files of
  [coar](https://github.com/hiroshi-unno/coar/tree/d293ee6759bd285f4a496b727f1df85e70f30f3e/benchmarks/OCaml/popl24/arm)
  at commit `d293ee67`, from Kawamata et al., POPL 2024. All 52 were fetched
  again for this audit and match the hashes in [the manifest](arm-manifest.json).
- **Ports:** the 52 files in [examples/atm/arm](../../examples/atm/arm).
- **Method:** the 30 distinct programs were read side by side with their
  originals. The other 22 files are variants (a different claim, or a different
  input condition) and were compared by diff against both their original and
  their sibling port.

Nothing here is a proof of equivalence. No original was executed.

## Summary

| Class | Meaning | Files | Accepted | Rejected |
| --- | --- | ---: | ---: | ---: |
| **Same program** | Same operations, same clauses, same control flow | 22 | 14 | 8 |
| **Ghost argument** | Same program, but an operation takes one extra argument that only its type uses | 15 | 9 | 6 |
| **Exception re-encoded** | A native exception becomes an operation that never resumes | 4 | 2 | 2 |
| **Rewritten** | State representation or control structure differs | 11 | 6 | 5 |
| | | **52** | **31** | **21** |

"Accepted" and "rejected" are the checker's verdicts in default mode. They
agree with the upstream `SAT`/`UNSAT` file names for 51 files; the exception is
`distribution-UNSAT`, explained [below](#the-false-contract-ports).

## Changes common to every port

These apply to all 52 files and are not repeated in the table.

1. **The handler is inlined.** Each original defines a reusable handler
   function that takes the handled computation as a thunk, such as
   `amb (body: unit -> int result)`. Each port writes `try { body } with h { … }`
   at the single place the original calls that function. No port types the
   handler as a reusable higher-order function.
2. **Specifications are written, not inferred.** The original gives only the
   shape of each operation's answer types, with unknown predicates (`s`, `s1`,
   `p1`, …) for coar to solve. The port states every one of them: the answer
   types of each operation, the type of each recursive helper, and often a
   recursive `measure` that defines the intended result (`search`,
   `readOutcome`, `merge`, …). The stated specification is usually much
   stronger than the property being checked, which then follows from it.
3. **The checked property is the type of `test`.** The original's
   `[@@@assert "typeof(f) <: …"]` becomes the declared type of a function
   `test`. `main` then calls `test` on one concrete input and compares the
   result; this run is a witness, not part of the property.
4. **Types are first-order instances.** Curried functions become nested unary
   functions; `'a list`, `'a option` and `'a result` become `ListInt`,
   `OptionInt` and so on. `Int` is the mathematical integers.
5. **Region annotations are added.** `at [top]`, `=>[R]` and `[R: Reg]` say
   where a function or stored continuation may be called. The originals have no
   counterpart.
6. **Every accepted port needs one inserted annotation.** In strict mode
   (`--check-strict`) all 31 accepted ports are rejected by the side condition
   of `A_Let`: the type synthesized for a `let` body mentions the bound
   variable. Default mode closes that type existentially (`closeLet`). See
   [FIDELITY.md](FIDELITY.md#strict-mode-the-printed-algorithm-without-inserted-annotations).

## Family by family

`S` and `U` count the upstream `SAT` and `UNSAT` files of a family.

### Same program (22 files)

| Family | S | U | What it checks | Notes on the port |
| --- | ---: | ---: | --- | --- |
| `expectation` | 1 | 1 | Weighted sum of both resumptions is `p*x + (100-p)*y` | None beyond the common changes |
| `choose-sum` | 1 | 1 | Sum over both choices of two decisions is `0` | Upstream already passes a `(bool, int)` context to `Decide`; kept |
| `choose-max` | 1 | 1 | Maximum over both choices is `v - x` under ordered inputs | Same context argument, kept |
| `choose-all` | 1 | 1 | All four outcomes, in order, are `[0, x-y, y-x, 0]` | List append is a checked helper with a measure |
| `state-easy` | 1 | 0 | Counter under a state handler returns a nonnegative result | No upstream `UNSAT` file exists |
| `state` | 1 | 0 | Counter returns exactly the initial state | Upstream already passes a context to `Lookup`/`Update`; kept. For `state-UNSAT` see the next table |
| `deferred-1`, `deferred-2` | 2 | 1 | Forcing a stored thunk returns its value | The thunk is stored in a datatype field and forced once, as upstream. `deferred-2` builds its thunk through the same `test` function |
| `distribution` | 1 | 1 | Every probability in the output lies in `0..100` | Higher-order `map` and `foldRight` are kept but typed for this one use |
| `queue-1`, `queue-2` | 2 | 1 | Enqueue 42, dequeue twice: result depends on whether the queue was empty | Upstream context argument on `Get_next` kept |
| `select` | 1 | 1 | First successful choice from `[x, -x]` is `Success [x]` | The collector's `desired_outcome` parameter is fixed to `true`, its only use |
| `yield` | 1 | 1 | Incrementing every yielded leaf turns a tree of 0s into a tree of 1s | The suspended iterator stores the real continuation |

### Ghost argument (15 files)

The port adds an argument to an operation. The handler ignores it at run time;
it exists so that the operation's answer types can mention the program state at
the call. Upstream does the same thing itself in several families (see above),
so this is a difference of degree.

| Family | S | U | What it checks | Argument added |
| --- | ---: | ---: | --- | --- |
| `amb-1`, `amb-2`, `amb-3` | 3 | 2 | Backtracking search over a list finds, or fails to find, a given element | `select` receives the current head and tail. The dummy context parameters of upstream's `amb` function are dropped with the function |
| `amb-3-simpl` | 1 | 0 | The same search returning a Boolean | Same |
| `io-read-1`, `-2`, `-3` | 3 | 1 | Reading `n` items from a list succeeds or fails as expected | `read` receives the number of reads still to come |
| `io-write-1`, `-2` | 2 | 2 | Written items are collected in order | `write` receives the value and the list still to be written |
| `state` (`UNSAT` only) | 0 | 1 | Claims the counter result differs from the initial state | Upstream's `UNSAT` file has **no** context arguments. The port is the `SAT` port with the claim negated, not a translation of this file |

### Exception re-encoded (4 files)

| Family | S | U | What it checks | Change |
| --- | ---: | ---: | --- | --- |
| `safe-div-1`, `safe-div-2` | 2 | 2 | Division returns `None` for a zero divisor and `Some (n/m)` otherwise | Upstream raises the native exception `MyExn` and handles it in the handler's exception clause. The port performs an operation `fail` whose result type is empty and whose clause does not resume. Upstream's `Dummy` operation, which is never performed, is dropped |

### Rewritten (11 files)

These ports are different programs. Each was checked to compute the same
result as its original *for the benchmark's own body*; the comments below say
where that stops being true.

| Family | S | U | What it checks | How the port differs |
| --- | ---: | ---: | --- | --- |
| `bfs`, `bfs-simpl` | 2 | 1 | Breadth-first search over one Boolean choice finds a square root | Upstream keeps a private mutable queue of pending continuations, shared by all `Select`s, and runs the next one whenever a branch fails. The port builds a queue *inside* the `Select` clause holding exactly that choice's two continuations, and moves "on failure, run the next" out of the return clause. This matches upstream only because the body performs one `Select`; with two, the port would not search breadth first. `Select` also receives the two branch results as a ghost argument |
| `round-robin` | 1 | 1 | Two spawned threads, each incrementing a shared counter twice, leave it at least as large as before | Upstream uses a mutable counter, a mutable run queue and a recursive handler that installs itself around each spawned thread. In the port, handlers only turn each operation into a constructor of a `Thread` datatype holding the continuation, and a separate recursive `schedule` function interprets that data with an explicit queue and counter. Incrementing the counter, a plain assignment upstream, becomes an operation |
| `transaction` | 1 | 1 | A transaction either commits (42) or aborts (0), never leaving 2 | Upstream writes the final state into a mutable reference; the port returns it from a state-passing handler. `MyExn` becomes an `abort` operation. The types of `lookup` and `abort` name the one state value (2) that occurs at their single call sites |
| `modulus` | 1 | 1 | Two integer sequences that agree at index 10 give the same result, the largest index queried | Upstream's `mu f sequence` takes an effectful functional `f`. The port drops that parameter and inlines the benchmark's `f` (`0 * call(10)`) |
| `shift` | 1 | 1 | `shift (fun k -> k (k (k n)))` under `reset` gives `8*n + 7` | Upstream's `reset_` is recursive: it re-installs the handler around `f (continue k)`. The port does not. This is the same for this body, which performs no further `Shift`, but it is not a port of shift/reset |

## The false-contract ports

Twenty-two upstream files are named `UNSAT`. Each differs from a `SAT` sibling
by one changed claim, and each port mirrors that change.

- **How they are rejected.** In 20 of the 21 rejected ports, the handler and
  its hand-written specification are identical to the accepted sibling, and
  only the final claim differs. The checker derives the true specification and
  then fails one subtyping query, for example
  `Expected {r: Int | r == 4*n+3}, found {v: Int | v == 8*n+7}` in
  `shift-UNSAT`. The exception is `safe-div-1-UNSAT`, where the false claim
  also appears in the handler's annotation and an operation clause fails.
- **What a rejection means.** The checker is sound but incomplete, so a
  rejection says the stated specification does not imply the claim. In
  `round-robin-UNSAT` the specification is only `r >= init`, so the false claim
  `r == init` is rejected, and so would be the true claim `r == init + 4`.
- **`distribution-UNSAT` is accepted.** Upstream's `SAT` and `UNSAT` files have
  the same code and the same assertion; the second only adds the comment
  `(*unsat*)`. Both ports keep that assertion and both are accepted. This is an
  upstream labeling issue, recorded as `label_discrepancy` in the manifest.
- **`amb-2-UNSAT` and `choose-max-UNSAT`** carry no `(*unsat*)` comment
  upstream but do change the claim; their ports are rejected.

## What the numbers do and do not show

- **They show** that the ATM checker, with refinement types and Z3, accepts 31
  annotated programs built from answer-type-modifying handlers and rejects 21
  programs whose final claim does not follow.
- **They do not show** inference of refinements, answer types, or handler
  specifications. Those are all supplied.
- **They do not show** that the original handler libraries can be typed. Each
  handler is checked at one use, and five families are rewritten around
  features the language lacks: mutable references, native exceptions,
  effectful higher-order parameters, and recursive handlers.
- **They are not** a comparison with coar's running times or annotation
  burden. The two tools are given different inputs.

## Record keeping

- The manifest stores a `fidelity_class` per file, using the earlier names:
  `structurally_close` (20) and `higher_order_monomorphic` (2) are "same
  program" here; `context_instrumented` (15) is "ghost argument";
  `exception_encoding` (4) and `substantial_adaptation` (11) are the last two
  classes.
- 38 recorded port hashes were stale and have been refreshed. They went stale
  when commit `6b04e70d3` replaced the removed `local` arrow syntax with
  fixed-region arrows, line for line. No program logic changed, and
  `python3 docs/atm/paper_coverage.py` passes again.
- Eight more were refreshed after this audit, when unit parameters such as
  `def f(u: Unit)` were shortened to `def f()` in the ports. Verdicts, checked
  types and run results are unchanged.
- To re-verify the originals: `python3 docs/atm/check_arm_manifest.py /path/to/coar`.
