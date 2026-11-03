# Arbitrary integer widths: Stage 1 backend feasibility

Stage 1 complete: 2026-11-03. This record owns the representation decisions, probe
implementation and observed verification for Stage 1 of the
[implementation plan](../../../../todo/arbitrary-integer-widths-plan.md).
The [accepted numeric design](semantics.md#arbitrary-fixed-integer-widths-design-confirmed-2026-11-03)
still governs source semantics. Stage 1 did not widen accepted Cloth source
types: at that milestone only signed/unsigned 8/16/32/64-bit integers were admitted,
and floats remain 16/32/64 with default 32.

For current support, read the
[final implementation record](arbitrary-width-integers.md). It links later
typing, arithmetic, storage and output records and the completed production matrix.
The measurements below remain Stage 1 evidence.

## Probe implementation and invariants

- [IntegerBackendProbe](../../../../Compiler/src/test/kotlin/cloth/backend/IntegerBackendProbe.kt)
  provides a supervisor and a child entry point, outside production sources.
  [The Gradle task](../../../../Compiler/build.gradle.kts) `:Compiler:probeIntegerWidths`
  uses the test runtime classpath. Children use Java argument files to avoid
  Windows command-line length limits, and native-access settings matching the
  compiler. JVM crash logs and every experiment's files live under workspace tmp/.
- Each target/width/group has a separate JVM, a wall-clock timeout, a phase
  marker, object size and generation duration, layout facts, native status and
  helper-like symbols. A timeout kills descendants as well as the JVM. An LLVM
  fatal error cannot terminate the supervisor or the Gradle test worker.
- [IntegerProbeIR](../../../../Compiler/src/test/kotlin/cloth/backend/IntegerProbeIR.kt)
  generates raw LLVM experiments, bypassing current source validation. Integer
  operands come from volatile loads and helper calls are noinline. Native drivers
  compare full-width results against independently calculated BigInteger values;
  zero-only or constant-folded smoke tests are not the acceptance mechanism.
- Core probes cover wrapping add/subtract/negate, signed/unsigned comparison,
  narrowing and sign/zero extension, loads/stores, phi and select. Separate groups
  cover multiply, both signednesses of division/remainder, floating round trips,
  direct recursive calls/aggregate results and indirect recursive copies.
  Signed minimum/-1 and zero divisors are excluded from raw instruction inputs;
  compiler/runtime guard integration remains Stage 3 work.
- The `fp` group uses float32/64; `half` isolates native LLVM half conversion.
  `fp-mixed` reproduces the initial experiment that combined all three formats.
  Existing Cloth half conversion is software-based; failure of a raw half probe
  is not evidence of a regression in Cloth's current float16 implementation.
- [IntegerLimbProbeIR](../../../../Compiler/src/test/kotlin/cloth/backend/IntegerLimbProbeIR.kt)
  prototypes bounded word loops for carry propagation, high-limb masking,
  nullable-carrier storage, indirect results and recursive copies. It tests
  sign-boundary crossing, all-limb wrap to zero and a distributed-bit sum against
  full-width expected arrays. It is a feasibility prototype, not a production
  integer runtime or evidence that multiplication/division/casts are integrated.
- [ArbitraryIntegerBackendTest](../../../../Compiler/src/test/kotlin/cloth/ArbitraryIntegerBackendTest.kt)
  verifies generated IR across the planned widths, native odd-width arithmetic,
  full-cap limb execution and isolation of a rejected experiment. Tests create
  and remove their own directories under tmp/.

The task's successful exit means the supervisor finished collecting experiments,
not that every experiment passed. Read statuses and phases in results.tsv.
`OBJECT_PASS` does not imply linkability or native execution. `NATIVE_PASS` means
the produced executable linked and its full-value checks returned zero.
`FAIL/link` preserves linker diagnostics, while CRASH/TIMEOUT records the last
completed phase. The heap column sums the JVM heap pools' individual peak usage;
it is not a simultaneous whole-heap peak, LLVM native memory or total process
peak working set. Native peak memory was not
available through this harness and must not be inferred from the heap column.

## Bundled backend and pipeline

The compiler and experiments use LLVM 22.1.8 through JavaCPP 1.5.14, with generic
CPU, production target features, PIC relocation and default codegen optimization.
The bundled llc reports that same LLVM version. Its pass traces for x86_64 and
AArch64 include `libcall-lowering-info` and `expand-ir-insts` before instruction
selection; the latter is displayed as "Expand IR instructions". Older standalone
pass names are not the configuration authority for this version.

The x86_64 trace after expand-ir-insts leaves i65 division intact, while i129
division is expanded to an IR loop. The resulting i65 object references
`__divti3`, `__modti3`, `__udivti3` and `__umodti3`; the default Windows CRT does
not supply those helpers in the observed link. This explains why some widths
below a larger passing width can nevertheless fail to link. Do not infer
support for an interval from one successful width or from IR verification alone.

The raw AArch64 floating probes also reproduce an LLVM fatal error,
`unsupported library call operation`, at wider integer widths. Native half
probes on Windows can reference `__extendhfsf2` and `__truncsfhf2` which the
selected runtime does not supply. Maintain Cloth's existing software half path.

Pipeline inspection uses the bundled llc with `-debug-pass=Structure` and
`-print-after=expand-ir-insts`, the selected target triple, `-mcpu=generic`,
`-relocation-model=pic`, `-O2` and the probe input IR. This is inspection evidence;
the measurements and objects themselves use the same C API target-machine
configuration as the production emitter, without adding a Clang frontend or a
new runtime dependency to Cloth.

## Observed raw baseline

The baseline completed 918 isolated cases: 17 widths (including the internal
131072-bit width), six operation groups and nine ABI profiles. Durable
[baseline measurements](arbitrary-width-baseline.tsv) preserve every status,
object size, codegen duration, layout and available heap/helper observation.
The initial combined floating group is labeled `fp-mixed` in that file; constant
pool symbols are filtered from the helper column. The initial control-flow case
was later strengthened with an independent low-bit condition; final IR/native
regressions and the additional core probes cover that stronger case.

| ABI profile | Native pass | Object-only pass | Link/other failure | LLVM crash | Timeout |
| --- | ---: | ---: | ---: | ---: | ---: |
| Windows x86 MSVC | 76 | 0 | 10 | 0 | 16 |
| Windows x86_64 MSVC | 75 | 0 | 14 | 0 | 13 |
| Windows AArch64 MSVC | 0 | 85 | 0 | 7 | 10 |
| Linux x86_64 GNU | 0 | 91 | 0 | 0 | 11 |
| Linux AArch64 GNU | 0 | 85 | 0 | 7 | 10 |
| Linux x86_64 musl | 0 | 91 | 0 | 0 | 11 |
| Linux AArch64 musl | 0 | 85 | 0 | 7 | 10 |
| macOS x86_64 | 0 | 91 | 0 | 0 | 11 |
| macOS AArch64 | 0 | 85 | 0 | 7 | 10 |

Totals: 151 native passes, 613 object-only passes, 24 failures, 28 crashes and
102 timeouts. These are experiment outcomes, not accepted compiler failures.
Two children ran concurrently with a 12-second wall deadline per child, including
JVM startup, code generation and any linking/running. A TIMEOUT does not prove
an operation can never lower; its phase identifies what was still running.

Representative raw outcomes:

- int<12> uses two storage bytes, while int<24> stores three bytes but has a
  four-byte allocation stride/alignment. Raw i65 has a nine-byte store size and
  sixteen-byte allocation/alignment on the measured profiles. This differs from
  the selected limb carrier and is why size cannot be computed as N/8.
- Windows x64 direct/aggregate call experiment at width 65536: 369387 object
  bytes, 4115 ms code generation, successful native checks. Windows x86:
  588453 bytes, 7352 ms, successful native checks. Passing huge scalar calls
  therefore do not establish an efficient complete representation.
- macOS x86_64 raw core experiment at width 65536: 1474824 object bytes and
  9683 ms code generation. Many other full-cap arithmetic cases exceed the
  deadline, including multiplication/division. Foreign native runs are not
  implied by these object measurements.

## Observed limb, conversion and control-flow follow-up

The [follow-up measurements](arbitrary-width-followup.tsv) contain 306 cases:
153 limb cases and 153 isolated float32/64 conversion cases, with two children
and a 15-second wall deadline. The limb cases all passed: 34 native passes on
Windows x64/x86 and 119 foreign object passes. These include every planned width,
65536-bit values and the 131072-bit internal intermediate. At widths below the
selected threshold, the limb group is only a prototype experiment; it does not
replace the chosen small-scalar representation or change bool storage.

| Full-cap limb prototype target | Object bytes | Codegen ms | Payload bytes | Alignment | Nullable bytes |
| --- | ---: | ---: | ---: | ---: | ---: |
| Windows x86 | 66708 | 8 | 8192 | 4 | 8196 |
| Windows x86_64 | 67004 | 8 | 8192 | 8 | 8200 |
| Windows AArch64 | 66985 | 8 | 8192 | 8 | 8200 |
| Linux x86_64 GNU | 67824 | 7 | 8192 | 8 | 8200 |
| Linux AArch64 GNU | 68216 | 8 | 8192 | 8 | 8200 |
| Linux x86_64 musl | 67824 | 11 | 8192 | 8 | 8200 |
| Linux AArch64 musl | 68216 | 8 | 8192 | 8 | 8200 |
| macOS x86_64 | 67064 | 8 | 8192 | 8 | 8200 |
| macOS AArch64 | 67608 | 9 | 8192 | 8 | 8200 |

Object sizes include the full-width input/expected-value fixtures. COFF section
inspection on Windows x86 measures just 360 bytes of .text at widths65535,
65536 and 131072 (328 bytes at width65). Thus generated loop code remains
bounded while fixture data grows with width. Native internal-width probes also
pass at 131072: 16384 payload bytes, with nullable storage 16388 on x86 and
16392 on x64. These are prototype measurements, not production throughput or
all-operation benchmarks.

Separating ordinary floats from half leaves 21 native passes, 82 object-only
passes, six link failures, 28 crashes and sixteen timeouts. The six link failures
are widths65/127/128 on Windows x64/x86: the objects reference unavailable
128-bit integer/float runtime conversions. Wider widths can take a different
LLVM expansion path, confirming that isolated successes are not an interval
support guarantee. All four AArch64 profiles still crash on raw conversions at
129, 257, 1024, 4096, 65535, 65536 and 131072; the failure is therefore not
explained solely by raw half conversion.

The [additional core measurements](arbitrary-width-flow.tsv) cover widths31,
65 and 129 on all nine profiles with assembly inspection, including the
strengthened phi/select case. All 27 passed: six native, 21 object-only.

The main measurement matrices contain 1251 cases. A further
[six repeat assembly inspections](arbitrary-width-assembly.tsv) at widths65536
and 131072 pass on Windows x64/x86 (native) and Linux AArch64 (objects). Inspected
addition/copy assembly uses bounded loops over 1024/2048 i64 limbs or 2048/4096
i32 limbs, with carry instructions and native-word memory operations rather than
unrolled full-width arithmetic.

Source feature acceptance, wide multiplication,
division, casts, formatted output and value-snapshot integration remain work for
later stages even though the representation prototype passes.

## Selected representation and lowering policy

These are compiler implementation choices for subsequent stages, not new
programmer-visible types or a public binary ABI.

1. **Source range:** retain the accepted 1..65536 range on x86_64 and AArch64.
   Use that same range on the already supported Windows x86 profile through
   software lowering. Pointer, usize/isize and C ABI widths remain independent.
2. **Small values:** use exact iN LLVM values for N <= 64. Preserve current
   float16 software conversion and existing native C ABI adaptations. Probe
   evidence supports this conservative threshold; it is not a hardware limit.
3. **Wide values:** use a private fixed-count array of native-sized unsigned
   limbs: i64 on supported 64-bit targets, i32 on Windows x86. Limb zero holds
   the least significant bits. The count is ceil(N/limbBits); mask unused top
   bits after construction/operations. Signed interpretation uses logical bit
   N-1, not the limb sign or padding. No raw i65536 SSA arithmetic is required
   by this design. Wide expression handles refer to compiler-owned value
   storage; avoid loading/copying the entire limb array as a giant LLVM SSA
   aggregate merely to call an operation helper. A handle must represent the
   evaluated value's snapshot, not a mutable binding that later effects can
   change before the operation consumes it.
4. **Storage:** for small values use target LLVM allocation size/alignment of
   their storage type. For wide values use allocation size/alignment of the limb
   array. Numeric width remains N; `::size` measures actual storage, and array
   stride/copy/allocation checks must agree. Nullable size includes its flag and
   ABI padding. Existing widths and bool storage remain unchanged. Target-local
   sizes are allowed to differ: int<65> is planned as 16 bytes on 64-bit targets
   and 12 bytes on Windows x86 under this representation. int<65536> has 8192
   payload bytes on both. This is not a packed-bit collection guarantee.
5. **Functions/results:** lower wide parameters to private pointers to stable
   caller-owned value snapshots, with explicit destination storage for wide
   results. Extend the same classification to nullable and multiple results.
   Preserve left-to-right argument evaluation, once-only effects, independent
   values and existing buffer lifetimes. Pointers and descriptors stay internal;
   no GC or reference counting is introduced. The prototype proves basic
   indirect recursion/copy feasibility, not the finished snapshot integration.
   Add a dedicated internal value/transport classification; do not masquerade
   wide integers as source CArray values or reuse string/array `buffered` flags
   in a way that silently allocates a string arena for every integer operation.
6. **Operations:** generate shared bounded loops over limbs for values above
   the direct threshold. Do not unroll O(limbs squared) multiplication into IR
   or rely on the target backend to split huge scalar expressions. Keep native
   scalar fast paths where verified. Signedness is an operation/type property;
   the limb array itself is unsigned storage.
7. **Internal widths:** logical range intermediates may need up to 131072 bits.
   Permit those internal limb counts without widening the source-visible cap.
   Checked add/sub/multiply must determine overflow without exposing or storing
   a wrapped range counter.

The threshold deliberately favors one complete portable path over an irregular
map of direct backend successes. Future tuning can widen fast paths only with
operation, target, runtime-dependency and resource evidence; it must not change
accepted source semantics or storage accidentally.

## Required work handed to later stages

Stage 1 settles the approach. It does not implement these production helpers:

- Stage 2: independent fixed-width interval and literal limits, unchanged machine
  width validation and target extent bounds; exact contextual types and compact
  diagnostics; no source width admitted without a working integrated backend.
- Stage 3: shared wide value construction/masking and limb add/sub/negate,
  signed/unsigned comparison, full and checked multiplication, division/remainder
  and powers. Implement signed magnitudes safely for minimum values, guard invalid
  operations once, and avoid i1 shifts by one. Include efficient internal limb
  shifts/carry handling rather than adding source operators.
- Stage 3: narrowing/widening casts, integer/float conversion with correct
  rounding, finite checks and post-truncation bounds. Use the existing float
  formats and half helpers; do not require missing compiler-rt symbols.
- Stage 3: range-counter update overflow, including int<1> mathematical +1,
  full-cap multiplication and internal wider results. Preserve ordinary wrapping
  in bodies and step expressions, and once-only stepper effects before exit.
- Stage 4: connect physical carriers to bindings, arrays, nullable values,
  function snapshots/captures/results, metadata/storage-size recipes and native
  main normalization. Check wide indices before narrowing to usize.
  Refactor the current final `CInteger.llvmValue` iN-only assumption and the
  scalar binding load/store paths through the new representation classification;
  keep logical type identity separate from storage and transport types. Snapshot
  wide operands before later operand/call effects can mutate their source, then
  reclaim scratch only after the last consumer.
- Stage 5: shared exact decimal conversion, width-derived checked capacities,
  unsigned magnitude for signed minimum, radix arithmetic in a sufficiently wide
  working type and bounded temporary use during recursive array formatting.

No frontend relaxation, new numeric cast behavior, output behavior or unsupported
operation acceptance was integrated by Stage 1.

## Reproduction and verification

Focused regression command:

```powershell
.\gradlew.bat :Compiler:test --tests cloth.ArbitraryIntegerBackendTest --tests cloth.NumericWidthsTest --tests cloth.NativeCABITest --tests cloth.TargetLayoutTest --offline --console=plain --no-daemon
```

Raw baseline matrix (all default widths and nine ABI profiles):

```powershell
.\gradlew.bat :Compiler:probeIntegerWidths --args="--groups=core,mul,div,fp-mixed,calls,indirect --output=tmp/integer-backend-matrix --timeout=12 --workers=2" --offline --console=plain --no-daemon
```

Bounded limb matrix and separate float32/64 conversion probes:

```powershell
.\gradlew.bat :Compiler:probeIntegerWidths --args="--groups=limbs,fp --output=tmp/integer-backend-followup --timeout=15 --workers=2" --offline --console=plain --no-daemon
```

Use `--widths=12,65,129` and `--assembly=true` to inspect representative odd and
wide assembly. Outputs must reside under workspace tmp/. Preserve summarized
results in this record (or linked durable evidence) before removing scratch.
Foreign object generation is distinct from native execution; AArch64 and foreign
Linux/macOS executable runs require their own available runners.

Full-cap assembly inspection uses `--widths=65536,131072 --groups=limbs
--targets=i686-pc-windows-msvc,x86_64-pc-windows-msvc,aarch64-unknown-linux-gnu
--assembly=true` with a fresh output directory. These are repeat inspections,
separate from the 1251 main matrix cases above.

Observed regression results: `ArbitraryIntegerBackendTest` (4),
`NumericWidthsTest` (8), `NativeCABITest` (4) and `TargetLayoutTest` (6) passed all
22 tests with zero failures/errors/skips. The first suite verifies 180 generated
IR modules, native int31 operations, all-bit carries/masking and recursive copies
through the full cap/internal width, and continuation after a rejected probe.
Source-width rejection/default/float regressions remain intact. The full compiler
suite was not rerun for this test-harness/build-only stage; Stage 6 retains its
full-suite requirement. Native foreign execution and native peak-memory figures
are not claimed.
