# Growable arrays: confirmed language contract

Stage 0 decisions confirmed by the user on 2026-11-07. This is intended language
behavior, with implementation support staged in the
[implementation plan](../../../../todo/growable-arrays-and-lists-plan.md).
The [Stage 0 record](growable-arrays-stage-0.md) owns its baseline;
[Stage 1](growable-arrays-stage-1.md) owns typing, receiver/effect and whole-array
reception support. [Stage 2](growable-arrays-stage-2.md) owns capacity, private typed
storage operations and executable unsized struct fields. Public mutators retain
the C4032 execution boundary.
Existing [array semantics](semantics.md) continue to own element
inference, exact typed values, rectangularity, independent copies and snapshots.

## Types, inference and reception

- `T[]` is an array with a growable outer length. `T[N]` has exactly N elements.
  Growth is explicit in the type: `def a = [1, 2, 3]` still infers `int[3]`,
  while `def<int[]> a = [1, 2, 3]` declares a growable value initially containing
  three elements. A known current count does not make a growable type fixed.
- Copying or receiving a growable value without a fixed receiving annotation
  preserves its growable type. In particular, `def copy = a` for an `int[]`
  source infers `int[]`, including when the current count is statically known.
  Results declared `T[]`, including deferred function-block reads, follow that
  same rule when stored. Reading a computation still evaluates it on each use.
- A mutable `T[]` binding may receive another outer length. A `T[N]` destination
  requires N elements: a known mismatch fails during compilation; an unknown
  length is checked at runtime. Both forms retain the ordinary exact element
  identity and rectangular inner-shape rules.
- Receiving `T[N]` into `T[]` produces an independent growable value. Receiving
  `T[]` into `T[N]` checks N and produces an independent fixed-size value. These
  are array-shape reception rules for bindings, fields, arguments and results,
  not implicit numeric or element-layout conversions.
- Each array parameter is an independent value. A function can grow its own
  `T[]` parameter; changing the caller's binding requires returning and assigning
  the changed array. Fixed parameters and results retain their explicit extents.
- An explicitly typed empty initializer such as `def<int[]> a = []` starts with
  zero live elements. Growth does not add a new element-inference rule for an
  uncontextualized empty literal.
- Value `::size` reports current outer length as target `usize`. Fixed type-only
  size queries retain their existing capabilities. Known length facts and spare
  capacity do not change the declared type, canonical spelling or type identity.

```cloth
def fixed = [1, 2, 3]         // int[3]
def<int[]> growing = fixed   // Independent int[]; current length is 3.
def copy = growing           // Independent int[], not int[3].
copy.append(4)
growing = [5, 6]             // Permitted: growing still has type int[].
```

Examples using mutators type-check in Stage 1 and become executable in later
storage stages. Neither assigning a growable value nor calling a function changes the
type or contents of an independent source array.

## Binding immutability and value lifetimes

`:=` and `final` keep Cloth's binding-only immutability. They forbid rebinding
that name but permit array contents and length mutation when its type is
growable. Read-only default-evaluation views still forbid mutation.

```cloth
def<int[]> values := [1, 2]
values.append(3)             // Permitted mutation of the bound array.
// values = [1, 2, 3]       // Rejected: the binding cannot be reassigned.
```

Copies, arguments, results, removed elements, extracted rows and foreach captures
retain recursive value independence. Class-valued elements copy class identity
rather than cloning objects. Removing or clearing a class identity does not
implicitly delete the object; explicit class deletion remains the lifetime rule.
Deleting a collection releases its owned array/value buffers under existing
field ownership rules. An owning collection of class objects needs its own
explicit element-deletion policy.

## Initial array API

The user selected all five operations below. The displayed parameter names
describe the operands; ordinary primitive member argument rules still apply.

| Operation | Parameters | Result | Behavior |
| --- | --- | --- | --- |
| `append(value)` | Exact element type T | `void` | Append one independently received element. |
| `insert(index, value)` | `usize`, exact T | `void` | Insert before index, preserving order. Valid indices are 0 through current length, inclusive. |
| `removeAt(index)` | `usize` | T | Remove an existing element, preserving order, and return its independent value. The index must be below current length. |
| `clear()` | None | `void` | Remove every live element, retaining reusable capacity and established inner-shape constraints. |
| `reserve(minimum)` | `usize` | `void` | Ensure capacity for at least minimum elements without changing length or contents. A request already satisfied is a no-op. |

These mutators are the growable `T[]` surface. `T[N]` retains ordinary indexed
replacement; it does not acquire append/insert/removal/clear/reserve. Index/count
arguments follow exact `usize` typing with ordinary contextual integer literals.
This does not change the integer identities already accepted by indexed `a[i]`.

Only live, initialized elements contribute to length. New elements come from
supplied values. Spare capacity cannot be indexed, formatted or traversed.
General resize, implicit defaults for T, bulk append, capacity shrinking and
public capacity inspection remain outside this first surface.

