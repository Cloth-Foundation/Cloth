---
name: cloth-output
description: Design, explain, or extend Cloth print and println, built-in registration and lookup, printable-value adaptation, or length-aware native UTF-8 output.
---

# Cloth output and built-in functions

Read [confirmed built-in semantics](references/semantics.md). For interpolation
use [strings](../cloth-strings/SKILL.md); for normal executable completion use
[program entry](../cloth-program-entry/SKILL.md).

## Development rules and current boundaries

- `print(value)`, `println(value)`, and `println()` are unimported void functions.
  `print()` and multi-value calls are arity errors. Interpolation composes text.
- Preserve lexical shadowing: bindings shadow built-ins even in call position;
  a source function group shadows the whole built-in group. Mismatched source
  overloads do not fall back to the built-in. Names are ordinary case-sensitive
  identifiers; source-level printf is not a provided built-in.
- Add built-ins through named typed registrations in `BuiltinFunctions` with
  argument contracts and emission owned by the definition. Keep lookup centralized
  and reusable definitions free of per-emission LLVM handles.
- Printable adaptation uses supported value-receiver `str` providers without
  changing ordinary expression typing or introducing general implicit conversion.
  Preserve canonical metadata text. Array/class value formatting remains deferred.
- Evaluate an argument once before conversion/output. If it exits its enclosing
  function, emit no output or added newline on that path. General multi-result
  expansion is not a print call convention.
- Native output is length-aware UTF-8: NULs remain data and percent signs remain
  text. Newlines follow native text-stream conventions. Detected write failures
  use the runtime error path; no rollback, atomic-write or explicit flush guarantee
  is promised. Console/code-page behavior and delayed buffered flush failures
  retain the limits recorded in the contract.

## Source and verification

Inspect [BuiltinFunctions](../../../Compiler/src/main/kotlin/cloth/builtin/BuiltinFunctions.kt),
[BuiltinFunction](../../../Compiler/src/main/kotlin/cloth/builtin/BuiltinFunction.kt),
[PrintFunction](../../../Compiler/src/main/kotlin/cloth/builtin/PrintFunction.kt),
[BuiltinEmissionContext](../../../Compiler/src/main/kotlin/cloth/emitter/types/BuiltinEmissionContext.kt),
and [EmitOutput](../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitOutput.kt).
Example: [Builtins.co](../../../examples/Builtins.co).

Use `BuiltinFunctionsTest`, affected `CallResolutionTest` cases, and native output
tests. Verify lookup identity, shadowing, arity, void contracts, exact UTF-8/NUL
bytes and interrupted argument behavior. The
[built-in milestone](../cloth-language/references/development.md#built-in-function-registry-and-explicit-output-2026-10-31)
records the integrated behavior and evidence; the current suite must be rerun
before claiming new validation.
