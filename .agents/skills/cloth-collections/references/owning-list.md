# COLL-2 Stage 2 owning list

The [public API and example](../../../../Standard-Library/cloth/collections/README.md#owninglist)
show import, ownership transfer, bracket reads, snapshot foreach, and deletion.
[OwningList.co](../../../../Standard-Library/cloth/collections/OwningList.co)
defines the final class with `T: Object`. The bound admits non-null class and
class-backed interface values. Elements keep their underlying checked identity.

## Source and runtime responsibilities

- The list stores class identities in a private `T[]`. `append` acquires
  authority before inserting. `removeAt` validates the operation, removes the
  entry, then releases authority and returns the live object. The array handles
  index bounds, and `[]` pairs with a read-only `[]?` validator.
- `operator foreach()` returns the ordinary `Snapshot<T>` containing the initial
  ordered aliases. It reuses [collection foreach](traversal.md). Removing a
  live element leaves its snapshot alias usable. Deleting it makes the alias
  stale under the ordinary class lifetime rules.
- `clear()` marks cleanup active, copies the ordered identities into local
  storage, and empties the list before calling any element destructor. It keeps
  deletion authority over the detached objects until each is destroyed. The
  list's destructor runs the same routine through direct destructor `self`.
- The [checked object table](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitClassRuntime.kt)
  holds the cleanup flag. Acquisition, release, and public mutators respect it.
  Ordinary deletion checks it before marking the list as destroying. The
  owned-delete operation can delete detached children while their owner is
  cleaning up. Slot initialization resets the flag on reuse.

The private `collections.ownership.check_mutation` binding takes no argument.
`collections.ownership.set_cleanup` takes one required `bool`. Both return void
and use the same final class, source origin, and method visibility checks as
the [Stage 1 bindings](ownership-runtime.md#private-bindings). `clear()` permits
reads of the empty list through a live alias. During `::delete`, the existing
destruction rule rejects ordinary aliases of the list at liveness checking.

Stage 3 adds compile-time diagnostics for provable ownership violations.

## Verification

[StandardOwningListTest](../../../../Compiler/src/test/kotlin/cloth/StandardOwningListTest.kt)
imports the actual standard-library file. It exercises interface elements,
mutable aliases from bracket reads and foreach, transfers, nested owning lists,
ordered cleanup, the empty view during `clear()`, and rejected reentrant changes.
It checks that invalid insertions and deletions fail before an owned destructor
runs. Snapshot membership survives removal and rejects later access to a deleted
object. Negative checks cover element bounds, bracket writes, and private bindings.

On 2026-11-13, 499 tests passed across 59 focused compiler suites. The owning
list ran natively on Windows x64 and x86. LLVM verification and object emission
passed on all nine target profiles. The Shuttle bundle test also passed with
the exported standard-library sources and a compiled owning list consumer.
[Bundle test](../../../../Shuttle/native/src/jvmTest/kotlin/shuttle/ToolchainBundleTest.kt)

The standalone standard-library object build passed. Running the public API
example through `cloth run` printed `10`, `2`, `2`, and `10` on successive lines
and exited with status 0.
