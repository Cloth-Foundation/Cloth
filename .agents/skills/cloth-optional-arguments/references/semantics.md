# Cloth optional arguments: confirmed semantics

On 2026-11-01 the user requested implementation of the optional/default plan and
explicitly accepted its recommended ordinary-call contract: runtime defaults
reading earlier parameters, supplied source order before defaults, deterministic
mapping without type-based backtracking, positional before named arguments, and
fewer omitted defaults as the final overload tiebreaker. The canonical modifier
order and internal omission-flag ABI are implementation choices for that contract.

## Declarations and values

- Declare `opt Type name ?: expression`, or `opt final Type name ?: expression`.
  `opt` is reserved parameter metadata, not a value type. The fallback is mandatory,
  including for nullable parameters; reject defaults without `opt` and `= defaults`.
- Optionality permits omission only. Nullability independently permits null.
  `opt int? n ?: 7` yields 7 when omitted, null when supplied null, and the supplied
  integer otherwise. `int? n` still requires an argument; `opt int n ?: 7` rejects
  null. No nullable buffer support follows from optional argument support.
- Defaults produce one value under the parameter's declared type context, using
  ordinary strict typing, contextual literals, nullability and array shape rules.
  A default's leading `?:` is a declaration delimiter; later `?:` operators in its
  expression keep ordinary lazy nullable Elvis behavior.
- Parameter names, optionality, defaults and `final` do not distinguish overload
  signatures. Inside the body a resolved optional parameter is an ordinary local
  parameter value and follows ordinary mutability rules.

## Binding calls

- Use `name=expression` only at call argument boundaries. Labels identify callee
  parameters; they neither read nor write caller bindings. Positional arguments
  precede named arguments; named arguments may appear in any order. Reject repeated
  or unknown labels, duplicate supply, missing required parameters and excess
  positional arguments. Built-in labels are not published yet.
- Collect labels, then walk parameters in declaration order. A required parameter
  consumes a positional value if one remains. An optional parameter consumes one
  only when enough positional values remain for later required parameters not
  already named. Otherwise omit it. Apply named supplies afterward; a named
  parameter already consumed positionally is a duplicate, not a reason to slide
  the positional value onward. Any unsupplied required parameter fails binding.
- Type-check the resulting placement without backtracking. For `(opt int a ?: 0,
  int b, opt string label ?: "Value")`, `(25, "Age")` binds a and b, then fails
  b's type check. Use `b=25, label="Age"` for the intended alternative.
- For `(opt string name ?: "Jack", int age)`, `(25)` binds age; `("Mike", 25)`
  binds both; `("Mike", age=25)` and `(25, name="Mike")` both bind the same values.
  A required-only pair `(int a, int b)` rejects `(1, a=2)` as duplicate supply.
  With multiple optionals, extra positional values select earlier optionals first.

## Scope, evaluation and lifetimes

- Resolve and validate defaults at declaration scope, even if every call supplies
  them. Defaults may call forward or recursive functions and read earlier resolved
  parameters. Self/later parameters, caller locals and body locals are unavailable.
  Earlier parameter views are read-only, including indexed writes and deferred
  captured writes. Local independent copies can be modified normally.
- A return cannot escape a default into the caller or callee. Helper calls and
  explicitly declared local deferred blocks retain their own return boundaries.
  Instance fields, `this` and state payload defaults await their separate runtime.
- At each executed call, evaluate supplied arguments once in written order and
  snapshot them before later argument effects. Then evaluate omitted defaults once
  each in parameter order, using captured supplied values and earlier defaults.
  Finally enter the body with a complete parameter list. Reordering slots never
  reorders evaluation. A supplied value skips all execution/allocation of its default.
- A supplied argument returning from the caller prevents later arguments, defaults
  and body execution. A fatal default prevents later defaults and body execution.
  Never execute defaults during overload probing or cache them between calls.
- Array arguments and defaults retain recursive independent value semantics and
  shape checks. Returned buffers outlive the callee through existing arena copies.
  Optionality introduces no implicit object construction/destruction, GC or
  programmer-visible pointer/reference syntax.

## Overloads

Filter each candidate through its one binding plan and supplied strict types.
Use existing whole-argument ordinary exact-type preference before adaptation or
contextual numeric candidates. Ordinary numeric preference precedes nullable
lifting; proven-present nullable reads prefer the payload type as usual.

When otherwise equally good candidates have identical mapped supplied type
contracts, prefer fewer omitted parameters. If their supplied contextual types
are incomparable, preserve ambiguity regardless of omission count. Equal remaining
choices are ambiguous, independent of declaration order or return context.
Names can filter candidates, but default values never infer caller types or run
during selection. No synthetic family of source overloads is created.

For `(int n)` versus `(int n, opt bool flag ?: false)`, `(25)` selects the former.
For `(int n, opt bool flag ?: false)` versus `(int<64> n)`, literal `(25)` retains
the ordinary int preference. `(int<8> n)` versus `(int<64> n, opt bool flag ?: false)`
remains ambiguous for `(25)`. Two equally good candidates each omitting one
different parameter remain ambiguous unless a named supply distinguishes them.
