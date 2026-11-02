# Cloth diagnostics: confirmed semantics

These decisions were recorded in the shared Cloth semantics reference.
User-confirmed rules govern intended behavior; historical implementation
notes and unresolved choices retain their original qualifications. Read
the parent skill for current implementation boundaries and source/test routing.

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
  [development status](../../cloth-language/references/development.md); native executable runtime reporting is a
  separate path from JVM compiler diagnostic rendering.
