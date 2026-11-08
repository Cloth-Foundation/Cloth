---
name: cloth-diagnostics
description: Design or extend Cloth compiler diagnostics, stable error codes, structured source labels, span propagation, CLI failure statuses, or fail-fast runtime error paths.
---

# Cloth diagnostics

Read [confirmed diagnostic semantics](references/semantics.md). Cloth stops on
the first detected error. Never add recovery, diagnostic accumulation, or a
multi-error reporter. Multiple labels may explain that same error.

## Development rules and implemented boundaries

- Use shared structured diagnostic definitions with stable codes and original
  source spans. Preserve intrinsic errors during overload probing rather than
  replacing them with an unrelated generic no-match diagnostic.
- Keep source snapshots with tokens/nodes. Internal positions are one-based UTF-16
  with exclusive ends; Yvette ranges are zero-based. Preserve CRLF, EOF/empty
  final lines, interpolation spans, and four-column tab stops.
- Pass facts and locations to diagnostic definitions instead of assembling raw
  JVM exception messages at checks. Declaration labels help explain duplicate,
  reassignment and immutable-binding errors without implying separate errors.
- Follow the shared [canonical spelling rule](../cloth-language/SKILL.md#canonical-operator-and-keyword-spellings):
  render Cloth operators and keywords through their enum `.toString()` methods,
  including within diagnostic prose; enum interpolation also calls `.toString()`.
- Diagnose unsupported source behavior in the earliest appropriate checker before
  output/linker work. Existing stage-specific APIs still retain their boundaries;
  target-dependent checks may need target selection before validation.
- JVM diagnostic rendering and generated native runtime errors are separate paths.
  Expected compiler failures return 1; internal failures return 2. `--debug` may
  reveal retained causes. Terminal theme/encoding detection chooses Unicode or
  readable ASCII drawing. Existing wide/combining display-width limits remain.
- Runtime guards preserve once-only operand effects, source locations and the
  first executed failure. Do not print ordinary PEC completion after fatal exit.

## Source and verification

**C3107 / INVALID_ARRAY_MUTATION** rejects fixed receivers and shape-changing
embedded-row operations. **C4032 / UNSUPPORTED_ARRAY_MUTATION** guards otherwise
typed public array mutators before output/linker work. Stage 2 retires its unsized
struct array field boundary after implementing retained-shape storage. Defaults, lost nullable
receiver proofs and provably deleted field owners retain C3066/C3062/C3092.
[Contract and verification](../cloth-arrays/references/growable-arrays-stage-1.md)
[Owned storage and fault probes](../cloth-arrays/references/growable-arrays-stage-2.md)

**C0014 / OUTPUT_OVERWRITES_SOURCE** rejects a CLI output path that identifies
any loaded input source. The emission path checks the final target-specific filename and covers
filesystem aliases such as hard links. `DiagnosticsTest` covers object output,
an extensionless executable, and a Windows executable with an appended suffix.
Stage 4 adds transitive graph/source identity checks. Imported executable
requests conservatively check all possible final
suffixes before ABI selection. [Graph verification](../cloth-imports/references/stage-4.md)

**C0015 / RUN_REQUIRES_EXECUTABLE** rejects `run --emit object` before reading
source or creating output. **C0016 / PROGRAM_LAUNCH_FAILED** reports a failure
to launch the emitted artifact or an interrupted wait. Both return 1. A child
that completes supplies its own status. [Build and run commands](../cloth-toolchains/SKILL.md#build-and-run-commands)

**C0017 / INVALID_COMPILATION_REQUEST** rejects invalid public request paths,
entry/output combinations and module IDs/physical roots before source work,
including multiple standard-library roles.
The public facade preserves original compiler diagnostics, maps source/output
I/O to C0010/C0013 with causes, and performs no reporting or process launch.
[Request tests](../../../Compiler/src/test/kotlin/cloth/CompilationRequestTest.kt) ·
[API integration](../cloth-imports/references/stage-1.md)

Bundled-library [Stage 2](../cloth-toolchains/references/standard-library-stage-2.md)
adds **C0018 / INVALID_STANDARD_LIBRARY** for default context, structure or metadata
failures, **C0019 / STANDARD_LIBRARY_MODULE_CONFLICT** for an occupied implicit ID,
and **C0020 / OUTPUT_OVERWRITES_TOOLCHAIN_INPUT** for selected metadata/marker
output aliases. Both commands render the original C error once; missing defaults
fail before source/project loading, even without library imports.
[Selection Stage 3](../cloth-toolchains/references/standard-library-stage-3.md)
extends C0018 to invalid explicit roots without fallback. Conflicting selection
flags or a value attached to `--no-stdlib` use C0003; missing/blank override paths
use C0002 and invalid path syntax uses C0012. Overrides/disable bypass default
checks; reached incompatible code retains its original source diagnostic.
[Bundling Stage 5](../cloth-toolchains/references/standard-library-stage-5.md)
rechecks these failures through both packaged launchers alongside reserved-path,
input-alias, explicit-import and ordinary-ambiguity diagnostics. Each expected
failure still stops before output/launch and renders its original C code once.

**C2009 / IMPORT_OUTSIDE_HEADER** rejects imports after declarations and at
function-statement boundaries. Imports in type bodies are rejected by their
existing located grammar checks. **C2010 / DUPLICATE_IMPORT_SELECTION** labels
the repeated selector and its original occurrence as one syntax diagnostic.
**C3096 / IMPORTS_REQUIRE_SOURCE_GRAPH** prevents direct single-unit semantic
adapters from silently ignoring parsed imports. Stage 4's driver discovers
the graph instead: **C3097 / MISSING_IMPORT_CONTEXT**, **C3098 /
IMPORTED_SOURCE_NOT_FOUND**, **C3099 / AMBIGUOUS_IMPORT_SOURCE** and **C3100 /
IMPORT_SOURCE_ACCESS** locate discovery failures at the original import path.
Ambiguity lists matching module/file candidates before parsing them or selecting
symbols. Imported read/UTF-8 errors retain C0010, import context and their cause;
syntax failures retain the imported source's original code/snapshot/location.
C3097 applies to explicit requests without roots and standalone `--no-stdlib`
imports. The standalone command otherwise supplies its selected library root,
so missing project files use C3098; it still
does not infer project roots from source folders or discover manifests in Compiler.
**C3101 is retired** after Stage 5. **C3102 / IMPORT_NAME_NOT_FOUND** locates a
selector absent from the target file's own exports. **C3103 / IMPORT_PRIVATE_NAME**
labels the selector and private definition. **C3104 / IMPORT_NAME_CONFLICT** labels
the conflicting import, existing/imported definitions and earlier import when
applicable. Bindings reuse original symbols; repeated identities are idempotent.
**C3105 is retired** after Stage 6 whole-program emission. Other
malformed import grammar retains C2000/C2001 and exact EOF positions.
**C3106 / RESERVED_STANDARD_LIBRARY_PATH** locates a reached cloth import supplied
by a regular root while a marked library is selected, with the selected root and
conflicting candidate paths. It precedes parsing those candidate sources, including
when the library lacks that file. Unmarked requests retain ordinary ambiguity.
[Import diagnostics](../../../Compiler/src/main/kotlin/cloth/diagnostics/ImportErrors.kt) ·
[Stage 3 verification](../cloth-imports/references/stage-3.md)

The [Stage 5 record](../cloth-imports/references/stage-5.md) covers defining-file
contexts and graph-wide checking. Binding diagnostics precede ABI selection;
type/body errors keep their original code and source. [Stage 6](../cloth-imports/references/stage-6.md)
emits checked graphs without an intermediate diagnostic gate. DiagnosticReporter
renders the common Yvette Diagnostic base so Shuttle can report its S codes and
propagate original C diagnostics through the same renderer. Shuttle command and
launch codes/statuses are owned by its [Stage 6 record](../shuttle-build/references/stage-6.md).

**C4030 / UNSUPPORTED_FUNCTION_ABI** identifies the rejected type at the
parameter or result annotation. `EmitFunctions.validate` owns the callable ABI
capability check. Executable emission checks the target and callable signatures
before linker discovery.
[ABI validation](../../../Compiler/src/main/kotlin/cloth/emitter/callables/EmitFunctions.kt) ·
[Diagnostic](../../../Compiler/src/main/kotlin/cloth/diagnostics/BackendErrors.kt)

**C2007 / FUNCTION_IN_TYPE** rejects `fn` inside a parsed struct body at its
keyword. C3078–C3082 diagnose duplicate
types, invalid fields, recursive dependencies, invalid literals and invalid
stored targets. C3052 rejects inline structs exceeding the target addressable
size. The [struct storage record](../cloth-structs/references/local-storage.md)
and [Stage 4](../cloth-structs/references/function-integration.md) link fail-fast,
CLI and native verification.

**C3020 / UNKNOWN_NAME** reports unresolved names in function bodies. C3000,
C3060, C3077 and C3086 are reserved after removal of the snippet checker.
[Migration record](../cloth-language/references/development.md#production-path-fixture-migration-2026-11-06)

**C2008 / METHOD_OUTSIDE_TYPE** locates an `mt` outside a type member list.
**C3083 / DUPLICATE_METHOD** labels matching signatures in one nominal owner.
**C3084 / RECEIVER_OUTSIDE_METHOD** locates a use of `self` outside method scope.
**C3095 / INVALID_ENUM_MEMBER** locates invalid enum cases, payload
declarations, and payload access. Payload field-type mismatches use this code
for single and multiple cases and for header defaults, with original value,
field annotation and applicable case labels. `EnumPayloadTyping` checks defaults
then supplied case values through the shared checker before generated reader
bodies; intrinsic literal/cast/array/struct failures retain their own codes.
`EnumPayloadDiagnosticsTest` covers source labels, first-error ordering and CLI
failure before output/linker work across all nine target selections.
[Struct and enum operators](../cloth-language/references/value-operators.md) ·
[Diagnostic integration](../cloth-language/references/development.md#enum-payload-diagnostic-preflight-2026-11-06)

**C3005 / INVALID_CAST** also rejects an enum payload's closed numeric conversion
when its rounded source is non-finite or its truncated integer result is out of
range. It identifies the first failing cast inside nested payload/default values
with original cast, field annotation and applicable case labels. Numeric constant
validation preserves wrapping integer casts and skips absent nullable payloads.
`EnumConstantExpressionsTest` covers first-error source labels and CLI rejection
before output/linker work for all nine target selections.
[Constant-cast integration](../cloth-language/references/development.md#enum-constant-cast-validation-2026-11-07)

C3062 rejects lost receiver presence proofs before emission. C3085 was
retired when [Stage 4 method execution](../cloth-functions/references/method-execution.md)
was integrated. Runtime bounds and field shape guards still stop at the
first executed failure.

**C3075 / FLOAT_SIGNUM_NAN** and **C3076 / INVALID_FLOAT_CLAMP_BOUNDS** reject
closed NaN signum and NaN/reversed float bounds. Dynamic cases validate after
ordered operand evaluation with original call locations and fatal exit without
PEC. `FloatAttachmentsTest` covers all widths, skipped-code/overload intrinsic
errors and CLI first-error rejection before object/linker work. See the
[float attachment record](../cloth-numerics/references/float-attachments.md).

**C3072 / INTEGER_ABS_OVERFLOW**, **C3073 / INVALID_CLAMP_BOUNDS** and
**C3074 / INVALID_INTEGER_RADIX** reject closed invalid integer-member operations.
Dynamic equivalents guard evaluated operands with original call locations and
fatal exit without PEC. `IntegerAttachmentsTest` covers skipped-code/overload
intrinsic errors and first-error CLI failure before object/linker work; see the
[integer attachment record](../cloth-numerics/references/integer-attachments.md).

[String editing](../cloth-strings/references/editing-methods.md) checks output
growth using target byte limits before allocation/replacement-payload reads,
retaining call source locations in controlled fatal runtime errors. `BufferCountsTest`
executes synthetic all/first/empty replacement overflow guards on Windows x64/x86.
`StringEditingTest` preserves first-error argument C3049 before output/linker work.

**C3071 / UNKNOWN_MEMBER** locates an unavailable ordinary value member at its
method name. String attachments reuse C3023 for explicit argument arity, C3049
for argument types and C3065 for unsupported named arguments. Preserve intrinsic
operand/member diagnostics during enclosing overload probes. `StringAttachmentsTest`
checks source labels and first-error CLI failure before output/linker work; see
[the member-call record](../cloth-strings/references/member-calls.md).
Split's exact char/string overload group reports both accepted types in C3049,
retaining ordinary arity/label priority, original argument spans and intrinsic
errors inside outer overload trials. `StringSplitTest` checks first-error CLI
rejection and both selected signatures; see [split](../cloth-strings/references/split-method.md).

**C3070 / INVALID_RANGE_MEMBERSHIP** rejects nonnumeric or incompatible typed
operands, standalone/stored range values, range-to-range comparisons and known
nonfinite float endpoints. Contextual literal overflow retains C3004/C3028.
`RangeMembershipTest` covers located diagnostics, first-error CLI exit 1 before
object/linker work, and dynamic endpoint failures preserving source order.
Membership owns its numeric context; preserve its original C3070/C3004/C3028
errors inside source-call argument probes instead of replacing them with C3024.
See the [membership record](../cloth-numerics/references/range-membership.md).

**C4031 is retired** after [Stage 5 integer text](../cloth-numerics/references/arbitrary-width-output.md).
Its temporary integer/type/text preflight is removed; do not reassign this code.
Source values and exact decimal output support widths 1..65536. C3011 rejects widths outside the
interval, with a compact range message; floating widths retain their finite set.
`ArbitraryIntegerWidthsTest` covers original labels, first-error CLI status and
all emission entry points and successful wide text/CLI object emission. The
[Stage 2 record](../cloth-numerics/references/arbitrary-width-types.md) retains the historical gate.
Stage 3 scalar and wide operation guards retain controlled runtime exit and
source filename/span for invalid division/remainder, powers and numeric casts.

**C1013 / INVALID_NUMERIC_LITERAL** locates malformed literal prefixes, digits
and suffixes, including `123.i` and forbidden suffixes on base-prefixed integers.
Separators retain C1002; contextual integer/float range errors retain C3004/C3028.
`NumericLiteralsTest` and `NumericLiteralsIntegrationTest` cover original spellings,
spans, first-error CLI exit 1 and failure before object/linker work. See the
[literal implementation record](../cloth-numerics/references/numeric-literals.md).

**C3069 / INVALID_LOOP_JUMP** locates a break/continue without an enclosing loop
body, including attempts to cross a deferred-block boundary. `LoopControlTest`
checks placement, first-error priority and CLI failure before object/linker work;
`DoWhileSemanticsTest` adds do-body/deferred boundaries and trailing conditions
whose jumps resolve only to an enclosing body. See [loop control](../cloth-control-flow/references/loop-control.md).

**C3014 / NON_BOOLEAN_CONDITION** also locates non-bool while and trailing
do-while conditions, naming the while keyword through its enum spelling.
`DoWhileSemanticsTest` covers body-before-condition checking, unreachable tests
and intrinsic diagnostics preserved in call probes. `DoWhileTest` verifies
located body/test runtime failures suppress later effects and process-completion
output. `WhileTest` covers strict conditions and skipped-body errors; implemented
while runtime failures preserve location and suppress later effects.

Start with [ClothDiagnostic](../../../Compiler/src/main/kotlin/cloth/diagnostics/ClothDiagnostic.kt),
[DiagnosticCode](../../../Compiler/src/main/kotlin/cloth/diagnostics/DiagnosticCode.kt),
[DiagnosticReporter](../../../Compiler/src/main/kotlin/cloth/diagnostics/DiagnosticReporter.kt),
[SourceFile](../../../Compiler/src/main/kotlin/cloth/diagnostics/SourceFile.kt),
[Entry](../../../Compiler/src/main/kotlin/cloth/Entry.kt), and
[EmitRuntimeError](../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitRuntimeError.kt).

Use `DiagnosticsTest` and the relevant feature's CLI/error tests. Check located
codes, labels, first-error termination, encoding fallback, debug causes, and
whether failure happens before outputs are created. Runtime cases must execute
the guard to verify effect/failure order. See
[development history](../cloth-language/references/development.md#earlier-milestone-snapshots)
for recorded renderer integration evidence; stable passing historical tests do
not establish a new feature's diagnostic coverage.
