# Compiler data access

Status: phase 3 implemented for currently supported types (2026-10-31).
Chunks 3.1 through 3.4 implement source accessor syntax, numeric bounds, sizes,
scalar text conversion and built-in type metadata. `addr` and array/class text
formatting remain deferred. Chunk 3.5 reviews integration and strengthens
metadata facts across control flow without adding providers or source syntax.

Compiler data access is the working name for compiler-provided data associated
with particular Cloth types or values. The user intends this facility to extend
beyond arrays, with the available data depending on the receiver's type.

The confirmed receiver-based spelling is `value::name`, without parentheses for
data access. The motivating example is this function-body fragment:

```cloth
def a = [1, 2]
def count = a::size // Produces 2 as unsigned int<64>.
```

Array count access is implemented under the confirmed contract below. Neither
`.length` nor a free-standing `::size(a)` has been adopted.

## Accepted compiler direction (2026-10-31)

The user accepted making the existing primitive classes the actual `ValueType`
descriptors passed through semantic analysis and emission. `CObject` is the common
compiler base; `CInt`, `CFloat`, `CBool`, `CChar`, `CString` and `CArray` own their
type-specific facts and LLVM value/storage representations. This internal base
does not establish an inheritance contract for Cloth source classes.

Providers supply typed results describing constants or operations. Each resolved
result owns its LLVM emission method; LLVM coupling is acceptable for the current
compiler. A shared emission path evaluates the receiver once and delegates to
that result.

Providers also own their contextual ("loose keyword") spellings. A spelling gains
provider meaning in the applicable receiver context rather than becoming a globally
reserved keyword. Parentheses distinguish state calls from provider access;
the confirmed rules and first implementation chunk are recorded below.

The source contracts confirmed below supersede earlier exploratory sketches,
including size meanings, result types and reuse of interpolation formatting. Primitive
descriptors describe the compiled program; their Kotlin instances do not store
the program's eventual runtime values.

## Implementation phases

1. **Type descriptors and LLVM representations (implemented).** Consolidate the
   previously separate primitive scaffolding and `ValueType` into `cloth.data`.
   Semantic analysis, ABI profiles and emission use the primitive classes
   directly. `LLVMTypes` delegates to descriptor methods. Preserve numeric
   contracts, recursive array shapes, descriptors and runtime behavior. Remove
   the unused `Any` provider API and its placeholder results.
2. **Typed provider infrastructure (implemented).** `ProviderResult<T>` supplies
   exact integer constants, floating constants, raw array extent reads and
   existing scalar interpolation formatting. Each recipe records its receiver
   type and result type and owns its LLVM emission. Type descriptors provide
   contextual lookup; only integer `min`/`max` are registered internally so far.
   `SemanticModel.providerAccess` resolves a receiver and spelling; shared
   `EmitExpression.emitProvider` evaluates that receiver once, including for
   constant results, preserves terminating control flow and delegates emission.
   Native receiver/result representations are checked against the descriptors.
   Receiver buffers conservatively retain their owning frame lifetime; buffered
   results use the existing return-copy/cleanup path. No provider dispatch switch
   or source syntax was added.
