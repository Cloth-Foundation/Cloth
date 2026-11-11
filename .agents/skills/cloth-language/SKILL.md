---
name: cloth-language
description: Use Cloth's shared language authority and select the focused feature skills for language design, compiler behavior, source examples, or implementation work.
---

# Cloth language

Cloth is a modern, strongly typed, object-oriented language for applications and
libraries, with first-class state machines in classes. Java/Kotlin and a light
C++ influence provide context, not imported semantics. Preserve concise confirmed
syntax, explicit object creation/destruction, no GC, and no programmer-visible
pointer, reference, or `mut` qualifiers.

## Authority and reading scope

- [Test.co](../../../examples/Test.co) describes intended language, including
  unimplemented features. Explicit user decisions refine and extend it.
- [Shared semantics and feature index](references/semantics.md) retains portability
  and object/state design, with links to feature-owned confirmed
  semantics. Read the relevant sections and skills before deciding behavior.
- Each feature skill owns its current development guidance, implementation
  boundaries, source/test routing, and links to evidence. Load only those needed
  for the current work; follow cross-feature links when changing an interaction.
- [Development history](references/development.md) preserves dated implementation
  and verification evidence. Read the relevant milestone when needed; older
  snapshots do not establish current support. Recheck source and test results.
- Distinguish confirmed semantics, implementation choices, proposals and unresolved
  design. Missing implementation does not invalidate intended syntax. Ask one
  focused question at a time when a necessary semantic choice remains unresolved.
- Compilation stops at the first detected error, with one diagnostic and any
  labels needed to explain it. Preserve this across every compiler feature.

## Compiler package ownership

The shared driver resolves the ABI and passes its `TargetLayout` to
`ProgramAnalyzer`. LLVM emission consumes the checked model and verifies that
its layout matches the selected ABI. Use a synthetic layout for analysis-only
tests or `nativeTestLayout` from the fixtures for host-specific cases.
[Driver](../../../Compiler/src/main/kotlin/cloth/CompilerDriver.kt) ·
[Target and toolchain rules](../cloth-toolchains/SKILL.md)

- `semantic.declarations` resolves names, scopes and declared types using the
  identities in `semantic.symbols`.
  [Declaration analyzer](../../../Compiler/src/main/kotlin/cloth/semantic/declarations/DeclarationAnalyzer.kt)
- `semantic.typing` checks bodies and resolves calls and operators. It owns
  inferred types, candidate trials and committed callable facts.
  [Function analyzer](../../../Compiler/src/main/kotlin/cloth/semantic/typing/FunctionAnalyzer.kt)
- `semantic.flow` owns completion, presence, metadata and class lifetime
  analysis. Entry validation and shared plans, including reception and places,
  live in `semantic`.
  [Completion model](../../../Compiler/src/main/kotlin/cloth/semantic/flow/ControlFlowModel.kt)
- `emitter.callables` owns callable ABI lowering, entry adapters, argument
  and result transport, and function frames.
  [Function emitter](../../../Compiler/src/main/kotlin/cloth/emitter/callables/EmitFunctions.kt)
- `emitter.storage` owns bindings, places, shapes, value transfer and arena
  lifetimes.
  [Value transfer](../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitValueTransfer.kt)
- `emitter.text` owns provider emission services, metadata materialization,
  formatting and UTF-8 output.
  [Provider context](../../../Compiler/src/main/kotlin/cloth/emitter/text/ProviderEmissionContext.kt)

Kotlin `internal` visibility still applies across the compiler module.
Keep helpers private to their file or class when all callers live there.

## Focused verification

For a focused verification run from the repository root, use
`.\gradlew.bat :Compiler:test --tests cloth.ArraysTest --offline --console=plain --no-daemon`,
substituting the suite identified by the feature skill. Use `./gradlew` on Unix.
Choose tests for the affected behavior; report actual results and target scope.

## Compiler fixtures

