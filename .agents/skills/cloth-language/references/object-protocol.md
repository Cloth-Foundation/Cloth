# Object and operator declarations

Cloth's design uses `Object` as the implicit class root and a general
`operator` declaration form for classes, structs, and enums. The formatting,
equality, and instance-check examples below illustrate that form. See the
[class inheritance implementation](class-inheritance.md) and
[operator declarations and resolution](operator-declarations.md) for the
foundations. [Class equality and identity](class-equality.md) records the
implemented comparison rules. [Class formatting](class-formatting.md) records
the implemented provider and output rules.
[Operator publication](operator-publication.md) links the maintained examples
and verification for the integrated slices.

## General operator overloading

User-defined types can provide implementations of Cloth operators for their
operands. This is a general language feature. `operator ::str()`,
`operator ==(Object other)`, and a declaration such as
`operator +(Vector other)` use the same class or value-type member syntax.
The operator retains its expression spelling, precedence, and grouping.
[Kotlin operator overloading](https://kotlinlang.org/docs/operator-overloading.html) ·
[C++ operator declarations](https://eel.is/c++draft/over.oper)

Member declarations use an implicit `self` and an expression or block body.
Each operand form needs rules for signature matching, result typing, and
evaluation. The [ordinary operator implementation](operator-declarations.md)
records ranking for exact, compatible, and contextual matches. Each special
operator family specifies any additional ranking and fallback rules.
Operators with special evaluation rules, such as short-circuiting, need their
own evaluation contract before their overloads are implemented.

### Binary receiver position

Either operand's type can declare an ordinary binary overload. A single
parameter declares the right operand and places `self` on the left. To put
the receiver on the right, write `self` in the second operand position. The
enclosing type supplies `self`'s type. These declarations belong inside `Player`.

```cloth
operator +(int points) = self.score + points        // player + 10
operator +(int points, self) = points + self.score  // 10 + player
```

Lookup considers applicable declarations from both operand types in their
declared positions. It selects the single best match. If no applicable
implementation exists, report a compile-time error. If candidates tie for
best match, report a compile-time ambiguity. Either diagnostic stops
compilation. Operands evaluate once, left to right, before the body runs,
including when the right operand supplies the receiver.

### Operand context

An operator declaration can supply the value its receiver contributes to an
operation. The call site substitutes that value in the receiver's original
operand position and applies the operator to the resulting operands.

The declaration form is `operator<T> +()`. The angle brackets select the
other operand's type. The empty parentheses mark a body with `self` and zero
explicit parameters. A context may select any operand type. Its body
determines the type and value contributed by `self`.

```cloth
operator<int> +() = self.points
operator<string> +() = self.name
```

In `Player`, the first declaration makes `player + 10` evaluate
`player.points + 10` and `10 + player` evaluate `10 + player.points`. The
second makes `player + "!"` evaluate `player.name + "!"` using string `+`.
The named-parameter form, such as
`operator +(int bonus) = self.points + bonus * 2`, supplies the complete
result instead. The context form preserves operand order and evaluates each
source operand once.

1. Look for a full overload on the original operands. A unique best match
   computes the result. A tie for best match is a compile-time error.
2. If no full overload matches, use the matching context from each operand.
   Either or both operands can supply context. Each value stays in its original
   position, and operands without a matching context contribute their original
   value.
3. Apply the operation to the resulting operand pair. Report a compile-time
   error if that pair has no applicable operation.

The resolution uses one context pass, with selectors matched against the
original operand types. The resulting pair uses a full overload or built-in
operation. [Operand context resolution](operand-contexts.md#resolution-and-evaluation)
records selector ranking and the implemented families.

Precedence and associativity determine the expression tree. At each operator,
evaluate each reached operand once in source order and evaluate its selected
context immediately afterward. Save the contributed value before moving to the
next operand. Then apply the selected operation to those values. For
`player + bonus * 2`, `+` first captures the player's points, then evaluates
`bonus * 2` as its right operand. A short-circuiting operation evaluates a
context only when its evaluation contract reaches that operand.

#### Matching a numeric width

In an operand context declaration, `int<auto>` matches a signed fixed-width
integer and binds `auto` to the other operand's concrete width. The bound width
is available in the declaration body, including as the target of an explicit
cast.

```cloth
operator<int<auto>> +() = self.points -> int<auto>
```

For an `int<64>` operand, the cast above contributes an `int<64>` value. The
call site then resolves ordinary `int<64>` addition. Matching alone preserves
the types of both values. If the declaration contributes a different integer
width, the resulting pair follows ordinary exact integer typing. Explicit
casts follow Cloth's existing widening and narrowing rules, including
truncation when narrowing. Unsigned and target-sized integers have distinct
type identities and require their own selectors.

`float<auto>` similarly selects a 16-, 32-, or 64-bit float peer. A body
can use an explicit `-> float<auto>` cast to contribute the selected width
or `-> float` to contribute the ordinary 32-bit float.

[Cloth numeric rules](../../cloth-numerics/references/semantics.md) ·
[Target-sized integers](../../cloth-target-integers/references/semantics.md)

## The `Object` root

`Object` is a built-in class type available without an import. A class without
an explicit class base inherits `Object` directly. A class with an explicit
base inherits it through that base. Primitive values, strings, arrays, structs,
enums, and `typeinfo` have their own value types. An array of class instances is an
array value whose elements carry object identities.

A class instance can be used as an `Object` through an implicit upcast. The
upcast carries the same identity and concrete class. Copying or passing the
upcast value creates another alias. Deleting through any live alias destroys
the concrete instance and invalidates all ordinary aliases under the
[class lifetime rules](semantics.md#object-lifetime-and-nullability).

`Object` is an instance type. `value::type` yields `typeinfo` describing the
static type of the expression. Given `def<Object> object = player`,
`object::type` describes `Object` and `object::str` uses the formatter for the
actual instance. A public query for the concrete runtime type is a separate
design decision. [Compiler data access](../../cloth-compiler-data/references/semantics.md)

## Class formatting with `::str`

`Object` supplies the default value formatter, which returns the concrete
class name. An override is a class-body member with an implicit `self`, an
empty explicit parameter list, and a fixed `string` result. It can use an
expression body or a block with an ordinary `string` return.

```cloth
typedef<class> Player (def<string> name) {
    operator ::str() = #"Player({self.name})"
}
```

`operator ::str()` declares the formatter. `player::str` reads its result.
The empty parentheses show that the declaration has an executable body with
zero explicit parameters. At the use site, `player::str` is read-only provider
access and `player::name(...)` is state-method syntax. `Player::str` is a
compile-time error because the provider requires an instance.

Each class may declare one formatter. A subclass inherits the nearest
formatter in its base chain until it declares its own. Dispatch uses the
receiver's initialized class level and follows ordinary receiver lifetime checks,
including the existing destructor access rule for `self`. The body runs at
formatting time and sees current fields. A literal body such as
`operator ::str() = "Player"` returns that literal verbatim.

`::str` is the public conversion spelling for class and supported data values.
`print`, `println`, interpolation, and recursive array formatting use the same
provider. Each occurrence evaluates the receiver once and runs the selected
formatter once per formatted value, including when array formatting uses
separate sizing and writing passes. Printable nullable contexts render an
absent value as `null` and dispatch for a present class payload. Direct
`value::str` on a nullable class receiver requires a presence proof, in line
with existing provider access rules. [Output contract](../../cloth-output/references/semantics.md)

## Class equality with `==`

`Object` supplies `operator ==(Object other)` with identity as its default.
A class override takes one non-nullable `Object` operand, has an implicit
left operand `self`, and returns `bool`. A subclass inherits the closest
implementation until it declares its own. An explicit `!=` overload for the
matching runtime class supplies the result, including for aliases of the same
instance. The default behavior of `a != b` negates `a == b`.

```cloth
final typedef<class> Account (def<int> id) {
    operator ==(Object other) {
        if (other is Account) return self.id == other.id
        return false
    }
}
```

Comparison evaluates both operands once, left to right. Two absent operands
compare equal, and an absent operand compares unequal to a present operand.
The equality body receives two live instances. For two present operands,
dispatch for the inherited `Object` equality signature checks
liveness, returns true for the same instance, and returns false for
different concrete classes. For distinct instances of the same concrete
class, it invokes that class's selected
implementation, including through `Object` bindings. This keeps both operand
orders on the same equality implementation.

Overrides must define an equivalence relation for live objects. The result is
reflexive, symmetric, and transitive, and stays consistent while the data
being compared is unchanged. A future hashing contract must give equal keys
the same hash. Two separately created `Account` instances with the same ID
compare equal under this override.

## Class identity with `===`

The built-in behavior of `a === b` checks whether two class values carry the
same instance identity, including when their static class types differ.
Separate allocations compare unequal under this behavior even when `a == b`
is true. Nullable class operands compare by presence first, with two absent
operands comparing equal. Both operands evaluate once, left to right.

The built-in `===` can inspect stale aliases without
accessing instance storage. A stale alias has a present identity distinct
from `null` and from any later object that reuses the same storage.
[Class identity and liveness](class-lifetime.md)

An overload is selected from the operands' static types and can supply the
result when both operands are live. Explicitly upcast both operands with
`(a -> Object) === (b -> Object)` to select the built-in comparison. For nullable
operands use `Object?`. The compiler tracks actual instance identities for
alias safety and deletion.

## Instance checks with `is`

`player is Player` checks whether `player` is an instance of `Player`, including
an instance of a subclass. A successful built-in type check can narrow the
binding. When the right operand names a state, `player is Alive` checks the
active state. [Type and state checks](../../cloth-control-flow/references/semantics.md#type-checks-state-checks-and-equality)

`operator is(Object other)` declares a value-operand overload, as in `a is b`
when `b` is an object value. The type operand in `player is Player` uses the
built-in check. Narrowing requires a verified runtime type match. See the
[implemented forms and remaining contracts](operand-contexts.md#forms-of-is).

## Operator declarations on structs and enums

A struct can declare `operator ==(StructName other)` to compare two values
of that struct type. The result is `bool`, and the default `!=` negates it.
Stage 5 requires a declaration for whole-struct equality.
[Struct equality scope](../../cloth-structs/references/semantics.md#equality-scope)

```cloth
typedef<struct> Point {
    int x,
    int y;

    operator ==(Point other) = self.x == other.x && self.y == other.y
}
```

An enum can declare `operator ::str()` to format its values. Default enum
equality compares cases of the same enum type. An enum equality overload uses
`operator ==(EnumName other)` returning `bool`, as for a struct.
[Struct and enum operators](value-operators.md) records the payload,
comparison, and formatting rules.

```cloth
typedef<enum> DamageType (pub def<int> number = 1) {
    ATTACK,
    FALL,
    UNKNOWN(-1);

    operator ::str() = #"DamageType({self.number})"
}
```
