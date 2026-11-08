# Cloth bindings and assignments: confirmed semantics

These decisions were recorded in the shared Cloth semantics reference.
User-confirmed rules govern intended behavior; historical implementation
notes and unresolved choices retain their original qualifications. Read
the parent skill for current implementation boundaries and source/test routing.

## Bindings

- Explicit primitive bindings use `def<int>`, `def<float>`, `def<bool>`, `def<char>`, or `def<string>`, with the same `=`
  and `:=` binding behavior as inferred declarations.
- Omitting the type annotation infers the binding's type from its initializer,
  for example `def x = 10` and `def y := x + 2`.
- On 2026-10-31 the user confirmed comma-separated names sharing a definition
  initializer: `def x, y, z = 10` creates three bindings, each inferred as `int`
  and initialized to 10. Each follows ordinary definition and assignment rules;
  an explicit annotation and the `=` or `:=` form apply to every name.
- The user confirmed once-total initializer evaluation: `def x, y, z = make()`
  calls `make()` once and gives each binding an independent copy of its result
  under ordinary value-copy rules. In particular, array bindings do not alias
  each other's mutable element storage.
- On 2026-10-31 the user also confirmed `x, y, z = value` for existing named
  bindings: evaluate the RHS once and assign its result to every target under
  ordinary assignment rules. Each target must exist and permit reassignment;
  its fixed type and initial array shape remain in force. Array values copy
  independently, and the compiler still stops at the first detected error.
- The user confirmed one exact RHS result type for multiple assignment, even
  for contextual integer literals. The first target supplies ordinary assignment
  context; the resolved value must satisfy every other target without implicit
  conversion. Thus `int<8>` and `int<64>` targets cannot share `= 20` just because
  the literal fits both ranges. Array extent compatibility retains its ordinary
  static/runtime checks rather than demanding identical extent annotations.
- A binding's declared or inferred type stays fixed. Reassignment must satisfy
  that type; assigning a different type does not change the binding's type.
- `=` creates a mutable binding.
- `:=` creates an immutable binding. It prevents reassignment of that binding;
  it does not freeze the object or its fields.
- Arrays follow the same binding-only rule: `:=`/`final` permit element mutation
  while prohibiting rebinding, subject to ordinary element type and shape checks.
  The [growable-array revision](../../cloth-arrays/references/growable-arrays.md)
  confirms that length changes of `T[]` follow this rule too, without changing
  the binding's type. It supersedes retained initial outer shape for growable
  bindings, including broadcast targets. Stage 1 implements growable typing and
  whole-array replacement; mutator storage/execution remains guarded by C4032.
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
