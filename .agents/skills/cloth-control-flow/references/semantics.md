# Cloth control flow: confirmed semantics

These decisions were recorded in the shared Cloth semantics reference.
User-confirmed rules govern intended behavior; historical implementation
notes and unresolved choices retain their original qualifications. Read
the parent skill for current implementation boundaries and source/test routing.

## Type checks, state checks, and equality

- `is` supports both active-state checks, such as `player is Alive`, and runtime
  type checks, such as `entity is Player`. The target declaration determines
  which check is requested.
- `==` handles value equality and numeric range membership. Do not use it as a
  type-check or active-state-check operator.
- Numeric `==` and `!=` support `int` and `float` operands and return `bool`.
  As with boolean equality, both operands are evaluated once, left to right.
  Boolean/numeric comparisons are type errors; numbers are not converted to
  booleans for equality. Assignment type checks remain unchanged.
- Numeric `==` / `!=` also support range membership, confirmed on 2026-11-03:
  `10 == 1..100` is true, `100 == 1..100` is false, and `1..=100 == 100`
  is true. Either operand order is valid and inequality is the inverse.
  Read the [membership contract](../../cloth-numerics/references/range-membership.md)
  for contextual widths, float intervals and implementation boundaries.
- Numeric `<`, `>`, `<=`, and `>=` support `int` and `float` operands and return
  `bool`. Both operands are evaluated once, left to right. Boolean operands are
  type errors; Cloth does not order `false` and `true` as numbers.
- `instanceof` is not Cloth's type-check syntax.
- A successful type check narrows the binding's type inside its `if` branch.
  In `if (entity is Player) { ... }`, `entity` is treated as `Player` without an
  explicit cast, subject to ordinary member visibility.

## Conditional expressions and statements

For repeated strict-bool testing, see [implemented while loops](while-loops.md).

- On 2026-11-03 the user confirmed that each `if`, `else if` and `else` body
  may omit braces around one expression or statement:

  ```cloth
  fn<bool> matches(int x) {
      if (x == 1) return true
      else if (x == 2) return true
      else return false
  }
  def label = if (matches(1)) "match" else "other"
  ```

  Braced and unbraced bodies can mix in one chain. Newlines and comments may
  separate a header from its body or precede `else`. A branch still has its
  own lexical scope; it contains exactly one source statement when unbraced.
  A broadcast definition can expand into several AST binding nodes without
  changing that source-statement boundary. Empty bodies require braces.
  Returns keep the enclosing function's strict return contract; omitting
  braces does not permit mixed return types.
- Nested unbraced syntax attaches `else` to the nearest unfinished `if`, also
  through single-statement loop bodies. Use braces to select an outer owner.
  The existing semicolon placement rule remains: a semicolon ends the chain
  and cannot intervene before `else`. A bare `return` immediately before
  `else` ends that branch without a value. Expression bodies keep the existing
  branch-value and exhaustive-chain checks described below.
- The user selected `if` / `else if` / `else` conditionals as the next feature
  after string equality and concatenation.
- An `if` chain can produce a value used as a binding initializer. The branch
  expressions in the following example yield their values implicitly, without
  an explicit `return`:

  ```cloth
  def<string> isUnsigned = if (a is unsigned int) { "is unsigned" } else if (a > 25) { "is above 25" } else { ":(" }
  ```

- An `if` can also stand alone as a statement. In that context it has no implied
  return. Branch value production in an initializer does not imply returning
  from the enclosing function or method.
- Exhaustiveness requirements for value-producing conditionals, branch result
  type rules, and value selection in branches containing multiple statements
  have not been separately confirmed. See [development status](../../cloth-language/references/development.md)
  for the initial implementation's final-`else`, matching-type, final-expression
  rules and the remaining design questions.

## Do-while loops (execution and proven cleanup implemented)

