# Confirmed Cloth semantics

These decisions were confirmed directly by the user during the language design
discussion from 2026-10-29 through 2026-10-31. `../../../../examples/Test.co` remains the main syntax example; these rules
clarify it and extend it where noted. This document describes intended behavior,
not the compiler's current feature coverage.

## Portability

- Windows, Linux and macOS are all required targets. On 2026-10-30 the user
  reaffirmed that implementation choices must support all three without
  compromising behavior on the others.
- Portable Cloth features must expose a shared language contract across these
  systems. Platform-specific startup, ABI and encoding adapters are implementation
  details used to fulfill that contract, not a reason to make an entire feature
  depend on one operating system's mechanism.
- `main(string[] args)` keeps the same Cloth interface across all three targets.
  A Windows Unicode startup adapter must coexist with the Linux/macOS startup
  paths; `wmain` is not a proposed universal native entry point.
- On 2026-10-31 the user confirmed Unicode-preserving command-line arguments
  on Windows, Linux and macOS. Native arguments become well-formed UTF-8 Cloth
  strings without replacing characters or normalizing their Unicode sequence.
  Windows receives UTF-16 through a wide-character startup adapter; Linux and
  macOS validate native argument bytes as UTF-8.
- Validate user arguments in order before entering Cloth's `main`. The first
  invalid argument produces a controlled startup error and a nonzero exit
  status; do not continue to later arguments or execute user code. This also
  applies when Cloth's `main()` has no argument parameter. Preserve empty
  arguments, argument order and exclusion of the executable name.

## Concise syntax and bodies

- Cloth aims to remove unnecessary ceremony. Use the compact forms established
  by the language design; do not extrapolate unrelated syntax changes from this
  preference alone.
- A transition to a `state<void>` destination needs no empty argument list:
  `player -> Alive`. Parentheses are not required for that transition. Whether
  an explicit empty argument list is also accepted has not been specified.
- A single-line `mt` or `fn` body may use `=` instead of a brace-delimited body.
  For a non-void function, the expression's value is returned implicitly, for
  example `fn<int> add(int a, int b) = a + b`. A void function's expression
  must yield void; `expression -> void` explicitly discards a produced value.
- An empty class body may be omitted entirely; the declaration can end after its
  header without an empty brace pair.

## Function return contracts

- Omitting the return-type annotation on `fn` declares a `void` function;
  it does not infer a return type from the body. Explicit `<void>` is also
  accepted. For example, `fn assumedReturnType() { ... }` has the same return
  contract as `fn<void> assumedReturnType() { ... }`.
- Returning a value from a function whose return type is omitted or explicitly
  `void` is a compile-time error, even if the value's type is otherwise known.
- A bare `return` is allowed to exit a `void` function early, whether its
  return-type annotation is omitted or explicitly `<void>`.
- A void function may also return a void expression, such as `return cleanup()`
  when `cleanup` returns void, or `return calculate() -> void`. These evaluate
  the expression and exit the enclosing function without returning a value.
- Brace-delimited function bodies require an explicit `return` to return a
  value; a final expression does not implicitly return from the function.
  Implicit function returns are reserved for `= expression` bodies.
- A non-void function that can reach the end of its body without returning a
  value is a compile-time error.
- Returned values follow the same strict type rules as binding assignments,
  including contextual integer literals. An already-typed value must match the
  declared return type; type changes require an explicit conversion. This
  applies to both explicit `return` and implicit returns from `= expression`
  bodies. For example, `fn<int<64>> ten() = 10` is valid, while returning an
  `int` binding from an `int<64>` function requires an explicit cast.
- A `void` function's `=` body must yield void. A produced value can be
  explicitly discarded with `expression -> void`, for example
  `fn work() = calculate() -> void`. The cast evaluates its operand once,
  preserving its side effects, discards the result, and yields void. An
  expression that already yields void also satisfies the contract. A
  value-producing expression without an explicit discard does not satisfy
  the void return contract. This applies to omitted and explicit `<void>`
  return annotations. The user's explicit discard spelling is `-> void`.
- Inside a brace body, a standalone function-call statement may ignore a
  produced result without a cast. Explicit discarding with `-> void` is also
  allowed. Neither form returns the discarded value from the enclosing
  function; an `=` body's expression must still satisfy its return contract.
- These function-contract decisions were confirmed by the user on 2026-10-30;
  they describe intended semantics, not implemented function support.

## Function blocks

- A typed binding may name a deferred, parameterless function block:
  `def<int> x = { ... }`. The explicit binding type declares the
  block's return contract; inferred function-block return types are forbidden.
- Declaring the block does not execute its body. Every evaluated occurrence of
  its name executes the body again and produces the declared result type.
  There is no first-use cache. For example, `consume(x)` executes the block
  during argument evaluation and passes its result, not the block itself.
- Existing evaluation order and short-circuit rules apply. `x + x` executes the
  block twice, left to right. An occurrence skipped by short-circuit evaluation
  does not execute. Call arguments still evaluate before the callee's body runs.
- Blocks take no arguments and follow ordinary brace-delimited function return
  rules: explicit value returns, strict return typing, and no non-void path
  reaching the end without returning. A `return` exits the block itself, not
  the enclosing function or method.
- An ordinary binding initialized from a block's result stores that value:
  `def<int> saved := x` executes `x` once at that declaration; later reads of
  `saved` use the stored result.
- The accepted example reads a surrounding local binding's current value on
  each execution: changing that local between uses changes the next result.
  Escaping capture lifetimes and assignment to the block binding remain open
  design questions; do not infer a general closure or block-transfer mechanism.
- The user confirmed execution on every use on 2026-10-30. Local function blocks
  are implemented; see [development status](development.md) for coverage and
  current restrictions.

