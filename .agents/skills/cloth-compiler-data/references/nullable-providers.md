# Nullable type providers (2026-11-03)

The user requested the missing nullable type providers, including `int?::max`.
Nullable type receivers now expose instance-independent providers supported by
their payload type. These queries construct no nullable value and read no payload.

```cloth
def maximum = int?::max // Plain int, with the same bound as int::max.
def byteLimit = unsigned int<8>?::max // Plain unsigned int<8>, value 255.
def count = int[2]?[3]?::size // usize, value 2.
```

## Contract

- Numeric `T?::min` and `T?::max` return plain T, preserving exact width,
  signedness and usize/isize identity. Floats retain the ordinary finite bounds,
  including the most negative finite value for min. Null introduces no ordered
  numeric endpoint and these constant results cannot be null.
- A fixed-outer-extent nullable array type exposes `size`, returning the known
  outer count as target usize. Inner nullable rows and unspecified inner extents
  do not prevent this query: `int[2][]?::size` is 2. An unsized outer extent still
  cannot provide a type-level count.
- Nullable-owned providers take precedence. `T?::type` describes T?, preserving
  nullable metadata identity. Nullable scalar `::size` retains complete storage
  bytes, including presence and ABI padding, rather than delegating payload size.
- Delegation is type-only. An unproven nullable value's `value::min`/`::max`
  still requires presence; after proof the ordinary payload provider applies.
  Buffered value `::size` still needs presence, including fixed-size arrays.
  Nullable scalar storage size and declared-type metadata remain absence-aware.
- Instance-dependent type queries are errors: `string?::size`, `int[]?::size`,
  `int?::str` and `typeinfo?::rank` report invalid receiver form. Unknown provider
  names, case mismatches and deferred `addr` remain unknown-provider errors.
  The former blanket C3060 for nullable type providers is removed.
- Bounds are ordinary strictly typed values in bindings, calls, results, optional
  defaults and arithmetic. Numeric constants remain discoverable for existing
  compile-time division/remainder checks and range validation. They do not create
  implicit widening or new nullable inference.

## Source ownership

- `CNullable.providers` delegates only payload recipes admitting TYPE receivers,
  excluding keys supplied by nullable-owned recipes.
- `ProviderResult.NullableType` validates that its delegate belongs to the exact
  payload type and supports TYPE. It emits through a payload TYPE receiver and
  keeps the delegate's result type. It supports no VALUE receiver and performs no
  nullable extraction. Recipes retain no context-owned LLVM handles.
- `ProviderResult.integerConstant`/`floatingConstant` expose numeric facts from
  ordinary constants and delegate them through nullable type recipes.
  `ExpressionTyping` and `RangeConstants` consume those facts without concrete
  recipe casts, preserving compile-time checks.
- `ExpressionTyping` distinguishes unknown provider names, invalid receiver forms
  and unproven nullable payload access. Metadata provenance still uses the
  nullable-owned TypeMetadata recipe. First-error compilation is unchanged.

Example: [NullableTypeProviders.co](../../../../examples/NullableTypeProviders.co).

## Verification

Use `NullableTypeProvidersTest`, `ProviderResultsTest`, `ScalarProvidersTest`,
`BufferProvidersTest`, `TypeInfoTest`, nullable suites and range tests.
The new suite checks every supported fixed-width signed/unsigned integer and
float, target-sized usize/isize, exact result types, defaults, overloads,
nullable storage/metadata identity, fixed/partial array counts, unsupported
receivers and compile-time zero division. The CLI matrix checks one diagnostic
before output/linking in executable/object modes on both native widths.

Native execution covers Windows x64/x86. The same fixture verifies LLVM IR and
emits objects for all nine supported profiles; this is not native Linux/macOS
execution evidence. See [development history](../../cloth-language/references/development.md#nullable-type-providers-2026-11-03)
for fresh test results.
The full `:Compiler:test --offline --console=plain --no-daemon` run passed all
1,013 tests across 76 suites with zero failures, errors or skips.