On 2026-11-03 the user approved `do body while (condition)`, with braced or
single-statement bodies and guaranteed first body entry. The
[do-while reference](do-while-loops.md) owns the accepted syntax, semicolon
boundaries, strict bool conditions, scopes, jumps and required flow/lifetimes.
Stages 0-5 integrate syntax, scopes, strict conditions, jump resolution,
first-entry completion/nullable/metadata joins, execution and proven cleanup.
The [completed plan](../../../../todo/do-while-loops-plan.md) and
[Stage 5 record](do-while-loops.md#stage-5-integration-portability-and-publication-2026-11-03)
link the maintained example, full-suite evidence and native/IR target limits.

## For loops and ranges (design confirmed; implementation deferred)

The integrated range/foreach contract below is extended by
[break/continue and reusable loop control](loop-control.md), approved on
2026-11-02. That record owns jump behavior, header placement and future reuse.

On 2026-11-02 the user confirmed `<-` between the loop binding and its
range or collection for both stepped loops and foreach loops:

```cloth
for (def<int> i <- 0..100; i++) println(i)
for (def<int> i <- 0..=100; i += 2) println(i)
for (def<int> i <- 5..100) println(i)
for (def<float> x <- 0.0..=1.0; x += 0.1) println(x)
for (def<int[]> row <- matrix) {
    for (def<int> value <- row) println(value)
}
```

- `..` excludes the ending endpoint; `..=` includes it, as already illustrated
  in `examples/Test.co`.
- A range loop without an explicit stepper increments by one.
- Descending ranges are supported with an explicit decreasing stepper:
  `for (def<int> i <- 10..0; i--) println(i)` visits 10 through 1, and
  `10..=0; i--` includes 0. Descending end tests use ordinary `>` / `>=`;
  ascending tests use `<` / `<=`. Without a stepper the default remains `+1`,
  so `10..0` is empty rather than automatically switching to subtraction.
- The saved bounds remain fixed while step direction may change freely. A
  counter may revisit earlier in-range values without a wrong-direction error.
  End traversal when the next range test finds the counter outside either bound
  or at an excluded ending endpoint. The starting endpoint is included:

  | Saved endpoints | `..` membership | `..=` membership |
  | --- | --- | --- |
  | `start < end` | `start <= counter && counter < end` | `start <= counter && counter <= end` |
  | `start > end`, explicit stepper | `end < counter && counter <= start` | `end <= counter && counter <= start` |

  The default-step reversed-range rule remains unchanged. Inclusion of an ending value
  does not force immediate termination: an explicit stepper may move back into
  the range. Existing non-finite-value and unchanged-update errors still apply;
  revisiting different values or oscillating is not a general termination error.
  This supersedes the proposed monotonic-direction requirement.
- Equal endpoints form an empty exclusive range and a singleton inclusive range:
  `5..5` executes no body, while `5..=5` contains only 5. The inclusive case uses
  the ordinary body/update/test schedule: do not skip an explicit stepper merely
  because the range is a singleton. An update leaving the singleton exits;
  an unchanged update that would run another iteration raises the confirmed
  runtime error. This applies equally to integer and float ranges using their
  ordinary comparisons, including numerically equal signed zeros.
- On 2026-11-02 the user confirmed floating-point ranges as well as integer
  ranges. The default step of one also applies to float counters.
- The user subsequently confirmed ordinary floating-point comparisons and
  rounding, with no endpoint snapping or tolerance. Each update follows existing
  arithmetic and storage rounding; ascending exclusive/inclusive end tests are
  `<` / `<=`, with `>` / `>=` for descending traversal. Inclusion permits an equal produced value but
  does not guarantee that repeated floating-point updates reach the endpoint.
- The user confirmed finite-only float ranges: NaN/infinite endpoints are invalid
  before the first range comparison, including an otherwise empty range. An
  executed stepper supplying NaN/infinity or producing a non-finite counter is
  invalid; a skipped stepper is not executed solely for validation. A finite
  overshoot ends traversal normally. Known-invalid values receive a compile-time
  diagnostic; dynamic invalid values receive a located runtime failure, stopping
  at the first error. This does not change ordinary floating arithmetic elsewhere.
- The user confirmed a located runtime error at the stepper when an executed
  update leaves the counter unchanged and another iteration would otherwise run.
  This applies to integer and float counters, explicit steppers and the default
  `+1`, including zero steps and float increments lost to rounding. Do not
  silently end the loop, increase the step or snap to another value. For an
  implicit step, label the range header. This is an unchanged-update guard, not
  a general proof of termination or a detector for arbitrary multi-step cycles.
- Range endpoints must match the counter's exact type under ordinary binding
  rules. An already-typed value is not implicitly converted across width,
  signedness, integer/float or usize/isize identity. Literal context follows
  existing rules; accepting a literal is not an implicit conversion of a typed
  function result.
- This implementation admits non-nullable numeric range counters: signed and
  unsigned `int` at all existing widths, distinct `usize`/`isize`, and
  `float<16>`, `float<32>`/`float`, `float<64>`. Reject `bool`, `char` and nullable
  counter types. Nullable endpoint expressions must supply the required
  non-nullable numeric value through ordinary presence-proof/fallback rules.
  This does not restrict foreach to numeric elements: arrays may contain any
  element type already supported by the compiler. Character advancement is
  outside this implementation's range contract.
- Both loop forms permit omitted type annotations. Infer one fixed compile-time
  type from range endpoints or the array element type, using ordinary literal
  defaults/context. Already-typed endpoints must agree exactly; do not infer a
  widest numeric type or convert typed values to make them agree. Body statements
  and the stepper neither determine nor change the inferred type. Explicit
  annotations remain available, for example `def<int> i` alongside `def i`.
- Each loop binding is local to the loop. Resolve range endpoints or the foreach
  source in the surrounding scope before introducing the new variable. The new
  binding is visible in the body and header stepper, not after the loop; a
  shadowed outer binding remains available afterward. Apply ordinary shadowing,
  duplicate-name and body-block scope rules. Body-block locals do not become
  visible in the header stepper. This does not roll back writes to outer bindings.
- An unbraced loop body may be any one supported source statement, including an
  assignment, local `def`, `return`, conditional or nested loop. Give it an
  implicit body scope so local names do not escape or enter the header stepper's
  scope. Multiple statements require braces. This does not relax existing `if`
  brace requirements or change which `else` belongs to a conditional.
- The user confirmed endpoint expressions including function calls such as
  `func1()..func2()`: evaluate, validate and store the starting endpoint first,
  then evaluate, validate and store the ending endpoint, once per loop entry
  before the first comparison. A failure or early return stops that sequence.
  Iterations use the saved bounds; changes in the body do not reevaluate the
  expressions or change those stored bounds. Entering the loop again evaluates
  the endpoints again. Each endpoint supplies one value of the required type.
- Runtime-computed step amounts, including variables and function calls, are
  allowed. Execute the explicit stepper once after each normally completed body,
  then perform the next range test. Its expression is evaluated anew on every
  executed update, not saved on entry like the endpoints. An empty loop or a body
  that returns does not execute the stepper. Preserve existing update type checks
  and the confirmed non-finite-value and unchanged-counter checks.
- A range stepper is one existing assignment/update targeting its declared
  counter: `i++`, `i--`, `i += e`, `i -= e`, `i *= e`, `i /= e`, `i %= e`, or
  `i = e`, including function calls inside `e`. Preserve ordinary assignment
  types and existing division/remainder failures. Exclude declarations, multiple
  updates, other-target updates and standalone calls from the stepper clause.
  The counter-update overflow exit applies to the arithmetic update operator
  itself; arithmetic inside an assignment RHS retains ordinary wrapping. Thus
  `i += 1` can exit on update overflow while `i = i + 1` computes its RHS with
  ordinary wrapping before assignment. A plain assignment has no separate
  arithmetic operation on which to apply the overflow exit. Existing integer
  division/remainder invalid-operation checks still fail normally.
- The loop body may assign/update its range counter under ordinary type and
  assignment rules. Finish the body normally, then execute the header stepper on
  the counter's current value and perform the fixed-bound membership test. An
  out-of-range body assignment does not interrupt the body or bypass the stepper;
  it may be brought back inside by subsequent body work or the stepper. A body
  return still bypasses the stepper. Saved endpoints remain unchanged.
- Integer overflow in the header's counter update ends traversal normally after
  executing any stepper-expression effects once. Detect overflow without storing
  a wrapped counter, including for default `+1`; do not turn it into an error or
  apply this special rule to arithmetic inside the step expression or body, which
  retains ordinary wrapping. An explicit stepper still executes after an included
  endpoint because it may move inward instead. Preserve existing evaluation order
  and failure/return behavior; an update that does not complete cannot continue
  into the overflow exit. Float non-finite updates retain their separate errors.
- An explicit stepper requires a separating semicolon inside the loop header:
  `for (def<int> i <- 5..100; i += 2) println(i)`. This separator is mandatory
  even though ordinary statement terminators are optional. With no stepper,
  omit the entire `; stepper` clause. Requiring every range loop to spell out
  a stepper was discussed but has not replaced the confirmed default of one.
- The user retained the complete-range form after considering
  `def<int> i = 5 : ..100`. A nonzero start is written directly in the range,
  such as `for (def<int> i <- 5..100; i++) println(i)`; no separate initializer
  is required. The range supplies the counter's starting value from its starting
  endpoint. This loop syntax does not authorize uninitialized ordinary bindings.
- `<-` replaces the earlier colon spelling for both loop forms. The accepted
  headers also replace the earlier `= range` proposal and the older
  initialization-plus-range header. Maintained examples are in
  [Test.co](../../../../examples/Test.co) and
  [Arr.co](../../../../examples/Arr.co).
- Array foreach evaluates its source once per loop entry and traverses an
  independent snapshot of its contents and shape. Writes/reassignment to the
  original source during the body do not change later values visited. The
  compiler may avoid physical copies only when it proves the same observable
  value independence and lifetime behavior. Re-entering the loop captures anew.
  Stage 4 implements this traversal for currently supported array elements.
- Foreach variables are ordinary mutable value bindings, initialized anew from
  each element. Rebinding a scalar/row variable or modifying an array-valued row
  affects neither the traversal snapshot nor the original source. Preserve
  recursive array-value independence and ordinary type/shape rules. The next
  iteration initializes the variable from the next element; changed values from
  the prior iteration are not carried forward. This does not add class-object
  deep-copy semantics or an immutable loop-header spelling.
- Header steppers are exclusive to numeric range loops. Reject a foreach header
  such as `for (def value <- values; value++)`; array foreach automatically visits
  each snapshot element. Changes to the local element variable belong in the
  body and cannot control traversal. This range design does not establish default
  initialization.
- Stage 1 parses syntax into a located AST. Stage 2 resolves scopes, exact
  receiving/source types and repeated completion/presence/metadata flow. Stage 3
  executes integer/float ranges with saved bounds, all accepted updates, ordinary
  rounding, overflow exits, finite/progress guards and real return ownership.
  Stage 4 adds independent foreach snapshots/elements and proven arena/stack
  region cleanup. C3068 reports invalid loop contracts or known nonfinite values;
  C3067 is retired and reserved. Buffered/captured writes and unproven allocations
  keep frame lifetime; no universal bounded-memory guarantee is implied. Read the
  [stage scope and evidence](for-implementation.md).

The [implementation plan](../../../../todo/for-loops-plan.md) records the confirmed
contract, source ownership, implementation stages and verification. Stages 0-5
integrate the agreed executable slice and its maintained examples/portability
coverage; the stage record retains exact verification and support limits. Future
extensions are separate from the confirmed rules above.