## Program entry and executable statements

- Executable statements are allowed only inside bodies, not directly at file
  scope. File-level declarations remain distinct from executable statements.
- Executable programs start in `main`. Libraries do not require a `main`.
- `main` may take no parameters or one `string[] args` parameter. Either
  parameter form may return `void` or `int` at any supported integer width:
  `int<8>`, `int<16>`, `int<32>`, or `int<64>`. Plain `int` retains its default
  32-bit width, and an omitted return annotation means `void` as usual.
  For example, `fn main()`, `fn<void> main(string[] args)`,
  `fn<int> main()`, and `fn<int<64>> main(string[] args)` are valid headers.
- Each executable must have exactly one entry-point `main`. Defining both
  `main()` and `main(string[] args)` is a compile-time error; startup does not
  select between entry-point overloads.
- An integer returned by `main` becomes the program's process exit status.
  Normal completion of a void `main`, including a bare `return`, produces
  process exit status `0`.
- At the native exit-code boundary, signed results narrower than the target's
  native exit-code integer are sign-extended; wider results retain the low bits,
  following Cloth's explicit integer-cast rules. In particular, an `int<64>`
  result outside the native exit-code range is truncated rather than producing
  a runtime error. The user confirmed low-bit truncation on 2026-10-30.
- These rules were confirmed by the user on 2026-10-30. File-level executable
  snippets now exist only in internal test helpers, not the source-file grammar.
  See [development status](development.md) for native entry support and pending
  argument-array construction.

## Compiler data access (phase 3 implemented for supported types)

- Chunks 3.1 through 3.4 implement the confirmed contracts below for the
  currently supported primitive/array types and built-in `typeinfo`. Class/state
  execution, `addr` and array/class text conversion remain deferred. The early
  planning notes below are historical; subsequent confirmed decisions govern.

- On 2026-10-31 the user deferred array length/count access until a broader
  type-specific facility can be designed. Its working name is **compiler data
  access**, intended to expose compiler-provided data for types beyond arrays.
- The proposed receiver-based form is `value::name`: with `def a = [1, 2]`,
  `a::size` would produce 2, without parentheses. This records the design
  direction, not implemented support or a complete syntax/typing contract.
- Result types, multidimensional meaning and the relationship to the proposed
  soft/hard system-call categories remain unresolved. The earlier `.length`
  recommendation and its suggested `int` result were not adopted.
