---
name: cloth-optional-arguments
description: Design, explain, or extend Cloth opt parameter wrappers, final parameters, omission-only defaults, named arguments, deterministic argument placement, and default-aware overload resolution.
---

# Cloth optional arguments and defaults

Interface calls share the argument binder and evaluate omission expressions in
the static declaration's scope. Stage 5 reuses the ordinary prelude in the
dispatch entry. The selected body receives every argument as supplied.
[Interface call contracts](../cloth-interfaces/references/stage-3.md#static-call-binding-and-defaults) ·
[Native interface defaults](../cloth-interfaces/references/stage-5.md#static-arguments-and-closed-fallback-bodies)

[Generic Stage 3](../cloth-generics/references/stage-3.md) checks owner-generic
defaults with unknown exact parameters and again after substitution, preserving
earlier-parameter scope, read-only defaults and ordinary argument placement.
Source-linked body views isolate candidate/default facts for each closed owner.

Read [confirmed semantics](references/semantics.md) before changing the binder or
default evaluation. Use [functions](../cloth-functions/SKILL.md) for ordinary
function contracts, [nullability](../cloth-nullability/SKILL.md) for nullable
payloads, and [buffer lifetimes](../cloth-buffer-lifetimes/SKILL.md) for snapshots.

## Current executable scope

Ordinary functions support `opt<T>` or `final opt<T>` with required `?:` defaults,
named and mixed calls, runtime defaults with earlier parameter reads,
declaration-order defaults, source-order supplied snapshots, strict types and
omission-aware overload ranking.
All existing executable scalar, string and array types work, including nullable
strings, whole arrays and scalar/string elements. See the
[nullable buffer contract](../cloth-nullability/references/buffers.md) for snapshots,
shape retention, startup and `opt<string[]?> args ?: null`. Nullable rectangular
rows also work under their [row contract](../cloth-arrays/references/nullable-rows.md).
Struct and class methods and class constructors use the shared optional
argument transport. Built-ins reject named labels until their public labels
are defined.
[Struct method execution](../cloth-functions/references/method-execution.md) ·
[Class inheritance](../cloth-language/references/class-inheritance.md)

```cloth
fn<string, int> nameAndAge(opt<string> name ?: "Jack", int age) = name, age
fn<int> area(int width, final opt<int> height ?: width) = width * height
// nameAndAge(25), nameAndAge(25, name="Mike"), area(width=5)
```

Runnable acceptance example: [OptionalArguments.co](../../../examples/OptionalArguments.co).
The [implementation plan](../../../todo/optional-arguments-plan.md) retains the
original design review and deferred callable integration; its former proposal
qualifiers do not supersede the subsequently accepted ordinary-function contract.

## Source ownership and invariants

- `Parser` reads `final` first, then encloses the entire ordinary type in
  `opt<...>` for optional parameters. Widths, unsignedness, arrays and nullability
  stay inside the wrapper; `TypeReference` retains the underlying T unchanged.
  `ParameterDeclaration` preserves `optOpening`/`optClosing`, `optionalTypeSpan`,
  `final`, fallback delimiter and expression spans. The parser captures the
  complete parameter span through the last consumed token, including closing
  parentheses around a default. Its whole span starts at `final` when present;
  never reconstruct its end solely from the unparenthesized expression AST.
  Keep `opt<T>` out of the general type grammar and reject
  legacy unbracketed forms rather than supporting two declaration syntaxes.
  `CallArgument` is shared by ordinary/state-call syntax; its labels are
  metadata rather than name reads or assignment expressions. The computed
  `arguments` view preserves written order for flow, effects and cleanup walkers.
- [ArgumentBinder](../../../Compiler/src/main/kotlin/cloth/semantic/typing/ArgumentBinding.kt)
  computes one placement per candidate without expression typing. Preserve its
  required-slot reservations, named duplicate policy and source-index mapping.
  [FunctionAnalyzer](../../../Compiler/src/main/kotlin/cloth/semantic/typing/FunctionAnalyzer.kt)
  probes isolated facts and stores the selected binding in `ResolvedCall.Source`.
  Emission consumes that plan and never searches for a new mapping.
- [DeclarationAnalyzer](../../../Compiler/src/main/kotlin/cloth/semantic/declarations/DeclarationAnalyzer.kt)
  resolves defaults in a separate declaration scope containing earlier parameter
  symbols. Their views are read-only, including array elements and deferred
  captured writes. Default-local copies remain ordinary independent values.
  Returns may belong to an explicitly nested deferred block, never escape the
  default. Every default is typed and metadata-checked even without omitted calls.
- The shared callable layout appends one internal i1 omission flag per optional
  parameter after ordinary parameters and any return arena. Source calls use
  the resolved omission list, and startup marks its arguments supplied.
  The definition-owned prelude branches before
  decoding an optional payload, evaluates missing values in declaration order,
  and binds each complete parameter before the next default. Zero placeholders
  are never decoded, shape-checked, copied or freed as supplied values. Presence
  flags for nullable payloads remain separate. ABI decoding and default
  expressions may branch. Phis use the actual continuing predecessor blocks.
  [Callable layout](../../../Compiler/src/main/kotlin/cloth/emitter/callables/EmittedCallable.kt) ·
  [Callee prelude](../../../Compiler/src/main/kotlin/cloth/emitter/callables/EmitFunctions.kt)
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

See the [final syntax review](../cloth-language/references/development.md#optional-syntax-final-review-2026-11-02),
the [syntax migration evidence](../cloth-language/references/development.md#optional-parameter-syntax-final-optt-2026-11-02)
and [original integration evidence](../cloth-language/references/development.md#optional-arguments-and-runtime-defaults-2026-11-01)
for actual results. The confirmed
[interface call contract](../cloth-interfaces/references/design.md#static-interface-call-contracts-confirmed-2026-11-08)
uses labels and defaults from the static interface declaration, then dispatches
only the body with resolved values. Interface execution remains unimplemented.
State and other future dispatch must settle their visible parameter/default
contracts, revalidation, allocation and delegation ordering before adopting this
binder; recording reusable plans implements none of those runtimes.
