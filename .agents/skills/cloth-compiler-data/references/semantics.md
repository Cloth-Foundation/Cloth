# Cloth compiler data access: confirmed semantics

These decisions were recorded in the shared Cloth semantics reference.
User-confirmed rules govern intended behavior; historical implementation
notes and unresolved choices retain their original qualifications. Read
the parent skill for current implementation boundaries and source/test routing.

## Compiler data access (phase 3 implemented for supported types)

Nullable type receivers now expose the payload's instance-independent providers
under the [nullable provider contract](nullable-providers.md). Numeric bounds
return the exact plain payload type; fixed-outer-extent nullable arrays expose
their count as usize. Nullable type identity and scalar storage-size semantics
take precedence over delegation. Nullable value payload access still needs proof.

The later `usize` decision below supersedes the earlier fixed unsigned-64 result
types for size/count/width providers. Those source contracts now specify
`usize`; step 5 of the [usize migration](../../cloth-language/references/usize-implementation.md) implements
those result types for the supported primitives, arrays, strings and metadata.

- Chunks 3.1 through 3.4 implement the confirmed contracts below for the
  currently supported primitive/array types and built-in `typeinfo`. Class/state
  execution, `addr` and class text conversion remain deferred. Recursive array
  `str` was integrated on 2026-11-02; see the
  [output contract](../../cloth-output/references/semantics.md#recursive-array-text). The early
  planning notes below are historical; subsequent confirmed decisions govern.

- On 2026-10-31 the user deferred array length/count access until a broader
  type-specific facility can be designed. Its working name is **compiler data
  access**, intended to expose compiler-provided data for types beyond arrays.
- The proposed receiver-based form is `value::name`: with `def a = [1, 2]`,
  `a::size` would produce 2, without parentheses. This records the design
  direction, not implemented support or a complete syntax/typing contract.
- Result types, multidimensional meaning and the relationship to the proposed
  soft/hard system-call categories remain unresolved. The earlier `.length`
  recommendation and its suggested `int` result were not adopted.
- On 2026-10-31 the user confirmed that providers own their contextual ("loose
  keyword") spellings, valid in the applicable receiver context rather than
  globally reserved. The user accepted an implementation direction using
  `ValueType`, typed provider results and emission methods owned by those results;
  coupling that layer to LLVM is acceptable. The discussed accessor examples
  remain sketches; their size types, size meanings and formatting rules are
  proposals except where subsequently confirmed below.
- The user subsequently approved a phased implementation of behavior-bearing
  Kotlin type descriptors, retaining their `CObject`, `CInt`, `CFloat`, `CChar`,
  `CString` and `CArray` names and adding `CBool`. These are the compiler's actual
  types, shared by semantic analysis and LLVM emission, rather than a parallel
  wrapper hierarchy. This authorizes internal refactoring; it does not settle
  source accessor meanings, introduce primitive boxing or change runtime storage.
- The user authorized phase 2's typed provider results and provider-owned LLVM
  emission. These internal operations may now be implemented and tested without
  introducing source accessor syntax; the deferred result-type, size, formatting
  and dispatch questions above remain open.
- On 2026-10-31 the user confirmed support for both type receivers and value
  receivers in compiler data access. Each provider declares which receiver forms
  it supports; supporting one form does not automatically enable the other. The
  receiver-form assignments for individual providers remain to be settled.
  A type receiver does not require constructing or evaluating an
  instance; value receivers retain the agreed once-only evaluation behavior.
- The user confirmed strictly lowercase provider spellings on 2026-10-31.
  Names such as `max` and `size` are case-sensitive; uppercase variants are not
  aliases. The user updated the example accordingly.
- Integer `min` and `max` support both type and value receivers. Each returns
  the receiver's exact integer type, preserving width and signedness, and yields
  that type's minimum or maximum representable value. For example, signed
  `int<8>` has a maximum of 127, while unsigned `int<8>` has a maximum of 255.
  This source contract was confirmed by the user on 2026-10-31.
- Floating `min` and `max` yield the most negative finite value and largest
  positive finite value, respectively. They support both type and value
  receivers and return the receiver's exact floating-point type, preserving
  its width. The user confirmed this on 2026-10-31. Floating `min` does not mean
  the smallest positive normal or subnormal value.
- Scalar `size` for integers, floats, booleans and characters means storage
  size in bytes. It supports both type and value receivers and returns
  `usize`. Its earlier unsigned-64 result was superseded by the later
  `usize` decision on 2026-10-31. For example,
  `int::size` is 4, `float<16>::size` is 2, `float<64>::size` is 8,
  `bool::size` is 1 and `char::size` is 4. This uses the scalar's storage
  representation, so a boolean's one-bit LLVM value still reports one byte.
- String `size` counts Unicode scalar values, not UTF-8 bytes. For example,
  `"é😀"` has size 2; a base character followed by a combining mark counts as two
  scalars. The user confirmed this on 2026-10-31. The current UTF-8 descriptor's
  byte length is not the source-level scalar count.
- String `size` supports value receivers only, returns `usize` and
  yields 0 for an empty string. A type receiver such as `string::size` is a
  compile-time error because no string contents are supplied. The user
  confirmed this on 2026-10-31.
- `str` supports value receivers only and returns `string`, using the same
  formatting as interpolation. It applies to integers, floats, booleans,
  characters and strings; string receivers preserve their contents. Type
  receivers are rejected. Recursive array formatting was subsequently confirmed
  on 2026-11-02 under the linked output contract; class formatting remains deferred.
  The user confirmed this on 2026-10-31. This does not settle the relationship
  to the separately proposed `toString()` method or future class overrides.
- `type` returns a dedicated Cloth type-metadata value, preserving structural
  information such as numeric width, signedness and array element type rather
  than reducing the result to a type-name string. The user confirmed this on
  2026-10-31. Its confirmed inspection operations are recorded below; native
  representation is an implementation choice. This describes a Cloth value,
  not exposure of Kotlin descriptor instances to compiled code.
- `value::type` reports the expression's static, compile-time type, not its
  concrete runtime type. The user confirmed this on 2026-10-31. For a binding
  declared as a base class that holds a derived-class object, the result
  describes the base type.
- `type` supports both type and value receivers. A type receiver produces
  metadata describing that type without constructing an instance; a value
  receiver produces metadata describing its expression's compile-time type.
  The user confirmed this on 2026-10-31.
- Type-metadata values support `==` and `!=` using exact represented-type
  equality, independent of where a metadata value was produced. For example,
  `int::type == int<32>::type` is true, while comparisons to `int<64>::type`
  or `unsigned int::type` are false. The user confirmed this on 2026-10-31.
- Array type-metadata equality includes statically known extents at every
  dimension. Unspecified extents are not wildcards: `int[]::type` differs from
  `int[3]::type`, while two `int[]::type` values compare equal. Runtime lengths
  do not change static metadata. The user confirmed this on 2026-10-31.
- The built-in `typeinfo` value type can describe any Cloth type. Metadata
  describing different types has the same value type, `typeinfo`, so a mutable
  `typeinfo` binding can be reassigned from `int::type` to `float<64>::type`
  under ordinary binding rules. The user confirmed this on 2026-10-31.
- `typeinfo` value receivers support `str`, returning a normalized Cloth type
  spelling with fixed numeric widths always explicit, including default widths.
  Interpolation of `typeinfo` uses the same text. Thus `int::type::str` and
  `int<32>::type::str` both yield `"int<32>"`, and an array of default-width
  integers includes that width in its spelling. The user confirmed this on
  2026-10-31, overriding the proposed omission of default widths. Other
  diagnostic/source type spellings are not changed by this decision.
- The later usize decision uses `"usize"` for its normalized metadata text,
  including in arrays such as `"usize[3]"`. Its numeric width is exposed by
  `usize::type::width`; it is never normalized into a fixed unsigned int type.
- A `typeinfo` value describing an array exposes `element`, returning the
  immediate element's `typeinfo` and removing exactly one array dimension.
  Thus `int[3]::type::element` describes `int<32>`, while
  `int[2][3]::type::element` describes `int<32>[3]`. Repeated access follows
  the remaining array structure. The user confirmed this on 2026-10-31.
- Reject `element` on non-array metadata at compile time when the represented
  type is known. Otherwise, accessing `element` on non-array metadata produces
  a controlled runtime error. The user confirmed this on 2026-10-31.
- Every `typeinfo` value exposes `isarray`, returning `bool`: true when the
  represented type is an array, false for every other represented type. This
  permits guarding array-specific metadata operations such as `element`.
  The user confirmed this on 2026-10-31.
- Every `typeinfo` value exposes `rank`, returning the represented array's
  dimension count as `usize`, or 0 for a non-array type. Unspecified
  extents still count as dimensions: `int[]` has rank 1 and `int[2][3]` has
  rank 2. The user confirmed this on 2026-10-31.
- Every `typeinfo` value exposes `hasextent`, returning `bool`. It is true for
  arrays with a statically known outer extent, including 0, and false for
  arrays with an unspecified outer extent and all non-array types. Runtime
  lengths do not affect this static metadata query. The user confirmed this
  on 2026-10-31.
- On array metadata with a statically known outer extent, `extent` returns
  that count as `usize`, including 0. Reject `extent` on a non-array
  or an array with an unspecified outer extent at compile time when known;
  otherwise produce a controlled runtime error. Never substitute a runtime
  array length for the static extent. The user confirmed this on 2026-10-31.
- `addr` is deferred and must remain unavailable in phase 3. Its address meaning,
  receiver forms, result type and lifetime contract require a separate design.
  The user confirmed this scope decision on 2026-10-31.
- On 2026-11-01 the user accepted the design direction of explicit unsafe
  acknowledgement for raw memory handles: `unsafe def memory = thing::addr`
  may acquire a handle outside an unsafe block, while reading or writing through
  it requires an unsafe context. Copying a handle must preserve its restrictions
  and require acknowledgement; passing one to a native API also requires an
  unsafe context. This is language design only; `addr` remains unimplemented.
  The handle's type spelling, unsafe parameter contracts, escape and
  lifetime rules, and the precise boundary with normal deletion guarantees
  remain under discussion. The previously illustrated `read()` and `write()`
  names and single-statement unsafe form are proposals, not settled syntax.
- On 2026-11-01 the user confirmed that an `unsafe fn` makes its entire body
  an unsafe context; memory operations inside it do not require nested unsafe
  blocks. Calling an unsafe function requires an unsafe context, which may be
  an unsafe block or another unsafe function's body. This settles the scope of
  acknowledgement, not raw-handle parameter spelling or runtime memory safety.
- On 2026-11-01 the user requested a standalone
  [unsafe-memory design and implementation plan](../../../../todo/unsafe-memory-plan.md)
  and deferred implementation while nullable values, `null`, coalescing/Elvis
  operations and related contracts are still being explored. Implicit handle
  reads/writes, `unsafe T` parameter/result spelling and compile-time origin
  analysis are proposals in that plan, not newly confirmed language rules.
- On metadata describing an integer or floating-point type, `width` returns
  the bit width as `usize`. Reject access for other represented
  types at compile time when known; otherwise produce a controlled runtime
  error. The user confirmed this on 2026-10-31. This query reports numeric
  format width, not storage bytes or array dimensions.
- Every `typeinfo` value exposes `isunsigned` as `bool`. It is true only for
  unsigned integer types, and false for signed integers and every non-integer
  represented type. The user confirmed this on 2026-10-31.
- Every `typeinfo` value exposes `isnumeric` as `bool`, true for integer and
  floating-point represented types and false for every other type. This permits
  guarding `width` access. The user confirmed this on 2026-10-31.
- Array `size` counts elements in the outer dimension. An empty outer array
  yields 0. For `[[1, 2, 3], [4, 5, 6]]`, the outer array's `size` is 2 and
  either row's `size` is 3. The user confirmed this on 2026-10-31.
- Array `size` returns `usize`. On 2026-10-31 the user first revised the
  earlier unsigned 32-bit choice to unsigned 64-bit, then accepted the
  target-sized primitive and migration of compiler-provided sizes/counts.
  The later `usize` decision supersedes that fixed-width result contract.
- Reject a `size` access at compile time if its count is known to exceed the
  result type's range; otherwise, a count overflow produces a controlled runtime
  error. Never silently wrap or truncate the count. The user confirmed this
  policy alongside the unsigned 64-bit result on 2026-10-31.
- Array `size` supports value receivers and fixed-size array type receivers.
  For example, `int[5]::size` yields 5. An unsized array type receiver such as
  `int[]::size` is a compile-time error because the type provides no outer count.
  The user confirmed this on 2026-10-31.
- For array type-receiver `size`, only the outer extent must be statically
  known. Unspecified inner extents do not prevent access: `int[2][]::size`
  yields 2, while `int[][3]::size` is a compile-time error. The user confirmed
  this on 2026-10-31, clarifying which partially specified array types support
  the provider.
- All compiler data accessors are read-only: assigning to or updating an accessor
  result is a compile-time error. Copying a result into a mutable binding permits
  changing that independent stored value under its ordinary type and binding
  rules; this does not modify the receiver or the provider's data. The user
  confirmed this on 2026-10-31. This does not settle deep-copy semantics for future
  class-valued provider results.
- Parentheses distinguish state-method calls from compiler data access:
  `receiver::name` requests a provider and `receiver::name(...)` calls a state
  method. A provider and state method may share the same name without shadowing
  each other. Construction and deletion retain their established special forms.
  The user confirmed this on 2026-10-31.
- Accessors bind tightly like indexing and chain left to right. Any
  value-producing expression can be a value receiver, subject to the provider's
  supported types and receiver forms. Each step resolves its provider using the
  previous result's type, preserving evaluation order and once-only receiver
  evaluation. The user confirmed this on 2026-10-31; examples include
  `makeArray()::size`, `matrix[0]::size`, `(a + b)::max` and `values::size::max`.
- Track the deferred design and its open questions in
  [compiler data access](../../../../todo/compiler-data-access.md).
