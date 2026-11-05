# Boolean attachments

Integrated 2026-11-04. The [confirmed contract](semantics.md#boolean-attachments-design-confirmed-2026-11-04)
adds only `bool.toInt()`: no explicit arguments, signed int32 result, false to 0
and true to 1. Maintained example:
[BoolAttachments.co](../../../../examples/BoolAttachments.co).

## Source and invariants

- [BoolAttachments](../../../../Compiler/src/main/kotlin/cloth/builtin/attachments/BoolAttachments.kt)
  is a separate provider extending `PrimitiveAttachment(ValueType.BOOL)`. Its
  named `TO_INT` value uses the same inherited
  `valueMethod(name, parameters, returns) { context, values -> ... }` declaration
  style as strings, integers and floats. The declaration registers itself;
  no additional method list, operation enum or source-name mapping is needed.
- [PrimitiveAttachments](../../../../Compiler/src/main/kotlin/cloth/builtin/attachments/PrimitiveAttachment.kt)
  owns one target-independent `BOOL` instance and includes its entry in the
  read-only `all(target)` catalog. Boolean lookup is exact; numeric/string
  receivers do not acquire `toInt`. Definitions retain no LLVM state.
- [BuiltinEmissionContext.boolToInt](../../../../Compiler/src/main/kotlin/cloth/emitter/types/BuiltinEmissionContext.kt)
  zero-extends the existing i1 value to `ValueType.INT`'s i32. Sign extension
  would turn true into -1 and is incorrect. The result type does not depend on
  target pointer width or a receiving numeric context. Wider/unsigned results
  require ordinary explicit casts after the method call.
- Existing member typing and emission preserve once-only receiver evaluation,
  early exits, skipped execution, original diagnostic locations and intrinsic
  member errors during enclosing source-overload probes. Nullable receivers
  need proof or a non-null fallback. Bool remains nonnumeric: existing implicit
  arithmetic and ordinary bool/numeric cast rejections are preserved.
- The shared read-only, independent-result contract is valid for this scalar
  conversion: it allocates nothing and neither retains nor publishes operands.
  Array elements, deferred reads, ordered results and existing temporary cleanup
  use the ordinary value/member paths. No new lifetime mechanism or runtime guard
  is needed, and no new diagnostic code is introduced.

## Verification

`BoolAttachmentsTest` checks the single registration/signature and read-only
contract; native true/false values loaded from bindings, calls and arrays; scores,
independent received integers, captured deferred reads, lexical-name separation,
nullable presence/fallbacks, ordered results and chained integer methods.
Receiver effects occur once, while short circuits and early returns skip calls.
While/do-while conditions and foreach scalar reception execute through existing
flow paths. Strict arity/namespaces/result identities and first-error CLI failures
are located and stop before object/linker work. A snippet check uses the same API.

The maintained example and source integration fixtures execute on Windows
x64/MSVC and x86/MSVC. Parameterized bool-to-int IR contains zero extension;
it and the maintained example emit nonempty objects on all nine supported
profiles. Native Linux/macOS/ARM64 execution is untested for this feature.

```powershell
.\gradlew.bat :Compiler:test --tests cloth.BoolAttachmentsTest --tests cloth.FloatAttachmentsTest --tests cloth.IntegerAttachmentsTest --tests cloth.StringAttachmentsTest --tests cloth.StringMemberStorageTest --tests cloth.ArrayTemporaryCleanupTest --tests 'cloth.Nullability*' --offline --console=plain --no-daemon
```

Passed 140 tests across 10 suites in 52 seconds, zero failures/errors/skips.
The previous 1,269-test full run predates this addition and was not repeated.
See the [development milestone](../../cloth-language/references/development.md#boolean-attachment-2026-11-04).
Changed skill metadata, relative links and whitespace pass direct checks. The
optional Python validator remains unavailable because PyYAML is missing.

Static/type calls, named/default method arguments, method values, additional bool
methods and a general boolean/numeric conversion policy remain outside this slice.
