# Cloth structs: accepted design and unresolved choices

## Authority and status

[Test.co](../../../../examples/Test.co) establishes the intended declaration,
field and contextual aggregate syntax. The user accepted independent value copies
on 2026-11-04 while planning basic structs before classes. The
[structs skill](../SKILL.md) routes to current implementation and verification.

The [completed plan](../../../../todo/basic-structs-plan.md) records the
implementation stages. [Structs.co](../../../../examples/Structs.co) demonstrates
the implemented value semantics and the [publication record](publication.md)
lists verification and deferred features.

## Established syntax and visibility

Put `pub` before `typedef<struct>` to declare a public struct. Omitting `pub`
keeps the struct private. The name and body follow the declaration header.
Write each field as a type followed by a name and separate fields with commas.

```cloth
pub typedef<struct> DeathInfo {
    int cause,
    string message
}
```

The example uses an existing primitive for `cause` so its syntax does not depend
on enum implementation. The intended `DeathInfo` in `Test.co` uses `DamageType`.

Fields and methods inherit the enclosing struct's visibility. The field list
comes first and ends with a semicolon when followed by another member. Read
the [shared method contract](../../cloth-functions/references/semantics.md#user-defined-methods)
for `mt`, its implicit receiver and the current design boundary.

Contextual positional aggregate construction is established by `Test.co`:

```cloth
def<DeathInfo> info = { 1, "Fell" }
```

The annotation supplies the expected type for the brace literal.

The shared [nested type declaration rules](../../cloth-language/references/semantics.md#nested-type-declarations)
apply to structs and other `typedef` bodies.

## Nominal type identity (confirmed 2026-11-04)

Each struct declaration defines a distinct type. Two structs with identical
field names, types and order still have different types. Assignments and function
arguments follow the ordinary type matching rules. A value of one struct type
cannot fill a position that requires the other merely because their fields match.

```cloth
def<ScreenPosition> screen = { 10, 20 }
def<WorldPosition> world = screen // Type mismatch if both structs are declared separately.
```

A contextual literal such as `{ screen.x, screen.y }` can construct a
`WorldPosition` where that type is expected. This example assumes both types
declare `int x, int y`.

## Type, variable and function names (confirmed 2026-11-04)

A type and a variable may share a name. A type annotation looks up the type
independently of visible variables. An ordinary variable expression follows
existing lexical value lookup.

```cloth
def<Position> Position = { 1, 2 }
println(Position.x)
```

Here `<Position>` resolves to the struct declaration and `Position.x` reads
the variable's field. The shared spelling leaves both declarations accessible
in their respective contexts.

A function and a struct type can also share a name. `Position()` uses ordinary
callable lookup and `<Position>` in a type annotation names the struct. Existing
rules for local variables that shadow a function still apply in call contexts.

```cloth
fn<Position> Position() = { 0, 0 }
```

This declares an ordinary function returning the two-field `Position` struct.

### Local lookup for `::type` (confirmed 2026-11-04)

For a bare name before `::type`, check the local variable first. If a variable
with that name is in scope, use its static type. When no variable with that
name is in scope and it names a visible struct, use the struct as the type
receiver.

```cloth
def<int> Position = 7
Position::type // Describes int.
```

This example assumes a struct named `Position` is also visible. Without the
local variable, `Position::type` describes the struct. This follows the existing
[compiler data access contract](../../cloth-compiler-data/references/semantics.md)
for static type metadata and value receiver evaluation.

## Independent values: confirmed 2026-11-04

- Assigning or passing a struct to a function produces an independent value.
- Nested strings and arrays preserve their independent contents; copying a
  descriptor alone is insufficient if it exposes shared mutable array storage
  or storage that expires with a temporary/callee frame.
- Nested structs follow the same value-copy rule recursively.
- A class-valued field copies the object's identity. The object retains its
  explicit lifetime through copies and cleanup of the struct.
  [Class values](../../cloth-language/references/class-values.md)

Implementation may avoid redundant physical copies only while preserving this
observable independence and the required lifetimes.

## Immutable bindings and field writes: confirmed 2026-11-04

- A struct binding declared with `:=`, or a struct parameter declared `final`,
  cannot be rebound to another struct value.
- Fields of the bound struct may still be changed. Binding immutability does not
  freeze the struct's fields, matching the existing distinction for arrays.
- Field changes preserve independent value copies: modifying one copied struct
  does not change the original struct.

```cloth
def<Position> position := { 1, 2 }
position.x = 10       // Allowed: change a field.
position = { 3, 4 }   // Error: replace an immutable binding.
```

This example assumes a two-field `Position` declaration. Nested stored writes
and temporary struct field-write restrictions follow the rules below.

## Nested stored field and index writes: confirmed 2026-11-04

- A field assignment follows the field/index path into the containing stored
  value. `record.position.x = 10` updates the nested stored struct, and
  `positions[0].x = 20` updates the struct stored in that array element.
- This is a write to the stored path, not a write to an independent temporary
  created by an ordinary struct value read.
- Reading a struct into another binding still produces an independent copy:
  changing that binding's fields does not change the original record or array.

```cloth
record.position.x = 10
positions[0].x = 20

def position = positions[0]
position.x = 30 // Does not change positions[0].
```

These intended examples assume corresponding declarations and types. They do
not introduce shared struct identity.

## Temporary struct field assignments: confirmed 2026-11-04

- Assigning a field through a temporary struct value is a compile-time error.
  For example, `makePosition().x = 10` is rejected.
- This also applies to nested field/index paths rooted in temporary struct
  values and to struct results produced by evaluated deferred function blocks.
  A deferred binding's name evaluates its block; it is not stored struct data.
- For a direct field assignment, store the result in an ordinary binding first.
  The write changes that stored independent value.

```cloth
makePosition().x = 10 // Error: field assignment on a temporary struct.

def position = makePosition()
position.x = 10       // Allowed: field assignment on a stored struct.
```

A method call on a temporary struct can change its fields through `self` for
the duration of the call. See the
[struct method receiver rule](../../cloth-functions/references/semantics.md#struct-method-receivers).

## Aggregate literals and deferred blocks: confirmed 2026-11-04

For struct-typed bindings, keep the same `{ ... }` spelling for both forms:

- A list of field values constructs and stores a struct immediately.
- A statement body with an explicit return remains a deferred function block.
  It executes each time the binding is evaluated, under the existing deferred
  block contract; it does not run at declaration time or memoize its result.

```cloth
def<Position> position = { 1, 2 }
def<Position> next = {
    return makePosition()
}
```

The first binding stores a value. Reading `next` executes its body and produces
the returned struct value. The example assumes `Position` and `makePosition`
declarations.

For an expected struct type with one field, a single expression is sufficient.
`def<Score> score = { 7 }` constructs a `Score` with an `int` field set to `7`.
The literal requires no trailing comma.

Preserve existing non-struct deferred blocks, including void blocks. The parser
and AST design are implementation choices.

## Positional construction (confirmed 2026-11-04)

A struct literal supplies exactly one value per field, in declaration order.
Missing and extra values are compile-time errors. For a `Position` that
declares `int x, int y`, `{ 10, 20 }` sets `x` to `10` and `y` to `20`.
`{ 10 }` and `{ 10, 20, 30 }` both have the wrong field count.

## Expected type for literals (confirmed 2026-11-04)

A struct literal requires an expected struct type from its surrounding context.
An annotated binding, a known parameter type or a declared return type can
supply it. Field values alone cannot identify a struct declaration.

```cloth
def<Position> position = { 10, 20 }
moveTo({ 10, 20 }) // Assumes moveTo takes Position.
fn<Position> origin() = { 0, 0 }
```

An initializer such as `def point = { 10, 20 }` is a compile-time error because
there is no expected struct type.

## Calls with struct literals

Apply the existing [function overload rules](../../cloth-functions/references/semantics.md#function-overloads)
and [optional argument ranking](../../cloth-optional-arguments/references/semantics.md#overloads).
During type checking, each candidate's parameter can supply the expected struct
type. Check the literal's count and field expressions against that declaration.
The literal has no default struct type, and ordinary ranking compares whole
arguments. Select a unique best match and report a compile-time ambiguity if
multiple candidates remain equally good.

If `draw` has overloads for `ScreenPosition` and `WorldPosition` and both have
fields `int x, int y`, `draw({ 10, 20 })` is ambiguous. A typed binding supplies
the desired type before the call.

```cloth
def<ScreenPosition> point = { 10, 20 }
draw(point)
```

These are applications of the existing call rules and the contextual literal
requirement. Additional expected-type propagation paths remain open.

## Field evaluation and snapshots

Follow the existing [aggregate evaluation order](../../cloth-arrays/references/semantics.md#array-construction-and-value-semantics)
and [value snapshot rules](../../cloth-optional-arguments/references/semantics.md).
Evaluate field expressions once from left to right. Preserve the value of each
field before later expressions can change its source. Candidate checking is
compile-time work. The selected call evaluates its literal once as an argument.

## Field list separators (confirmed 2026-11-04)

Struct field declarations and aggregate literals follow the existing
comma-separated list grammar. Lists can span lines. A comma must separate
consecutive entries, and a comma after the last entry is a syntax error.

```cloth
def<Position> position = {
    10,
    20
}
```

## Empty structs (confirmed 2026-11-04)

A struct can declare zero fields. An empty literal `{}` constructs a value when
its expected type is a zero-field struct. That value has the struct's declared
type, so empty structs with different declarations have distinct types.

```cloth
pub typedef<struct> Marker {}
def<Marker> marker = {}
```

## Recursive field types (confirmed 2026-11-04)

The first structs implementation requires an acyclic field type graph. A struct
can contain other structs, arrays and nullable fields. Reject every path through
field types that reaches its starting struct again, including direct and mutual
recursion and paths through array or nullable wrappers.

```cloth
pub typedef<struct> Node {
    Node[] children
}
```

`Node` is a compile-time error in this slice. Report one diagnostic with the
source labels needed to explain the cycle.

## Explicit array field dimensions (confirmed 2026-11-04)

Every dimension of an array in a struct field declaration must have an explicit
extent. This includes arrays within nullable field types. `int[2][3] grid` is
valid. `int[] values` and `int[2][] grid` are compile-time errors.

The field's declared shape constrains every instance. Construction, direct
field assignment and whole-struct replacement all respect that shape. Apply
the existing [array shape checks](../../cloth-arrays/references/semantics.md#array-construction-and-value-semantics).
Reject a known mismatch at compile time and check an unknown source dimension
at runtime. The explicit dimension requirement applies to struct field declarations.

```cloth
pub typedef<struct> Series {
    int[2] values
}

def<Series> series = { [1, 2] }
series.values = [3, 4]    // Allowed.
series.values = [1, 2, 3] // Error. The declared length is two.
series = { [1, 2, 3] }    // Error. The replacement must fit the same field type.
```

## Presence proofs for stored fields (confirmed 2026-11-04)

A null check can establish presence on a nullable struct field. Later reads of
the same stored field path use the payload type when the compiler can prove the
field is unchanged. Assigning to the field or replacing a containing struct
invalidates the old proof. Apply existing flow rules to joins, repeated paths
and effects that can change the checked storage.

```cloth
if (record.position != null) {
    println(record.position.x)
    record.position = null
    println(record.position.x) // Error. The field is absent after reassignment.
}
```

Access to a field on a nullable receiver follows the ordinary requirement for a
presence proof or a non-null fallback. Continue to distinguish a field value
from a stored field place for assignments. See the existing
[nullability contract](../../cloth-nullability/references/semantics.md).

## Struct type metadata

Apply the existing [type metadata rules](../../cloth-compiler-data/references/semantics.md).
Metadata preserves nominal identity, so `ScreenPosition::type` and
`WorldPosition::type` differ even when their field lists match. Metadata for a
nullable stored field describes its declared type after a presence check.

## Equality scope (confirmed 2026-11-04)

Whole-struct `==` and `!=` are deferred, including equality between two
nullable struct values. Nullable presence comparisons with `null` continue to
use ordinary nullability rules. Comparisons of `typeinfo` values continue to
use nominal type identity, and individual field comparisons use their own type
rules.

## Implemented slice and deferred extensions

The compiler implements the rules above for ordinary source units, including
typed assignments, nested aggregate fields, function arguments and results,
deferred blocks and array traversal. Brace forms preserve existing deferred
block behavior and the compiler reports the first detected error.

Whole-struct formatting and output, `::size` and other reflection, methods,
constructors and field defaults, generics, and a native struct ABI remain deferred.
