---
name: cloth-program-entry
description: Design, explain, or extend Cloth executable main validation, Unicode command-line startup, process exit-code normalization, or PEC completion output across supported targets.
---

# Cloth program entry

[Arbitrary integer Stage 4](../cloth-numerics/references/arbitrary-width-storage.md)
integrates signed fixed main results through 65536 bits. Startup supplies a result
arena when required, reads the low native C-int bits before releasing it, and
preserves supplied optional arguments, PEC timing and cleanup. Unsigned fixed
integers/usize remain invalid main results; defaults and floating formats do not change.
Use the [final integer record](../cloth-numerics/references/arbitrary-width-integers.md)
for completed-stage routing and target evidence; native wide-main normalization
coverage is Windows x64/x86, with additional integrated program execution on
Linux x64 GNU and object/assembly evidence across all supported profiles.

Read [confirmed entry semantics](references/semantics.md) and the shared
[portability contract](../cloth-language/references/semantics.md#portability).
Use [toolchains](../cloth-toolchains/SKILL.md) for linking and object mode and
[output](../cloth-output/SKILL.md) for explicit print/println calls.

For multi-file/project entry selection, read the
[Stage 0 API contract](../cloth-imports/references/compiler-api.md#entry-selection).
It selects main from the primary file and keeps other files' mains ordinary
callables. [Import Stage 6](../cloth-imports/references/stage-6.md) implements this
policy over a complete checked source graph, preserving the startup rules below.

## Development rules

- File scope accepts declarations. Executable validation requires exactly one
  own `main` in the primary file, accepting no parameters or one `string[]` or `string[]?`. Startup always
  supplies a present array, including for `opt<string[]?> args ?: null`.
  Valid results are void,
  signed fixed-width integers, or isize; all unsigned main results are rejected.
- Separate library/function checking from executable entry validation. Object
  emission needs no main and emits no startup adapter or normal completion report.
- Convert native arguments to well-formed UTF-8 without normalization/replacement;
  preserve order and empty values, excluding the executable name. Validate in
  order before either form of Cloth main; reject the first invalid argument.
- Windows uses wide UTF-16 startup; Linux/macOS validate native UTF-8. Keep each
  platform adapter behind the shared Cloth interface. Startup owns its buffers;
  cleanup and copies must preserve returned/captured array/string lifetimes.
- Use the declared callable's layout for startup's call to Cloth `main`. Supply
  its converted arguments and any caller-owned result arena through the shared
  packer. The startup adapter owns the native entry signature and cleanup.
  [Callable layout](../../../Compiler/src/main/kotlin/cloth/emitter/callables/EmittedCallable.kt)
- Normalize main's result to native C int using sign extension or low-bit truncation.
  Void completion is zero. Emit a newline, `PEC: N`, and a newline after the
  entry call returns, then return that same normalized status. An ordinary Cloth
  call to main does not print a second completion report. Fatal startup/runtime
  failure before normal completion prints no PEC message.

## Source, evidence, and limits

Inspect [ProgramAnalyzer](../../../Compiler/src/main/kotlin/cloth/semantic/ProgramAnalyzer.kt),
[EmitEntryPoint](../../../Compiler/src/main/kotlin/cloth/emitter/callables/EmitEntryPoint.kt),
[EmitArguments](../../../Compiler/src/main/kotlin/cloth/emitter/callables/EmitArguments.kt),
and [NativeCABI](../../../Compiler/src/main/kotlin/cloth/emitter/NativeCABI.kt).

Relevant suites: `ProgramEntryTest`, `UnicodeArgumentsTest`, `WindowsX86Test`,
`ISizeIntegrationTest`, `NullableBuffersTest`, and the `ProcessCompletion` test helper. Existing tests
exercise invalid encodings, no-argument main, quoting/empty inputs, native exit
normalization, completion timing, link variants and startup storage cleanup.

Read the [Unicode startup record](../../../todo/unicode-command-line-arguments.md)
and [milestone evidence](../cloth-language/references/development.md#unicode-command-line-startup-2026-10-31).
Windows x64/x86 execution and the recorded Linux startup harness evidence have
different scopes; object/IR checks alone do not prove macOS native execution.
Operating-system interpretation of process status remains platform-specific.
