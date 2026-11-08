# Bundled standard library Stage 4: allocation service boundary

Designed and verified 2026-11-07. Through a popup, the user selected allocation for
an owning collection as the first consumer, using existing class `::new`/`::delete`
and ordinary Cloth collection logic. The [bundling contract](standard-library.md)
owns source selection; [class lifetime](../../cloth-language/references/class-lifetime.md)
and [class values](../../cloth-language/references/class-values.md) retain the
language and storage rules reused here.

This stage establishes and verifies the existing allocation boundary for library
code. It adds a maintained compiler-test prototype, with no new production runtime
code, public collection API, generic List declaration or standard-library revision.

## First consumer and required service

The [OwnedTextChain fixture](../../../../Compiler/src/test/resources/standard-library-runtime/cloth/collections/OwnedTextChain.co)
owns private linked nodes, each containing an independent string value. It requires
class-instance allocation, buffered-field ownership and explicit destruction.
Prepending, counting, clearing and traversal are ordinary Cloth methods.
Bootstrap's [TokenList](../../../../Bootstrap/Frontend/token/TokenList.co) already
provides a concrete consumer of the same class operations; it remains unchanged.

```cloth
using cloth.collections.OwnedTextChain : OwnedTextChain

fn main() {
    def values = OwnedTextChain::new
    values.prepend("Cloth")
    def saved = values.front()
    values::delete
    println(saved)
}
```

The declaration above is a verification fixture, not a shipped library API.
Its `prepend(string) -> void`, `front() -> string`, `size() -> usize` and
`clear() -> void` methods provide just enough behavior to test ownership.
An empty fixture returns an empty string from front. These choices establish
no general collection ordering, iterator, element-ownership or empty-read policy.
Fixed-shape arrays retain their existing rules; no growable contiguous buffer
or generic type implementation is implied.

## Ordinary Cloth to compiler-owned runtime

Library code uses typed language operations. It does not declare foreign
functions, spell allocator symbols, obtain addresses or convert integers to
memory handles. The boundary for this first service is the existing class
operation lowering, backed by private emitted helpers and target C allocation.

| Source operation | Result and ownership |
| --- | --- |
| `T::new(arguments)` | A live T identity after normal argument/default evaluation and construction. The compiler owns instance storage and recursive value buffers in fields. Failure terminates execution. |
| `value::delete` | Void. Run the concrete destructor chain, release buffered fields, free instance storage and invalidate that identity. The library destructor explicitly deletes owned child objects. |
| Deletion of an absent nullable class | Void, with no destructor or allocation access. |
| A class-value copy | An alias to the same instance. It does not clone the collection or transfer ownership automatically. |
| A string/array/struct value copy | Existing independent value-storage rules, recursively preserving class identity for class-valued members. |

Use the existing semantic class-creation/deletion plans. The compiler, rather
than the selected library, computes class storage, carries destruction permission
and emits checked access. A marked standard-library role is a namespace boundary,
not an allocation capability. Explicit regular modules and `--no-stdlib` programs
can use the same language operations and builtins.

### Current private lowering protocol

These signatures describe compiler-internal IR and may change with the compiler.
They are not callable Cloth declarations or a stable binary/runtime ABI.

| Helper | Private signature and responsibility |
| --- | --- |
| `cloth.objects.allocate` | `(target_size bytes, u32 concreteTag) -> u64 identity`; allocate instance storage and a generation-checked table slot. |
| `cloth.objects.lookup` | `(u64 identity, bool duringDestruction) -> internal slot address`; validate generation, occupancy and destruction access. |
| `cloth.objects.release` | `(u64 identity) -> void`; free the instance payload, invalidate the generation and recycle or retire its slot. |

The fixed 64-bit identity is private to class lowering. Pointer and size widths
come from the compilation target independently. Slot addresses never cross a
source-call boundary; resolve them again after effects that can move the table.
Only the compiler carries destructor-self permission. An ordinary copied identity
does not acquire that permission.

The private C calls are `malloc(target_size) -> internal address`,
`realloc(internal address, target_size) -> internal address` and
`free(internal address) -> void`, using the selected target's C calling convention.
The allocator family stays paired. Source code has no access to those addresses.

## Lifetime, copying and failure

