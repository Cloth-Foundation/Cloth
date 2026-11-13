# Cloth arrays: confirmed semantics

These decisions were recorded in the shared Cloth semantics reference.
User-confirmed rules govern intended behavior; historical implementation
notes and unresolved choices retain their original qualifications. Read
the parent skill for current implementation boundaries and source/test routing.

## Growable-array revision (2026-11-07)

The user confirmed the [growable-array contract](growable-arrays.md): `T[]`
has a resizable outer length, `T[N]` stays fixed, and unannotated literals
continue to infer sized arrays. Binding-only immutability, rectangular inner
shape, independent values and foreach snapshots are retained. The contract owns
the initial mutation API and stored/temporary receiver rules.

This supersedes the historical fixed-initial-outer-shape rules for `[]` below.
[Stage 0](growable-arrays-stage-0.md) published the contract and baseline.
[Stage 1](growable-arrays-stage-1.md) implements the type distinction, separate
count/witness facts, receiver plans/effects and native whole-array replacement
with different outer counts. [Stage 2](growable-arrays-stage-2.md) adds owned
capacity, private typed storage operations and executable unsized struct fields,
including nullable-field witnesses. [Stage 3](growable-arrays-stage-3.md) executes
one-dimensional local/parameter/temporary append and reserve.
[Stage 4](growable-arrays-stage-4.md) adds class/aggregate and array-element field
growth, owner-aware publication and scoped selections through source methods.
[Stage 5](growable-arrays-stage-5.md) executes insert/removeAt/clear on those same
one-dimensional receivers with independent removed values and owned shifts.
[Stage 6](growable-arrays-stage-6.md) executes whole-row rectangular mutation,
retaining inner witnesses through remove/clear/null. Embedded rows cannot change
shape; extracted growable rows are independent. Explicit extents and inner shape
still constrain receiving values.
The [Stage 8 record](growable-arrays-stage-8.md) owns the final caller audit,
regression and portability evidence. The public source
[List](../../cloth-collections/SKILL.md) supplies independently sized array
elements without changing rectangular array semantics.

## Array construction and value semantics

