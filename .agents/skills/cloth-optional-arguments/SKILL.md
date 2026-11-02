---
name: cloth-optional-arguments
description: Design, explain, or extend Cloth opt parameters, omission-only defaults, named arguments, deterministic argument placement, and default-aware overload resolution.
---

# Cloth optional arguments and defaults

Read [confirmed semantics](references/semantics.md) before changing the binder or
default evaluation. Use [functions](../cloth-functions/SKILL.md) for ordinary
function contracts, [nullability](../cloth-nullability/SKILL.md) for nullable
payloads, and [buffer lifetimes](../cloth-buffer-lifetimes/SKILL.md) for snapshots.

## Current executable scope

Ordinary functions support required `opt`/`?:` pairing, named and mixed calls,
runtime defaults with earlier parameter reads, declaration-order defaults,
source-order supplied snapshots, strict types and omission-aware overload ranking.
All existing executable scalar, string and array types work, including nullable
scalars. Nullable buffers retain the separate nullability gate. Methods, state
dispatch, constructors and foreign default ABIs remain deferred. Built-ins reject
named labels until their public labels are defined.

```cloth
fn<string, int> nameAndAge(opt string name ?: "Jack", int age) = name, age
fn<int> area(int width, opt final int height ?: width) = width * height
// nameAndAge(25), nameAndAge(25, name="Mike"), area(width=5)
```

Runnable acceptance example: [OptionalArguments.co](../../../examples/OptionalArguments.co).
The [implementation plan](../../../todo/optional-arguments-plan.md) retains the
original design review and deferred callable integration; its former proposal
qualifiers do not supersede the subsequently accepted ordinary-function contract.

## Source ownership and invariants

- `ParameterDeclaration` retains `opt`, `final`, fallback delimiter and expression
  spans. `CallArgument` is shared by ordinary/state-call syntax; its labels are
  metadata rather than name reads or assignment expressions. The computed
  `arguments` view preserves written order for flow, effects and cleanup walkers.
- [ArgumentBinder](../../../Compiler/src/main/kotlin/cloth/semantic/ArgumentBinding.kt)
  computes one placement per candidate without expression typing. Preserve its
  required-slot reservations, named duplicate policy and source-index mapping.
  [CallAnalyzer](../../../Compiler/src/main/kotlin/cloth/semantic/CallAnalyzer.kt)
  probes isolated facts and stores the selected binding in `ResolvedCall.Source`.
  Emission consumes that plan and never searches for a new mapping.
- [DeclarationAnalyzer](../../../Compiler/src/main/kotlin/cloth/semantic/DeclarationAnalyzer.kt)
  resolves defaults in a separate declaration scope containing earlier parameter
  symbols. Their views are read-only, including array elements and deferred
  captured writes. Default-local copies remain ordinary independent values.
  Returns may belong to an explicitly nested deferred block, never escape the
  default. Every default is typed and metadata-checked even without omitted calls.
- [EmitFunctions](../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitFunctions.kt)
  appends one internal i1 omission flag per optional parameter after ordinary
  parameters and any return arena. The definition-owned prelude branches before
  decoding an optional payload, evaluates missing values in declaration order,
  and binds each complete parameter before the next default. Zero placeholders
  are never decoded, shape-checked, copied or freed as supplied values. Presence
  flags for nullable payloads remain separate. ABI decode/default expressions may
  branch; phis use their actual continuing predecessor blocks.
- Supplied arguments snapshot arrays in written order before entering the callee.
  Defaults use the callee frame's ordinary arena/copy/transfer rules; returned
  buffers copy to the caller arena before frame release. Recursive defaults call
  stable function entries, without recursive AST expansion or shared mutable
  default storage. Preserve scalar bool/half ABI representations and target widths.
- Executable startup supplies `main`'s `string[]` even when empty, so an optional
  entry parameter receives that array rather than executing its default. Ordinary
  calls to `main` use the usual binder. Existing entry type restrictions remain.
- C3065 reports invalid argument binding; C3066 reports invalid default scope or
  escaping return. Preserve existing strict type, required-only positional arity
  and ambiguity diagnostics, first-error compilation, and intrinsic probe errors.

## Verification and boundaries

Use `OptionalArgumentsTest` and `OptionalArgumentsEmissionTest`, then the affected
call, nullable, array, result, lifetime and entry suites. The emission suite checks
Windows x64/x86 execution and nine verified LLVM target profiles, covering
effects, recursive defaults, early returns/failure, independent recursive arrays,
outgoing Unicode strings, nullable half and other carriers, startup and CLI errors.
Cross-target IR verification does not establish native Linux/macOS execution.

See the [dated integration evidence](../cloth-language/references/development.md#optional-arguments-and-runtime-defaults-2026-11-01)
for actual results. Future dispatch must settle visible parameter/default contracts,
state revalidation, allocation and constructor delegation ordering before adopting
this binder; recording reusable argument plans implements none of those runtimes.
