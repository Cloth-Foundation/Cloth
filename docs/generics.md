# Generics in Cloth

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

Use explicit arguments at every type application, including nested applications such as
`Box<Slot<int>>`. `Box::new(1)` does not infer T. Arguments preserve exact identities:
`Box<int>`, `Box<usize>`, `Box<int[]>` and `Box<int[2]>` are different types.
`Box<Child>` does not convert to `Box<Parent>` when Child derives from Parent.

A type declared inside `Box<T>` uses `Box` as its qualifier. `Box.Item` is the
same type for every specialization of `Box`. If `Item` declares its own type
parameters, apply them on the final component, as in `Box.Item<int>`.

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

## Numeric bounds

`T: Number` accepts primitive integer and floating-point types, including
`usize` and `isize`. It gives `T` same-type arithmetic, comparisons, updates,
explicit numeric casts and formatting. Cast literals to `T` when using them as
operands.

```cloth
fn<T> half<T: Number>(T value) = value / (2 -> T)

fn main() {
    println(half<int>(5))
    println(half<float>(5f))
}
```

Each specialization uses its concrete numeric rules, so these calls print `2`
and `2.5`. Applying `half` to `string` produces C3135 at the type argument.
[Number bound operations](../.agents/skills/cloth-generics/references/number-bound-operations.md)

## Class and interface bounds

`T: Base` exposes the class bound's public fields and methods. An interface
bound exposes its methods. A receiving type can use ordinary widening from
the proven bound. Calls on `T` keep the overload, labels, and defaults
selected during declaration checking, with ordinary virtual or interface
dispatch. Equality, formatting, and deletion use the checked object rules.
[Operations through bounds](../.agents/skills/cloth-generics/references/constraints-stage-3.md)

## Generic functions

Declare a function's type parameters after its name, as in
`pub fn<T> magic<T>(T value) = value`. Supply the types explicitly at the call.
The standard-library `magic` helper marks a deliberately inline value and
returns it at the supplied type.

```cloth
using cloth.lang.num.NumberUtilities : magic

fn main() {
    def<int> count = magic<int>(64) + 1
    println(count)
}
```

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

## Generic interfaces

Classes can implement explicit, unconstrained interface applications such as
`Value<string>` or `Value<T>` in a generic class. Parent contracts substitute
their arguments before conformance and dispatch. Closed interface applications
can also appear in a holder such as `Slot<Value<string>>`. Calls use the static
interface's labels and omitted-argument defaults.
[Interfaces.co](../examples/Interfaces.co) · [Interface guide](../README.md#interfaces)

## Follow-up scope

Follow-ups cover combined bounds, constructor guarantees, method-owned
type parameters, generic overloads with the same type parameter count,
omitted-argument inference, variance, type defaults, generic enums and states,
and higher-kinded types. Direct collection
indexing, indexed assignment and foreach require their own protocol. Keep the
intended constrained forms in [Test.co](../examples/Test.co).

Bootstrap's `TokenList` owns the tokens in a private `List<Token>` and returns
borrowed tokens through `get()`. [Bootstrap lexer skill](../.agents/skills/cloth-bootstrap-lexer/SKILL.md)

The generic follow-ups track the remaining designs. Stage 6 records target
execution and publication evidence. [Generic follow-ups](../todo/generic-follow-ups.md) ·
[Stage 6 record](../.agents/skills/cloth-generics/references/stage-6.md)
