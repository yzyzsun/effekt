# Function requirements and the A_Abs boundary

Updated 2026-11-13. This supersedes the November 12 claim that ordinary
caller-indexed result families implement Definition 17. That claim was wrong:
the fresh caller belongs to the elaboration, and must not occur free in the
source codomain. The paper sources are unchanged.

## Ordinary source arrows

An ordinary source arrow is `(x: T) ->rho C`. Its region `rho` is the minimum
requirement for calling the function. A call from `r` reconstructs evidence
`r <= rho`; it does not require `r = rho`. Both `T` and `C` are independent of
this function's fresh body region `alpha`.

`A_AbsChk` takes `T`, `rho`, and `C` from the expected arrow. It checks the body
at fresh `alpha`, using `tail(C)`, and discharges each captured obligation
`alpha <= rho_i` through the supplied requirement `rho <= rho_i`.
`A_AbsSyn` checks the body in empty-answer mode, enforces `alpha notin fv(C)`,
and chooses `rho` as the meet of captured targets (or `top` for an empty set).
Residuals unrelated to `alpha` propagate outward. Neither rule binds `alpha`
in the source type. Checking rejects caller-dependent result annotations too.

Definition 17 translates the source arrow as:

```text
forall r: Reg. forall d: Dist. forall e: Ev(r,d,rho).
  (x: [[T]]) ->[r] [[C]]
```

The quantifiers are fresh for `T` and `C`. They permit the elaborated function
to execute at any admissible caller; they do not turn `C` into a family `C(r)`.
`A_App` keeps the source codomain and substitutes the value argument for `x`.
The target application also supplies the actual caller, distance, and witness.

`Checker.abstraction` stores an ordinary `Type.Function` without caller
metadata. Its `FunctionAbstraction` separately retains the elaboration's fresh
region and call witness. Application reconstructs the caller-to-requirement
path. Core independently checks codomain freshness against the abstraction
region. Its type annotations still use algorithmic constructors rather than a
complete separate declarative type language; these checks are not a compiler
soundness proof.

## Rejected result dependencies and explicit alternatives

This previously accepted synthesis is now rejected:

```effekt
val make = fun (u: Unit) { fun (x: Int): Int { x } };
```

The annotated inner function defaults its requirement to the outer body's
fresh region, so that region occurs in the synthesized outer codomain.
An inner function with an inferred, caller-independent requirement is valid:

```effekt
val make = fun (u: Unit) { fun (x: Int) { x } };
```

An expected source type can also supply a stable codomain:

```effekt
val make: ((Unit) => ((Int) => Int)) = fun (u) { fun (x) { x } };
```

Returned evidence kinds, fixed arrows, nested domains, and answer endpoints
are all included in the free-region check. A result depending on a region
explicitly quantified *outside* the ordinary arrow remains valid:

```effekt
val make = fun [R: Reg] (u: Unit) { fun [E: Ev(R, 0, R)] { 42 } };
make[top]()[refl(top)]
```

The post-parameter name in `fun (x: T)[R: Reg] { ... }` is retained as a
surface alias for the body region. It is available for internal evidence and
later call-ATC kinds, but cannot occur free in the function's result annotation
or inferred result. It does not bind a region in the resulting ordinary source
arrow. The same restriction applies to `(T)[R: Reg] => C` annotations and
recursive declarations. The alias does not scope over `T`. Handler labels and
`top` name existing regions; `here` has no predefined meaning.

## Explicit call ATCs

Post-parameter ATCs remain an extension beyond printed `A_Abs`:

```effekt
val callback = fun ()[K: ATC(dist(path(h)))]:
    Int / (K[Int] => K[Bool]) {
  h.ask[path(h); K]()
};
callback[[]]()
```

The explicit telescope uses caller/distance metadata to check ATC depths at
each call. Its parameters can occur in `C`, but the fresh caller region still
cannot occur free there. Call ATCs do not scope over the value-argument domain.
An ATC quantified before the ordinary function is already in scope and may
occur in its domain. The definition is checked once under rigid ATCs and call
evidence; calls supply contexts explicitly. Symbolic-depth ATC inference remains
unsupported, as described in the appendix's “Incompleteness” paragraph.

`dist(proof)` projects a checked witness's distance. The required region is the
meet of the lexically available `path(r)` targets in the declared ATC kinds,
excluding the named caller, or the lexical region when there are no targets.
The body cannot add an undischarged obligation from its fresh caller. Other
residuals propagate outward. This annotation-directed extension is distinct
from ordinary `A_AbsSyn`'s inference from body residuals.

## Reusable Fig. 1

[`file-reusable.effekt`](../../examples/atm/file-reusable.effekt) explicitly
quantifies `R: Reg` and `C: Cty` before its ordinary descriptor parameter:

```effekt
val file = fun [R: Reg, C: Cty] (c: Int) {
  fun (body: /* callback contract mentioning R */) at [R]: C {
    // Install h, then invoke body(h)[ev(h)][[]]().
  }
};
file[top][Int](7)(/* outer callback */)
// In the outer callback's body, whose region is named S:
file[S][A](35)(/* inner callback */)
```

The ordinary arrow's domain is `Int` and its result depends on the already
bound `R`, never its fresh caller. The returned consumer has an explicit
fixed-region contract, so its call must occur at `R`. The helper remains
checked once, with one handler body and two computation instantiations.
`interface File`, `read(): Int`, and `close(): Unit` remain unchanged.
The callbacks use explicit ATCs and evidence for symbolic-distance operations.

## Fixed-region contracts

`(T) =>[r] C` and `fun (x: T) at [r]: C { ... }` retain the declarative
`T_Abs`/`T_App` behavior: check and call at exactly `r`, with no implicit call
witness. Plain `fun` can check against an expected fixed arrow. This is an
explicit extension, not the meaning of `rho` on an ordinary source arrow.
Stored resumptions and the ARM ports use this bridge. Fixed functions cannot
be silently promoted to ordinary arrows. Annotated fixed recursion remains
at its enclosing lexical region. The `local` keyword remains removed.

See [VALIDATION.md](VALIDATION.md) for checks and [FIDELITY.md](FIDELITY.md) for
other implementation extensions and remaining limitations.