3. **Contextual source access (implemented for supported types).** Implement the confirmed contracts
   in bounded chunks:
   - **3.1 Syntax and integer bounds (implemented).** Type/value receiver AST,
     postfix chaining, separate state-call syntax, read-only targets, source
     diagnostics, provider resolution and source `min`/`max` for integer types
     and values. Type receivers emit without constructing a runtime value.
   - **3.2 Floating bounds and scalar storage sizes (implemented).** Exact finite
     limits preserve binary16/32/64 types; integer, float, boolean and character
     byte sizes return `unsigned int<64>`. Both receiver forms are supported by
     descriptor registrations without new parser or emission dispatch cases.
   - **3.3 Array/string sizes and scalar text conversion (implemented).** Outer
     counts, known-outer-extent type receivers, full unsigned 64-bit static counts,
     Unicode scalar counts and shared formatting. Inner runtime extents use one
     extra bit so unknown extents cannot alias a valid maximum count.
   - **3.4 Type metadata (implemented).** Built-in `typeinfo`, static type production,
     exact equality, normalized text and the confirmed inspection providers.
     Metadata uses immutable native descriptor handles; equality compares the
     complete normalized represented type, independently of handle identity.
     Definite represented-type facts reject invalid `element`, `extent` and
     `width` accesses at compile time; otherwise checked queries fail with a
     controlled runtime error carrying the source filename and accessor position.
   - **3.5 Integration follow-up (implemented).** Review
     the completed provider contracts and add combined regressions. Reuse existing
     control-flow proofs when joining metadata facts: returning branches,
     impossible constant branches and skipped/terminating logical operands do not
     erase definite facts on continuing paths. Unknown joins retain runtime checks.

Chunk 3.1 distinguishes `receiver::name(...)` in the AST and reports that state
calls are not implemented yet; it does not implement class construction, deletion
or state execution. Primitive type receiver syntax includes numeric modifiers,
widths and array suffixes. Chunks 3.2 and 3.3 add numeric bounds, sizes and scalar
text conversion. Chunk 3.4 adds `typeinfo` in bindings, parameters, results,
arrays, equality and interpolation. User-defined types remain tied to future
class support.

Phase 1 retains recursive `CArray(element, size)` descriptors to preserve existing
array behavior. It adds no source accessor syntax or public `size`/`min`/`max`/
`str`/`addr`/`type` behavior. Operator, conversion and native formatting dispatch
remain separate migration work; they were updated to the new class names without
changing their existing rules.

Phase 2's integer-bound registrations use the receiver's exact integer width and
signedness internally. The user subsequently confirmed the same source result
contract, with support for both type and value receivers. Raw
`ArrayExtent` operations require an explicit dimension and 64-bit result type;
known dimensions fold to constants, and unknown dimensions read native metadata.
An unwitnessed inner dimension can retain the existing -1 sentinel. This raw
metadata operation is separate from the subsequently confirmed outer-dimension
meaning of array `size`. `ScalarText` reuses interpolation formatting as an
internal operation. Chunk 3.2 registers floating bounds and scalar `size`;
chunk 3.3 registers array/string `size` and scalar `str` under their confirmed
contracts below. Chunk 3.4 registers `type` on the common descriptor base and
metadata queries on `CTypeInfo`; `addr` is explicitly deferred.
Unsupported lookups return no recipe; source-located first-error diagnostics
are implemented in phase 3.

## Confirmed source direction (2026-10-31)

Compiler data access supports both type receivers and value receivers. Each
provider declares whether it supports type receivers, value receivers or both;
support for one form does not imply support for the other. Type access requires
no instance construction or value evaluation. Value access retains once-only
receiver evaluation even for constant results. The confirmed providers' receiver
forms are recorded below. Provider names are strictly lowercase and
case-sensitive; uppercase spellings are not aliases. The user confirmed this
and updated the example on 2026-10-31.

Integer `min` and `max` support both receiver forms and return the exact integer
type of the receiver, preserving width and signedness. They yield that type's
minimum and maximum representable values. The user confirmed this source contract
on 2026-10-31.

Floating `min` and `max` yield the most negative finite value and largest positive
finite value, respectively. Both support type and value receivers and preserve
the exact floating-point width in their result type. The user confirmed this
on 2026-10-31. Floating `min` does not mean the smallest positive normal or
subnormal value.

Scalar `size` for integers, floats, booleans and characters means storage bytes.
It supports both type and value receivers and returns `unsigned int<64>`. The
user confirmed this on 2026-10-31. Integer and float results follow their selected
width divided by eight; `bool::size` is 1 and `char::size` is 4. This follows
storage representation rather than a boolean's one-bit LLVM value representation.