Recursive array contents for print, interpolation and `::str` were confirmed on
2026-11-02. The [output contract](../../cloth-output/references/semantics.md#recursive-array-text)
owns formatting policy, evaluation/lifetime guarantees and capability boundaries.
`::addr` remains deferred.

Array syntax and construction were confirmed on 2026-10-30:

- `def<int[]> unsized = [1, 2, 3]` starts with three elements and keeps type
  `int[]`; its outer length can change under the growable-array revision.
- `def<float[5]> sized = [1.0, 2.0, 3.0, 4.0, 5.0]` supplies an explicit size.
- `def inferredWithSize = [true, true, false]` infers `bool[3]`.
- On 2026-10-30 the user confirmed order-independent array element inference.
  An explicit element type supplies context; otherwise the compiler considers
  all elements' already-typed values before resolving context-sensitive literals.
  With an `int<64>` binding `n`, `[n, 1]` and `[1, n]` both infer `int<64>[2]`.
- Already-typed element values must match exactly, including numeric width and
  signedness. There is no automatic widest-type selection or implicit numeric
  promotion between elements. Differing typed values require explicit casts,
  including differing floating widths and integer/floating combinations.
- Literal context and range checks follow the existing numeric rules; arrays
  containing only literals retain their existing defaults. These rules apply
  recursively to rectangular nested array construction. Type inference happens
  at compile time and does not alter left-to-right, once-only element evaluation.
- Explicit sizes require every member to be initialized, with no extra members:
  `def<int[2]> oversized = [1]` and
  `def<float[1]> undersized = [1.0, 2.0]` are errors.
- `def<string[]> finalArray := ["Hello", "Cloth"]` uses the ordinary immutable
  binding convention. Indexing is zero-based, following C: `finalArray[0]`
  returns the string `"Hello"`.
- Arrays must be constructable values that can be passed as function arguments,
  beyond array syntax in declarations alone. Multidimensional arrays are allowed.
- On 2026-10-30 the user chose rectangular arrays. Every row/subarray at a given
  level must have the same shape, recursively. Jagged arrays are reserved for a
  future collection type. This applies equally to literals and arrays produced
  by functions; unknown dimensions do not relax the rectangular requirement.
- For example, `[[1, 2], [3, 4]]` is rectangular, while `[[1], [2, 3]]` is an
  error. Function-produced rows with those mismatched lengths are also errors.
- Replacing a row or a higher-dimensional subarray must preserve that subarray's
  shape so element assignment cannot turn a rectangular array into a jagged one.
- Historically, on 2026-10-31 the user confirmed that `[]` infers and preserves the initial
  shape of an array-valued binding, whether initialized from a literal, a
  function result or another array value. Every dimension remains fixed for
  that binding; whole-array replacement is permitted only with the same shape.
  Known mismatches fail at compile time. Dimensions unknown to the compiler
  are captured at initialization and checked on assignment at runtime. The
  growable revision supersedes permanent capture of a standalone `[]` outer
  count; this paragraph still describes the Stage 0 compiler implementation.
- `[]` in a function parameter or return signature leaves that dimension
  unspecified across calls. Each array parameter binding preserves the shape
  it receives for that invocation. Separate calls may receive or return
  different shapes; storing a result fixes the receiving binding's shape under
  the historical implementation. The growable revision permits a `T[]` parameter
  or stored result to change outer count while preserving its type and inner shape.
- A function-block declaration such as `def<int[]> compute = { ... }` names
  a computation rather than an initialized array value. Its results may have
  different lengths on different reads. `def a = compute` evaluates it once
  and preserves the shape of that stored result under the historical rule,
  without caching future reads. Under the growable revision, a result declared
  `T[]` retains that growable type when stored; known current count does not fix it.
- On 2026-10-30 the user requested element assignment, including nested indices:
  `arr[0] = 10` and `matrix[1][2] = 10`.
- The user then confirmed independent array values when copied or passed as
  arguments. Modifying a copied binding or a function's array parameter does
  not modify the source/caller's array. This applies recursively to nested
  arrays; copying only the outer array would not satisfy the rule.
- On 2026-10-31 the user authorized eliminating redundant physical copies of
  fresh array results while preserving independent value behavior, all shape
  checks and the agreed evaluation order. Reusing an unaliased temporary's
  storage is permitted when it preserves those guarantees and storage lifetime.
  A stored array remains independent of later copies and function arguments.
- The user then authorized conservative early cleanup of temporary array
  buffers only where the compiler can statically prove it safe. Otherwise,
  keep those buffers until the owning function invocation returns. This must
  preserve surviving arrays, strings and subarrays, captures, return values,
  shape checks and once-only source evaluation in the established order.
  This optimization must introduce neither GC nor reference counting.
- To apply a function's array changes to the caller's binding, return the changed
  array and assign that result, for example `values = change(values)`. Calling
  `change(values)` alone leaves the caller's array unchanged.
- This array decision extends the earlier local-parameter-rebinding rule to
  element writes. It does not establish deep-copy semantics for class objects.
- On 2026-10-31 the user confirmed retaining binding-only immutability for
  arrays. `:=` on a local and `final` on an array parameter forbid rebinding
  that name but permit element writes, recursively through nested indices.
  Replacing a row/subarray is permitted when the existing type and shape match.
  For example, with `def a := [1, 2]`, `a[0] = 10` is allowed while
  `a = [10, 2]` is a compile-time error.
- `final int[] values` similarly permits `values[0] = 10` inside the function.
  The parameter's recursive value copy remains independent of the caller.
  Binding immutability does not make the array's type read-only or propagate
  through copying; a new binding follows its own `=` or `:=` declaration.
- See development status for current nested-literal syntax and implementation
  policies that have not been separately confirmed as language design, including
  empty arrays and bounds failures.

## Indexed compound assignment

On 2026-11-12 the user confirmed that `items[i] += rhs()` reads the element
before evaluating `rhs()`. Evaluate the target and indices once under the
ordinary indexed-write rules. Save the checked element value, evaluate the
right-hand side once, apply the operator, and store through the same selection.
The ordinary place and bounds checks apply again before the store.

If the element starts at `10` and `rhs()` writes `20` to it and returns `1`,
the compound assignment stores `11`.

Compound assignments and standalone postfix `++` and `--` accept the same
indexed targets as plain `=`, subject to their ordinary operator type rules.
For `make()[0] += 1` or `make()[0]++`, evaluate `make()` once, update the
selected element, and discard the temporary value after the statement.
Postfix updates select and read once, apply the ordinary numeric update,
and store through that selection.

[Existing indexed-place rules](growable-arrays-stage-5.md#selections-and-scratch-across-destructive-effects) ·
[Compound and postfix operators](../../cloth-bindings/references/semantics.md#compound-assignments-and-update-operators)

## Array equality

On 2026-11-12 the user confirmed that array equality compares current shape
and corresponding elements. A fixed `int[2]` and a growable `int[]` both
holding `[1, 2]` compare equal. Appending a third element to the growable
array makes them compare unequal.

Shape comparison includes every axis, even when there are no present rows.
Two known extents must match, two unknown extents agree, and a known extent
differs from an unknown one. For example, two empty `int[][]` values that
retained row widths of two and three after `clear()` compare unequal.

Array equality requires the same element type and permits differences in
declared extents. `int[] == float[]` is a type error even though scalar
`int` and `float` values can be compared.

A typed array operand supplies element context to an array literal on the
other side. For `int<8>[] values`, `values == [1]` checks `1` as `int<8>`
under the ordinary literal range rules, and `values == []` uses `int<8>`
for the empty literal. A populated dimension supplies its own length. An
empty outer dimension inherits any declared inner extents from the typed
operand. For `int[0][2] rows`, `rows == []` compares equal with row width two.
Runtime witnesses belong to each captured value. An empty literal compared
with an unsized `int[][]` peer leaves its row width unknown.

`==` uses ordinary equality for corresponding elements. A struct element
type requires an applicable `==` overload. This is checked at compile time,
including for empty arrays.
[Struct equality](../../cloth-language/references/value-operators.md#equality)

Array `!=` negates array `==`, using the same shape checks and element `==`
operations.

An array may be compared to a compatible nullable array, and two compatible
nullable arrays may be compared without a presence proof. Two absent arrays
compare equal. One absent array and one present array compare unequal. Two
present arrays use the shape and element rules above. Both operands evaluate
once from left to right. Capture each array's presence, shape and contents
when its operand evaluates. Later operand effects leave the captured value
intact. Class and interface elements retain their ordinary object identities.
A comparison with literal `null` checks presence.

For two present arrays, compare shapes before calling any element operators.
When shapes match, compare corresponding elements in index order, recursively
through nested rows. Stop at the first unequal pair.

[Stage 2 implementation and verification](array-equality-stage-2.md)

## Nullable rectangular rows

The user chose Cloth's rectangular row rules after reviewing C#'s independent,
potentially different-length rows. C# nullability marker placement is retained;
jagged row shapes are not adopted.

- Nullable rows may be absent. A null row supplies no shape witness and is
  distinct from a present empty row, whose length is zero.
- Explicit dimensions constrain present rows even before any row is present.
  Otherwise, the first present row establishes unknown inner dimensions.
  All present rows must match those dimensions recursively.
- An array containing only null rows retains unknown unspecified inner
  dimensions until a present row supplies them. Its outer length is still known.
- Replacing a row with null does not reset established shape constraints, even
  when all rows become null. A later present replacement must still match.
- Known mismatches fail at compile time; unknown mismatches fail through the
  existing first-error runtime shape checks. Independent copies, parameter
  snapshots and returned values retain the established shape constraints.

For example, `def<int[]?[]> rows = [null, [1, 2]]` fixes the row length
at two. `rows[0] = [3, 4]` and `rows[1] = null` are valid; a later
`rows[1] = [5]` is a shape error. These rules now execute under the
[nullable-row implementation contract](nullable-rows.md), including nested
nullable levels, copies, parameters, results, output and typed foreach.

## Foreach source capture

On 2026-11-02 the user confirmed source evaluation once per loop entry and
traversal of an independent snapshot of array contents and shape. Changes to the
original array during the body do not alter later visited values. Physical copies
may be avoided only when the compiler proves the same observable behavior and
preserves buffer lifetimes. Re-entering the loop captures a fresh source value.
The user also confirmed ordinary mutable value reception, initialized anew for
each element. Rebinding a loop variable or changing an array-valued row changes
neither the snapshot nor the original source. Preserve recursive independence,
ordinary type/shape rules and saved values surviving later iterations.
The [loop contract](../../cloth-control-flow/references/semantics.md#for-loops-and-ranges-design-confirmed-implementation-deferred)
owns the iteration syntax and confirmed element-binding contract. This does not
expand class-object copy semantics. Stage 4 implements this traversal for the
currently supported array element types; see the
[stage scope and evidence](../../cloth-control-flow/references/for-implementation.md#stage-4-array-foreach-and-lifetime-integration-2026-11-02).
