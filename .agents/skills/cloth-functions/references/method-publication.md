# Struct method publication and class handoff

Stage 5 completed on 2026-11-05. The maintained
[StructMethods.co](../../../../examples/StructMethods.co) example demonstrates
a field and method with the same name, implicit receiver lookup, explicit
`self` when a parameter shadows a field, named and defaulted arguments,
mutation through a fixed binding, independent ordered results and a call
on a temporary. Run it from the repository root.

~~~powershell
.\gradlew.bat :Compiler:runExample --args="examples/StructMethods.co build/struct-methods/StructMethods"
~~~

The program exits with status zero and prints the following lines before
the usual blank line and `PEC: 0`.

~~~text
Saved main: 12
Current updated!: 17
Temporary result: 4
~~~

## Compiler coverage

- The [method contract](semantics.md#user-defined-methods) owns the accepted
  declaration and receiver rules.
- [Syntax and scope](method-syntax.md) owns nominal method groups, source
  spans and lexical-first resolution.
- [Method typing](method-typing.md) owns shared overload and result checks,
  read-only defaults, stored receiver plans and nullable flow.
- [Method execution](method-execution.md) owns the private callable ABI,
  receiver arena and result ownership.

This slice implements ordinary `mt` members of named structs. Class,
interface, enum and state dispatch, construction and object lifetime remain
separate work. Source methods have a private LLVM ABI.

## Verification

`StructMethodPublicationTest` checks the maintained source's exact output
and zero exit status on Windows x64/MSVC and x86/MSVC. It verifies executable
LLVM IR and emits nonempty objects across all nine target profiles. Its
AArch64 GNU Linux CLI case emits an object with an unavailable linker
argument, exercising linker-free object mode.

The same source executed as a GNU Linux x64 executable under Ubuntu WSL,
with exact output and zero exit status. Cloth emitted executable IR with
its usual entry adapter. LLVM emitted the GNU x64 object and the installed
`cc -lm` linked it. Windows ARM64, Linux musl and AArch64, and macOS coverage
consists of verified IR and emitted objects.

The focused selection passed 572 tests across 46 suites.
The full compiler run passed 1,357 tests across 115 suites.
Both ran on Windows with zero failures, errors or skips.

~~~powershell
.\gradlew.bat :Compiler:test --tests 'cloth.Struct*Test' --tests cloth.ArrayTemporaryCleanupTest --tests cloth.ArrayCopyElisionTest --tests cloth.ArrayAssignmentTest --tests 'cloth.Function*Test' --tests cloth.StringFunctionTest --tests 'cloth.OptionalArguments*Test' --tests 'cloth.MultiReturn*Test' --tests cloth.DestructuringTest --tests 'cloth.Nullability*Test' --tests cloth.NullableBuffersTest --tests cloth.NullableRowsTest --tests cloth.ForEachTest --tests cloth.LoopControlTest --tests cloth.DoWhileTest --tests cloth.ProgramEntryTest --tests cloth.BuiltinFunctionsTest --tests 'cloth.*AttachmentsTest' --offline --console=plain --no-daemon
.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon
~~~

## Handoff to classes

Reuse the callable declaration, signature and explicit argument pipeline,
including optional defaults and ordered results. Class lookup and dispatch
will supply a receiver plan with object identity and lifetime rules. The
existing struct receiver plan selects stored value addresses and carries
the arena for published buffers. Class work needs to decide how aliases
become invalid after deletion, including when held in struct fields or
arrays. See the [shared object lifetime contract](../../cloth-language/references/semantics.md#object-lifetime-and-nullability).
