# Basic structs publication and class handoff

Stage 5 completed on 2026-11-05. The [struct contract](semantics.md) owns the
source rules. [Structs.co](../../../../examples/Structs.co) demonstrates
contextual construction, independent copies of nested strings and arrays,
stored field writes, nullable field access, ordered method results and
foreach snapshots. `Entry.move()` calls `Position.translate()` through a
stored nullable field after a presence check. It includes a target-sized
`usize` field and a wide `int<129>` field.

Run the example from the repository root.

~~~powershell
.\gradlew.bat :Compiler:runExample --args="examples/Structs.co build/structs/Structs"
~~~

The maintained example prints these lines before `PEC: 0`.

~~~text
Entry 1: ridge at (7, 7), visits=18446744073709551617
Entry 2 has no position
Original: north at (4, 6)
Moved 3 steps, stored y=6
~~~

## Compiler routing

- [Syntax](syntax.md) owns declaration order, contextual braces, source spans
  and type-body diagnostics.
- [Semantic analysis](semantic-analysis.md) owns nominal identity, contextual
  overload checks, field/write plans, null proofs and type metadata.
- [Local storage](local-storage.md) owns target-aligned layouts, checked field
  and array addresses, ordered writes and recursive snapshots.
- [Function and traversal integration](function-integration.md) owns the
  private Cloth ABI, caller-owned results, deferred reads and foreach cleanup.

The basic slice covers acyclic, non-generic structs with explicit array
field extents. The compiler rejects ordinary functions inside type bodies.
Ordinary methods are covered in the
[method publication record](../../cloth-functions/references/method-publication.md).
Whole-struct equality and formatting, additional reflection, constructors,
field defaults, generics and a native C struct ABI remain deferred.

## Verification

`StructPublicationTest` reads the maintained example and checks exact output
and status zero on Windows x64/MSVC and x86/MSVC. It checks executable LLVM IR
and nonempty objects across all nine profiles, using the same source unit for
each target. Its AArch64 Linux CLI case emits an object with an unavailable
linker argument, exercising linker-free object mode.

The original basic struct example ran under Ubuntu WSL as a GNU Linux x64
executable with matching output and status zero. Cloth emitted the executable LLVM module with
its normal entry adapter. LLVM emitted the GNU x64 object from that module,
and the installed Ubuntu `cc -lm` linked it. Windows ARM64, Linux musl and
AArch64, and macOS were verified through IR and object emission.

After the example adopted methods, both `StructPublicationTest` checks
passed again, including Windows x64/x86 execution and IR and object
emission across all nine profiles.

The original focused run passed 303 tests across 25 suites. The full run passed
1,336 tests across 110 suites in 5m 42s. Both ran on Windows with zero
failures, errors or skips.

~~~powershell
.\gradlew.bat :Compiler:test --tests 'cloth.Struct*Test' --tests cloth.ArrayTemporaryCleanupTest --tests cloth.FunctionEmissionTest --tests cloth.FunctionBlocksTest --tests 'cloth.OptionalArguments*Test' --tests 'cloth.MultiReturn*Test' --tests cloth.NullableBuffersTest --tests cloth.NullableRowsTest --tests cloth.ForEachTest --tests cloth.LoopControlTest --tests cloth.TypeInfoTest --tests cloth.NullableTypeProvidersTest --offline --console=plain --no-daemon
.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon
~~~

## Handoff to classes

Reuse nominal name resolution and the separation between field-value reads
and stored field/index plans. Keep struct layouts acyclic and inline, with
recursive copies of existing value fields. A future class-valued field
copies object identity. The struct's value cleanup leaves object lifetime to
the explicit class operations.

Before executable class creation, choose how deleted objects invalidate
every alias, including aliases in structs and arrays, and how storage reuse
keeps a stale alias from accessing a new object. The accepted contract calls
for a compile-time error when invalid access is provable and a runtime error
otherwise. Class construction, deletion and dispatch belong to the separate
class plan. See the [object lifetime contract](../../cloth-language/references/semantics.md#object-lifetime-and-nullability).