The fixture owns its nodes by convention and deletes each explicitly. Child
class fields are identities; deleting a containing object does not automatically
delete every object it refers to. A collection exposing borrowed objects later
must specify its own ownership and invalidation contract before implementation.

String fields own recursive value buffers independently of caller frames. Normal
reads/returns snapshot values before later field replacement or instance deletion.
The [fixture driver](../../../../Compiler/src/test/resources/standard-library-runtime/Main.co)
retains front strings across clear/delete, grows past the initial table capacity,
and repeatedly creates/destroys collections to exercise slot reuse. Its alias
check also preserves class identity rather than implying container value copies.

Explicit collection destruction runs its ordinary destructor, which deletes nodes;
each node's compiler-owned field buffers are then released before its instance.
The runtime table retains peak capacity for reuse, including generation history.
It is process-lifetime infrastructure, not a garbage collector or a promise that
all heap allocations disappear after the last object is deleted. The poisoned-free
probe balances instance/field/frame malloc/free calls and observes table realloc
separately.

Allocation byte counts and table growth use checked target-layout arithmetic,
including LLVM's signed pointer-index limit. Preserve existing fail-fast behavior:

| Failure | Current behavior |
| --- | --- |
| Instance allocation returns null | Print `Unable to allocate class instance` once and exit 1. |
| Table reallocation returns null | Print `Unable to grow class instance table` once and exit 1. |
| Storage/table capacity cannot fit | Stop through the existing checked-memory runtime failure path before the unsafe operation. |
| Provably deleted identity | C3092 at the original source during compilation. |
| Invalid identity not proven statically | Print `Class object is no longer live` once and exit 1. |

Fatal runtime failure executes no continuation or normal PEC report. It promises
no rollback, partial-construction unwinding or execution of remaining destructors;
the process terminates. Allocation does not return a nullable class or introduce
a recoverable allocation-result convention. Compile-time errors keep their original
structured diagnostic, labels and first-error ordering. Private node selection in
the fixture retains C3103 rather than exposing implementation storage.

## Target selection, linking and distribution

The request's normalized target selects NativeCABI and TargetLayout before
class layout/emission. LLVM target data supplies storage size, stride and alignment.
Never select allocation code from the installation host or the JVM's pointer width.

| Target profiles | Private native size/pointer width | Verification in this stage |
| --- | --- | --- |
| Windows x86/MSVC | 32 bits | Native execution, failure injection, poisoned frees and object emission. |
| Windows x64/MSVC | 64 bits | Native execution, failure injection, poisoned frees and object emission. |
| Windows AArch64/MSVC | 64 bits | Verified executable IR and object emission. |
| Linux x64 GNU | 64 bits | Verified executable IR/object; linked with Ubuntu WSL cc and executed successfully. |
| Linux AArch64 GNU; x64/AArch64 musl | 64 bits | Verified executable IR and object emission. |
| macOS x64/AArch64 | 64 bits | Verified executable IR and object emission. |

Reachable class operations cause the compiler to emit their helpers into the same
program/object. NativeLinker resolves their C dependencies through the requested
target's existing CRT/sysroot/toolchain configuration. `--emit object` needs no
foreign linker and carries normal target C references for later linking.
This service requires no new runtime archive, dynamic Cloth runtime or platform
sources in the library tree. No versioned cross-module ABI is established.

`--runtime default|static|dynamic` continues to control existing C runtime linkage.
`--stdlib PATH` selects Cloth sources, and `--no-stdlib` removes implicit library
attachment/reservation. Source selection does not choose the CRT or disable class
allocation. Existing unsupported CRT/target combinations keep normal toolchain
diagnostics. The final packaged/offline/source-removal proof remains Stage 5.

## Later services

Add services when a selected public library feature requires them, keeping portable
behavior in Cloth and the platform operation narrow:

| Future consumer | Contract required before adding its native boundary |
| --- | --- |
| Contiguous generic collection/buffer | Generic element layout, capacity arithmetic, initialization/movement, element ownership, cleanup, failure and alias/invalidation rules. The present node fixture settles none of these. |
| File/stream I/O | Path encoding, owned handle lifetime, partial reads/writes, EOF and error results, close behavior and target implementation. |
| Clocks | Wall versus monotonic source, units/range/epoch, precision, failure and target implementation. |
| Threads/synchronization | Callable/data transfer, sharing and memory model, join/detach/cancellation, ownership and runtime-table safety. Current allocation infrastructure is not established as thread-safe. |

