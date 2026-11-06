# Object and operator publication

[ObjectOperators.co](../../../../examples/ObjectOperators.co) covers base
bindings, built-in type narrowing, value-target `is`, logical equality,
identity, inherited class formatting, nullable array formatting, and deletion
through `Object`. It also shows explicit struct equality and the difference
between built-in enum case equality and a typed enum overload.

[OperandContexts.co](../../../../examples/OperandContexts.co) covers full
overloads with either receiver position and a struct result. It also shows
`int<auto>`, `float<auto>`, and string contexts on a class.

Run the examples from the repository root.

```powershell
.\gradlew.bat :Compiler:runExample --args="examples/ObjectOperators.co build/operators/ObjectOperators"
.\gradlew.bat :Compiler:runExample --args="examples/OperandContexts.co build/operators/OperandContexts"
```

`ObjectOperators.co` prints the following output and exits with status 0.

```text
Static type: Object
Account ID: 7
Account 7
Equal: true, same instance: false
Alias: true, value check: true
[Account 7, null, Account 7]
Scores equal: true
damage 1, same case as HIT: false
Priorities equal: true
Closed account 7
Closed account 7

PEC: 0
```

`OperandContexts.co` prints the following output and exits with status 0.

```text
Scores: 22, 17
Points: 16, 15
Health: 102.5
Player: Rin

PEC: 0
```

## Source contracts and ownership

| Feature | Contract and verification |
| --- | --- |
| `Object`, inheritance, and checked class access | [Class inheritance](class-inheritance.md) and [class lifetime](class-lifetime.md) |
| Declaration syntax, full overload ranking, and operand order | [Operator declarations](operator-declarations.md) |
| `==`, `!=`, and `===` on classes | [Class equality and identity](class-equality.md) |
| Class `::str`, printing, and interpolation | [Class formatting](class-formatting.md) |
| Struct equality, enum cases and payloads, and enum formatting | [Struct and enum operators](value-operators.md) |
| Context selectors, captured widths, and value-target `is` | [Operand contexts](operand-contexts.md) |

The [family inventory](operand-contexts.md#operator-families) tracks contracts
still needed for equality contexts, state targets, conditional evaluation, and
writable operands. [Enum constructors and methods](value-operators.md#enum-values-and-formatting)
also have a separate implementation boundary.

## Verification

Stage 7 completed on 2026-11-06. Both examples ran through `runExample` on
Windows x64. The publication suite passed all three tests, running both
examples on Windows x64 and x86 with the exact output above and exit status 0.
It verified executable LLVM IR and emitted an object per example on all nine
supported target profiles. Windows ARM64, GNU Linux, musl Linux, and macOS
coverage for these examples consists of IR and object emission.

The full compiler suite passed 1,432 tests in 127 suites with zero failures,
errors, or skips in 6m 8s. The run used a fresh Compiler build directory and
an isolated Gradle project cache.

```powershell
.\gradlew.bat :Compiler:test --tests cloth.OperatorPublicationTest '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process' --offline --no-build-cache --console=plain --no-daemon
.\gradlew.bat :Compiler:test '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process' --offline --no-build-cache --console=plain --no-daemon
```

[OperatorPublicationTest](../../../../Compiler/src/test/kotlin/cloth/OperatorPublicationTest.kt)
owns exact native output and exit status for both examples, plus LLVM IR and
object emission for each supported target. Its CLI case supplies two equally
ranked full overloads, a matching context, and a later independent error.
The compiler exits with status 1 after one C3094 diagnostic and before
creating any output or invoking the linker.

[ClassEqualityTest](../../../../Compiler/src/test/kotlin/cloth/ClassEqualityTest.kt)
and [ClassFormattingTest](../../../../Compiler/src/test/kotlin/cloth/ClassFormattingTest.kt)
cover status 1 for stale checked identities. The source diagnostics
[C3093 and C3094](operator-declarations.md#implementation-and-diagnostics)
cover invalid declarations and failed overload resolution. C3095 covers invalid
enum cases, payload declarations, and payload access.
[Diagnostic skill](../../cloth-diagnostics/SKILL.md)