String `size` counts Unicode scalar values rather than UTF-8 bytes. The user
confirmed this on 2026-10-31. `"é😀"` has size 2; a base character followed by a
combining mark counts as two scalars. String `size` supports value receivers
only, returns `unsigned int<64>` and yields 0 for empty strings; `string::size`
is a compile-time error. The user confirmed these rules on 2026-10-31.
The current native string descriptor stores a UTF-8 byte length, so phase
3.3 counts validated UTF-8 leading bytes rather than exposing byte length directly.

Array `size` counts the outer dimension's elements; an empty outer array yields
0. For `[[1, 2, 3], [4, 5, 6]]`, the outer size is 2 and each row's size is 3.
The user confirmed this meaning on 2026-10-31 and subsequently revised the result
type from `unsigned int<32>` to `unsigned int<64>`. The source result width is
fixed independently of machine register or pointer width. If a count exceeds
the result type's range, reject the `size` access at compile time when the
overflow is known; otherwise produce a controlled runtime error. Never silently
wrap or truncate a count, including before it reaches the accessor.

Array `size` supports array values and fixed-size array type receivers. For
example, `int[5]::size` yields 5, while `int[]::size` is a compile-time error
because the type provides no count. The user confirmed this on 2026-10-31.

Only the outer extent must be statically known for type-receiver `size`;
unspecified inner extents do not prevent access. `int[2][]::size` yields 2,
while `int[][3]::size` is a compile-time error. The user confirmed this on
2026-10-31.

The phase 2 implementation carried only an evaluated value receiver. Phase 3.1
now represents type and value receiver forms explicitly. Constant recipes declare
support for both forms; other existing operations default to value-only. Type
access resolves and emits without manufacturing a runtime receiver value. Value
access preserves once-only evaluation, including indexing checks and side effects.

All compiler data accessors are read-only. Assignment to or updating an accessor
result is a compile-time error. A result copied into a mutable binding is an
independent stored value that follows ordinary type and binding rules; changing
it does not modify the receiver or provider data. The user confirmed this on
2026-10-31. This does not define deep-copy behavior for future class-valued
provider results.

Parentheses distinguish state-method calls from provider access:
`receiver::name` requests a provider, while `receiver::name(...)` calls a state
method. Both may share a name without shadowing each other. Construction and
deletion retain their established special forms. The user confirmed this on
2026-10-31.

Accessors bind tightly like indexing and chain left to right, resolving each
provider against the previous result's type. Value-producing expressions may be
receivers, including function results, indexed values and parenthesized
expressions, subject to provider availability. Evaluation order and once-only
receiver evaluation are preserved. The user confirmed this on 2026-10-31.

`str` supports value receivers only, returns `string` and uses the same formatting
as interpolation. Supported receivers are integers, floats, booleans, characters
and strings; string contents pass through unchanged. Type receivers are rejected.
The user confirmed this on 2026-10-31 and deferred array and class formatting
separately. The relationship to a future `toString()` method and class override
behavior remains open.

`type` returns a dedicated Cloth type-metadata value rather than a type-name
string, preserving information such as numeric width, signedness and array
element type. The user confirmed this on 2026-10-31. Its selected inspection
operations are recorded below; native representation is an implementation choice.
Kotlin descriptors are compiler objects, not the runtime representation of this
new Cloth value.

`value::type` reports the expression's static, compile-time type. A base-typed
binding holding a derived-class object reports its declared base type. The user
confirmed this on 2026-10-31; this facility does not report concrete runtime type.

`type` supports both type and value receivers. A type receiver produces metadata
for that type without constructing an instance; a value receiver describes the
expression's compile-time type. The user confirmed this on 2026-10-31.

Type metadata supports `==` and `!=` with exact represented-type equality,
independently of where the metadata value was produced. `int` and `int<32>`
represent the same type; width and signedness differences distinguish types.
The user confirmed this on 2026-10-31.

