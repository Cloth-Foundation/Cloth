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
see [loop control](../cloth-control-flow/references/loop-control.md).

**C3014 / NON_BOOLEAN_CONDITION** also locates non-bool while conditions and
names the while keyword. `WhileTest` covers strict conditions and skipped-body
errors; runtime condition failures preserve location and suppress later effects.

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