Use [ProgramFixtures](../../../Compiler/src/test/kotlin/cloth/ProgramFixtures.kt)
to wrap focused bodies in a complete `fn main() { ... }` source unit.
`parseMain` and `mainStatements` expose syntax for parser tests.
`FunctionAnalyzer` checks bodies and returns using the production model.
Use `DeclarationAnalyzer` for tests of name resolution and scopes alone.
Tests that check file-level syntax or exact source identity should pass a
complete source file directly to the parser.

`compileMain` calls the shared compiler driver. Put `print` or `println` in the
Cloth source to observe values. `assertMainOutput` checks UTF-8 output and the
normal completion report. Use `runExecutable` to check other exit statuses and
runtime diagnostics. Keep direct LLVM module composition for ABI probes and
fault injection. [Migration record](references/development.md#production-path-fixture-migration-2026-11-06)

## Canonical operator and keyword spellings

[Operators](../../../Compiler/src/main/kotlin/cloth/token/Operators.kt) and
[Keywords](../../../Compiler/src/main/kotlin/cloth/token/Keywords.kt) own Cloth's
operator and keyword spellings. Their `.toString()` methods are the source of
truth wherever compiler code needs those spellings.

- Use enum values for operator/keyword identity and dispatch. When comparing or
  producing text, use `.toString()`, for example
  `token.lexeme == Operators.UNDERSCORE.toString()` and
  `Keywords.FUNCTION.toString()`. Use the existing enum lookup APIs where needed.
- Apply this to lexer/parser checks, semantic checks, diagnostics, generated
  Cloth source and any spelling tables. Do not hard-code duplicate spellings,
  including keywords/operators embedded in longer diagnostic messages. Kotlin
  interpolation of an enum, such as `"Expected '${Keywords.FUNCTION}'"`, calls
  its `.toString()` and satisfies this rule.
- Do not derive spellings from enum `.name`, lowercasing or a parallel mapping;
  keyword aliases are defined by `Keywords.toString()`. For character-level
  operator matching, use the existing `Operators.toChar()` helper.
- Lower compound assignments through `Operators.compoundAssignmentBase()` and
  render the returned enum with `.toString()` when constructing an arithmetic
  token. Do not derive the binary operator by slicing its assignment spelling.
  Range emission also keeps comparison and update identities as enum values.
- Keep original token lexemes when reporting what the user actually wrote.
  Literal Cloth examples and fixed source/spelling test fixtures may contain
  syntax directly; compiler logic and dynamically constructed syntax must use
  the canonical methods. This rule concerns Cloth syntax, not target-language
  syntax such as LLVM IR.
- When changing code that duplicates a spelling, replace it with the enum-based
  form. Existing duplicates are cleanup work, not an alternative authority.

The [compiler-wide migration record](references/development.md#canonical-operator-and-keyword-spellings-2026-11-02)
captures implementation scope and regression evidence.

## Feature skills

| Work area | Skill |
| --- | --- |
| Source imports, selected/wildcard exports, circular graphs, file scopes and compiler requests | [cloth-imports](../cloth-imports/SKILL.md) |
| Shuttle TOML manifests, project/module roots and build/run orchestration | [shuttle-build](../shuttle-build/SKILL.md), [Native command](../shuttle-build/references/native.md) |
| Standard-library submodule, `cloth/` sources, bundling and namespace contract | [scaffold](../shuttle-build/references/standard-library.md), [contract](../cloth-toolchains/references/standard-library.md), [source integration](../cloth-toolchains/references/standard-library-stage-2.md), [selection controls](../cloth-toolchains/references/standard-library-stage-3.md), [allocation boundary](../cloth-toolchains/references/standard-library-stage-4.md), [publication](../cloth-toolchains/references/standard-library-stage-5.md) |
| Bootstrap's string-input lexer port, differential checks, token ownership and library boundaries | [cloth-bootstrap-lexer](../cloth-bootstrap-lexer/SKILL.md) |
| Compiler-backed standard-library callables, `Path`, read-only `File` and UTF-8 decoding | [file I/O and bindings](../cloth-toolchains/references/file-io.md) |
| Class declarations, constructors, lifecycle and alias safety | [basic class publication](references/class-publication.md), [class execution and lifetime](references/class-lifetime.md) |
| Ordinary class virtual methods, override checking and lifecycle dispatch | [Virtual method implementation](references/class-inheritance.md#virtual-method-implementation) |
| Nested nominal types, lexical and qualified lookup, privacy and generic identity | [nested types](references/nested-types.md) |
| Nested state declarations, shared call contracts, activation, transitions, dispatch and state facts | [state syntax and checking](references/state-syntax.md), [activation and dispatch](references/state-runtime.md) |
| Top-level state declarations, class and interface host contracts, imported attachments and attached defaults | [state attachments](references/state-attachments.md) |
| Maintained state examples, installed commands, first-error checks and target coverage | [state publication](references/state-publication.md) |
| Interface declarations, generic conformance, dispatch and class-backed value contexts | [cloth-interfaces](../cloth-interfaces/SKILL.md) |
| Named structs, aggregate construction, fields and independent copies | [cloth-structs](../cloth-structs/SKILL.md) |
| Enum cases, payloads, constant casts, `::name`, type-only `::values`, formatting, and explicit struct equality | [struct and enum operators](references/value-operators.md) |
| Optional semicolons and valid terminator placement | [cloth-semicolons](../cloth-semicolons/SKILL.md) |
| Bindings, inference, broadcast assignment, updates | [cloth-bindings](../cloth-bindings/SKILL.md) |
| Numeric literal prefixes/suffixes, integers, floats, booleans, arithmetic, casts | [cloth-numerics](../cloth-numerics/SKILL.md) |
| Arbitrary fixed integer widths, private value transport, full decimal output and target evidence | [integrated integer implementation](../cloth-numerics/references/arbitrary-width-integers.md) |
| Integer abs/clamp/signum, bit counts and radix formatting across every integer identity | [integer attachments](../cloth-numerics/references/integer-attachments.md) |
| Float abs/clamp/signum, signed zeros, NaNs, infinities and software half comparison | [float attachments](../cloth-numerics/references/float-attachments.md) |
| Boolean toInt conversion to signed int32 with separate BoolAttachments registration | [boolean attachments](../cloth-numerics/references/bool-attachments.md) |
| usize/isize, target-sized counts and identity | [cloth-target-integers](../cloth-target-integers/SKILL.md) |
| Nullable types, presence proofs, guarded member chains, Elvis, nullable casts and metadata | [cloth-nullability](../cloth-nullability/SKILL.md) |
| Nullable strings/whole arrays/elements, retained shapes and buffer lifetimes | [nullable buffer integration](../cloth-nullability/references/buffers.md) |
| Nullable rectangular rows, shared shape witnesses and nested row writes | [nullable row integration](../cloth-arrays/references/nullable-rows.md) |
| Arrays, fixed/growable shapes, indexing, typed mutations and independent values | [cloth-arrays](../cloth-arrays/SKILL.md) |
| Generic class/struct checking and native values/storage/metadata; List source integration | [cloth-generics](../cloth-generics/SKILL.md) |
| Generic bounds, satisfaction checks, and bound operations | [GEN-1 Stage 1](../cloth-generics/references/constraints-stage-1.md), [GEN-1 Stage 2](../cloth-generics/references/constraints-stage-2.md), [GEN-1 Stage 3](../cloth-generics/references/constraints-stage-3.md) |
| Implemented generic syntax, runnable example and first-slice publication | [generic guide](../../../docs/generics.md), [Stage 6](../cloth-generics/references/stage-6.md) |
| Public standard-library List, Deque, Queue, and Stack, independent elements and explicit lifetime | [cloth-collections](../cloth-collections/SKILL.md) |
| Buffered copies, snapshots, fresh storage, cleanup | [cloth-buffer-lifetimes](../cloth-buffer-lifetimes/SKILL.md) |
| Concatenation arenas, replaceable string owners, safe reads/stores and bounded prefix storage | [string storage](../cloth-buffer-lifetimes/references/string-concatenation-storage.md) |
| String member operand copy elision and proven temporary reclamation | [member storage cleanup](../cloth-buffer-lifetimes/references/string-member-storage.md) |
| Characters, strings, interpolation, concatenation | [cloth-strings](../cloth-strings/SKILL.md) |
| Character Unicode classification, code-point toInt, pinned tables and generation | [character attachments](../cloth-strings/references/character-attachments.md) |
| Character simple uppercase/lowercase mappings, char results and unchanged unmapped scalars | [character casing](../cloth-strings/references/character-case.md) |
| Full Unicode string casing, expansions, final sigma and D-generated context tables | [string casing](../cloth-strings/references/string-case.md) |
| String attachments, typed primitive registry and ordinary value member calls | [string member calls](../cloth-strings/references/member-calls.md) |
| Scalar string positions, extraction, searches and character-array conversion | [scalar string methods](../cloth-strings/references/scalar-methods.md) |
| Char/string delimiter overloads, empty pieces and independent string arrays | [string splitting](../cloth-strings/references/split-method.md) |
| Literal replacement and prefix/suffix removal | [string editing](../cloth-strings/references/editing-methods.md) |
| Unicode White_Space trimming and blank-string checks | [whitespace methods](../cloth-strings/references/whitespace-methods.md) |
| Functions, overloads, captures, deferred blocks, explicit generic functions and `magic` | [cloth-functions](../cloth-functions/SKILL.md), [explicit generic functions](../cloth-functions/references/generic-functions.md) |
| `opt<T>` parameters, defaults, named calls and placement | [cloth-optional-arguments](../cloth-optional-arguments/SKILL.md) |
| Ordered results, forwarding, destructuring, discards | [cloth-multiple-results](../cloth-multiple-results/SKILL.md) |
| Conditionals, is checks, short circuits, branch merges, range/foreach/while/do-while, break/continue and loop cleanup | [cloth-control-flow](../cloth-control-flow/SKILL.md) |
| Compiler data access, providers, typeinfo | [cloth-compiler-data](../cloth-compiler-data/SKILL.md) |
| Nullable type providers, payload bounds and fixed array type counts | [nullable provider integration](../cloth-compiler-data/references/nullable-providers.md) |
| print, println, input, recursive array text, built-in registration and lookup | [cloth-output](../cloth-output/SKILL.md) |
| main, Unicode arguments, exit status and PEC | [cloth-program-entry](../cloth-program-entry/SKILL.md) |
| Diagnostics, spans, stable codes, first-error handling | [cloth-diagnostics](../cloth-diagnostics/SKILL.md) |
| Build and run commands, targets, C ABI, object mode, linking and CRT discovery | [cloth-toolchains](../cloth-toolchains/SKILL.md) |
| Installed JVM startup, bundled Java 25 and Unicode relocation | [Windows launchers](../cloth-toolchains/references/windows-unicode-launcher.md), [Unix launchers](../cloth-toolchains/references/unix-unicode-launcher-proof.md#stage-2-installed-distributions) |

## Existing and deferred design records

- The verified class-backed interface slice includes fields, arrays, generic
  holders, narrowing, `Object` operations and static metadata. The interface
  declaration supplies argument defaults before checked dispatch.
  [Stage 7 release verification](../cloth-interfaces/references/stage-7.md) ·
  [Stage 6](../cloth-interfaces/references/stage-6.md) ·
  [Stage 5](../cloth-interfaces/references/stage-5.md) ·
  [Stage 4](../cloth-interfaces/references/stage-4.md) ·
  [Stage 3](../cloth-interfaces/references/stage-3.md) ·
  [Stage 2](../cloth-interfaces/references/stage-2.md) ·
  [Stage 1](../cloth-interfaces/references/stage-1.md) · [Interface skill](../cloth-interfaces/SKILL.md) ·
  [Design record](../cloth-interfaces/references/design.md) ·
  [Implementation plan](../../../todo/interfaces-plan.md)

- Growable arrays and array-backed lists: Stage 0 confirms the
  [language contract](../cloth-arrays/references/growable-arrays.md), including
  explicit growable `T[]`, fixed literal inference, binding-only immutability,
  retained rectangular inner shape, five mutators and temporary receivers.
  The [baseline](../cloth-arrays/references/growable-arrays-stage-0.md) passes
  322 tests across 24 suites on the existing compiler, with Windows x64/x86
  execution and all-nine-profile IR/objects.
  [Stage 1](../cloth-arrays/references/growable-arrays-stage-1.md) implements
  growable typing, separate count/witness facts, mutation receiver plans/effects
  and native whole-array reception with different outer counts.
  [Stage 2](../cloth-arrays/references/growable-arrays-stage-2.md) adds owned
  capacity, private typed relocation/release and executable unsized struct
  array fields with retained nullable witnesses.
  [Stage 3](../cloth-arrays/references/growable-arrays-stage-3.md) executes
  one-dimensional local/parameter/temporary append and reserve with protected
  operands and selected-place relocation.
  [Stage 4](../cloth-arrays/references/growable-arrays-stage-4.md) executes stored
  class/aggregate field growth without whole-field repacking and preserves scoped
  selections across separately emitted methods.
  [Stage 5](../cloth-arrays/references/growable-arrays-stage-5.md) executes insert,
  removeAt and clear with owned shifts, independent removed values and reusable
  capacity. [Stage 6](../cloth-arrays/references/growable-arrays-stage-6.md) executes
  rectangular whole-row mutation, retaining witnesses through empty/all-null/null
  states and preserving providers, output, producers, results and foreach snapshots.
  Embedded-row shape changes remain C3107; the native rank restriction is removed.
  [Stage 7](../cloth-arrays/references/growable-arrays-stage-7.md) proves ordinary
  private-array collection methods and independent struct-slot row elements.
  The user confirmed different-length List array elements, independent copy and
  checked rectangular array export. [Generic Stage 5](../cloth-generics/references/stage-5.md)
  supplies the actual public List module and imported-library tests. Use
  [cloth-collections](../cloth-collections/SKILL.md) for its API and lifetime rules.
  [Stage 8](../cloth-arrays/references/growable-arrays-stage-8.md) owns the final
  array audit, publication and portability evidence, including Linux x64 examples
  alongside native Windows x64/x86 and all-nine-profile objects.
  [Generic Stage 0](../cloth-generics/references/stage-0.md) confirms
  explicit invariant arguments, owner parameter scope, declaration/instance checking,
  nullable substitution, value arguments and private implementation signatures.
  [Generic Stage 1](../cloth-generics/references/stage-1.md) implements class/struct
  parameter syntax, nested applications and lexical/imported name identities.
  [Generic Stage 2](../cloth-generics/references/stage-2.md) adds target-scoped
  closed identities, fields/base links/declared signatures, nullable substitution
  and bounded worklist instantiation.
  [Generic Stage 3](../cloth-generics/references/stage-3.md) checks unknown-parameter
  declarations and closed callable/flow facts using separate source-linked views,
  including public signature privacy. [Generic Stage 4](../cloth-generics/references/stage-4.md)
  executes closed layouts/callables, recursive storage and exact metadata through
  the existing ABI. C4033 retains the symbolic/unresolved guard; C4034 bounds
  canonical metadata expansion without truncating identity/text.
  The [array plan](../../../todo/growable-arrays-and-lists-plan.md)
  records the public delivery gate and its evidence. Bootstrap ownership migration
  stays separate.
  Use [cloth-arrays](../cloth-arrays/SKILL.md) for implemented behavior and
  [cloth-generics](../cloth-generics/SKILL.md) for current generic scope and prerequisites.
  [Generic Stage 6](../cloth-generics/references/stage-6.md) completes the first
  slice's publication, native examples and caller audit. The interface release
  record covers delivered unconstrained generic interfaces. Explicit top-level
  generic functions share the specialization machinery. Constraints, method-owned
  parameters, inference and collection protocols have separate follow-ups.
  [Explicit generic functions](../cloth-functions/references/generic-functions.md) · [Interface release](../cloth-interfaces/references/stage-7.md) ·
  [Generic follow-ups](../../../todo/generic-follow-ups.md)

- Imports and Shuttle: Stage 0 publishes the
  [import contract](../cloth-imports/references/semantics.md),
  [compiler API boundary](../cloth-imports/references/compiler-api.md) and
  [Shuttle manifest/build contract](../shuttle-build/references/semantics.md).
  Use [cloth-imports](../cloth-imports/SKILL.md) for source paths, selected/wildcard
  exports, file scopes and circular imports, and [shuttle-build](../shuttle-build/SKILL.md)
  for TOML, project roots and build orchestration.
  The [plan](../../../todo/shuttle-and-imports-plan.md) stages implementation.
  Stage 1 implements the one-file public request facade and a Shuttle consumer
  fixture; see its [integration evidence](../cloth-imports/references/stage-1.md).
  Stage 2 implements [Shuttle manifests and the resolved project model](../shuttle-build/references/stage-2.md),
  preparing compiler requests without compiling source. Stage 3 implements
  [located import headers](../cloth-imports/references/stage-3.md), with a
  diagnostic preventing unresolved imports from being ignored. Stage 4 adds
  [source graphs and exact file resolution](../cloth-imports/references/stage-4.md),
  retaining circular imports and loaded-source output protection. Stage 5 adds
  [per-file binding and contextual graph semantics](../cloth-imports/references/stage-5.md).
  [Stage 6](../cloth-imports/references/stage-6.md) adds whole-program emission,
  primary-file executable entry selection and exact nominal runtime metadata.
  [Shuttle Stage 6](../shuttle-build/references/stage-6.md) adds packaged build/run
  commands, shared compiler options and Unicode process argument transport.
  [Stage 7](../cloth-imports/references/stage-7.md) verifies the maintained nested
  project, Bootstrap's current scaffold and available native profiles. The user's
  Bootstrap enum comparison also integrates
  [equality operand contexts](references/equality-operand-contexts.md); preserve
  their one-pass evaluation, nominal/class comparison priority and alias effects.

- Basic structs are complete. [Structs.co](../../../examples/Structs.co)
  demonstrates contextual construction, nested writes, independent copies,
  function results and foreach. The [structs skill](../cloth-structs/SKILL.md)
  routes to syntax, typing, layout and function transport. The
  [publication record](../cloth-structs/references/publication.md) records
  portability, regression evidence and the class handoff. The
  [completed struct methods plan](../../../todo/struct-methods-plan.md)
  covers the shared callable pipeline. [StructMethods.co](../../../examples/StructMethods.co)
  exercises ordinary methods and the
  [method publication record](../cloth-functions/references/method-publication.md)
  covers verification and the class handoff. The
  [method contract](../cloth-functions/references/semantics.md#user-defined-methods)
  owns the confirmed rules for `mt` and `self`.

- Basic classes execute with methods, constructors, explicit deletion and
  checked identities. Class fields own their buffers, and aggregate value
  copies preserve class identity.
  [Runnable example](../../../examples/Classes.co) · [Publication and verification](references/class-publication.md) ·
  [Class syntax](references/class-syntax.md) · [Class typing](references/class-typing.md) ·
  [Class execution and lifetime](references/class-lifetime.md) · [Class values](references/class-values.md) · [Class plan](../../../todo/basic-classes-plan.md)

- [Object and operator declarations](references/object-protocol.md) records
  the implicit `Object` root and general operator overloading for classes,
  structs, and enums, including formatting, equality, and instance checks.
  [Class inheritance implementation](references/class-inheritance.md) covers
  the `Object` root, base constructors, upcasts, class tests, and deletion.
  [Operator declarations and resolution](references/operator-declarations.md)
  covers ordinary operator execution, staged syntax, diagnostics, and verification.
  [Class equality and identity](references/class-equality.md) covers class
  comparison dispatch, nullable operands, and raw handle comparison.
  [Class formatting](references/class-formatting.md) covers `operator ::str()`,
  checked dispatch, output, and once-only formatting of array elements.
  [Struct and enum operators](references/value-operators.md) covers explicit
  struct equality and nominal enum cases, payloads, equality, and formatting.
  [Operand contexts and value-target checks](references/operand-contexts.md)
  covers eager binary contexts, `int<auto>` and `float<auto>`, and
  overloads for value-target `is`.
  [Operator publication](references/operator-publication.md) links the runnable
  examples, supported feature records, and release verification.

- Do-while: the [accepted contract](../cloth-control-flow/references/do-while-loops.md)
  owns `do body while (condition)`, semicolon boundaries, scopes, jumps and
  guaranteed first entry. Stages 0-5 integrate syntax, scopes, strict conditions,
  jump resolution, completion/nullable/metadata flow, execution and proven cleanup
  with `DoWhileParsingTest`, `DoWhileSemanticsTest`, `DoWhileFlowTest`,
  `DoWhileTest` and poisoned-memory probes in `ArrayTemporaryCleanupTest`.
  Maintained [DoWhile.co](../../../examples/DoWhile.co) runs on Windows x64/x86;
  it and the nested-loop fixture verify IR/objects for all nine profiles.
  The [completed implementation plan](../../../todo/do-while-loops-plan.md) and
  [Stage 5 record](../cloth-control-flow/references/do-while-loops.md#stage-5-integration-portability-and-publication-2026-11-03)
  retain full-suite evidence and foreign native-execution limits.

- Numeric range membership: `value == start..end`, `start..=end != value`,
  both operand orders, integers/floats and contextual literal widths are
  implemented. The [numeric membership record](../cloth-numerics/references/range-membership.md)
  owns the contract, source invariants and verification; use `RangeMembershipTest`
  and [RangeMembership.co](../../../examples/RangeMembership.co).

- Arbitrary fixed integer widths: [implementation plan](../../../todo/arbitrary-integer-widths-plan.md)
  and [accepted numeric contract](../cloth-numerics/references/semantics.md#arbitrary-fixed-integer-widths-design-confirmed-2026-11-03).
  Signed/unsigned `int<N>` admits 1..65536 with the same cap on
  x86_64 and AArch64; default integers remain 32 bits, and floats remain 16/32/64.
  [Stage 2](../cloth-numerics/references/arbitrary-width-types.md) integrates all
  widths in semantic typing, contextual literals and diagnostics.
  [Stage 3](../cloth-numerics/references/arbitrary-width-arithmetic.md) enables
  source scalar widths 1..64 and production wide operation/conversion kernels.
  [Stage 4](../cloth-numerics/references/arbitrary-width-storage.md) integrates
  source storage, calls, nullable values, arrays, loops and metadata through the
  cap. [Stage 5](../cloth-numerics/references/arbitrary-width-output.md) adds exact
  decimal text through the cap for every output route and retires C4031.
  The [final integration record](../cloth-numerics/references/arbitrary-width-integers.md)
  publishes [IntegerWidths.co](../../../examples/IntegerWidths.co), source
  invariants, completed-stage coverage and full-cap resource measurements.
  Windows x64/x86 and Linux x64 GNU programs execute against independent full-value
  oracles; all nine profiles' objects/assembly pass. Native AArch64/macOS/musl
  execution remains untested.
  The [Stage 1 backend record](../cloth-numerics/references/arbitrary-width-backend.md)
  captures isolated LLVM experiments, the selected small-scalar/wide-limb
  representation and backend evidence. The plan separates fixed/target/native
  widths and stages backend feasibility, typing, arithmetic/casts, storage/calls,
  full-width output and publication. Probe success is not source feature support.
- Numeric literal prefixes and suffixes: [implementation plan](../../../todo/numeric-literals-plan.md).
  Decimal-only `i` / `I` and `f` / `F` select numeric category without fixing
  width; `123.f`, `123f` and `123.0f` are valid float forms, while `123.i` is
  invalid. Hexadecimal/binary/octal integers receive ordinary contextual integer
  types, including usize/isize. Contextual float widths in bindings/assignments/
  returns and integer literal forms in type widths/extents are also confirmed;
  declared widths and range checks remain authoritative. The
  [implementation record](../cloth-numerics/references/numeric-literals.md) covers
  the integrated syntax, shared numeric decoding, strict contexts and verification.
- Integer/float range and array foreach loops use `<-`; the default range step is one and an
  explicit stepper requires a semicolon. The [implementation plan](../../../todo/for-loops-plan.md)
  records the confirmed contract, stages, source ownership and verification.
  Stages 0-5 integrate design, parsing, scopes/strict types/repeated flow, numeric
  ranges, array foreach, conservative lifetimes and publication. Ranges preserve saved
  bounds, default/all explicit updates, finite/progress guards and overflow exits.
  Foreach uses independent snapshots and mutable independent elements; proven
  loop regions reclaim arena/stack temporaries. C3067 is retired. Runnable
  [integer](../../../examples/ForRanges.co), [float](../../../examples/FloatRanges.co)
  and [foreach](../../../examples/ForEach.co) examples execute on Windows x64/x86;
  loop IR/objects verify across all nine supported ABI profiles. All 936 compiler
  tests pass; the agreed loop slice is complete. See the
  [stage record](../cloth-control-flow/references/for-implementation.md).
  Deferred extensions in the plan are not additional confirmed language rules.
- `opt<T>` / `final opt<T>`, omission-only `?:` defaults and named arguments are implemented for
  ordinary functions; use [the feature skill](../cloth-optional-arguments/SKILL.md).
  The [plan](../../../todo/optional-arguments-plan.md) retains future dispatch and
  constructor questions separately from the accepted ordinary-call contract.
- `call().1` result selection: [deferred RFC](../../../todo/result-selection-rfc.md).
  Its one-based selectors are not an accepted contract or scheduled implementation.
- `::addr`, unsafe contexts and raw memory handles:
  [deferred plan](../../../todo/unsafe-memory-plan.md). Saving it does not authorize
  implementation or settle lifetime/native-interface contracts.

## Maintain project knowledge

Current for/while/do-while loops support `break`/`continue` through reusable loop targets,
completion outcomes and crossed-region cleanup. Read the
[loop-control extension](../cloth-control-flow/references/loop-control.md) before
changing loop exits. [While loops](../cloth-control-flow/references/while-loops.md)
implement repeated bool testing and conservative flow/cleanup. Read the
[accepted do-while contract](../cloth-control-flow/references/do-while-loops.md)
and [plan](../../../todo/do-while-loops-plan.md) for the published contract and
execution, independent body/test cleanup, retained publication and completed
Stage 5 example/portability evidence.

Use [feature integration](../cloth-feature-integration/SKILL.md) for every integrated
feature or stage. Keep confirmed decisions in the owning feature's references,
current implementation guidance in its skill, and dated verification evidence in
the development history or a linked focused record. Update routing when ownership
changes. Shared object/state decisions remain in the shared semantics reference.