- On 2026-10-31 the user confirmed that providers own their contextual ("loose
  keyword") spellings, valid in the applicable receiver context rather than
  globally reserved. The user accepted an implementation direction using
  `ValueType`, typed provider results and emission methods owned by those results;
  coupling that layer to LLVM is acceptable. The discussed accessor examples
  remain sketches; their size types, size meanings and formatting rules are
  proposals except where subsequently confirmed below.
- The user subsequently approved a phased implementation of behavior-bearing
  Kotlin type descriptors, retaining their `CObject`, `CInt`, `CFloat`, `CChar`,
  `CString` and `CArray` names and adding `CBool`. These are the compiler's actual
  types, shared by semantic analysis and LLVM emission, rather than a parallel
  wrapper hierarchy. This authorizes internal refactoring; it does not settle
  source accessor meanings, introduce primitive boxing or change runtime storage.
- The user authorized phase 2's typed provider results and provider-owned LLVM
  emission. These internal operations may now be implemented and tested without
  introducing source accessor syntax; the deferred result-type, size, formatting
  and dispatch questions above remain open.
- On 2026-10-31 the user confirmed support for both type receivers and value
  receivers in compiler data access. Each provider declares which receiver forms
  it supports; supporting one form does not automatically enable the other. The
  receiver-form assignments for individual providers remain to be settled.
  A type receiver does not require constructing or evaluating an
  instance; value receivers retain the agreed once-only evaluation behavior.
- The user confirmed strictly lowercase provider spellings on 2026-10-31.
  Names such as `max` and `size` are case-sensitive; uppercase variants are not
  aliases. The user updated the example accordingly.
- Integer `min` and `max` support both type and value receivers. Each returns
  the receiver's exact integer type, preserving width and signedness, and yields
  that type's minimum or maximum representable value. For example, signed
  `int<8>` has a maximum of 127, while unsigned `int<8>` has a maximum of 255.
  This source contract was confirmed by the user on 2026-10-31.
- Floating `min` and `max` yield the most negative finite value and largest
  positive finite value, respectively. They support both type and value
  receivers and return the receiver's exact floating-point type, preserving
  its width. The user confirmed this on 2026-10-31. Floating `min` does not mean
  the smallest positive normal or subnormal value.
- Scalar `size` for integers, floats, booleans and characters means storage
  size in bytes. It supports both type and value receivers and returns
  `unsigned int<64>`. The user confirmed this on 2026-10-31. For example,
  `int::size` is 4, `float<16>::size` is 2, `float<64>::size` is 8,
  `bool::size` is 1 and `char::size` is 4. This uses the scalar's storage
  representation, so a boolean's one-bit LLVM value still reports one byte.
- String `size` counts Unicode scalar values, not UTF-8 bytes. For example,
  `"é😀"` has size 2; a base character followed by a combining mark counts as two
  scalars. The user confirmed this on 2026-10-31. The current UTF-8 descriptor's
  byte length is not the source-level scalar count.
- String `size` supports value receivers only, returns `unsigned int<64>` and
  yields 0 for an empty string. A type receiver such as `string::size` is a
  compile-time error because no string contents are supplied. The user
  confirmed this on 2026-10-31.
- `str` supports value receivers only and returns `string`, using the same
  formatting as interpolation. It applies to integers, floats, booleans,
  characters and strings; string receivers preserve their contents. Type
  receivers are rejected. Array and class formatting remain separately deferred.
  The user confirmed this on 2026-10-31. This does not settle the relationship
  to the separately proposed `toString()` method or future class overrides.
- `type` returns a dedicated Cloth type-metadata value, preserving structural
  information such as numeric width, signedness and array element type rather
  than reducing the result to a type-name string. The user confirmed this on
  2026-10-31. Its confirmed inspection operations are recorded below; native
  representation is an implementation choice. This describes a Cloth value,
  not exposure of Kotlin descriptor instances to compiled code.
- `value::type` reports the expression's static, compile-time type, not its
  concrete runtime type. The user confirmed this on 2026-10-31. For a binding
  declared as a base class that holds a derived-class object, the result
  describes the base type.
- `type` supports both type and value receivers. A type receiver produces
  metadata describing that type without constructing an instance; a value
  receiver produces metadata describing its expression's compile-time type.
  The user confirmed this on 2026-10-31.
- Type-metadata values support `==` and `!=` using exact represented-type
  equality, independent of where a metadata value was produced. For example,
  `int::type == int<32>::type` is true, while comparisons to `int<64>::type`
  or `unsigned int::type` are false. The user confirmed this on 2026-10-31.
- Array type-metadata equality includes statically known extents at every
  dimension. Unspecified extents are not wildcards: `int[]::type` differs from
  `int[3]::type`, while two `int[]::type` values compare equal. Runtime lengths
  do not change static metadata. The user confirmed this on 2026-10-31.
- The built-in `typeinfo` value type can describe any Cloth type. Metadata
  describing different types has the same value type, `typeinfo`, so a mutable
  `typeinfo` binding can be reassigned from `int::type` to `float<64>::type`
  under ordinary binding rules. The user confirmed this on 2026-10-31.
- `typeinfo` value receivers support `str`, returning a normalized Cloth type
  spelling with numeric widths always explicit, including default widths.
  Interpolation of `typeinfo` uses the same text. Thus `int::type::str` and
  `int<32>::type::str` both yield `"int<32>"`, and an array of default-width
  integers includes that width in its spelling. The user confirmed this on
  2026-10-31, overriding the proposed omission of default widths. Other
  diagnostic/source type spellings are not changed by this decision.
- A `typeinfo` value describing an array exposes `element`, returning the
  immediate element's `typeinfo` and removing exactly one array dimension.
  Thus `int[3]::type::element` describes `int<32>`, while
  `int[2][3]::type::element` describes `int<32>[3]`. Repeated access follows
  the remaining array structure. The user confirmed this on 2026-10-31.
- Reject `element` on non-array metadata at compile time when the represented
  type is known. Otherwise, accessing `element` on non-array metadata produces
  a controlled runtime error. The user confirmed this on 2026-10-31.
- Every `typeinfo` value exposes `isarray`, returning `bool`: true when the
  represented type is an array, false for every other represented type. This
  permits guarding array-specific metadata operations such as `element`.
  The user confirmed this on 2026-10-31.
- Every `typeinfo` value exposes `rank`, returning the represented array's
  dimension count as `unsigned int<64>`, or 0 for a non-array type. Unspecified
  extents still count as dimensions: `int[]` has rank 1 and `int[2][3]` has
  rank 2. The user confirmed this on 2026-10-31.
- Every `typeinfo` value exposes `hasextent`, returning `bool`. It is true for
  arrays with a statically known outer extent, including 0, and false for
  arrays with an unspecified outer extent and all non-array types. Runtime
  lengths do not affect this static metadata query. The user confirmed this
  on 2026-10-31.
- On array metadata with a statically known outer extent, `extent` returns
  that count as `unsigned int<64>`, including 0. Reject `extent` on a non-array
  or an array with an unspecified outer extent at compile time when known;
  otherwise produce a controlled runtime error. Never substitute a runtime
  array length for the static extent. The user confirmed this on 2026-10-31.
- `addr` is deferred and must remain unavailable in phase 3. Its address meaning,
  receiver forms, result type and lifetime contract require a separate design.
  The user confirmed this scope decision on 2026-10-31.
- On metadata describing an integer or floating-point type, `width` returns
  the bit width as `unsigned int<64>`. Reject access for other represented
  types at compile time when known; otherwise produce a controlled runtime
  error. The user confirmed this on 2026-10-31. This query reports numeric
  format width, not storage bytes or array dimensions.
- Every `typeinfo` value exposes `isunsigned` as `bool`. It is true only for
  unsigned integer types, and false for signed integers and every non-integer
  represented type. The user confirmed this on 2026-10-31.
- Every `typeinfo` value exposes `isnumeric` as `bool`, true for integer and
  floating-point represented types and false for every other type. This permits
  guarding `width` access. The user confirmed this on 2026-10-31.
- Array `size` counts elements in the outer dimension. An empty outer array
  yields 0. For `[[1, 2, 3], [4, 5, 6]]`, the outer array's `size` is 2 and
  either row's `size` is 3. The user confirmed this on 2026-10-31.
- Array `size` returns `unsigned int<64>`. On 2026-10-31 the user revised the
  earlier unsigned 32-bit choice to unsigned 64-bit. This is a fixed source
  result type, independent of machine register or pointer width.
- Reject a `size` access at compile time if its count is known to exceed the
  result type's range; otherwise, a count overflow produces a controlled runtime
  error. Never silently wrap or truncate the count. The user confirmed this
  policy alongside the unsigned 64-bit result on 2026-10-31.
- Array `size` supports value receivers and fixed-size array type receivers.
  For example, `int[5]::size` yields 5. An unsized array type receiver such as
  `int[]::size` is a compile-time error because the type provides no outer count.
  The user confirmed this on 2026-10-31.
- For array type-receiver `size`, only the outer extent must be statically
  known. Unspecified inner extents do not prevent access: `int[2][]::size`
  yields 2, while `int[][3]::size` is a compile-time error. The user confirmed
  this on 2026-10-31, clarifying which partially specified array types support
  the provider.
- All compiler data accessors are read-only: assigning to or updating an accessor
  result is a compile-time error. Copying a result into a mutable binding permits
  changing that independent stored value under its ordinary type and binding
  rules; this does not modify the receiver or the provider's data. The user
  confirmed this on 2026-10-31. This does not settle deep-copy semantics for future
  class-valued provider results.
- Parentheses distinguish state-method calls from compiler data access:
  `receiver::name` requests a provider and `receiver::name(...)` calls a state
  method. A provider and state method may share the same name without shadowing
  each other. Construction and deletion retain their established special forms.
  The user confirmed this on 2026-10-31.
- Accessors bind tightly like indexing and chain left to right. Any
  value-producing expression can be a value receiver, subject to the provider's
  supported types and receiver forms. Each step resolves its provider using the
  previous result's type, preserving evaluation order and once-only receiver
  evaluation. The user confirmed this on 2026-10-31; examples include
  `makeArray()::size`, `matrix[0]::size`, `(a + b)::max` and `values::size::max`.
- Track the deferred design and its open questions in
  [compiler data access](../../../../todo/compiler-data-access.md).

## Function parameters

Array syntax and construction were confirmed on 2026-10-30:

- `def<int[]> unsized = [1, 2, 3]` infers a size of 3.
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
- On 2026-10-31 the user confirmed that `[]` infers and preserves the initial
  shape of an array-valued binding, whether initialized from a literal, a
  function result or another array value. Every dimension remains fixed for
  that binding; whole-array replacement is permitted only with the same shape.
  Known mismatches fail at compile time. Dimensions unknown to the compiler
  are captured at initialization and checked on assignment at runtime.
- `[]` in a function parameter or return signature leaves that dimension
  unspecified across calls. Each array parameter binding preserves the shape
  it receives for that invocation. Separate calls may receive or return
  different shapes; storing a result fixes the receiving binding's shape.
- A function-block declaration such as `def<int[]> compute = { ... }` names
  a computation rather than an initialized array value. Its results may have
  different lengths on different reads. `def a = compute` evaluates it once
  and preserves the shape of that stored result, without caching future reads.
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

- Parameter bindings are mutable inside the function by default. Reassignment,
  compound assignment, and standalone updates are permitted subject to the
  ordinary type and operator rules.
- The `final` keyword makes a parameter binding immutable. Attempts to reassign
  or update that binding are compile-time errors. As with other immutable
  bindings, this does not freeze the bound object's fields.
- `final` appears before the parameter type, for example `final int value`
  or `final unsigned int<64> value`.
- Reassigning a parameter changes only the function's local parameter binding;
  it does not reassign the caller's binding. For example, passing an `int`
  binding to a function that assigns `20` to its parameter leaves the caller's
  binding unchanged. This rule concerns rebinding, not object field mutation.
- Call arguments follow the same strict type rules as binding assignments and
  returns. Each parameter's declared type supplies context for integer literals;
  already-typed values must match that type, with explicit conversions required
  for type changes. For an `int<64>` parameter, `10` is valid but an `int`
  binding requires an explicit cast to `int<64>`.
- Call arguments evaluate exactly once, from left to right, before the called
  function's body runs. In `combine(first(), second())`, `first()` finishes
  before `second()` starts, and both finish before `combine` begins its body.
- These parameter rules were confirmed by the user on 2026-10-30.

## Function scopes

- Parameters and declarations directly inside the function body share one
  scope. A local declaration in that scope cannot reuse a parameter's name;
  it is a compile-time duplicate-name error.
- Nested branches have their own scopes and may shadow parameters or enclosing
  local bindings.
- Functions and local/parameter bindings use one namespace. A nearer binding
  shadows a file-level function of the same name, including in call position;
  `name()` does not bypass that binding to find the function.
- These scope rules were confirmed by the user on 2026-10-30.

## Function recursion

- Functions support direct recursion and mutual recursion. A function may call
  itself, and functions may call each other cyclically regardless of declaration
  order. This was confirmed by the user on 2026-10-30.

## Function overloads

- Functions with the same name and ordered parameter types conflict even if
  their return types differ. Return types do not distinguish overloads.
- Different parameter types can distinguish overloads.
- An exact match takes priority over a match requiring adaptation. With
  overloads accepting `int` and `int<64>`, the literal `10` prefers its default
  `int` type and selects the `int` overload. The user confirmed this preference
  on 2026-10-30. This does not settle a general conversion-scoring system.
- The user reaffirmed the strict argument rule: already-typed arguments do not
  implicitly widen or otherwise change type to match an overload. Such type
  changes require explicit casts. Contextual integer literal typing remains
  permitted and is distinct from converting an already-typed argument.
- If there is no exact default `int` match and multiple overloads can accept an
  integer literal through contextual typing, the call is a compile-time
  ambiguity error. For example, overloads accepting `int<8>` and `int<64>`
  both accept `10`, so `choose(10)` is ambiguous; an explicit cast selects the
  intended parameter type. No preference for a smaller or wider type is implied.
- These overload rules were confirmed by the user on 2026-10-30.
- Floating arguments follow the same default-type preference: a floating literal
  prefers an exact `float` (`float<32>`) overload when available. At call sites,
  floating literals can receive a parameter's floating-width context, including
  `float<16>` and `float<64>`. Already-typed floating values still require an
  explicit cast to change width. The user confirmed contextual floating call
  literals and the default `float` preference on 2026-10-30.

## Visibility in Cloth source

- `pub` is optional. Declarations default to private when `pub` is omitted.
- Cloth has no `private` keyword. Do not introduce one into Cloth source.
- Interface methods remain callable through the interface without `pub`. The
  implementing class determines whether they are also callable directly on it,
  as described in `../../../../examples/Test.co`.
- Struct fields inherit the enclosing struct's visibility without needing `pub`.
  This is an exception to the general default-private rule.

## Type checks, state checks, and equality

- `is` supports both active-state checks, such as `player is Alive`, and runtime
  type checks, such as `entity is Player`. The target declaration determines
  which check is requested.
- `==` is reserved for equality between values. Do not use it as a type-check or
  active-state-check operator.
- Numeric `==` and `!=` support `int` and `float` operands and return `bool`.
  As with boolean equality, both operands are evaluated once, left to right.
  Boolean/numeric comparisons are type errors; numbers are not converted to
  booleans for equality. Assignment type checks remain unchanged.
- Numeric `<`, `>`, `<=`, and `>=` support `int` and `float` operands and return
  `bool`. Both operands are evaluated once, left to right. Boolean operands are
  type errors; Cloth does not order `false` and `true` as numbers.
- `instanceof` is not Cloth's type-check syntax.
- A successful type check narrows the binding's type inside its `if` branch.
  In `if (entity is Player) { ... }`, `entity` is treated as `Player` without an
  explicit cast, subject to ordinary member visibility.

## Conditional expressions and statements

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
  have not been separately confirmed. See [development status](development.md)
  for the initial implementation's final-`else`, matching-type, final-expression
  rules and the remaining design questions.

## Bindings

- Explicit primitive bindings use `def<int>`, `def<float>`, `def<bool>`, `def<char>`, or `def<string>`, with the same `=`
  and `:=` binding behavior as inferred declarations.
- Omitting the type annotation infers the binding's type from its initializer,
  for example `def x = 10` and `def y := x + 2`.
- A binding's declared or inferred type stays fixed. Reassignment must satisfy
  that type; assigning a different type does not change the binding's type.
- `=` creates a mutable binding.
- `:=` creates an immutable binding. It prevents reassignment of that binding;
  it does not freeze the object or its fields.
- Arrays follow the same binding-only rule: `:=`/`final` permit element mutation
  while prohibiting rebinding, subject to ordinary element type and shape checks.
- An immutable binding can call methods that mutate its object's fields, such as
  `player.applyDamage(...)` when that method is accessible.
- There is no separate `mut` qualifier.

## Compound assignments and update operators

- Cloth has `+=`, `-=`, `/=`, `*=`, and `%=` compound assignments, plus `++`,
  `--`, and `**`, as confirmed by the user on 2026-10-30. Their tokens are
  defined in `Operators.kt`; `Test.co` demonstrates standalone postfix `i++`.
- `//` starts a line comment and is not a division operator.
- `x ** y` is exponentiation. With two integer operands the result is an
  integer; negative integer exponents are errors. These decisions were confirmed
  directly by the user on 2026-10-30.
- `x++` and `x--` are standalone statements only. Prefix updates and use inside
  expressions are not supported by the language design; there is no old/new
  expression-result distinction. The user confirmed this on 2026-10-30.
- See development status for current support and initial exponentiation choices.

## Integer signedness and widths

- `int` supports 8, 16, 32 (default), and 64 bits, signed or unsigned. Explicit
  widths use the required angle-bracket syntax: `int<8>`, `int<16>`, `int<32>`,
  and `int<64>`. Width is a parameter of the numeric type, not a separate type
  spelling hardcoded for each size. Compiler spellings follow `Keywords.INTEGER`
  and `Keywords.FLOAT`.
- C type widths used by the compiler come from explicit ABI profiles selected
  using LLVM's target triple. Unsupported targets must be rejected instead of
  falling back to a global C data model. These profiles do not change Cloth's
  numeric widths or defaults, and do not require a Clang frontend.
- Linker/toolchain configuration is separate from C type definitions and from
  process execution. It resolves the executable, flags, runtime libraries, and
  search paths using the selected target ABI and explicit build options.
- `unsigned` decorates `int`: `def<unsigned int> a = 10` declares an unsigned
  32-bit binding. The modifier applies at every supported integer width, for
  example `unsigned int<8>` and `unsigned int<64>`; `unsigned int<32>` explicitly
  names the default width.
- `unsigned` applies only to integers. Floats retain their existing behavior;
  `unsigned float` is not a Cloth type.
- Unsigned bindings follow the ordinary mutable `=` and immutable `:=` rules.
  Copies and inferred results retain signedness and width, and binding types
  remain fixed on reassignment.
- Integer literals can take their type from a declared integer binding or a
  typed integer operand: `a = 20`, `a + 1`, and `1 < a` work with unsigned `a`.
  This does not convert an already-typed signed binding to unsigned. Without
  integer context, integer literals and their expressions default to `int`.
- Integer literals must fit their contextual type. An unsigned 32-bit literal
  ranges from `0` through `4294967295`; its 64-bit counterpart ranges from `0`
  through `18446744073709551615`. Negative unsigned literals are errors.
- Arithmetic and comparisons between signed and unsigned integer bindings
  require an explicit conversion. Do not silently reinterpret a negative signed
  operand as unsigned or automatically promote the pair to a wider signed type.
- Unsigned arithmetic wraps at the selected width: for example, adding one to
  the maximum value gives zero, and subtracting one from zero gives the maximum.
  Unsigned division, remainder, and ordering use the full nonnegative value.
- Existing mixed integer/float expression promotion also accepts unsigned
  operands, converting their nonnegative value to float before arithmetic or
  comparison. The existing float32 rounding policy still applies. This does not
  permit implicit numeric conversions in binding assignments.

## Explicit numeric casts

- `expression -> Type` explicitly converts a numeric value, as demonstrated in
  `../../../../examples/Test.co`. Targets include signed and unsigned integer types and float types,
  with their width syntax: `value -> int<64>`, `value -> unsigned int`, and
  `value -> float<64>`.
- A cast applies to the preceding arithmetic expression: `1 + 2 -> float` means
  `(1 + 2) -> float`. Casts bind below arithmetic and above comparisons. Chains
  convert left to right: `x -> float<64> -> int<64>`. Parentheses select a
  different grouping, including widening an operand before arithmetic.
- Evaluate the source once in its own type, then convert the result. A cast
  does not change an existing binding's type or retroactively widen arithmetic
  inside its operand. Widen first to avoid overflow or precision loss in that
  arithmetic, following the `../../../../examples/Test.co` example.
- Integer-to-integer casts wrap/truncate to the target width. Narrowing retains
  the low bits; widening extends according to the source signedness. The target
  signedness determines how the resulting bits are interpreted. For example,
  `-1 -> unsigned int` is `4294967295`, while converting an unsigned 32-bit
  maximum to `int<64>` preserves its positive value.
- Float-to-integer casts truncate the fractional part toward zero. NaN,
  infinities, and values whose truncated result is outside the target range
  raise an error. The range applies after truncation: `-0.75 -> unsigned int`
  yields zero, while `-1.0 -> unsigned int` is invalid.
- Integer-to-float and float-width casts convert numeric values with floating
  point rounding. Widening an already-rounded float cannot recover lost bits.
- `float` supports 16, 32 (default), and 64 bits, written `float<16>`,
  `float<32>`, and `float<64>`. Two floating operands use the wider of their
  formats: two `float<16>` operands keep a `float<16>` result, rounded to half
  precision. Mixing a floating operand with an integer uses at least float32;
  a float64 operand promotes the expression to float64. This promotion also
  applies to numeric comparisons and does not permit implicit binding conversions.
- Cast results participate in inference, binding assignment, arithmetic,
  comparisons, and ordinary boolean expressions. Binding assignment still
  requires the resulting type to match; a cast does not permit unrelated
  implicit conversions or immutable reassignment.
- Booleans and numbers remain separate, including for explicit casts.
  `true -> int` and `1 -> bool` are errors. Casting a boolean to `bool` preserves
  its value without introducing numeric truthiness.

## Booleans and storage

- `bool` stores the boolean values `true` and `false`.
- Boolean bindings support both explicit `def<bool>` annotations and inference
  from boolean initializers, following the ordinary `=` and `:=` binding rules.
- Prefix `!` accepts a `bool` and returns its logical negation as a `bool`:
  `!true` is `false`, and `!false` is `true`. Numeric operands are errors;
  there is no implicit numeric truthiness. Repeated negation such as `!!alive`
  and parenthesized operands are permitted. Negation itself does not modify its
  operand; `alive = !alive` uses ordinary reassignment.
- `&&` and `||` accept two `bool` operands and return `bool`. They evaluate
  left to right with short circuiting: `a && b` evaluates `b` only when `a` is
  `true`; `a || b` evaluates `b` only when `a` is `false`. Each evaluated operand
  runs once. Both operands must be valid and well typed even when runtime
  evaluation would skip the right operand. No numeric truthiness is introduced.
- For `bool` operands, `==` returns `true` when the values match and `!=` returns
  `true` when they differ. Both return `bool` and evaluate both operands once,
  from left to right; they do not short-circuit. No numeric conversions are
  introduced by boolean equality.
- Each `bool` uses its own byte of storage for now. This user decision supersedes
  the earlier one-bit storage goal and shared-byte packing implementation.
- Boolean values still have only two values; byte storage does not introduce
  numeric conversions or change the ordinary binding rules.
- Packed collections, field layout, atomics, and foreign interfaces have not
  been designed by this decision.

## Characters and storage

- The user selected `char` as the next storage type and confirmed that each value
  represents one full Unicode scalar stored in 32 bits, including supplementary
  characters such as `'😀'`. Surrogate code points are excluded.
- Character declarations use single-quoted literals, as in `../../../../examples/Test.co`:
  `def<char> x = 'n'`. Inferred declarations and mutable `=` / immutable `:=`
  bindings follow the ordinary binding rules.
- One scalar need not be an entire displayed grapheme: a base letter followed
  by a combining mark contains multiple scalars and cannot be one `char` literal.
- Character arithmetic, ordering, and conversions to other types have not been
  designed. See development status for the supported operations and diagnostics.

## Built-in functions

- On 2026-10-31 the user requested built-in `print` and `println` functions
  and confirmed that both are available without an import. The
  historical `use Console.IO.println` import has been removed from
  `examples/Test.co`.
- A source-level `printf` is not part of this requested design; string
  interpolation supplies composed messages. This does not reserve the name or
  prohibit a user-defined function named `printf`.
- The user approved a central `BuiltinFunctions` object whose named, typed
  fields call `register(name, factory)`. Registration creates a definition,
  adds it to the common collection and returns that same definition. Each
  function owns its argument contracts and LLVM emission behavior. Adding a
  built-in should require its implementation and one registration declaration,
  without function-name switches throughout the compiler.
- The initial implementation supports `print(value)`, `println(value)` and
  `println()`, all returning `void`. `print` writes text without adding a
  newline; `println` adds a newline, and its zero-argument form writes a blank
  line. `print()` and calls with multiple values are arity errors. Interpolation
  composes messages containing multiple values.
- Printable arguments use their value-receiver `str` provider, returning
  `string`, and keep ordinary expression typing and literal defaults. This
  is an internal parameter constraint rather than a boxed source type or a
  general implicit conversion to string. Formatting matches interpolation and
  `::str`, including canonical `typeinfo` text. Array/class value formatting
  remains deferred; metadata describing arrays is printable now.
- Ordinary lexical lookup takes precedence over built-ins. A local or parameter
  binding shadows a built-in even in call position. A file-level function group
  shadows the entire same-named built-in group; a mismatched call does not fall
  back to the built-in. Built-in names are case-sensitive ordinary identifiers.
- Arguments evaluate once before conversion and output. If argument evaluation
  returns from the enclosing function, the built-in performs no conversion,
  output or added newline on that path. Calls retain ordinary void-function
  and return contracts. Programs continue to have no implicit last-value output.
- Initial output uses length-aware UTF-8 text through buffered native stdout,
  preserving embedded NULs and treating percent signs as ordinary text. Newline
  uses the native text-stream convention (CRLF on Windows, LF on Unix). There is
  no explicit flush or atomic-write guarantee. Detected native write failures
  terminate through the controlled runtime-error path; already-written bytes
  are not rolled back. Native Windows console/code-page behavior and failures
  delayed until a buffered flush still need separate verification and design.

## Strings

- The user requested interpolated strings using `#"Hello, {name}!"`, primarily
  to make `examples/EX.co` valid. Braced expressions contribute text to a string.
  This does not change the strict string operand requirement for ordinary `+`.
  See development status for initial formatting and brace-escaping choices;
  those details have not been separately confirmed as language design decisions.
- The native type-printing/conversion contract remains under design. On
  2026-10-30 the user mentioned a possible `toString()` method and requested
  documenting missing guards while deferring that design. The user subsequently
  specified that, if introduced, `toString()` must be available on every data
  type with a non-overridable implementation, and on future classes with
  overridable behavior. This requirement does not authorize implementing it now
  or settle the general member/override model, its relationship to interpolation,
  or array formatting syntax. Track the current unsupported-array
  interpolation guard in [the formatting TODO](../../../../todo/native-formatting-guards.md).
- The user authorized the temporary unsupported-array interpolation diagnostic
  while deferring `toString()` and array text formatting. This is a compiler
  capability guard, not a permanent ban on array interpolation in the language.
- The user selected string definitions after character support. Explicit
  `def<string>` and inferred declarations such as `def hello := "Hello World!"`
  follow the ordinary fixed-type binding and `=` / `:=` rules shown in
  `../../../../examples/Test.co`.
- The user then requested string equality and concatenation. `==` and `!=`
  compare two strings by contents and return `bool`; `+` concatenates two strings
  and returns a new string value. Both operands are evaluated once, left to
  right. `+` uses ordinary addition precedence and left associativity, above
  equality. String operators do not implicitly convert numbers, booleans, or
  characters to strings. Existing copies are preserved when a binding is
  reassigned to a concatenation result.
- Current equality compares exact Unicode scalar sequences (including embedded
  NULs), implemented as length-aware UTF-8 byte comparison. It performs no case
  folding or Unicode normalization.
- Indexing, content mutation, strings escaping into future objects/containers,
  and explicit dynamic string lifetime APIs remain unresolved. See
  [development status](development.md) for internal storage and implemented
  string function boundaries; that representation does not define future
  object lifetime rules or add programmer-visible ownership syntax.

## Object lifetime and nullability

- Object lifetime is explicitly managed by the programmer. Cloth does not and
  will not have a garbage collector, as reaffirmed by the user on 2026-10-31.
- Compiler-managed temporary value buffers may be released at statically
  proven safe points, with function-lifetime storage as the conservative
  fallback. This introduces no runtime reachability analysis or reference
  counting and does not implicitly destroy class objects or run destructors.
- `Type::new(...)` creates an object. `object::delete` explicitly destroys it and
  runs its destructor. Do not assume cleanup on scope exit or automatic cleanup
  of objects the programmer has not deleted.
- Deleting an object invalidates every binding to that object. The bindings do
  not become `null`. This decision supersedes the earlier proposal to null all
  aliases, including bindings whose types lack `?`.
- Access after deletion is a compile-time error wherever the compiler can prove
  it. Otherwise it must raise a runtime error. Use-after-deletion is not undefined
  behavior, and a merely probable crash does not meet this rule.
- `?` permits intentional nullability. It does not express object lifetime or
  whether an object has been deleted.
- Pointer representation, alias tracking, and runtime checking mechanisms remain
  implementation choices. Cloth does not expose pointer or reference syntax to
  the programmer.

## States and method dispatch

- Subclasses inherit the states declared by their base class.
- Inherited states cannot be overridden. Redeclaring an inherited state name in
  a subclass is a compile-time error. Subclasses may add distinctly named states;
  selecting a subclass initial state does not redefine an inherited state.
- Each object has exactly one active state across its inheritance chain.
  Inherited and newly declared states belong to the same state machine. A base
  class and its subclass do not have independently active states.
- An object's permitted states are the states its class explicitly supports,
  including inherited states. A transition target must belong to that supported
  set; public visibility alone does not make an unrelated state a valid target.
- `object is StateName` is a boolean expression that checks whether the object
  is currently in the named state, for example `player is Alive`.
- State methods are separate from ordinary class methods. They do not fulfill
  the hosting class's interface method requirements; those must be implemented
  by ordinary class methods.
- State methods use `object::method(arguments)`, for example
  `player::attack(source)`. Ordinary class method calls use
  `object.method(arguments)`, for example `player.applyDamage(10f)` where visible.
- The state-call spelling selects the active state's implementation, not a
  particular named state's implementation. Missing implementations still raise
  the established invalid-state error.
- Different states can implement the same method signature.
- Calls to state methods select the implementation in the object's active state.
- If the active state has no matching implementation, raise an invalid-state
  error. Do not silently ignore the call or execute another state's body.
- An ordinary class method and a state method may share the same signature.
  `object.method(...)` selects the ordinary method; `object::method(...)` selects
  state dispatch. Sharing a signature does not establish overriding or fallback
  between those two kinds of methods.
- Overloading is supported when argument lists distinguish the overloads. Do not
  assume additional overload-resolution rules that have not been specified.
- As demonstrated in `../../../../examples/Test.co`, `this -> Dead(payload)` activates the destination
  state and runs its `init` before the calling method continues. A transition
  does not itself terminate the method that performed it.
- Transitioning into the already-active state replaces its payload and reruns
  its `init` block. It is a re-entry, not a no-op or an error.
- States have no `exit` block or state-exit hook.
- Outside code may transition an object using `object -> TargetState(payload)`
  when the target state is public. Transitions to non-public states must happen
  internally. Do not infer protected or module-scoped transition permissions.
- Within a running state method, `state` remains bound to the payload of the
  state invocation that started that method. A transition does not change the
  payload or its type underneath that call.
- Newly called state methods dispatch through the currently active state and
  use that state's current payload.
- Re-entering a state installs a new payload for the new activation. Calls from
  the previous activation retain their original payload.
- The compiler retains old state payload storage until calls using it finish.
  This does not require copying the payload or introduce GC. Explicitly created
  objects referenced within a payload still follow normal deletion rules.

## Top-level states

- States may be declared at the top level, separately from their host classes.
- A top-level state is reusable by unrelated classes, provided each host class
  satisfies the state's declared host contract.
- A host contract may be an interface or a class. A class contract permits that
  class and its subclasses to host the state, subject to explicit state support.
- Top-level states obey normal member visibility rules. Hosting a state grants
  it no special access to the host class's private members.
- Within a top-level state, `this` has the declared host-contract type. Extra
  members of a particular hosting class are not directly available merely
  because that class hosts the state.
- Top-level state declarations use `typedef<state<Payload>>`, followed by
  `pub StateName for HostContract` and the state body. `pub` is optional under
  Cloth's default-private visibility rule. `for` introduces the host contract.
- Nested state declarations use the `state<Payload> StateName` form in `../../../../examples/Test.co`.
  Do not substitute that nested spelling for the top-level `typedef` form.
- A class attaches top-level states using a class-level `@State(StateName, ...)`
  decorator, for example `@State(Dead, Stunned)`. Each attached state is added to
  the class's permitted states, alongside inherited and nested states.
- The class must satisfy every attached state's host contract. State-name
  conflicts are compile-time errors under the existing non-overriding rules.
- If an attached state contains a transition to another state, the hosting class
  must explicitly support that target too, either directly or through inheritance.
  Attaching `Dead` when it transitions to `Alive` is a compile-time error if the
  class does not support `Alive`. Referencing a target never attaches it implicitly.
- Attaching a public top-level state automatically permits external transitions
  into that state on the hosting object. There is no separate host-class opt-in
  for external transition permission beyond attaching the public state.
- Importing a state name into a file does not itself attach that state to a class.
  `use state StateName` is not Cloth's state-attachment syntax.

Accepted top-level declaration example, with `Player` as a class host contract:

```cloth
typedef<state<DeathInfo>>
pub Dead for Player {
    init {
        this.die()
        println(state.message)
    }
}
```

## Initial state and construction

- For an attached state, the class selects its initial state with the class-level
  decorator `@Initial(StateName, payloadExpression)`, for example
  `@Initial(Alive, { name })`. An attached `state<void>` uses `@Initial(Alive)`.
- The selected attached state must be in the class's supported state set. Its
  initial payload follows the same post-construction evaluation order as a
  nested initial state's payload.
- Every class that declares states must declare exactly one `@Initial` state.
- When constructing a subclass object, the subclass's declared `@Initial` state
  replaces the base class's initial state as the selected initial state.
- After the entire base-to-subclass constructor chain finishes, the selected
  initial state becomes active and its `init` block runs automatically.
- The initial state may require a payload; it is not restricted to `state<void>`.
- On a nested state declaration, the accepted payload syntax is
  `@Initial(payloadExpression)`. Constructor arguments provide the inputs; the
  class builds its initial-state payload using the constructed object's fields.
- Construction order is: finish the entire constructor chain, evaluate the
  selected initial payload, activate that state with its payload, then run its
  `init` block.
- A nested `state<void>` initial state can use plain `@Initial`.
- The payload expression is evaluated during object construction. This special
  syntax does not imply that arbitrary annotations accept runtime expressions.

Accepted extension to the syntax examples, assuming `SessionInfo` contains a
`name` field:

```cloth
typedef<class>
pub Player (def<string> name) {

    @Initial({ name })
    pub state<SessionInfo> Alive {
        init {
            println(state.name)
        }
    }

}

def player := Player::new("Alex")
```

`@Initial(payloadExpression)` was accepted in the discussion and has not yet been
added to `../../../../examples/Test.co`.

## Compiler diagnostics

- Cloth fails at the first detected error. Stop compilation and render that
  diagnostic; do not recover to continue compilation or accumulate/report
  multiple errors. The user explicitly confirmed this as Cloth philosophy,
  not a temporary implementation limitation. Multiple source labels may
  explain the same error.
- The user selected Yvette for compiler diagnostic rendering, with predefined
  issue definitions, stable error codes, source-line rendering, labels, and help.
  Compiler checks should pass structured values and source locations to shared
  definitions rather than assemble raw JVM exception messages.
- The first requested case was `examples/EX.co`'s string-plus-integer error, followed
  by migration of the remaining compiler failures. This does not change string
  concatenation or introduce implicit string conversions.
- The implementation and its current limits are recorded in
  [development status](development.md); native executable runtime reporting is a
  separate path from JVM compiler diagnostic rendering.
