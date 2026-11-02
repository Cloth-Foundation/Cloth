# Cloth multiple results: confirmed semantics

These decisions were recorded in the shared Cloth semantics reference.
User-confirmed rules govern intended behavior; historical implementation
notes and unresolved choices retain their original qualifications. Read
the parent skill for current implementation boundaries and source/test routing.

## Multiple function and method results

- On 2026-11-01 the user established the design direction of ordered multiple
  return types, for example `fn<string, int> nameAndAge(string name, int age)`,
  with `return name, age` returning the corresponding values. Multiple results
  require destructuring at a receiving declaration rather than storing the
  results together in one ordinary binding.
- The supplied receiving forms are `def<string, int> name, age = nameAndAge(...)`
  and `def name, age = nameAndAge(...)`, with explicit or inferred types for
  the individual bindings. These forms now have syntax, semantic and native
  execution support for implemented value types. Existing multiple-name
  declarations receiving one result retain their separately confirmed once-total
  evaluation and copy rules.
- On 2026-11-01 the user chose `_` for discarded receiving positions. A
  discarded position still appears in an explicit positional type list, as in
  `def<string, int> name, _ = nameAndAge(...)`. The earlier `void` target
  spelling was provisional and is superseded by this decision.
- The user retained implicit returns after `=`: the shorthand is
  `fn<string, int> nameAndAge(string name, int age) = name, age`. Brace bodies
  use `return name, age`; no explicit `return` is added after `=`.
- Multiple results also extend to value-producing conditional expressions.
  The user's example is `def x, _ = if (true) { true, false } else { false, true }`.
  Its branches supply two boolean results, enabling destructuring without a
  function signature. This does not introduce return-type inference for an
  unannotated `fn`, which continues to mean void.
- Preserve existing single-result multiple-name definitions and assignments
  alongside positional destructuring. Their distinction follows the RHS result
  shape, rather than whether the RHS syntax is a function call or conditional.
  Existing arithmetic compound assignments and updates also retain their rules.
- Detailed rules for result evaluation/snapshot order, result forwarding,
  destructuring assignment to existing bindings, and other uses of multiple
  results remain design recommendations in the
  [implementation plan](../../cloth-language/references/multi-return-implementation.md), not separately
  confirmed decisions. All five implementation stages are complete: syntax parsing, ordered
  producer/return typing, positional reception and native emission, including
  the plan's whole-result forwarding, explicit discards and typed reception
  plans, integration and documentation. Native execution is verified on Windows
  x64/x86, with verified LLVM IR across all nine supported target profiles. See
  development status for coverage, implementation choices and the remaining
  unsupported-feature diagnostics.
- On 2026-11-01 the user requested a formal
  [result-selection RFC](../../../../todo/result-selection-rfc.md) to preserve the
  idea of `myFunc().1` selecting the first returned value. The user explicitly
  deferred implementation. The RFC's one-based selectors, full-call evaluation,
  restrictions and alternatives are proposals, not newly confirmed semantics.
