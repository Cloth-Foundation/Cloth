# Generic types: Stage 0 acceptance fixtures

These fixtures originated in Stage 0. [Stage 4](stage-4.md) now executes the
imported List and nullable acceptance sources and checks the native storage
contract. [Stage 5](stage-5.md) now supplies the real public standard-library List;
the fixtures remain historical compiler inputs, while public-library tests import
the actual file. Rejection inputs remain deliberately invalid.

The [generic contract](semantics.md) owns the rules. The
[Stage 0 record](stage-0.md) owns baseline results and current support.

## Acceptance: imported List with a private generic struct slot

Intended file `collections/List.co` in an explicit source module:

```cloth
typedef<struct> ListSlot<T> { T value }

pub typedef<class> List<T> {
    def<ListSlot<T>[]> items = []

    pub mt append(T value) { items.append({ value }) }
    pub mt<T> get(usize index) = items[index].value
    pub mt insert(usize index, T value) { items.insert(index, { value }) }
    pub mt<T> removeAt(usize index) {
        def<ListSlot<T>> removed = items.removeAt(index)
        return removed.value
    }
    pub mt<usize> size() = items::size
    pub mt clear() { items.clear() }
    pub mt reserve(usize minimum) { items.reserve(minimum) }
    pub mt<T[]> toArray() {
        def<T[]> result = []
        result.reserve(items::size)
        for (def<ListSlot<T>> item <- items) result.append(item.value)
        return result
    }
    pub mt<List<T>> copy() {
        def result = List<T>::new
        result.items = items
        return result
    }
}
```

Copying complete slots preserves nested buffers and hidden nullable-field
witnesses. A public constructor does not expose `ListSlot<T>`; private storage
and the implementation's defining-file bindings stay private.

Intended importing `Main.co`:

```cloth
using collections.List : List

fn<int> main() {
    def numbers := List<int>::new
    def alias = numbers
    numbers.reserve(8)
    numbers.append(1)
    alias.insert(0, 2)
    def removed = numbers.removeAt(1)
    def exported = numbers.toArray()
    numbers.clear()
    numbers.append(3)
    def copy = numbers.copy()
    numbers::delete
    exported.append(9)
    println(removed)
    println(exported)
    println(copy.get(0))
    copy::delete

    def words = List<string>::new
    words.append("é😀")
    def saved = words.get(0)
    words.clear()
    words::delete
    println(saved)

    def rows = List<int[]>::new
    rows.append([1])
    rows.append([2, 3])
    def rowCopy = rows.copy()
    def first = rows.get(0)
    first.append(9)
    def last = rows.removeAt(1)
    rows.clear()
    rows.append([])
    rows::delete
    println(first)
    println(last)
    println(rowCopy.get(0))
    println(rowCopy.get(1))
    rowCopy::delete

    println(List<int>::type == List<int>::type)
    println(List<int>::type == List<string>::type)
    return 0
}
```

Expected contents, followed by normal `PEC: 0`:

```text
1
[2, 9]
3
é😀
[1, 9]
[2, 3]
[1]
[2, 3]
true
false
```

The class aliases share identity. Results/exports are independent, rows have
different lengths, and explicitly created collections are explicitly deleted.
This fixture exercises generic classes and structs, substitution in fields and
methods, nested type arguments, imports, canonical identity and existing buffers.

## Acceptance: nullable substitution and explicit generic destinations

```cloth
typedef<class> Maybe<T> (def<T?> value ?: null) {
    pub mt<T?> get() = value
    pub mt<T> identity(T input) {
        def<T> copied = input
        return copied
    }
}

fn main() {
    def ordinary = Maybe<int>::new
    def nullable = Maybe<int?>::new
    def<int?> first = ordinary.get()
    def<int?> second = nullable.get()
    def<int?> value = nullable.identity(7)
    println(first)
    println(second)
    println(value)
    println(nullable.get()::type == int?::type)
    ordinary::delete
    nullable::delete
}
```

Both get results have one nullable layer. `def<T>` supplies the explicit receiving
type even when T contains nullability. Keep ordinary explicit nullable receiving
rules rather than adding inference exceptions for generic bodies. Execution prints
`null`, `null`, `7`, `true`, followed by normal completion.

## Additional acceptance matrix

| Input | Required result |
| --- | --- |
| `Pair<int, string>` and `Pair<string, int>` | Ordered arguments produce distinct exact closed identities and field types. |
| `List<List<int[]>>` | Nested closed class arguments parse through consecutive closing angles; outer elements copy inner List identity. |
| `List<int[2]>` versus `List<int[]>` | Fixed/growable array argument identities stay distinct; List row slots remain independent. |
| `List<int<129>?>`, `List<usize>`, `List<isize>` | Preserve wide nullable storage and target-sized identities through parameters/results. |
| Type parameter T with an outer type T | Owner T shadows the outer type in type positions; ordinary value lookup remains separate. |
| Generic header parameter, field initializer, method/default/result and deferred block using T | The same owner parameter is in scope, with existing default/capture rules. |
| Public imported template instantiated with a caller-private class | Allowed where the caller can name that class; actual arguments retain original identity. |
| Same-spelled private templates in separate files | Same text does not merge declaration identities or typeinfo. |
| Same imported template and arguments reached through multiple files/circular imports | Reuse one closed identity and preserve definition-file private names. |
| A same-key recursive class such as `Node<T>` containing `Node<T>?` | Reuse the provisional class identity; no infinite new instantiations. |
| Equal-width int, unsigned int, usize and isize arguments | Preserve semantic identities rather than merging equal native widths. |
| `RowList`-style generic copy after absent nullable slot fields | Preserve complete slot witnesses before source buffers/class owners are released. |

