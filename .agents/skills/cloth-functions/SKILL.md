---
name: cloth-functions
description: Design, explain, or extend Cloth functions and methods, strict parameters and returns, scopes, recursion, overload resolution, or deferred local function blocks.
---

# Cloth functions and function blocks

[Explicit generic functions](references/generic-functions.md) covers top-level
`fn<T> name<T>(T value)` declarations, `name<int>(value)` applications, and the
standard-library `magic` helper. Reuse the existing symbolic and closed
worklist and preserve the defining file and separate facts of each specialization.

[GEN-2 Stage 1](references/generic-methods-stage-1.md) parses method-owned type
parameters and explicit member call arguments. It binds both scopes in the
method and resolves explicit type arguments in the calling file.

GEN-2 Stage 2 resolves same-count top-level generic function overloads.
Filter candidates by bounds, then rank substituted signatures with ordinary
overload trials. Expand a body only after a committed selection.
[GEN-2 Stage 2](references/generic-overloads-stage-2.md)

GEN-2 Stage 3 applies that resolver to ordinary class and struct methods.
Hide inherited signatures before checking bounds and specialize by the
declaring owner, method declaration, and explicit arguments.
[GEN-2 Stage 3](references/generic-methods-stage-3.md)

GEN-6 Stage 1 matches generic virtual overrides by position and checks their
bodies with the base declaration's bounds.
[GEN-6 Stage 1](references/generic-virtual-stage-1.md)
Stage 2 specializes the base call contract and dispatches the matching
body using the receiver's current class level.
[GEN-6 Stage 2](references/generic-virtual-stage-2.md)

GEN-3 infers function and ordinary method type arguments when ordinary
overloads have no match. Named generic classes infer owner arguments from
`::new(...)` values. Reuse argument placement, candidate trials, and the
specialization worklist. Partial lists use `_` and declared type defaults
fill slots after inference.
[GEN-3 Stage 1](../cloth-generics/references/function-inference-stage-1.md) ·
[GEN-3 Stage 2](../cloth-generics/references/method-inference-stage-2.md) ·
[GEN-3 Stage 3](../cloth-generics/references/constructor-inference-stage-3.md) ·
[GEN-3 Stage 4](../cloth-generics/references/partial-arguments-and-defaults-stage-4.md)

