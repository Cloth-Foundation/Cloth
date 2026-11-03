# Arbitrary fixed integer widths: integrated implementation

Final integration/publication: 2026-11-03. The
[accepted numeric contract](semantics.md#arbitrary-fixed-integer-widths-design-confirmed-2026-11-03)
owns language semantics; this record owns the finished implementation, source
routing, target evidence and limits. The
[implementation plan](../../../../todo/arbitrary-integer-widths-plan.md) retains
the contract and six implementation stages. Earlier stage records preserve dated
scope and measurements.

## Supported source contract

Signed `int<N>` and `unsigned int<N>` admit every whole literal width 1..65536.
Plain signed/unsigned int remains 32 bits. Floats remain exactly 16/32/64 with
default 32; bool remains a separate nonnumeric type. Existing radix/suffix width
literals normalize to the same type; expressions/fractional widths are not added.
Zero and widths above 65536 fail with compact located C3011.

```cloth
fn main() {
    def<int<31>> x = 12
    def<unsigned int<129>> large = 18446744073709551616
    println(x)
    println(large * large)
}
```

The maintained [IntegerWidths.co](../../../../examples/IntegerWidths.co) also
demonstrates bounds, wrapping, narrowing/widening, a nonzero result above uint64,
metadata and unchanged float syntax. It executes with exact expected output on
Windows x64/x86, and its objects verify across all nine supported profiles.
[README](../../../../README.md#fixed-integer-widths) introduces the feature.

Signed values range from -2^(N-1) to 2^(N-1)-1; unsigned values from zero to
2^N-1. Literals must fit their context; a large literal does not infer a larger
type. Typed widths/signedness and fixed/usize/isize identities remain exact.
Ordinary integer operations wrap modulo 2^N. Explicit casts narrow to low bits
and widen according to source signedness. One-bit signed values are -1/0,
unsigned 0/1. Keep the existing power/range policies documented in the arithmetic
record; this feature does not independently redesign those operators.

Values compose with supported bindings, functions/defaults/recursion, ordered
results, conditionals, nullable values, arrays/rows/foreach, ranges/while,
providers and signed main. Decimal output retains every bit through all routes.
C4031 is retired. Runtime zero division, signed minimum/-1, negative exponents
and invalid float casts retain controlled located failures. Compilation stops
at the first detected error; no recovery or multi-error reporting was added.

## Representation and source ownership

| Area | Owning record and implementation |
| --- | --- |
| Width model, bounds, literals, exact typing and independent target limits | [Stage 2](arbitrary-width-types.md), CInteger/CInt, TypeResolver, NumericLiterals and semantic typing |
| Wrapping arithmetic, comparisons, guarded division/remainder, powers, casts and 2N checks | [Stage 3](arbitrary-width-arithmetic.md), EmitWideInteger, IntegerKernelIR, IntegerFloatKernelIR and scalar emitter dispatch |
| Physical bytes, snapshots, private calls/results, nullability/arrays, indices, layout and entry | [Stage 4](arbitrary-width-storage.md), EmitStoredValue, ValueType.indirect, EmitFunctions/EmitFunctionFrame, EmitStringArena, EmitArray and EmitEntryPoint |
| Decimal conversion, capacities, recursive traversal and scratch lifetimes | [Stage 5](arbitrary-width-output.md), EmitIntegerText, IntegerTextKernelIR, EmitString and EmitArrayText |
| Raw backend experiments and selected policy | [Stage 1](arbitrary-width-backend.md), IntegerBackendProbe and durable baseline tables |
| Final example, production measurements and regression | [Publication tests](../../../../Compiler/src/test/kotlin/cloth/ArbitraryIntegerPublicationTest.kt), [production probe](../../../../Compiler/src/test/kotlin/cloth/backend/IntegerProductionProbe.kt) and the evidence below |

Keep exact LLVM scalar values through 64 bits. Above 64 use inline arrays of
native-sized limbs (i64 on 64-bit profiles, i32 on Windows x86), least significant
first. Private expression/call handles point to stable snapshots; helpers operate
on i32 chunks with i64 intermediates. Mask logical high bits and zero unused
wide padding. No huge LLVM scalar, per-arithmetic heap object, GC or public
pointer/reference syntax is introduced. No LLVM handles belong in shared type
descriptors or survive their context.

`ValueType.indirect` classifies wide transport separately from source string/array
buffering. Reads snapshot before later effects; stores copy bytes inline. Callees
receive independent parameters and copy outgoing values into caller-owned storage
before cleanup. Nullable storage uses byte presence plus inline payload/padding;
small value/call carriers use presence and snapshot pointers. Arrays copy inline
integer bytes rather than allocating a block per integer element.

The source cap stays 65536; checked range updates may compute at 131072 bits
internally. Do not reject a valid source width because its internal calculation
needs more bits. Header overflow exits before storing a wrapped counter, after
stepper effects. Safe loop regions reclaim arena suffixes/stack scratch; unknown
captures/publication remain conservative. General bounded memory for arbitrary
unproven loops is not promised.

## Layout, output and native boundaries

`::type::width` reports logical N as target usize. `::size`, nullable padding,
array stride and checked byte copies all use the emitted target storage type:

| Integer | Windows x64 bytes | Windows x86 bytes |
| --- | ---: | ---: |
| int<12> | 2 | 2 |
| int<24> | 4 | 4 |
| int<65> | 16 | 12 |
| int<129> | 24 | 20 |
| int<65536> | 8192 | 8192 |
| int<65536>? | 8200 | 8196 |

These are allocation facts, not packed collection/serialization guarantees.
Pointer, usize/isize, C ABI and array-count limits remain independently validated.
Wide indices compare every original bit against the target-sized bound before
narrowing. Signed main normalizes low native C-int bits; unsigned main remains
invalid. The private module ABI is not a C interop or public cross-module ABI.

Small integer/native float formatting retains its verified C path. Wide decimal
formatting uses a private base-1e9 magnitude loop, without mutating the input or
losing signed minimum. Capacity is ceil(N*30103/100000)+2, computed with integer
arithmetic: at the cap 19731 bytes reserve all digits, sign and NUL. Every store
is guarded. Print/interpolation/str/nullable/recursive-array output share this
path. Per-element stack checkpoints precede snapshot decoding in both array
passes; one measured output buffer receives nested text directly.

## Production backend/resource evidence

The durable [production matrix](arbitrary-width-production.tsv) contains 18
isolated integrated-source cases: widths 65535/65536 across all nine ABI profiles.
Each child uses the same 512 MiB Java heap cap and per-pool heap observation as
Stage 1. Two workers ran concurrently with a 60-second child deadline. LLVM is
the bundled 22.1.8/JavaCPP 1.5.14; production generic CPU/features, PIC and default
codegen settings are retained.

This is dynamic nonzero work: source functions receive full-width operands for
dense multiply/add, division/remainder and power. An independent BigInteger
oracle requires product and remainder to retain nearly all source bits. Signed
division, floating conversions, nullable array text and a checked range step
(2N up to 131072 internally) execute too. Each native run's 98674 output bytes
match the oracle exactly. Constant-folded zero-only programs cannot pass this
probe. IR checks exclude wide scalar instructions and unrolled blowups; assembly
shows bounded i32/i64 multiply/divide loops on x86_64 and AArch64.

Observed width-65536 measurements:

| Profile | Object bytes | Object emission ms | Sum of heap-pool peaks, bytes |
| --- | ---: | ---: | ---: |
| Windows x86 MSVC | 108161 | 57 | 73467016 |
| Windows x64 MSVC | 111146 | 54 | 72428416 |
| Windows AArch64 MSVC | 109799 | 66 | 62993744 |
| Linux x64 GNU | 113064 | 64 | 62888696 |
| Linux AArch64 GNU | 112144 | 56 | 62988888 |
| Linux x64 musl | 113064 | 56 | 62874424 |
| Linux AArch64 musl | 112144 | 60 | 63008856 |
| macOS x64 | 109992 | 61 | 62944080 |
| macOS AArch64 | 114240 | 53 | 63022072 |

Across both widths, object emission takes 53..69 ms, objects occupy
108161..114240 bytes and the recorded JVM heap-pool sum is 62874424..73480264
bytes. Width-65536 source analysis/IR generation takes 158..196 ms. These are
single-run observations, not performance guarantees. Object emission includes
production target-machine setup. The heap metric is a sum of individual pool
peaks, not a simultaneous whole-heap peak, native LLVM memory or process working
set; do not infer a total-memory bound from it.

For comparison, the [Stage 1 raw baseline](arbitrary-width-baseline.tsv) records
full-cap scalar calls at 369387 bytes/4115 ms on Windows x64 and 588453 bytes/
7352 ms on x86. Raw macOS x64 core arithmetic occupies 1474824 bytes/9683 ms;
some raw cap arithmetic cases timed out. The integrated helper program contains
more behaviors than those isolated probes, so this is strategy/resource evidence,
not an apples-to-apples speedup benchmark. Heap observations are similarly not
uniformly lower: raw Windows call-only probes recorded about 57..59 MiB versus the
integrated source compiler's roughly 69..70 MiB. Keep that distinction explicit.

Native Windows x64/x86 checks pass for both widths; their process run intervals
are 66..130 ms including launch/output. All 18 objects and assembly outputs pass.
No compiler-rt 128-bit arithmetic/conversion dependency appears; Windows x86 uses
its existing CRT 64-bit division/remainder helpers, and Windows stack probes
remain ordinary platform dependencies.

Supplementary [Linux execution evidence](arbitrary-width-linux-execution.tsv)
links the GNU x64 objects with the installed Ubuntu WSL `cc -lm` and runs both
programs successfully. The output hashes match the same oracle as Windows;
observed dispatch/run intervals are 99/95 ms including WSL invocation. This adds
Linux x64 GNU execution evidence. It does not validate musl, AArch64 or macOS
execution. No AArch64 cross compiler/emulator was installed in that environment;
no runtime/toolchain installation was performed for this stage.

## Reproduction and verification

From the repository root:

```powershell
.\gradlew.bat :Compiler:test --tests 'cloth.ArbitraryInteger*' --tests cloth.ArrayTemporaryCleanupTest --offline --console=plain --no-daemon
.\gradlew.bat :Compiler:probeIntegerProduction --offline --console=plain --no-daemon
.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon
```

The opt-in production probe writes measured artifacts beneath
`tmp/integer-production-stage6/`; `--args="--widths=65536 --targets=aarch64-unknown-linux-gnu"`
selects a smaller matrix. Inspect recorded statuses rather than treating object
success as native execution. The supervisor fails if any production case fails,
crashes or times out. Temporary test artifacts clean themselves; preserve desired
measurement tables before removing opt-in scratch.

`ArbitraryIntegerPublicationTest` adds three regressions for native example output,
nine-profile example objects/CLI object publication and isolated cap execution on
available native profiles. The focused run passes 88 tests across nine suites.
The existing NumberTypes.co fixture also independently checks
`unsigned int<0x200>` initialized from `unsigned int<512>::max` with BigInteger;
the example itself was preserved. Its five tests plus the three publication tests pass in a
follow-up targeted run.
The dated [development entry](../../cloth-language/references/development.md#arbitrary-integer-widths-final-integration-and-publication-2026-11-03)
records the final full regression and metadata/link checks.
Observed final result: 1081 tests across 86 suites pass, zero failures/errors/skips,
in 4m32s. Skill metadata/local links and whitespace checks pass; scratch is clean.
The bundled skill validator lacks PyYAML, so metadata/link validation used local
manual checks rather than claiming that unavailable validator passed.

All planned implementation stages are integrated. Remaining verification limits
are native AArch64, macOS and musl execution, not width/operation-specific source
gates. New bitwise operators, atomics, packing, serialization, public C/cross-module
ABI, dynamically growing precision and arbitrary float formats remain outside
this feature. Later optimizations must retain the accepted cap, independent value
semantics and observed first-error/runtime behavior.
