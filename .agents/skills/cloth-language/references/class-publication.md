# Basic class publication

Stage 5 completed on 2026-11-05. The maintained example exercises zero-argument construction, named
arguments to a secondary constructor, ordinary methods, identity in struct
and foreach copies, explicit deletion, and a buffered snapshot that survives
deletion. [Classes.co](../../../../examples/Classes.co)

Run it from the repository root.

```powershell
.\gradlew.bat :Compiler:runExample --args="examples/Classes.co build/class-publication/Classes"
```

The executable exits with status zero and prints the following output.

```text
Current updated: 17
Deleted updated at 17
Deleted idle at 2
Saved main: 10

PEC: 0
```

## Compiler coverage

- The declaration and call model resolves nominal class types, class-private
  members, constructor overloads, delegation and initialization order.
  [Class syntax](class-syntax.md) · [Class typing](class-typing.md)
- Explicit allocation and deletion use checked slot-and-generation identities.
  Destructor access is carried only by `self` and calls on that receiver.
  The compiler diagnoses provably invalid accesses and checks the others when
  they execute. [Class lifetime](class-lifetime.md)
- Aggregate copies preserve object identity. Class fields own their recursive
  value buffers, and class-backed nested writes and struct receivers replay
  captured paths against current storage after source effects. [Class values](class-values.md)

## Verification

The focused Windows run passed 379 tests across 36 suites with zero failures,
errors or skips. The full Windows suite passed 1,389 tests across 120 suites
in 5m 43s with zero failures, errors or skips. The maintained source ran through the normal `runExample`
task and in the publication suite on Windows x64 and x86. The suite verifies
executable LLVM IR and emits objects for all nine profiles. Its AArch64 GNU
Linux CLI case requests object output with an unavailable linker argument.
[ClassPublicationTest](../../../../Compiler/src/test/kotlin/cloth/ClassPublicationTest.kt)

The same source also ran on GNU Linux x64 under Ubuntu WSL with exact output
and status zero. Cloth emitted executable IR with its usual entry adapter and
generated an object from that IR. Ubuntu `cc -lm` linked the object. Windows
ARM64, Linux AArch64 and musl, and macOS coverage consists of IR and object
emission.

```powershell
.\gradlew.bat :Compiler:test --tests 'cloth.Class*Test' --tests 'cloth.Struct*Test' --tests 'cloth.Function*Test' --tests 'cloth.Nullable*Test' --tests 'cloth.OptionalArguments*Test' --tests 'cloth.MultiReturn*Test' --tests cloth.ArrayTemporaryCleanupTest --tests cloth.ForEachTest --tests cloth.TargetLayoutTest --offline --console=plain --no-daemon
.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon
```

### Audit follow-up on 2026-11-05

The full Windows suite passed 1,394 tests across 120 suites with zero failures,
errors or skips using the command above. New native host cases cover early exit
from nested class-field writes and ordered result reception, independent header
parameter and field storage, and a loop with both live and deleted exits. The
semantic cases cover deletion on break and continue paths in non-repeating
do-while loops. The CLI cases verify C0014 and source preservation for object
output, extensionless executables and a hard link at the final Windows filename.
[Class values tests](../../../../Compiler/src/test/kotlin/cloth/ClassValueIntegrationTest.kt) ·
[Lifetime tests](../../../../Compiler/src/test/kotlin/cloth/ClassLifetimeTest.kt) ·
[Diagnostics tests](../../../../Compiler/src/test/kotlin/cloth/DiagnosticsTest.kt)

## Handoff to inheritance and states

Basic classes use direct method calls and a private LLVM representation.
Class declarations retain `final` for the future inheritance check.
Inheritance enforcement, interface and virtual dispatch, and state activation
are separate implementation slices. Keep object identity and lifetime checks
through future receiver conversions. Apply the established initial-state
ordering after the constructor chain finishes. [Class plan](../../../../todo/basic-classes-plan.md) ·
[Construction and initial state](semantics.md#initial-state-and-construction)