Array metadata equality includes statically known extents at every dimension;
unspecified extents are not wildcards. `int[]` metadata differs from `int[3]`
metadata, while two unspecified `int[]` types compare equal. Runtime lengths do
not alter static metadata. The user confirmed this on 2026-10-31.

The built-in `typeinfo` value type can describe any Cloth type. Different
represented types do not change the metadata value's own type; a mutable
`typeinfo` binding may be reassigned from `int::type` to `float<64>::type`.
The user confirmed this on 2026-10-31.

`typeinfo` values support `::str` and interpolation with the same normalized
Cloth type spelling. Numeric widths are always explicit, including default
widths: both `int::type::str` and `int<32>::type::str` yield `"int<32>"`.
Arrays include the normalized spelling of their element types. The user
confirmed this on 2026-10-31, changing the proposed default-width omission.
This does not change other diagnostic/source type spellings.

Array metadata exposes `::element`, returning the immediate element's `typeinfo`
and removing exactly one array dimension. For `int[2][3]` metadata, the result
describes `int<32>[3]`; another `::element` describes `int<32>`. The user confirmed
this on 2026-10-31. The user then confirmed that access on non-array metadata is
rejected at compile time when the represented type is known and produces a
controlled runtime error otherwise.

Every `typeinfo` value exposes `::isarray`, returning `bool`: true for array
types and false for all other represented types. This permits guarding
array-specific operations such as `::element`. The user confirmed this on
2026-10-31.

Every `typeinfo` value exposes `::rank`, returning the represented array's
dimension count as `unsigned int<64>`, or 0 for non-arrays. Unspecified extents
still count as dimensions: `int[]` has rank 1 and `int[2][3]` has rank 2.
The user confirmed this on 2026-10-31.

Every `typeinfo` value exposes `::hasextent` as `bool`. It is true for arrays
with a statically known outer extent, including 0, and false for arrays with an
unspecified outer extent and all non-arrays. Runtime lengths do not change this
query. The user confirmed this on 2026-10-31.

`::extent` returns the static outer array count as `unsigned int<64>`, including
0. Access is invalid for non-array metadata or an unspecified outer extent;
reject it at compile time when known and produce a controlled runtime error
otherwise. Runtime lengths are never substituted for static extents. The user
confirmed this on 2026-10-31.

`addr` is deferred and remains unavailable in phase 3. Its address meaning,
receiver forms, result type and lifetime contract require a separate design.
The user confirmed this scope decision on 2026-10-31.

`::width` on integer or floating-point metadata returns the bit width as
`unsigned int<64>`. Access for other represented types is rejected at compile
time when known and produces a controlled runtime error otherwise. The user
confirmed this on 2026-10-31. This reports numeric format width, not storage
bytes or array dimensions.

Every `typeinfo` value exposes `::isunsigned` as `bool`: true only for unsigned
integer types, false for signed integers and all non-integer types. The user
confirmed this on 2026-10-31.

Every `typeinfo` value exposes `::isnumeric` as `bool`: true for integer and
floating-point represented types, false for everything else. This permits
guarding `::width` access. The user confirmed this on 2026-10-31.

## Questions to resolve later

- The deferred `addr` address meaning, receiver forms, result type and lifetimes.
- Array/class text formatting and its relationship to the proposed `toString()`
  method and future class overrides.
- Additional metadata inspection operations beyond the confirmed phase 3 set.
- Its relationship to the user's exploratory soft/hard system-call idea; those
  categories have no confirmed definitions yet.

The confirmed contracts cover a first phase 3 implementation for currently
supported types plus `typeinfo`. Native metadata layout and source-located
first-error diagnostic wording are implementation choices. The deferred questions
do not require adding more providers before implementing the confirmed set.

The user confirmed outer-dimension array counts, including 0 for empty arrays,
an `unsigned int<64>` result and checked overflow. The earlier unsigned 32-bit
choice was superseded; the earlier signed `int` recommendation was not adopted.
