# Cloth functions and function blocks: confirmed semantics

These decisions were recorded in the shared Cloth semantics reference.
User-confirmed rules govern intended behavior; historical implementation
notes and unresolved choices retain their original qualifications. Read
the parent skill for current implementation boundaries and source/test routing.

## User-defined methods

Declare a method with `mt` inside an enclosing `typedef`. Any kind of
`typedef` can contain methods. A `fn` declaration inside a type body is a
compile-time error. A method follows the ordinary function rules for
parameters, overloads, bodies, returns and result lists. It also has an
implicit receiver for the instance on which it was called.

The implicit receiver is named `self` (confirmed 2026-11-05) and is a fixed
binding. Assigning a new value to `self` is a compile-time error. Fields remain
writable through `self` under the receiver type's field rules.
Method defaults can read `self` with the same read-only access they have to
[earlier parameters](../../cloth-optional-arguments/references/semantics.md#scope-evaluation-and-lifetimes).
Defaults can read receiver fields. A user-defined method call through the
read-only receiver is a compile-time error, including an implicit receiver call.

Bare names use ordinary lexical lookup first. When that lookup finds no name,
a bare value read or assignment target looks for a field on `self`, and a bare
call looks for a method on `self`. Lookup selects the callable group before
overload resolution. A lexical group with no matching overload reports the
ordinary call error. Explicit `self.` selects receiver lookup directly. For
example, in `mt setDamage(float damage)`, `self.damage = damage` writes the
field using the lexical parameter `damage`.

Fields and methods on a type may share a name. In `source.damage`, lookup
selects the field. In `source.damage()`, lookup selects a method overload.
Existing state dispatch has its own rules in the
[shared semantics](../../cloth-language/references/semantics.md#states-and-method-dispatch).

In a struct, the field list comes first. A semicolon separates it from
following members. Fields and methods inherit the struct's visibility.
The `DamageSource` example illustrates updating a receiver field and returning
the updated amount. Under the ordinary function body rules, write the update
as a statement and return the result explicitly.

~~~cloth
pub typedef<struct> DamageSource {
    DamageType type,
    float damageAmount;

    mt<float> addDamage(float damage) {
        damageAmount += damage
        return damageAmount
    }
}
~~~

### Struct method receivers

For a stored struct receiver, `self` refers to that storage. Field changes
through `self` persist in the receiver, including when the receiver binding
uses `:=` or is a `final` parameter. Resolve the receiver's storage once,
before evaluating the explicit arguments. The method reads the contents of
that storage when it starts. For example, if the deferred argument in
`source.addDamage(reset)` replaces the contents of `source` with damage
`100f` and yields `5f`, the method sets `source.damageAmount` to `105f`.

If the stored receiver is nullable, its presence proof must hold after
evaluating the explicit arguments. Reject the call at compile time if an
argument invalidates that proof before `self` is bound. For example, a
deferred argument that clears `source` to `null` invalidates the proof from
an earlier `source != null` check.

A call on a temporary struct, such as `makeSource().addDamage(5f)`, is also
valid. `self` refers to the temporary's storage for the call. Field changes
affect that temporary, and returned values follow ordinary function result
rules. All methods use this receiver rule without a mutating-method modifier.

[Stage 2](method-syntax.md) implements declarations and receiver name
resolution. [Stage 3](method-typing.md) implements call and body typing,
receiver plans and flow checks. [Stage 4](method-execution.md) executes
ordinary struct methods with stored or temporary receivers.

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
- A void function or method can use `=` followed by one statement, with
  either an omitted return annotation or explicit `<void>`. The statement
  follows the same scope and return rules as inside braces. For example, a
  class with a mutable `float` field named `health` can declare
  `pub mt<void> addHealth(float add) = health += add`. Plain assignments,
  all five compound assignments, postfix updates, bindings, loops and bare
  returns use the ordinary statement grammar. A conditional uses statement
  form when it lacks an `else` or an arm ends without a result expression or
  explicit return. A single optional semicolon can terminate the body.
- A void function or method with an expression body must yield void. A produced
  value can be explicitly discarded with `expression -> void`, for example
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
- Struct fields and methods inherit the enclosing struct's visibility.