Do not add those APIs, broad foreign-call syntax or a public unsafe-memory facility
as a prerequisite for source bundling. The separately deferred [unsafe-memory plan](../../../../todo/unsafe-memory-plan.md)
retains its own unresolved design and authorization boundary.

## Implementation and verification routing

- [Class runtime](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitClassRuntime.kt),
  [checked memory](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitMemory.kt),
  [class fields](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitClassStorage.kt)
  and [string arena](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitStringArena.kt)
  own the existing private service and value-buffer cleanup.
- [Callable lowering](../../../../Compiler/src/main/kotlin/cloth/emitter/callables/EmitFunctions.kt)
  orders construction/destruction and field release; [class lifetime analysis](../../../../Compiler/src/main/kotlin/cloth/semantic/flow/ClassLifetimeAnalysis.kt)
  retains static checks. [NativeCABI](../../../../Compiler/src/main/kotlin/cloth/emitter/NativeCABI.kt),
  [LLVMEmitter](../../../../Compiler/src/main/kotlin/cloth/emitter/LLVM.kt),
  [native toolchain resolution](../../../../Compiler/src/main/kotlin/cloth/emitter/ToolchainResolver.kt)
  and [NativeLinker](../../../../Compiler/src/main/kotlin/cloth/emitter/NativeLinker.kt)
  own target selection, layout and linking.
- [StandardLibraryRuntimeTest](../../../../Compiler/src/test/kotlin/cloth/StandardLibraryRuntimeTest.kt)
  uses the maintained source tree through the real source graph and explicit
  selection. It instruments verified graph IR only for allocation observation,
  poisoning and deterministic failure injection, without concatenating sources
  or adding production hooks.

The focused offline run passes in 37s: six tests, zero failures/errors/skips.
Four normal programs exercise selected and regular roots on Windows x64/x86;
both native profiles also pass poisoned-free balance, exactly four table reallocs
(16/32/64/128 slots, then reuse), allocation/table-failure injection and stale-alias
termination. All nine profiles' executable IR verifies and emits nonempty objects.
GNU Linux x64 links/runs under Ubuntu WSL with exit 0 and `PEC: 0`. No new native
AArch64, macOS or musl execution is claimed.
The maintained fixture also runs through the installed Stage 3 compiler launcher
with `--stdlib Compiler/src/test/resources/standard-library-runtime`, returning
zero and printing PEC 0. Existing production runtime code already supports it.

```powershell
.\gradlew.bat :Compiler:test --tests cloth.StandardLibraryRuntimeTest --offline --console=plain --no-daemon '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'
```

The observed run uses an isolated Gradle init script with build directories at
`build/standard-library-stage4/{root,Compiler,Shuttle}`, project cache under
`tmp/standard-library-stage4/project-cache`, and test property
`cloth.test.standardLibraryRuntimeArtifacts=F:/Cloth/build/standard-library-stage4/evidence`.
GNU executable IR and the startup object are retained there for the native check:

```powershell
wsl.exe -d Ubuntu -- cc /mnt/f/Cloth/build/standard-library-stage4/evidence/owned-chain-linux-x64.o -lm -o /mnt/f/Cloth/build/standard-library-stage4/evidence/owned-chain-linux-x64
wsl.exe -d Ubuntu -- /mnt/f/Cloth/build/standard-library-stage4/evidence/owned-chain-linux-x64
```

The Stage 3 full-regression result remains its own historical evidence. This stage
changes tests/fixtures and records the verified existing boundary; it does not
claim a new full-suite run or rebuild the release archives. The standard-library
scaffold and pinned Git revision remain unchanged. Stage 5 remains pending.

Publication checks pass for 21 documents, 834 relative links, 92 heading anchors,
balanced fences, whitespace, 29 source/configuration files and completion of only
Stages 0-4. All three changed skills pass the full bundled validator with temporary
PyYAML under workspace `tmp/`, removed after validation. Scoped Git checks pass,
and unrelated Bootstrap/emitter/string work is preserved.
