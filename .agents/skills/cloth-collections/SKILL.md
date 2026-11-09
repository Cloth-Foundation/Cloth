---
name: cloth-collections
description: Use, design, or extend Cloth source standard-library List, Deque, Queue, and Stack, including independent elements, checked array export, and explicit object lifetime.
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
[Stage 6](../cloth-generics/references/stage-6.md) closes first-slice integration;
the [caller audit](../cloth-generics/references/caller-audit.md) records why
Bootstrap's owning list is preserved. Use the
[generic guide](../../../docs/generics.md) for supported language syntax and the
[follow-up record](../../../todo/generic-follow-ups.md) for deferred protocols.
The [final growable-array record](../cloth-arrays/references/growable-arrays-stage-8.md)
adds the completed backing-storage audit and Linux x64 execution of the imported
Collections project alongside native Windows x64/x86 and all-profile objects.

Import `using cloth.collections.List : List` and construct an empty
`List<T>::new` with an explicit invariant ordinary value type argument. Methods
are append/get/insert/removeAt/size/clear/reserve/toArray/copy. Indices and counts
are exact target `usize`; `size()` is a method. Preserve the documented signatures
and ordinary first-error bounds/allocation behavior.

## Preserve the value and lifetime contract

- Keep portable collection logic in Cloth. List is an ordinary class with a
  private generic struct-slot array; it has no compiler collection intrinsic.
  Private ListSlot names resolve in the library's defining file and stay private.
- Use a slot per element rather than direct `T[]` backing. `List<int[]>` accepts
  independently sized arrays; each element retains its own shape constraints.
  Changing the backing representation must preserve this rule.
- Get/remove/export results are independent values. Strings, arrays and structs
  copy recursively; class-valued T keeps object identity. Assigning a list to
  another binding aliases the collection identity. `copy()` creates a new list
  and copies complete slots, including hidden absent nullable-field witnesses.
- `toArray()` receives into ordinary rectangular `T[]`. Mixed present row shapes
  fail at the existing shape guard; do not silently add jagged arrays or relax
  receiving rules. Use get/size to traverse differently sized array elements.
- Explicitly delete each created list and each copy. Clear/destruction release
  stored value buffers without deleting class-valued elements. Bootstrap's owning
  TokenList and borrowed cursor require a separate migration/lifetime decision.
- `:=`/`final` protect the binding and permit mutation. Generic support adds no
  class indexing, indexed assignment, direct foreach or collection `::size`.
  New collection protocols require their own design and tests.

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

For command attachment, override/disable behavior and release pins, use
[toolchains](../cloth-toolchains/SKILL.md) and
[imports](../cloth-imports/SKILL.md). The public compiler API discovers no implicit
installation. Keep release cleanliness/pin checks; development selection does
not require a committed checkout. Tests may export a temporary pinned snapshot
of the actual bytes without committing or changing the working library's pin.

The Stage 5 record distinguishes Windows x64/x86 execution, foreign IR/object
evidence and the temporary packaging proof. It adds no release publication,
generic interfaces, constraints, method/function generics or Bootstrap migration.
