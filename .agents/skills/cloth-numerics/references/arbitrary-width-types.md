# Arbitrary integer widths: Stage 2 type and literal integration

Stage 2 complete: 2026-11-03. This record owns Stage 2 of the
[implementation plan](../../../../todo/arbitrary-integer-widths-plan.md).
The [numeric design](semantics.md#arbitrary-fixed-integer-widths-design-confirmed-2026-11-03)
and [Stage 1 representation decisions](arbitrary-width-backend.md#selected-representation-and-lowering-policy)
remain authoritative. This stage integrates the frontend model, not the pending
arithmetic/storage/output implementation.

Historical boundary: the emission statements below describe Stage 2. Later
[Stage 3](arbitrary-width-arithmetic.md) enables source scalar widths 1..64 and
implements wide arithmetic/conversion kernels; widths above 64 remain gated.
The later [Stage 4](arbitrary-width-storage.md) enables both examples as numeric
values; [Stage 5](arbitrary-width-output.md) subsequently integrates full decimal
text and retires C4031. The old boundary below remains historical evidence.

## Implemented semantic scope

Every signed `int<N>` and `unsigned int<N>` with a whole literal width from 1
through 65536 is a valid semantic type. Plain signed/unsigned int remains 32 bits;
floats remain 16/32/64 with default 32. Existing literal forms also denote widths:
`int<31>`, `int<0x1F>`, `int<0b11111>`, `int<0o37>` and `int<31i>` have the same
identity and function-signature key. Width expressions and floating widths are
not added. Zero, negative syntax, fractional widths and widths above 65536 fail.

```cloth
def<int<31>> x = 12
def<unsigned int<65>> large = 18446744073709551616
```

These examples pass semantic analysis. The current native emission boundary
described below still rejects them until the following stages are integrated.

- Signed values range from `-2^(N-1)` to `2^(N-1)-1`; unsigned values range from
  zero to `2^N-1`. Literal notation does not change bounds or signedness. The
  signed minimum's magnitude remains representable in the parser before unary
  minus receives context. Positive signed overflow and negative unsigned values
  remain errors rather than wrapped or reinterpreted literals.
- Without context, integers still default to signed int32. A large literal does
  not infer a large type. The parser now retains integer magnitudes through
  65536 bits, so a value just above uint64 can reach contextual range checking;
  a larger-than-cap magnitude still fails with C2004 during parsing.
- Context is preserved in bindings, reassignment, arrays, calls, returns,
  omission defaults, named arguments, nullable destinations/fallbacks, ordered
  results/broadcast reception, conditionals with a declared receiving context and
  numeric loops. Already-typed values still require exact widths, signedness and
  fixed/target-sized identity. This stage does not change conditional inference
  when no destination context is supplied.
- Default int32 overload preference and candidate isolation remain intact.
  A literal exceeding int32 can fit a wider candidate, without widening a
  different typed value or leaking facts from a rejected candidate.
- `int<1>` holds -1 and 0; unsigned int<1> holds 0 and 1. Bool remains a distinct
  nonnumeric type. Width-one runtime arithmetic/powers/ranges are still pending
  backend work, not new truthiness or boolean casts.
- Shared BigInteger wrapping and existing known division/remainder checks retain
  the chosen width. Zero divisors and signed minimum/-1 remain invalid. Constant
  evaluation does not introduce unbounded power construction or execute calls.

## Source ownership and limits

[ValueType](../../../../Compiler/src/main/kotlin/cloth/data/ValueType.kt) now
separates these facts:

| Fact | Definition | Meaning |
| --- | --- | --- |
| Source fixed width interval | MIN_INTEGER_BITS = 1, MAX_INTEGER_BITS = 65536, FIXED_INTEGER_WIDTHS | Validate explicit int widths and the parser magnitude ceiling |
| Machine width set | MACHINE_INTEGER_WIDTHS = 8/16/32/64 | Preserve existing target, usize/isize and C ABI validation |
| Largest target count width | MAX_TARGET_INTEGER_BITS = 64 | Bound a target-independent CArray descriptor without growing usize |
| Floating formats | FLOAT_WIDTHS = 16/32/64 | Preserve rigid formats and floating defaults |

The interval is an IntRange, not a 65536-element set, table or registry of bounds.
Machine/native regression loops now explicitly name the machine-width set; the
new semantic suite owns arbitrary-width coverage.

- [CInt/CArray](../../../../Compiler/src/main/kotlin/cloth/data/PrimitiveTypes.kt)
  validate fixed widths and the independent maximum target count respectively.
  [CUSize](../../../../Compiler/src/main/kotlin/cloth/data/CUSize.kt),
  [CISize](../../../../Compiler/src/main/kotlin/cloth/data/CISize.kt) and
  [TargetLayout](../../../../Compiler/src/main/kotlin/cloth/target/TargetLayout.kt)
  keep the machine set. This preserves existing synthetic machine-layout support
  without authorizing arbitrary pointer/native C widths.
- [TypeResolver](../../../../Compiler/src/main/kotlin/cloth/semantic/TypeResolver.kt)
  compares decoded width BigIntegers against the fixed interval before narrowing
  to host Int. Huge values that truncate to 31 or 32 cannot masquerade as valid
  widths. Float widths retain their three-member validation/message.
- [ExpressionParser](../../../../Compiler/src/main/kotlin/cloth/parser/subs/ExpressionParser.kt)
  uses MAX_INTEGER_BITS for exact integer magnitudes. Existing NumericLiterals
  decoding and structural signature keys preserve notation-independent values.
- [CInteger](../../../../Compiler/src/main/kotlin/cloth/data/CInteger.kt)
  checks literal fit using signed BigInteger bit length, avoiding creation of
  two enormous bounds on every candidate probe. Bounds and wrapping remain exact
  BigInteger recipes when requested.
- Integer provider registration no longer attempts whole-byte size arithmetic
  while merely registering odd/wide bounds or metadata. New widths use a lazy
  [StorageSize recipe](../../../../Compiler/src/main/kotlin/cloth/data/ProviderResult.kt);
  existing machine-width size constants remain unchanged. Target layout and
  physical carriers must be connected in Stage 4 before emitting new-width size
  recipes. This stage does not publish planned limb sizes as emitted facts.

Known array extents still fit the selected target usize. A parsed AST containing
`int<31>[4294967296]::type` passes semantic analysis on the 64-bit profile and
fails on the 32-bit profile, including when those layouts reuse the same AST.
The global descriptor extent ceiling remains uint64 maximum, not uint65536.

## Located diagnostics and native emission boundary

C3011 now describes invalid fixed widths as 1 through 65536 without enumerating
the interval. Its label retains the original width token and source location,
including prefixed/suffixed/underscored forms. Float C3011 still lists 16, 32, 64.
Contextual integer/float range checks retain C3004/C3028; malformed syntax and
beyond-cap magnitudes retain their existing parser/lexer meanings.

**C4031 / UNSUPPORTED_INTEGER_LOWERING** identifies a valid integer type whose
native implementation is not integrated yet. This is distinct from invalid
width C3011. Current emission still supports only 8/16/32/64-bit fixed integers.
It must not silently feed a huge scalar to LLVM or claim partial operation
support for a newly accepted type.

The Stage 2 `IntegerEmissionSupport` preflight (removed by
[Stage 5](arbitrary-width-output.md)) inspected function signatures,
nested array/nullable types, committed expression
and statement types, represented metadata, provider receivers/results, type-check
targets and loop bindings. It stops on one unsupported type and throws one
located diagnostic. Sorting source facts for deterministic visitation does not
collect diagnostics or add recovery.

[EmitFunctions.validate](../../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitFunctions.kt)
and the [LLVM emitter](../../../../Compiler/src/main/kotlin/cloth/emitter/LLVM.kt)
run this preflight before creating LLVM modules or output paths. Executable and
snippet compilation also check before linker discovery. Direct CInteger LLVM
type construction retains a defensive C4031 boundary, even when a caller bypasses
normal source validation. The located source preflight is the ordinary path.

This applies to all emitter APIs, unused function boundaries, type-only metadata
and skipped expressions. It is a temporary stage boundary, not a permanent
restriction to machine widths. Advance its integrated-width policy only when the
corresponding arithmetic, carriers/calls and output paths are integrated. Do not
remove it solely because raw LLVM accepts an iN type.

Semantic errors still terminate the checker immediately. Thus an out-of-range
initializer later in a semantically checked body can be the first detected error
before native preflight examines otherwise valid types. CLI failures return 1,
render one structured diagnostic and leave no output; no recovery, multiple-error
reporter or backend crash path was introduced.

## Verification and remaining work

[ArbitraryIntegerWidthsTest](../../../../Compiler/src/test/kotlin/cloth/ArbitraryIntegerWidthsTest.kt)
contains 13 semantic/diagnostic tests. It constructs every signed/unsigned width
through the cap, then samples extrema and contextual assignment, all literal
radices, exact width decoding/source spans, one-bit identities, provider setup,
defaults/calls/receptions/loops, normalized overloads and strict typed rejection.
It checks independent target bounds with a reused AST, all emission entry points,
defensive LLVM type construction and CLI first-error/output behavior. Its own
temporary files live under workspace tmp/ and are removed after each test.

Existing unsupported-width fixtures now use genuine invalid widths, including
zero and 65537, rather than newly valid 7/24/128. Existing uint64/usize overflow
fixtures now expect contextual C3004 where the larger parser ceiling allows the
value to reach typing. Native tests keep their explicit machine-width matrices;
this stage does not run arbitrary-width source programs successfully.

Focused command:

```powershell
.\gradlew.bat :Compiler:test --tests cloth.ArbitraryIntegerWidthsTest --offline --console=plain --no-daemon
```

Full regression command:

```powershell
.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon
```

Observed results: all 13 new tests pass, zero failures/errors/skips. The full
compiler run passes 1047 tests across 80 suites, with zero failures/errors/skips.
This includes existing native Windows x64/x86 and nine-profile IR/object
regressions, not native execution of new-width Cloth programs. Stages 3-5
still own integer operation/conversion/range lowering, storage/calls/providers
and full-width decimal output; Stage 6 retains final publication/portability.