[Generic Stage 0](../cloth-generics/references/semantics.md#declaration-and-closed-body-checking)
confirms owner-generic bodies are checked at declaration, including unused
templates, then checked again after closed substitution. Unknown T grants no
numeric/member/class capabilities. Preserve independent candidate/body facts;
generic function or method-owned parameters remain outside the first slice.
[Generic Stage 1](../cloth-generics/references/stage-1.md) binds owner parameters
through signatures, defaults, results and deferred function blocks. It does not
check generic bodies or specialize callables. Legacy signature/type resolution
rejects applications with C4033 rather than erasing their arguments.
[Generic Stage 2](../cloth-generics/references/stage-2.md) supplies per-closed-owner
declared signature patterns and substituted annotation facts, including
constructors/defaults/results. Existing inferred operator widths stay patterns.
[Generic Stage 3](../cloth-generics/references/stage-3.md) checks symbolic
declarations and closed bodies, including defaults/captures/results/operators,
using separate source-linked AST views and exact signature keys. Candidate and
operator inference facts stay isolated. Private access follows the original
class declaration across its instances. This stage covered owner-generic methods.
[Generic Stage 4](../cloth-generics/references/stage-4.md) emits the closed
constructors/destructors, methods/operators, defaults, captures and ordered results
through the existing private ABI and frame storage. Keep callable/receiver caches
keyed by closed symbols/owners. C4033 guards symbolic/unresolved native contexts.

[Arbitrary integer Stage 4](../cloth-numerics/references/arbitrary-width-storage.md)
extends ordinary/deferred functions, recursion, optional arguments and ordered
results through 65536 bits. Private wide arguments point to stable snapshots;
parameters copy into inline bytes and escaping results copy to caller-owned
storage before callee cleanup. `ValueType.indirect` classifies this transport
without treating integers as string/array buffers. No public integer ABI is added.

Read [confirmed function semantics](references/semantics.md). For ordered result
lists and positional reception, also use [multiple results](../cloth-multiple-results/SKILL.md).
For buffered parameters/results use [buffer lifetimes](../cloth-buffer-lifetimes/SKILL.md).
[Growable arrays Stage 2](../cloth-arrays/references/growable-arrays-stage-2.md)
extends private array transport with capacity and owner fields. Callers, nullable
carriers, ordered results and callees consume the same `CArray` layout; string
transport stays two-field. Outgoing arrays copy live slots and their buffers into
the caller's arena before frame release. Spare capacity is not copied.
[Stage 3](../cloth-arrays/references/growable-arrays-stage-3.md) executes append
and reserve on independent one-dimensional parameters and temporaries. Stored
array-element struct receivers select/check indices before arguments and resolve
their selected address after argument growth. The private callable ABI is unchanged.
[Stage 4](../cloth-arrays/references/growable-arrays-stage-4.md) executes array-field
append/reserve through ordinary class/struct methods. Existing owners preserve
capacity across calls. Scoped observations update caller selections before callee
growth frees capacity, while class-backed receivers retain checked current-field
replay. Outgoing field arrays still copy independently before owner deletion.
[Stage 5](../cloth-arrays/references/growable-arrays-stage-5.md) executes insert,
removeAt and clear through the same receivers. Wanted removed indirect values
copy before slot cleanup or temporary receiver release, and outgoing results copy
before frame exit. Direct discarded builtins omit unused removed snapshots;
ordinary user-defined method/function result transport is unchanged.
[Stage 6](../cloth-arrays/references/growable-arrays-stage-6.md) executes whole-row
rectangular methods through the same ABI. Defaults, captures, function/method
producers and temporary receivers preserve independent row data and witnesses;
removed rows survive source clear, return and class deletion.
[Stage 8](../cloth-arrays/references/growable-arrays-stage-8.md) consolidates
the array/callable audit and final target evidence. Independent parameters and
returned snapshots keep their declared growable/fixed types and inner witnesses;
ordinary collection methods introduce no separate array transport convention.
[Struct Stage 4](../cloth-structs/references/function-integration.md) integrates
recursive, named and defaulted calls, ordered results and deferred struct reads.
Struct parameters receive independent snapshots and outgoing structs copy to
caller storage through the private Cloth ABI. The maintained
[Structs.co](../../../examples/Structs.co) demonstrates a final parameter's
mutable fields and positional reception of the returned struct and a scalar.

## Development rules

- An omitted return annotation means void. Brace bodies require explicit returns;
  `= expression` bodies return implicitly. Preserve void expression returns,
  bare void returns, and explicit `-> void` discards. Check every completing path.
- Void functions and methods also accept [one statement after `=`](references/semantics.md#function-return-contracts).
  `FunctionParser` uses the unbraced statement parser and retains the `=` token
  on the resulting `BlockBody`. Resolve and emit that body through the same
  path as a brace body. Declaration boundaries allow a semicolon after
  either body introduced by `=`. Ordinary operator declarations and
  value-returning callables retain their expression-body rules.
  See the [verification record](../cloth-language/references/development.md#single-statement-void-callable-bodies-2026-11-06).
- Do-while Stage 3 can complete a non-void body through its guaranteed first
  entry or intentional nontermination. Only fallthrough/continue reaches the
  test; skipped tests supply no runtime returns or jumps. Deferred returns keep
  their own contract and non-continuing loop branches supply no value. Read
  [do-while flow](../cloth-control-flow/references/do-while-loops.md#stage-3-completion-nullable-and-metadata-flow-2026-11-03)
  and use `DoWhileFlowTest`. Stage 4 executes body/test/deferred returns and
  non-fallthrough branches in `DoWhileTest`; poisoned-memory probes in
  `ArrayTemporaryCleanupTest` verify outgoing buffered copies before cleanup.
- Keep names/signatures, typed call selection, return checking, and executable
  entry validation as distinct stages. Resolve forward/direct/mutually recursive
  calls by stable symbols. Return context does not choose an overload.
- Already-typed arguments/results match exactly. Contextual numeric literals are
  distinct from converting typed bindings. Prefer the ordinary default literal
  type when an exact overload exists; otherwise preserve unique-match/ambiguity
  rules, independent of declaration order. Array extents also affect matching.
- Arguments finish once, left to right, before the callee body; preserve snapshots
  before later captured effects. Mutable parameters rebind locally. `final`
  prevents rebinding while preserving the separate array element-write rule.
- A local `def<int> calculate = { return input + 1 }` declares a deferred,
  parameterless block. Its explicit annotation is mandatory. Every evaluated
  name occurrence reruns the block against current captures; declaration does
  not execute and ordinary copies store the evaluated result.
- A block return exits that block. Captures resolve only visible surrounding
  bindings; the declaration cannot capture itself or later locals. Current
  implementation expands each read with separate local/return control flow.

## Boundaries, source, and verification

Import [Stage 5](../cloth-imports/references/stage-5.md) predeclares functions across
source cycles, then binds each file's own public export groups. Imported functions
retain original symbols, private helpers and default contexts. Cross-file groups
never merge by spelling; method signature keys include nominal parameter identity.
Type annotations and body lookup use the declaration's original file context.

`EmittedCallable` owns each declared callable's private LLVM signature and
argument slots. The body and callers share its layout for receiver or
constructor fields, explicit arguments, result arena and omission flags.
Source calls evaluate and snapshot operands in written order. Startup, enum
readers and generated class dispatch pass evaluated values through the same
packer.
[Callable layout](../../../Compiler/src/main/kotlin/cloth/emitter/callables/EmittedCallable.kt) ·
[Integration record](../cloth-language/references/development.md#shared-private-call-abi-2026-11-06)

Both `=` and `:=` block declarations work without memoization. Block binding
assignment/updates are currently rejected (C3042); future replacement and escaping
capture contracts remain unresolved. `opt<T>` / `final opt<T>`, named arguments and runtime defaults
are implemented for ordinary functions; read [optional arguments](../cloth-optional-arguments/SKILL.md)
for binding, declaration scope, evaluation, ranking and ABI invariants.
[Primitive string member calls](../cloth-strings/references/member-calls.md)
implement ordinary value receivers with separate typed lookup and shared built-in
call contracts. [Split's primitive overload group](../cloth-strings/references/split-method.md)
binds exact char/string arguments and records the chosen body/conversions in
`BoundBuiltinCall`; it does not alter source-function or numeric overload ranking.
The [user-defined method contract](references/semantics.md#user-defined-methods)
records the shared `mt` rules and implicit receiver. The
[struct methods plan](../../../todo/struct-methods-plan.md) stages the shared
source callable API and ordinary struct methods. Stage 1 routes source functions
through `CallableDeclaration`, `CallableSymbol` and the shared `CallSite`
overload selector. Argument binding uses explicit parameters, and LLVM emission
consumes the resolved source call and written operands.
[Struct method syntax and scope](references/method-syntax.md) describes Stage 2.
[Stage 3 method typing](references/method-typing.md) covers shared call and
result checking, stored receiver plans, defaults and nullable flow.
[Stage 4 method execution](references/method-execution.md) covers receiver
storage, buffer publication and the private ABI. Ordinary struct methods are
complete. Run [StructMethods.co](../../../examples/StructMethods.co) and see
the [publication record](references/method-publication.md) for exact output,
target coverage, full regression results and the class handoff.

Start with [DeclarationAnalyzer](../../../Compiler/src/main/kotlin/cloth/semantic/declarations/DeclarationAnalyzer.kt),
[FunctionAnalyzer](../../../Compiler/src/main/kotlin/cloth/semantic/typing/FunctionAnalyzer.kt),
and [EmitFunctions](../../../Compiler/src/main/kotlin/cloth/emitter/callables/EmitFunctions.kt).
Examples: [Functions.co](../../../examples/Functions.co) and
[FunctionBlocks.co](../../../examples/FunctionBlocks.co).

Relevant suites: `FunctionParsingTest`, `DeclarationScopesTest`,
`CallResolutionTest`, `FunctionReturnsTest`, `FunctionEmissionTest`, and
`FunctionBlocksTest`; add `StringFunctionTest` for buffered captures/returns.
Recorded stages and native verification are in the
[development history](../cloth-language/references/development.md#earlier-milestone-snapshots).
Use complete function declarations for body and call checks. `FunctionAnalyzer`
resolves calls and validates returns and completion. Executable entry selection
belongs to `ProgramAnalyzer`. [Compiler fixtures](../cloth-language/SKILL.md#compiler-fixtures)

## Body facts and overload trials

[FunctionAnalyzer](../../../Compiler/src/main/kotlin/cloth/semantic/typing/FunctionAnalyzer.kt)
orchestrates signature validation and [BodyTyping](../../../Compiler/src/main/kotlin/cloth/semantic/typing/BodyTyping.kt).
[CallResolution](../../../Compiler/src/main/kotlin/cloth/semantic/typing/CallResolution.kt)
uses `ArgumentBinder` and the written argument order to select a
`ResolvedCall`. Ordinary exact matches precede compatible matches and
contextual typing. Omission counts break ties between identical supplied
types. [OperatorResolution](../../../Compiler/src/main/kotlin/cloth/semantic/typing/OperatorResolution.kt)
owns receiver sides, specificity and the full-overload priority over contexts.

Use `FunctionSemanticModel.trial()` for candidate typing. Commit the selected
trial to its parent and drop the others. Each fact table registers its
snapshot behavior beside its declaration. Preserve identity keys for syntax
and bindings. Share declared callable contracts and pre-typing flow facts.
Keep inferred signatures and width-specific operator bodies inside the
requesting trial until it commits. Catch only `CandidateMismatch` when rejecting
a candidate so located errors retain the first-error rule.
[Function semantic model](../../../Compiler/src/main/kotlin/cloth/semantic/typing/FunctionSemanticModel.kt) ·
[Operator bodies](../../../Compiler/src/main/kotlin/cloth/semantic/typing/OperatorBodies.kt)

`ResultShape` owns expression and statement results. Derive scalar queries
from it and preserve ordered results as one shape.
[Semantic model](../../../Compiler/src/main/kotlin/cloth/semantic/typing/SemanticModel.kt) ·
[Integration record](../cloth-language/references/development.md#semantic-facts-and-overload-trials-2026-11-06)
