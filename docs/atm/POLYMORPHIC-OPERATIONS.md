# Polymorphic operation signatures

The ATM frontend supports explicit quantifiers on individual operations, in
named interfaces, structural capability contracts, and inline handler headers.
The supported parameters are value types (`A` or `A: Type`), predicates
(`P: Pred(Int)`), answer effects (`F: Eff`), and the existing `Reg`, `Dist`,
`Cty`, `Ev(...)`, and `ATC(...)` kinds. Kinds may depend on preceding parameters.

```effekt
interface Operations {
  def echo[A](x: A): A
  def ping[C: Cty](): Unit / (C => C)
}
def main(): Int = {
  try {
    h.ping[Int]()
    val flag = h.echo[Bool](true)
    if (flag) { h.echo[Int](42) } else { 0 }
  } with h: Operations {
    def echo(x) = resume(x)
    def ping() = resume(())
  }
}
```

The handler inherits its operation parameters and missing value annotations
from the signature. It need not repeat `[A]` or `[C: Cty]`. A repeated parameter
list may rename binders; its kinds must match the declaration. Signature names
are available in the clause when the list is omitted. A complete answer
transition in an interface is also inherited, and an implementation cannot
replace it with a different contract. Value-only interface declarations retain
the existing contextual answer convention.

Calls supply arguments explicitly, as `h.echo[Int](42)` or
`h.op[pure, 0, []](())`. An optional first group supplies the invocation's
evidence and ATC: `h.echo[refl; []][Int](42)`. Use `[e;]` or `[; K]` to
supply only one of those annotations before the typelike arguments. Chained
typelike groups also work after the annotation group. Evidence/ATC annotations
following typelike arguments are rejected.
An untagged single bracket argument is classified using the operation's
quantified kind. For monomorphic operations, the old single evidence/ATC
annotation remains supported.

The checker resolves each signature's telescope with fresh identities and
checks each handler clause once under those abstract parameters. It does not
specialize a clause for observed calls. Instantiation checks arity, kinds,
dependent kinds, and the substituted value and answer contracts. Capability
subtyping and equality account for the telescope. Clause residual constraints
cannot export its bound regions. Core records the resolved arguments and
checks their instantiation, evidence, and context depths.

Direct Lifted/Chez preserves method-level type/computation parameters and
runtime evidence arguments; other typelike arguments are erased. The legacy
Effekt/JavaScript emitter reports these operation schemes as unsupported.
Opaque effects can now be checked directly by the declarative application and
let rules described below. Abstract ATC result projections still have backend
restrictions. These changes do not establish a new soundness or
compiler-correctness theorem.

## Section 5.1 check

The checked source is the current
`lexical-effects/PLDI27/sections/extension.otex`, subsection **Parametric
Polymorphism**, including **Modular Effectful Functions**. The original
translation exposed separate handler and helper typing obligations. The
manuscript's original context-polymorphic signatures have been restored; the
calculus is unchanged, and that example remains unresolved. The executable
modular examples below use weaker, effect-only operation contracts. They do
not validate the restored signatures. File I/O is mocked and the paper's
exact-region arrows use `=>[r]`.

### Original context-polymorphic contract: unresolved

The original close signature is:

```effekt
def close[F: Eff, D: Dist, K: ATC(D)](): Unit /
  (K[ClosedAnswer / F] => K[OpenAnswer / F])
```

The read clause can forward `resume(42)` at its analogous context-polymorphic
type. The close clause must transform the result inside `K`: its resumption
returns `K[ClosedAnswer / F]`, which does not generally return a state function.
The clause `val next = resume(()); fun (s) { next(token(Closed)) }` is therefore
not a generic implementation of this signature. This also fails for the
concrete depth-one context `K = Int / (Unit => [])`, independently of abstract
result projection: the computation returns an integer and has the wrong final
answer for the intended close clause.

