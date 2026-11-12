# Generics in Cloth

Cloth supports named generic classes and structs with invariant type
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

Write the full type in annotations, including nested applications such as
`Box<Slot<int>>`. Arguments preserve exact identities. `Box<int>`, `Box<usize>`,
`Box<int[]>` and `Box<int[2]>` are different types.
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
`pub fn<T> magic<T>(T value) = value`. Supply the types explicitly or let Cloth
infer the full list from the supplied values.
The standard-library `magic` helper marks a deliberately inline value and
returns it at the supplied type.

```cloth
using cloth.lang.num.NumberUtilities : magic

fn main() {
    def<int> count = magic<int>(64) + 1
    println(count)
}
```

Generic overloads with the same type parameter count require distinct declared
value-parameter types. The compiler compares type parameters by position.
An explicit call filters candidates by their bounds and ranks the substituted
signatures with ordinary overload rules. A tie reports an ambiguous call.
[Generic overload examples](../.agents/skills/cloth-functions/references/generic-overloads-stage-2.md)

For `fn<T> echo<T>(T value) = value`, `echo(1)` infers `T = int`. A
matching ordinary overload takes precedence. A type parameter that appears
only in the result requires an explicit type argument, as in `empty<int>()`.
[Function inference examples](../.agents/skills/cloth-generics/references/function-inference-stage-1.md)

## Generic methods

Ordinary class and struct methods can declare their own type parameters.
The method can use both its own parameters and the enclosing type's parameters.

```cloth
typedef<class> Box<T> (def<T> item) {
    pub mt<T, U> pair<U>(U value) = self.item, value
}
```

On a `Box<int>` receiver, `box.pair("label")` infers `U = string` and returns
`int, string`. `box.pair<string>("label")` selects the same specialization.
The receiver supplies `T = int`. A matching ordinary overload takes
precedence, and lookup uses the receiver's static type. A matching child
declaration hides the inherited signature before candidate bounds are checked.
[Method inference](../.agents/skills/cloth-generics/references/method-inference-stage-2.md)

## Generic constructors

With `Box<T>` declared as above, `Box::new(value=7)` infers `Box<int>` from
the supplied value. Primary and secondary constructors compete using the
ordinary overload rules after inference and bound checks. Use an explicit
receiver such as `Box<int>::new(...)` when the supplied arguments leave a type
parameter unknown. This includes zero-argument construction such as
`List<int>::new`.
[Constructor inference](../.agents/skills/cloth-generics/references/constructor-inference-stage-3.md)

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

Follow-ups cover combined bounds, constructor guarantees, generic dispatch contracts,
partial type argument lists, variance, type defaults, generic enums and states,
and higher-kinded types. Direct collection
indexing, indexed assignment and foreach require their own protocol. Keep the
intended constrained forms in [Test.co](../examples/Test.co).

Bootstrap's `TokenList` owns the tokens in a private `List<Token>` and returns
borrowed tokens through `get()`. [Bootstrap lexer skill](../.agents/skills/cloth-bootstrap-lexer/SKILL.md)

The generic follow-ups track the remaining designs. Stage 6 records target
execution and publication evidence. [Generic follow-ups](../todo/generic-follow-ups.md) ·
[Stage 6 record](../.agents/skills/cloth-generics/references/stage-6.md)
