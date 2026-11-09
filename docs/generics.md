# Generic classes, structs and List

Cloth supports named generic classes and structs with explicit invariant type
arguments. The runnable [Generics example](../examples/Generics.co) uses both:

```cloth
typedef<struct> Slot<T> { T value }
typedef<class> Box<T> (def<T> value) {
    pub mt<T> get() = value
    pub mt<typeinfo> argumentType() = T::type
}
```

`Box<string[]>::new(["first"])` creates a Box whose get result is an independent
growable array. Mutating that result does not mutate the Box's stored value.
Class values retain explicit lifetime: delete every object you create. Structs,
strings and arrays copy their value storage; class-valued fields copy identity.

From this repository, run the maintained example:

```powershell
.\gradlew.bat :Compiler:run --args="run examples/Generics.co build/generics"
```

With a matching compiler on PATH, use `cloth run examples/Generics.co build/generics`.
It prints the following, followed by normal `PEC: 0`:

```text
string[]
[first, second]
[first]
null
int<32>?
true
false
```

## Arguments and checking

Use explicit arguments at every type application, including nested types such as
`Box<Slot<int>>`. `Box::new(1)` does not infer T. Arguments preserve exact identities:
`Box<int>`, `Box<usize>`, `Box<int[]>` and `Box<int[2]>` are different types.
`Box<Child>` does not convert to `Box<Parent>` when Child derives from Parent.

Ordinary value types are valid arguments, including nullable values, arrays,
enums, classes, structs, typeinfo, usize/isize and nested closed generics. Void,
bare generic names and omission-only opt wrappers are not value arguments.
Nullable substitution normalizes one layer: when T is `int?`, `T?` is `int?`.
Directly written `int??` stays invalid, and nullable receiving annotations remain
explicit where the language requires them.

The compiler checks a template when declared, including unused declarations,
then checks each required closed instance. An unconstrained T supports copies,
storage, passing/returning values and `T::type`. It cannot assume arithmetic,
equality, printing, arbitrary members or class creation/deletion. Choosing int
later does not make an invalid unconstrained body valid.

Type parameters belong to their owner and are visible throughout its header and
body. They may shadow outer type names; duplicates and a parameter matching its
owner's name are errors. Public generic APIs may use private helper types in
private storage, but may not expose them through public signatures. Callers can
supply their own private types where those names are visible.

Metadata distinguishes exact closed identities, including same-spelled private
types from different files. Canonical numeric text keeps explicit widths.
Compilation stops at the first detected error. Generic resource bounds are
64 dependency steps, 4,096 distinct closed keys and 256 argument layers; symbolic
checking has separate dependency/key quotas. Canonical generic metadata text is
limited to 1,048,576 UTF-8 bytes before expansion. The
[generic contract](../.agents/skills/cloth-generics/references/semantics.md) owns
the precise rules and diagnostics.

## Public List

Import the selected standard-library source explicitly:

```cloth
using cloth.collections.List : List
```

`List<T>::new` creates an empty list. Its methods are append/get/insert/removeAt/
size/clear/reserve/toArray/copy, with exact `usize` indices and counts. See the
[API and ownership contract](../Standard-Library/cloth/collections/README.md) and
[Collections project](../examples/Collections/README.md) for runnable examples.

Each element has independent value storage. `List<int[]>` permits different row
lengths; `copy()` preserves them. `toArray()` produces an ordinary rectangular
array and checks incompatible dimensions. Class-valued elements remain shared
identities; deleting a list does not delete those objects. List aliases share the
collection itself, while copy creates another collection requiring deletion.

## Follow-up scope

This delivery does not implement constraints, generic interfaces/functions,
method-owned type parameters, omitted-argument inference, variance, type defaults,
generic enums/states or higher-kinded types. Direct collection indexing, indexed
assignment and foreach require their own protocol. These are deferred features,
not permanent language restrictions. Keep the intended constrained forms in
[Test.co](../examples/Test.co).

[Generic follow-ups](../todo/generic-follow-ups.md) separate those future designs
from the completed slice. Bootstrap's owning TokenList and borrowed cursor keep
their existing lifetime contract; generic List availability alone does not
authorize replacing them. The [caller audit](../.agents/skills/cloth-generics/references/caller-audit.md)
records the migration gates. Target execution and publication evidence live in
the [Stage 6 record](../.agents/skills/cloth-generics/references/stage-6.md).
