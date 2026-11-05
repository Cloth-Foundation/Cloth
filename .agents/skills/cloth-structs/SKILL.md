---
name: cloth-structs
description: Design or implement Cloth named structs, contextual aggregate construction, fields, independent value copies and struct integration with existing value contexts.
---

# Cloth structs

Stages 0–3 are complete (2026-11-04). Local structs execute with inline storage,
recursive value copies and resolved field writes. C3077 gates struct-bearing
source function signatures, deferred struct results and struct foreach. Use the
[syntax record](references/syntax.md) for parsing, the [semantic record](references/semantic-analysis.md)
for type checking, and the [local storage record](references/local-storage.md)
for lowering, lifetime invariants and verification.

Read [the struct contract](references/semantics.md) for accepted syntax,
semantics and scope. Use the [basic structs plan](../../../todo/basic-structs-plan.md)
for stage boundaries, source routing and verification targets.

## Preserve these boundaries

- Each struct declaration defines a distinct type. Match struct values by
  declaration identity even when field lists are identical.
- A type and a variable can share a spelling. Type annotations and ordinary
  variable expressions resolve their names independently. For `Position::type`,
  check the local variable first and use the struct type if no variable is in scope.
  A function can also share a struct's name. Keep ordinary callable lookup and
  local shadowing in call contexts.
- Struct copies are independent values, recursively including nested strings,
  arrays and structs. Future class fields copy object identity; copying or
  cleaning up a struct must not clone or implicitly delete its class objects.
- `:=` and `final` prevent replacing the bound struct, but permit field changes.
  Nested stored field/index writes update the containing value directly; a
  separate struct binding receives an independent copy. Field assignments through
  temporary struct results are compile-time errors, including evaluated deferred
  results. Store the result first; a deferred name is not stored struct data.
- Write public declarations as `pub typedef<struct> Name { ... }`. Omit `pub`
  for private visibility. Fields inherit the enclosing struct's visibility.
  Type members use `mt`. A `fn` declaration in a type body is a syntax error
  (C2007 in a struct). Stage 1 struct bodies support field declarations.
- For struct-typed bindings, field-value lists construct immediately; statement
  bodies with explicit returns remain deferred blocks executed on every read.
  Keep braces for both and preserve existing non-struct blocks, including void
  blocks. A single-field literal such as `{ 7 }` requires no trailing comma.
  Empty structs are valid. With an expected zero-field struct type, `{}`
  constructs a value of that type. Field declarations and literals may span
  lines, require commas between entries, and reject trailing commas. Literals
  supply exactly one value per field in declaration order. A literal requires
  an expected struct type, such as an annotated binding, a known parameter type
  or a declared return type. The field list alone cannot infer the struct type.
  Calls use ordinary overload ranking with candidate parameter contexts. Equal
  matches are ambiguous. Evaluate fields once from left to right and preserve
  each field's value before later expressions run.
- Separate value reads from stored field/index places. Reading a copied nested
  value cannot be the implementation of a write to the original stored field.
- Basic structs require acyclic field types. Reject direct and indirect field
  type cycles, including paths through array and nullable wrappers.
- Every array dimension in a field type must have an explicit extent, including
  under nullable wrappers. Construction, field writes and whole-struct replacement
  respect the declared field shape using ordinary array checks.
- A null check can prove a stored field present when later access resolves the
  same unchanged path. Assigning the field or replacing a containing struct
  invalidates the proof. Use the existing nullable payload access and metadata rules.
- Defer whole-struct equality. Use ordinary null comparisons for nullable
  presence tests, and ordinary metadata and individual field comparisons.
- Local storage holds struct fields inline. Borrow addresses for projections
  and copy inline bytes and live nested buffers at value transfer boundaries.
  Resolve a write's stored path and read its old compound operand before the RHS.
  Field layout and array stride use the target data layout.
- Recursive string, array and shape copies must survive caller/callee cleanup.
  Indirect transport does not establish buffer ownership or safe early cleanup.
- Keep the first detected error as one structured diagnostic, with source labels
  as needed. Unsupported features must fail deliberately, not via internal errors.

For implementation interactions, load [buffer lifetimes](../cloth-buffer-lifetimes/SKILL.md),
[arrays](../cloth-arrays/SKILL.md), [functions](../cloth-functions/SKILL.md),
[nullability](../cloth-nullability/SKILL.md) or
[compiler data](../cloth-compiler-data/SKILL.md) as appropriate.

Each integrated stage must update this skill and its references with actual
support, diagnostics, tests and target evidence under
[feature integration](../cloth-feature-integration/SKILL.md). Stage 3 verifies
native local struct execution on Windows x64/x86 and IR and objects on all nine
supported profiles.