Invalid types/receiver forms and known shape mismatches fail during compilation.
Executed invalid indices, dynamic shape mismatches and allocation failures use
controlled first-error termination. Preserve the existing checked-index policy,
one compile-time diagnostic, once-only evaluation and failure ordering.

A bound in the stored receiver path is checked before explicit arguments, as for
ordinary methods. The operation's supplied index is checked against the selected
array's current length when mutation begins, after explicit arguments have been
evaluated. Insertion's inclusive upper bound remains distinct from removal's
exclusive upper bound.

## Stored and temporary receivers

Array mutation follows existing method receiver behavior, confirmed after review
of the draft plan's proposed restriction to stored receivers.

- A stored receiver mutates the selected array storage, including a local,
  parameter or field reached through `self`. Resolve its receiver path and
  indices once before explicit arguments. Argument values snapshot in written
  order. When mutation starts, use the current contents of that selected place,
  rechecking any owner, presence, bounds or shape affected by intervening effects.
- A temporary growable receiver is allowed. Materialize/protect its independent
  value before later arguments execute. Mutation affects that temporary only;
  result values follow ordinary independent-result lifetime rules. For example,
  `makeArray().removeAt(0)` may return an element from a result declared `T[]`.
- A temporary whose type is fixed still has a fixed extent. Allowing temporaries
  does not make an unannotated fixed-size literal growable.
- A nullable receiver needs a valid presence proof. As with existing stored
  struct receivers, reject a call if an explicit argument invalidates a required
  nullable presence proof before mutation begins. Dynamic identity and bounds
  checks protect paths whose validity cannot be established statically.
- Receiver mutation is distinct from reading an independent array value. A field
  read copied to a separate binding remains independent of subsequent mutation
  of the original field. No public pointer, reference or `mut` syntax is added.

An element supplied by the receiver, such as `values.append(values[0])`, must be
protected before reallocation or replacement can invalidate its old storage.
Returning a removed buffered element must preserve it beyond release of the
receiver or its temporary owner.

## Rectangular and nullable arrays

Only the outer length of an independent array value is growable. Rectangular
rows remain constrained by their enclosing array's established inner shape.

```cloth
def<int[][3]> matrix = [[1, 2, 3]]
matrix.append([4, 5, 6])      // Adds a complete matching row.
matrix.clear()              // Outer length becomes zero; width stays 3.
matrix.append([7, 8, 9])
// matrix.append([1, 2])    // Rejected: wrong row width.
// matrix[0].append(10)     // Rejected: an embedded row cannot change shape.
def<int[]> separate = matrix[0]
separate.append(10)          // Independent extracted row; matrix is unchanged.
```

- Unspecified inner dimensions acquire a witness from the first present row.
  All present rows must match that witness recursively. Null rows provide no
  witness; a present empty row establishes an actual zero extent.
- Established inner shape survives row removal, all-null rows, `clear()`,
  whole-array replacement and null assignment for the lifetime of that stored
  binding. Emptying the outer axis does not reset width or permit a new width.
  Explicit dimensions constrain present values even before any witness exists.
- For a nullable growable outer array, the outer count is current value state,
  not a permanently retained constraint. Established inner dimensions and any
  explicitly fixed extents survive absence. A later present value may have a
  different growable outer count while still satisfying those retained extents.
- Copying array data also makes the shape witnesses independent. Extracting a
  row into an explicitly growable independent binding permits changing its own
  outer length without changing the enclosing source array.
- `reserve` changes storage capacity, not any array dimension. It cannot weaken
  a retained row constraint. General resizing of matrix columns or multiple
  embedded rows together is outside this contract.

Foreach keeps its existing source evaluation once per loop entry and independent
contents/shape snapshot. Appending, removing or clearing the original array
cannot change the current traversal's visited values or count.

## Superseded rules and implementation boundary

This revision supersedes the 2026-10-31 requirement to infer and permanently
retain the initialized outer extent of a stored `T[]`. It applies to locals,
class/struct fields, parameters and stored results, including nullable arrays
and deferred computation results. Explicit `T[N]`, rectangular embedded-row
constraints, exact element identities and independent values remain in force.

Stage 0 published this contract without changing compilation. Stage 1 implements
growable types, current-count/inner-witness facts, the five signatures and
stored/temporary receiver plans. Whole-array replacement with a different outer
count executes. [Stage 2](growable-arrays-stage-2.md) adds owned capacity, private
typed reserve/relocation/release and executable unsized struct array fields with
retained nullable witnesses. Public mutator execution is still guarded by C4032;
private helper probes do not establish source-level growth. Record each supported
slice in the [array skill](../SKILL.md).

Generic `List<T>` remains dependent on a separately specified and implemented
user-defined generic-type facility. Growable arrays supply its typed backing
storage; Stage 0 establishes neither a public List API nor a List-specific
ownership, indexing or iterator protocol.