The helper has a separate obligation. `T_Do` applies its invocation context to
the instantiated signature. The original call uses the same `K` in both
positions, producing `K[K[C]]` where its stated type requires `K[C]`. Fixing the
handler alone would not resolve that mismatch. These contexts can be distinct
in general; the issue is this particular instantiation and claimed helper type.

The CPS translation supplies ATC `fmap` and `lift`, but they are not source
constructs. Exposing them would require an extension; no such extension is
introduced here. The Section 4.3 clause-order change addresses only the earlier
effect-polymorphic contract, where the resumption has effect `F` outside a pure
state function. It does not discharge either context-polymorphic obligation.

### Executable variant with effect-only operation signatures

The existing modular examples use the following weaker operation schemes:

```effekt
interface File {
  def read[F: Eff](): Int / (OpenAnswer / F => OpenAnswer / F)
  def close[F: Eff](): Unit / (ClosedAnswer / F => OpenAnswer / F)
}
```

The helper still quantifies its invocation region, distance `D`, `K: ATC(D)`,
evidence `E`, and outer effect `F`. Its body calls `x.close[E; K][F]()`.
The operation rule applies `K` exactly once, giving the stated helper result
`Unit / (K[ClosedAnswer / F] => K[OpenAnswer / F])`. The source capability
parameter binds the paper's capability region; the other five typelike
parameters remain explicit. No depth check or plugging rule has changed.

[`section5-1-modular-file.effekt`](../../examples/atm/section5-1-modular-file.effekt)
instantiates this helper at distance zero.
[`section5-1-modular-nested.effekt`](../../examples/atm/section5-1-modular-nested.effekt)
uses the same universally checked helper across an intervening handler, with
distance one and `K = Int / (ClosedAnswer => [])`. Both return **42**.
Tests still reject the original helper's doubly applied context.

### Check opaque effects and place them at the declared level

`T_App` can check a known codomain `T / F` directly. `T_LetEVar` permits
`let x = M in N : U / F` when `M : T / F` and `N : U / pure`; `T_LetPure`
also permits a pure prefix. The checker implements these rules when checking
an opaque effect, without inventing a final-answer projection. Conditionals
and exhaustive matches retain the same abstract effect. It rejects a pure
return at an arbitrary `F`, discarding `F`, changing it to another variable,
or composing `F` with itself. Core independently checks opaque application
effects, let composition and the prohibition on fabricating `F` with `return`.
Strict mode retains the printed algorithm's undefined-projection rejection.

This exposes an additional problem in the original clause layout: putting
`resume` inside the returned lambda delays `F` into that function's body, but
the declared state function is pure and `F` occurs outside it. The repair
resumes before constructing the pure wrapper:

```effekt
def read() = { val next = resume(42); fun (s) { next(token(Open)) } }
def close() = { val next = resume(()); fun (s) { next(token(Closed)) } }
```

The resumption runs at `F`; constructing the wrapper is pure. This is a
deliberate change in clause evaluation order. The original delayed-resumption
layout remains rejected; operation polymorphism alone does not make it valid.
For real I/O, reads/closes must likewise occur before the resumption, at clause
execution, rather than being deferred into the returned pure state wrapper.

[`section5-1-effect-file.effekt`](../../examples/atm/section5-1-effect-file.effekt)
returns **42**. [`section5-1-effect-nested.effekt`](../../examples/atm/section5-1-effect-nested.effekt)
instantiates `F` with the non-pure transition `Int => Bool` and returns **true**.
Its state function types use the enclosing handler's region, so their calls
also satisfy the exact-region rule.

Run the accepted example with:

```sh
sbt 'effektJVM/runMain effekt.atm.Main --run examples/atm/polymorphic-operations.effekt'
```

`AtmPolymorphicOperationTests` covers acceptance, execution with optimization
on/off, rejection of invalid generic clauses, telescope scoping, and the four
repaired Section 5.1 examples. `AtmOpaqueEffectTests` covers the declarative
checking rules and their rejection boundaries.