Class-valued elements remain caller-managed identities; clearing or deleting
their List does not implicitly delete those objects. Stage 4 executes buffer/lifetime
cases with the existing allocator/poison probes; its record distinguishes native
results from foreign-target IR/object evidence.

## Rejection: no type-argument inference or raw generic values

```cloth
typedef<class> Box<T> (def<T> value)
fn main() { def box = Box::new(1) }
```

Report missing type arguments at Box. The constructor argument does not infer T.
Likewise, `fn take(Box value) {}` rejects the raw template in a value type position.
Using/importing the template declaration itself for later explicit application is
different from treating a bare template as a concrete value type.

## Rejection: invariant arguments

```cloth
typedef<class> Parent {}
typedef<class> Child : Parent {}
typedef<class> Box<T> {}
fn take(Box<Parent> value) {}
fn main() { def value = Box<Child>::new; take(value) }
```

Report the incompatible argument to take. Child's ordinary class ancestry does
not make `Box<Child>` a subtype of `Box<Parent>`.

## Rejection: declaration checking happens even without instantiation

Each independent source is rejected during template declaration checking:

```cloth
typedef<class> Box<T, T> {}
fn main() {}
```

The second T is a duplicate parameter. `Box<Box>` likewise rejects a parameter
that duplicates its owner's name.

```cloth
typedef<class> Box<T> {
    pub mt<T> broken(T value) = missing
}
fn main() {}
```

Report the undefined name `missing`, even though Box has no closed use.

```cloth
typedef<class> Box<T> {
    pub mt<T> increment(T value) = value + 1
}
fn main() {}
```

Report an operation not guaranteed for unconstrained T. Similarly,
`value.nonexistent()` and `T::new` cannot assume members or a class constructor.
These are not deferred duck-typing tests that become valid by choosing int later.

## Rejection: private implementation types in public signatures

```cloth
typedef<struct> Hidden<T> { T value }
pub typedef<class> Box<T> {
    def<Hidden<T>[]> items = []
    pub mt<Hidden<T>[]> leak() = items
}
fn main() {}
```

The private field is allowed; the public method's Hidden result is not. Report
the signature exposure and label the private declaration, even without a closed
Box use. A public primary constructor taking Hidden is likewise an exposure,
even when it initializes a private field or supplies an omission fallback.

## Rejection matrix and first-error requirements

| Input | First required diagnostic |
| --- | --- |
| `Pair<int>` for a two-parameter Pair | Wrong arity at the application. |
| `Box<void>` | Invalid value type argument at void. |
| `Box<opt<int>>` | Omission-only wrapper is not a value type argument. |
| `Box<int??>` | The directly written duplicate nullable marker remains invalid. |
| Unknown owner parameter U in `Box<T>` | Unknown type/name at the original U token. |
| Constraints on an owner parameter in the first slice | Unsupported constraint syntax with the relevant source span; do not erase it. |
| Generic interface/function/method parameters, variance or type-argument defaults | Explicit first-slice boundary, not an accidental supported interpretation. |
| A private named helper exposed in a public generic signature | Visibility error at the exposed declaration/type, with the private declaration label. |
| Private template imported from another file | Existing import visibility failure. |
| Inline generic struct layout cycle | Existing inline-value cycle rule, rather than host recursion failure. |
| An expanding chain requiring new `Expands<T[]>` instances | Controlled instantiation limit error before unbounded work or emission. |
| More than the selected number of distinct closed instances | Controlled resource-limit error at the next requested instance. |
| Differently sized `List<int[]>` elements exported using `toArray()` | First executed rectangular shape guard; later effects and normal PEC do not run. |

Each case receives one compile-time diagnostic with explanatory source labels,
not recovery or error accumulation. A failed declaration/application creates no
native output. Diagnostic numbers for new generic errors are assigned during
their implementing stages; this record does not claim new codes already exist.

## Resource boundary tests for implementing stages

The selected policy needs exact boundary checks, not a host-stack failure test:

- Permit 64 newly required closed instances along a dependency chain; fail before
  expanding the 65th. Reusing a same-key recursive class does not charge another
  chain step. Inline struct/inheritance cycles still use their existing checks.
- Permit 4,096 distinct closed class/struct keys per compilation; reject the next.
  Duplicate graph routes, equal normalized nullable applications and repeated
  requests for one key do not increase the count. Private generic slot instances
  count alongside public class instances.
- Permit 256 levels of generic argument structure; reject before recursing into
  the 257th. This does not cap ordinary array rank or change integer widths.
- Keep limits scoped to one target-aware compilation. A second compilation starts
  fresh; source-name collisions do not merge unrelated identities.

The [contract's selected summaries](semantics.md#selected-implementation-policies)
and source labels identify each first failure. These cases are specified for
future implementation; Stage 0 has not executed generic boundary guards.
