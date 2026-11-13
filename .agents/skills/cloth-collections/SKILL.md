---
name: cloth-collections
description: Use, design, or extend Cloth collections, including checked List indexing, element replacement, snapshot foreach, array export, and explicit object lifetime.
---

# Cloth collections

The public Deque, Queue, and Stack classes share List's value and lifetime
rules. The [linear collections record](references/linear-collections.md)
covers their API, empty access, storage, and verification. The
[runnable example](../../../examples/LinearCollections.co) uses all three.

The public source [List](../../../Standard-Library/cloth/collections/List.co)
is implemented. Start with its
[API and ownership contract](../../../Standard-Library/cloth/collections/README.md)
and the runnable [Collections project](../../../examples/Collections/README.md).
[Generic Stage 5](../cloth-generics/references/stage-5.md) owns implementation and
verification evidence, including import visibility, native execution and packaging.
[Stage 6](../cloth-generics/references/stage-6.md) closes first-slice integration. Use the
[generic guide](../../../docs/generics.md) for supported language syntax and the
[follow-up record](../../../todo/generic-follow-ups.md) for deferred protocols.
Use the [collection indexing contract](references/indexing.md) for class
bracket reads, checked writes, updates, and validator pairing. The
[collection foreach contract](references/traversal.md) covers `operator foreach`,
the standard-library `Snapshot<T>` value, and traversal verification.
The [final growable-array record](../cloth-arrays/references/growable-arrays-stage-8.md)
adds the completed backing-storage audit and Linux x64 execution of the imported
Collections project alongside native Windows x64/x86 and all-profile objects.

Import `using cloth.collections.List : List` and construct an empty
`List<T>::new` with an explicit invariant ordinary value type argument. Methods
are append/get/set/insert/removeAt/size/clear/reserve/toArray/copy. Indices and counts
are exact target `usize`; `size()` is a method. Preserve the documented signatures
and ordinary first-error bounds/allocation behavior.

