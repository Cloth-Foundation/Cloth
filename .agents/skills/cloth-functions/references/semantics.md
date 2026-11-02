# Cloth functions and function blocks: confirmed semantics

These decisions were recorded in the shared Cloth semantics reference.
User-confirmed rules govern intended behavior; historical implementation
notes and unresolved choices retain their original qualifications. Read
the parent skill for current implementation boundaries and source/test routing.

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
  are implemented; see [development status](../../cloth-language/references/development.md) for coverage and
  current restrictions.

## Optional parameters and named calls

Read [confirmed optional-argument semantics](../../cloth-optional-arguments/references/semantics.md)
for `opt<T>` / `final opt<T>`, omission-only defaults, named/mixed calls, earlier-parameter scope,
ordered runtime evaluation and overload ranking. These rules extend the strict
parameter contracts below.

## Function parameters

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
