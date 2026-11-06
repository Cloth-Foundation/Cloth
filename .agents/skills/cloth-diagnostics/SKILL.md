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

**C0014 / OUTPUT_OVERWRITES_SOURCE** rejects a CLI output path that identifies
the input file. The check uses the final target-specific filename and covers
filesystem aliases such as hard links. `DiagnosticsTest` covers object output,
an extensionless executable, and a Windows executable with an appended suffix.

**C2007 / FUNCTION_IN_TYPE** rejects `fn` inside a parsed struct body at its
keyword. **C3077 / UNSUPPORTED_STRUCTS** reports struct syntax sent to the
body-only snippet analyzer. C3078–C3082 diagnose duplicate
types, invalid fields, recursive dependencies, invalid literals and invalid
stored targets. C3052 rejects inline structs exceeding the target addressable
size. The [struct storage record](../cloth-structs/references/local-storage.md)
and [Stage 4](../cloth-structs/references/function-integration.md) link fail-fast,
CLI and native verification.

**C2008 / METHOD_OUTSIDE_TYPE** locates an `mt` outside a type member list.
**C3083 / DUPLICATE_METHOD** labels matching signatures in one nominal owner.
**C3084 / RECEIVER_OUTSIDE_METHOD** locates a use of `self` outside method scope.
**C3095 / INVALID_ENUM_MEMBER** locates invalid enum cases, payload
declarations, and payload access. [Struct and enum operators](../cloth-language/references/value-operators.md)
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