COLL-1 keeps `size()` as the collection count API. The open questions plan
tracks a general collection count provider under
[DATA-1](../../../todo/open-questions-plan.md#deferred-proposals).

## Preserve the value and lifetime contract

- Keep portable collection logic in Cloth. List is an ordinary class with a
  private generic struct-slot array; it has no compiler collection intrinsic.
  Private ListSlot names resolve in the library's defining file and stay private.
- Use a slot per element rather than direct `T[]` backing. `List<int[]>` accepts
  independently sized arrays; each element retains its own shape constraints.
  `set` replaces a complete slot so the incoming value carries its own shape.
- Bracket reads, get, remove and export return independent values. Strings,
  arrays and structs copy recursively. Class-valued T keeps object identity. Assigning a list to
  another binding aliases the collection identity. `copy()` creates a new list
  and copies complete slots, including hidden absent nullable-field witnesses.
- `toArray()` receives into ordinary rectangular `T[]`. Mixed present row shapes
  fail at the existing shape guard. Direct foreach visits differently sized
  array elements using their independent shapes.
- Explicitly delete each created list and each copy. Clear/destruction release
  stored value buffers. Callers manage the lifetime of class-valued elements.
  Bootstrap's TokenList deletes its tokens before deleting its private `List<Token>`.
  [Bootstrap lexer skill](../cloth-bootstrap-lexer/SKILL.md)
- `:=`/`final` protect the binding and permit mutation. `List<T>` opts into
  checked bracket access with its source `[]`, `[]=`, and `[]?` declarations.

## Owning collections

The standard-library [`OwningList<T>`](../../../Standard-Library/cloth/collections/OwningList.co) takes
responsibility for deleting owned class objects. User-defined types can wrap
it. The list accepts non-null class types and class-backed interface views.
Ownership follows the same underlying identity across all views.
Successful insertion transfers deletion authority to the collection. The
original binding and other aliases can still read, call methods on, and mutate
the object while it is live. Only the collection can delete the object while it
owns it. Deletion invalidates those aliases under the ordinary class lifetime
rules. [Class lifetime](../cloth-language/references/semantics.md#object-lifetime-and-nullability)

Cloth rejects unauthorized deletion at compile time when it can prove the
object is still owned and the deletion lacks the owner's authority. Otherwise
it checks deletion authority at runtime when ownership or authority is uncertain.

Insertion rejects an object the current list or another collection already
owns, even when the caller passes an alias. Each owned object occupies one
slot. A transfer must go through the current owner. An owning list can own
another owning list. Cloth rejects a provably invalid insertion at compile
time, including an existing owner or an ownership cycle. It checks at runtime
when ownership is uncertain.
`removeAt(index)` returns the live object and transfers deletion responsibility
to the caller. The caller can delete it or insert it into another owning
collection. Ordinary deletion rules apply to every live alias until the object
is owned again.

`get(index)` returns an ordinary checked class alias. Callers can store it,
pass it, and mutate the object while it is live. The collection retains
deletion authority while it owns the object. Deleting the object invalidates
the alias. Bracket reads and snapshot foreach also yield ordinary checked
aliases. Insertion and removal use methods.

`clear()` deletes every object still owned by the collection and leaves the
collection empty and usable. `::delete` deletes those objects as part of
destroying the collection. Both operations run the owned objects' destructors
in current list order, from first element to last. Ordinary lifetime checks
apply to aliases of each deleted object. While the list is deleting its owned
objects, reject insertion, removal, `clear()`, or `::delete` on that list,
including calls through aliases from an element's destructor.
During `clear()`, the list detaches all entries before the first destructor.
An element's destructor sees `size()` return zero, and `get(0)` fails the
ordinary index bounds check. The list retains deletion authority over the
detached entries until it deletes them.
Bootstrap's `TokenList` is the concrete example for this design.
[Stage 1 runtime and verification](references/ownership-runtime.md) ·
[Stage 2 source integration](references/owning-list.md) ·
[Design decisions](../../../todo/open-questions-plan.md#collections-operators-and-control-flow) ·
[Implementation stages](../../../todo/open-questions-plan.md#coll-2-implementation-stages)

## Extend and verify the actual library

Use [cloth-generics](../cloth-generics/SKILL.md) for parameter scope, closed
identities and symbolic/closed checking; [cloth-arrays](../cloth-arrays/SKILL.md)
and [buffer lifetimes](../cloth-buffer-lifetimes/SKILL.md) for shape, copy and
relocation changes. Keep unconstrained T free of assumed arithmetic, equality,
printing or arbitrary members.

Test the real imported List file from a separate source module. The older
acceptance fixture and concrete wrappers do not replace actual-library tests.
`StandardListImportsTest` covers visibility/identity/selection without native
binaries. `StandardListTest` covers the public API, element matrix, fatal guards
and native/all-nine-profile object evidence. `StandardListStorageTest` supplies
the checked graph to the allocator/poison harness. Track `.co` library inputs in
Gradle so editing the implementation invalidates these tests.

[CollectionIndexWriteTest](../../../Compiler/src/test/kotlin/cloth/CollectionIndexWriteTest.kt)
exercises setter selection, operand order, invalidated targets, and independent
slot replacement through the compiler and the imported library.

For command attachment, override/disable behavior and release pins, use
[toolchains](../cloth-toolchains/SKILL.md) and
[imports](../cloth-imports/SKILL.md). The public compiler API discovers no implicit
installation. Keep release cleanliness/pin checks; development selection does
not require a committed checkout. Tests may export a temporary pinned snapshot
of the actual bytes without committing or changing the working library's pin.

The Stage 5 record distinguishes Windows x64/x86 execution, foreign IR/object
evidence and the temporary packaging proof. It adds no release publication,
generic interfaces, constraints, method/function generics or Bootstrap migration.
