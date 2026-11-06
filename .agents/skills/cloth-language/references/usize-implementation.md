# usize implementation plan

Status: all seven steps implemented and verified (2026-11-01).

The later user decision introducing signed isize revokes the usize-main
extension below. Step 2 of the [isize plan](isize-implementation.md) implements
its replacement: isize main is accepted and usize main is rejected. All other
usize contracts and completed migration steps are retained.

The user requested a documented implementation plan after a discussion-only
backend review, then explicitly authorized steps 1 through 7 with continuing skill updates.
The records below preserve each milestone; use the final contracts and target
coverage when continuing development.
Read [confirmed semantics](semantics.md) and
[development status](development.md) before continuing this plan.

## Agreed direction

- Introduce `usize` as a primitive unsigned integer for target-sized in-memory
  sizes and indices, retaining its own Cloth type identity.
- Resolve its width from the compilation target, not the compiler host or the
  machine on which an executable is later launched. Conventional 32-bit and
  64-bit targets use 32-bit and 64-bit representations respectively.
- Explicit `int<8>`, `int<16>`, `int<32>` and `int<64>` retain exactly their
  specified widths, signed or unsigned. Plain `int` retains its 32-bit default.
- After the migration the user initially extended main to allow usize returns
  (2026-11-01). The native C int exit boundary keeps low bits when narrowing;
  PEC reports the normalized signed native value. Earlier milestone references
  to the signed-int-only main policy below are historical. This extension was
  subsequently revoked by the isize decision linked above.
  This follow-up passed 626 tests across 46 suites, including native x64/x86
  usize entry returns and the existing Builtins.co returning usize::max - 1.
- Migrate scalar/array/string `size` and metadata `rank`, `extent` and `width`
  to `usize`. Numeric `min`/`max` retain the receiver's exact numeric type;
  predicates return `bool`, `type`/`element` return `typeinfo`, and `str` returns
  `string`. String size still counts Unicode scalars; array size is outer count.
- Carry target-sized counts through internal descriptors, loops, bounds checks
  and allocation helpers so the change delivers native-width operations.
- Every static array extent must fit the selected target's usize, including
  extents used only in type metadata. Full-range counts remain distinct from
  actual allocation byte limits. Retain existing signed/unsigned integer index
  operands with checked normalization; this migration does not narrow acceptance
  to usize or add implicit source conversions.
- Preserve first-error compilation, checked size production, once-only
  evaluation, rectangular shapes, independent array values and explicit object
  lifetime. This work introduces no pointer syntax, address provider or GC.

## Pre-step-1 review (historical)

The original review found these implementation facts. Step 1 changes the target
plumbing described here; consult its completion record and current source before
continuing. Later steps still need the size/descriptor/integer migrations below.

- `data/PrimitiveTypes.kt` and `data/ValueType.kt` own shared semantic descriptors
  and LLVM mappings. `CInt` is a final data class; integer bounds/providers and
  some consumers require that concrete class.
- `semantic/declarations/TypeResolver.kt` has no target parameter. The declaration, call,
  function, program and legacy snippet analyzers currently resolve types before
  the emitter selects its ABI. `ExpressionTyping` contextualizes integer literals
  with `as? CInt`; `EmitCast` casts float-to-integer targets to `CInt`.
- `emitter/NativeCABI.kt` already supplies `sizeType` and `pointerBits`, but only
  accepts x86-64/AArch64 and all profiles have 64-bit pointers. This is not
  existing 32-bit support. `LLVMEmitter` selects the native host target and
  installs the data layout during object emission, after body generation.
- Array/string descriptors have i64 outer lengths. Inner array shape fields
  use i65 so unknown (-1) cannot alias an unsigned 64-bit maximum extent.
  `EmitArray` widens indices to i64. `EmitString` has i64 length/count loops.
- `ProviderResult`, `MetadataQuery`, `EmitTypeInfo` and static extent validation
  embed unsigned-64 result/range assumptions. Static extents use `BigInteger`.
- `EmitStringArena` uses native `abi.sizeType` while consuming i64 lengths;
  allocation stride/byte arithmetic currently relies on those widths matching.
- `LLVMEmitter` uses default machine-code optimization; the review found no
  explicit LLVM IR optimization pipeline. Do not promise register counts or
  performance improvements without inspecting generated machine code.

## Settled decisions and target scope

No necessary choices remain open for this migration. Other 32-bit target/OS
combinations require their own authorization, ABI work and execution validation.

Settled during step 3 (2026-10-31): the user chose existing unsigned numeric
rules (wrapping arithmetic, truncating integer casts, checked float-to-integer
casts), and normalized metadata text `usize` / `usize[3]` with width available
through `::width`. Operational count validation must still precede narrowing;
explicit source casts retain their chosen numeric semantics. Do not insert
unchecked compiler-generated casts at allocation/index boundaries.

Settled during step 4: the user chose to require all static extents to fit the
target's usize, including metadata-only types. On a 32-bit target the maximum
extent is 4,294,967,295; no allocation is implied by a type-only query or a known
inner extent after an empty axis.

Settled during step 6: the user selected Windows x86 with the MSVC ABI as the
first real 32-bit target. The validated target is i686-pc-windows-msvc on the
Windows x64 host, with a backend SSE2 baseline preserving Cloth floating-point
rounding. Linux/macOS 32-bit support was not enabled by that selection.

## Steps and completion checks

Completion records follow dependency order. Earlier intermediate result types
and layout descriptions are historical; the completed feature uses the final
contracts with the target coverage recorded below.

### 1. Establish a shared target layout

- [x] Resolve one immutable target configuration before semantic analysis;
  supply size width, pointer layout and native ABI facts from that target.
- [x] Thread it through declaration/signature resolution, overload forks,
  function/program checking, provider lookup and snippet/test entry points.
  Keep semantic layout facts independent of LLVM context handles.
- [x] Supply the same configuration to LLVM types and runtime helpers; establish
  and validate the LLVM data layout before layout-dependent emission.
- [x] Retain native-target defaults; provide internal target injection for tests
  without claiming unsupported targets can already build runnable programs.

Done when: existing fixed-width programs retain their types and two compilation
configurations in one process resolve independently, without global target state
or host-size assumptions. A synthetic 32-bit layout check is not native coverage.

Implementation record (2026-10-31):

- `cloth.target.TargetLayout` holds immutable size/pointer width, pointer alignment,
  byte order and C numeric ABI facts, with no native handles. `NativeCABI.layout`
  owns the selected instance and derives its native numeric types from it.
- Declaration/semantic models retain that instance. Analyzers inherit a supplied
  declaration target or accept explicit layouts; type resolvers, overload forks,
  deferred blocks and source/built-in provider lookup receive it. Conflicting
  analysis targets and cross-target fact commits are rejected.
- Descriptors' LLVM mappings and provider lookup now take a target. Production
  LLVM adapters, binding/half helpers and runtime/provider services pass the
  compilation's layout explicitly; native defaults remain for standalone callers.
- `LLVMEmitter` selects its ABI before analysis and supports internal injection
  of an already supported ABI. It creates and validates a target-machine layout
  before emitting bodies, checking pointer width/alignment and byte order.
  Object emission rejects a conflicting module triple or data layout.
- LLVM registration includes both currently supported architectures for layout
  queries. Foreign startup fixtures now select their target before body emission.
  This adds neither a CLI cross-compilation option nor 32-bit ABI acceptance.
- Six `TargetLayoutTest` cases cover native defaults/ABI facts, interleaved
  synthetic 32-bit/native analyses, overloads/blocks/providers, fixed-width storage,
  cross-target rejection and installed LLVM layouts for x86-64/AArch64 Windows,
  Linux and macOS. Provider callers and startup fixtures use the new context API.
- Focused target/ABI/descriptor/provider/call tests passed, followed by the
  corrected startup-fixture and target-layout checks. The full
  `gradlew.bat :Compiler:test --offline --console=plain` run passed all 594 tests
  across 41 suites, with zero failures/errors/skips. `git diff --check` passed.
  Native execution coverage is on the Windows host; synthetic 32-bit semantics
  and foreign target IR/object checks do not establish execution there.

### 2. Share integer behavior while preserving type identity

- [x] Refactor concrete `CInt` assumptions into a shared integer descriptor
  contract, or another small design that preserves distinct source identities.
  Share bounds, signedness, literal validation and LLVM integer representation.
- [x] Audit providers, arithmetic, comparisons, division/remainder guards,
  exponentiation, casts, formatting, signatures and metadata for concrete-class
  assumptions. Preserve exact-type assignment and overload matching.

Done when: existing integer behavior passes its focused regressions, including
8/16/32/64-bit unsigned wrapping and explicit casts. An implementation that
merely aliases `usize` to `CInt(targetBits, true)` does not meet type identity.

Implementation record (2026-10-31):

- `data/CInteger.kt` is the shared abstract descriptor for integer width,
  signedness, exact bounds, contextual literal acceptance, constant-check bit
  normalization, integer providers and LLVM integer representation. `CInt`
  remains a distinct fixed-width data class with its existing equality, copy,
  supported widths and source spelling. This is a compiler abstraction, not
  source-language inheritance or a new primitive.
- Integer constants and raw extent recipes accept `CInteger` and preserve the
  concrete result descriptor. Literal typing, static division checks/range
  diagnostics, float-to-integer casts, runtime division/remainder guards and
  native printing dispatch use the shared contract instead of casting to CInt.
- Arithmetic/comparison/exponentiation, function signatures, assignment matching,
  array inference, indexing and metadata flags already consume general numeric
  facts or exact descriptor identity. Their existing paths were retained.
  `ValueType.metadataSpelling` is overridable so a future distinct integer can
  supply canonical text independently of its fixed-width machine representation.
  At this milestone usize text remained open; step 3 records its resolution.
- At the step 2 milestone, remaining concrete CInt uses were fixed-int construction, native C
  ABI argument representations, the signed fixed-int main return policy and
  the pre-step-5 unsigned-64 size/metadata/extent contracts. Do not globally
  replace deliberate fixed-int uses during subsequent refactoring. The later
  explicitly authorized usize main extension is recorded in Agreed direction.
- Three `SharedIntegersTest` cases use a test-only distinct integer descriptor
  across signed/unsigned 8/16/32/64-bit widths. They exercise shared bounds,
  literal context/errors, constant division errors, exact identity/array metadata,
  and verified LLVM for casts, division, power, formatting and distinct metadata.
  No test-only descriptor is added to source type resolution.
- Existing numeric/cast/division/provider/metadata/call suites passed in the
  focused run, and all three new cases pass. The full
  `gradlew.bat :Compiler:test --offline --console=plain` run passed all 597 tests
  across 42 suites with zero failures/errors/skips. Changed-file whitespace
  and skill-reference checks passed.
  Native execution regressions run on Windows; the distinct test descriptor is
  checked semantically and through verified IR rather than native execution.

### 3. Add the usize primitive end to end

- [x] Settle the necessary arithmetic/cast/metadata choices above, then add
  spelling, type receivers and type resolution with target-contextual literals.
- [x] Support bindings, inference, assignments (including name lists), function
  parameters/results, arrays, deferred blocks, operators and explicit conversions.
- [x] Expose min/max, storage size, text and type metadata through the ordinary
  descriptor/provider paths. Preserve the actual target width in numeric facts.
- [x] Ensure exact identity in overloads, `is` checks, metadata equality and text
  even when `usize` and a fixed unsigned integer lower to the same LLVM type.

Done when: both layout widths produce the intended primitive width; literals
are range-checked for that target; fixed integers retain all explicit widths;
transport and formatting work without wrappers or runtime type allocations.

Implementation record (2026-10-31):

- `data/CUSize.kt` implements distinct primitive identity using `CInteger`.
  `TypeResolver` selects its width from the supplied `TargetLayout.sizeBits`;
  there is no host-sized global singleton or alias to CInt. Primitive type
  parsing accepts usize bindings, function contracts, casts, type checks, array
  suffixes and type receivers. Explicit widths and the unsigned modifier remain
  unsupported on usize; fixed int syntax/widths and the main return policy stay
  unchanged.
- Shared integer and generic scalar/array/function paths supply contextual
  literals, strict assignments/overloads, operators, numeric casts, formatting,
  deferred blocks, binding/assignment lists and value transport. Bounds preserve
  CUSize identity. Canonical diagnostic/typeinfo spelling is `usize`, including
  within arrays; metadata reports the target width and unsigned/numeric flags.
- Eight `USizeTest` cases cover interleaved synthetic 32-bit/native resolution
  and range checks, equally wide fixed-integer distinctions, signature/overload
  selection, native wrapping and casts across all fixed integer widths and
  floating formats, arrays/blocks/copies/recursion, metadata/value-receiver effects,
  runtime float/division/index errors, and verified IR for both supported
  architectures across Windows/Linux/macOS. `examples/USize.co` demonstrates use.
- Focused usize/target/shared-integer/provider tests passed. The full
  `gradlew.bat :Compiler:test --offline --console=plain` run passed all 605 tests
  across 43 suites with zero failures/errors/skips. The CLI compiled and ran
  `examples/USize.co`, printing 1, usize and PEC: 0. Native execution is on the
  Windows host; synthetic
  32-bit checking/LLVM scalar mapping and foreign target IR are not 32-bit or
  foreign native execution.
- At the step 3 milestone, internal descriptor/count migration remained step 4.
  All existing size providers, including `usize::size`, and metadata
  rank/extent/width results still returned unsigned int<64> until step 5.
  Indexing continues accepting existing integer
  operands; at that milestone existing static extent limits and 32-bit ABI rejection remained.

### 4. Migrate internal counts and memory operations

- [x] Parameterize array and string descriptors and native count loops by the
  chosen layout. Cover literals, globals, arguments, returns, copies, concatenation,
  Unicode startup arguments and metadata text strings.
- [x] Choose an inner-shape representation preserving unknown separately from
  every valid count. Evaluate a separate known flag versus an extra bit; do not
  blindly replace i65 with a sentinel that collides with a valid usize value.
- [x] Make bounds comparisons, indexing and generated copy/count loops use
  coherent widths. Reject negative/out-of-range indices before narrowing; audit
  LLVM address-generation constraints independently of unsigned numeric range.
- [x] Check element-stride multiplication and allocation-header addition before
  allocating. Audit `LLVMSizeOf`, native size_t calls, memcpy, stack allocations
  and all conversions crossing count representations.
- [x] If this step is split into array/string chunks, retain explicit checked
  adapters at temporary boundaries. Existing unsigned-64 public size providers
  can widen a smaller internal count until step 5; never emit mismatched IR.

Done when: descriptor producers/consumers agree, empty/multidimensional maximum
shapes retain their meaning, allocation math cannot silently wrap, and hot
native-size loops on a 32-bit layout do not retain obligatory i64 counters.

Implementation record:

- `BufferLayout` supplies `{ptr, iN}` string/rank-one array descriptors and
  `{ptr, iN, {iN, i1}, ...}` higher-rank arrays, where N is target.sizeBits.
  Each inner axis has a native count and a separate known flag. Zero, unknown
  and usize::max remain distinct through shape inference, merging, constraints,
  erased signatures, indexed children and copies. No i65 arithmetic remains.
- Native counts flow through array/string literals, metadata text globals,
  concatenation, scalar counting, byte output, recursive return copies,
  formatting capacities, and UTF-8/UTF-16 argument conversion and arrays.
  Startup argument indices widen to i64 only for the existing %llu error ABI.
- `EmitMemory` checks allocation byte sums and element-stride multiplication
  before allocation. Strides come from the installed module ABI layout rather
  than an unchecked LLVMSizeOf conversion. Copied array storage pads its pointer
  header to the element's ABI alignment, including 8-byte-aligned fixed integers
  on a layout with 4-byte pointers. malloc, snprintf, memcmp, memcpy and
  variable stack allocations use the native count type. Oversized string
  globals fail compilation with C3052; dynamic failures use controlled errors.
- Actual objects must also fit LLVM's signed pointer-index byte limit; this is
  separate from the full unsigned extent range. Current supported profiles use
  pointer-width indices. See [LLVM allocated objects](https://llvm.org/docs/LangRef.html#allocated-objects).
  Header addition, concatenation and stride multiplication enforce the smaller
  byte limit; UTF-8 startup scanning checks its position before loading bytes.
  A future ABI with a distinct pointer-index width needs an explicit layout fact.
- Index checks compare in the original operand's range or a wider native type,
  explicitly reject signed negatives, then narrow to native width only on the
  valid branch. Existing signed/unsigned integer indexing remains accepted.
- Type resolution uses exact BigInteger checks against target usize::max for
  every static extent, including metadata-only use. The target-independent CArray
  model can still hold the maximum supported 64-bit extent; source validation
  and native constant construction enforce the selected smaller target.
- At the step 4 milestone, public size and metadata rank/extent/width contracts
  remained unsigned int<64>. The temporary `ProviderEmissionContext.countResult`
  widened native counts at that boundary; step 5 removes it. The internal raw extent recipe retains its legacy
  -1 result for unknown axes; descriptors themselves have no colliding sentinel.
- Seven `BufferCountsTest` cases cover both widths' layouts, maximum/zero/unknown
  shapes, type-only extent rejection, mixed-width index validation, native-width
  loops/calls for strings, metadata, arrays and both startup paths, and verified
  public-result adapters. A tiny synthetic layout exercises UTF-8 global byte
  limits including the NUL and the source-located C3052 diagnostic without
  constructing a multi-gigabyte literal. Native execution checks reject
  stride/header/sum overflow and negative indices before memory access, and accept
  boundary arithmetic without allocating huge objects. Existing buffer/provider/Unicode/
  usize and copy/cleanup regressions passed. The full
  `gradlew.bat :Compiler:test --offline --console=plain` run passed all 612 tests
  across 44 suites with zero failures/errors/skips. Changed-file whitespace and
  skill/documentation link checks passed.
- Native execution remains on the Windows host. Synthetic 32-bit helper IR
  neither enables a 32-bit ABI nor establishes execution there. Real target
  selection, ABI/toolchain support and assembly inspection remain step 6.

### 5. Migrate compiler data result contracts together

- [x] Change scalar/array/string size and metadata rank/extent/width results to
  the compilation's `usize`; update recipe types, constant bounds, runtime field
  layout and property loads consistently.
- [x] Preserve the static-extent policy implemented in step 4 using exact
  `BigInteger` checks; retain compile-time first errors for known failures and controlled runtime
  errors where validation depends on runtime data.
- [x] Update affected examples, provider tests and documentation deliberately.
  Keep min/max typed as their receiver, including usize receivers.

Done when: every listed provider has exact usize identity through assignment,
return and overload selection, not merely a matching LLVM width. Receiver
restrictions, Unicode/array meanings, read-only access and evaluation effects
continue to pass their regressions.

Implementation record (2026-11-01):

- Scalar storage-size recipes, ArraySize and StringSize now return the selected
  target's distinct CUSize. Primitive provider maps supply that target explicitly.
  Known counts are range-validated; runtime array and Unicode scalar counts
  already have the correct native width from step 4 and need no widening adapter.
- MetadataQuery resolves its result type from TargetLayout. MetadataProperty
  records that layout, and rank/extent/width fields, constants and property loads
  use the same native count width. Exact BigInteger checks protect metadata
  constants. Public usize result emission rejects a recipe from a different
  size width before producing a native value.
- TypeResolver's target-sized extent policy remains in force, including
  metadata-only types and maximum inner extents after empty axes. The
  target-independent CArray model bounds extents to the largest supported usize;
  ArraySize also validates extents against its specific target width.
- The temporary public countResult adapter is removed. Only the unregistered
  internal ArrayExtent recipe retains its historical 64-bit raw encoding and
  unknown-axis -1 result, with widening private to that recipe. It is not the
  source metadata extent provider and cannot change the public usize contract.
- Numeric min/max still return the exact receiver type; predicates return bool,
  type/element return typeinfo and str returns string. Existing integer indexing,
  first-error behavior, Unicode scalar counts, rectangular copies and once-only
  receiver evaluation retain their meanings. Fixed-width integer source types
  and, at that milestone, the signed fixed-int main contract were unchanged.
- Existing scalar/buffer/metadata/provider/target/usize tests were migrated
  deliberately, including overloads competing with equally wide fixed integers.
  Two new USizeTest cases check every measurement family through target-isolated
  bindings, inference, arrays, calls, returns, rejection of implicit fixed-int
  conversions, native overload selection, receiver effects and explicit casts.
  A new BufferCountsTest case checks 32/64-bit metadata constants/fields/loads,
  maximum extents and cross-target recipe rejection. Supported foreign target IR
  checks now include all dynamic measurement providers. examples/USize.co
  demonstrates typed size, rank and width results.
- Focused provider/metadata/target/usize checks pass. The full
  `gradlew.bat :Compiler:test --offline --console=plain` run passed all 615 tests
  across 44 suites with zero failures/errors/skips. The CLI compiled and ran the
  updated examples/USize.co, printing 1, usize and PEC: 0. Whitespace and skill/
  documentation link checks passed. Native execution
  at that milestone remained on Windows x64; synthetic 32-bit helper IR and
  foreign IR checks did not establish runnable 32-bit support. Step 6 below
  supplies the later real Windows x86 validation.

### 6. Validate a real 32-bit target

- [x] After selecting its scope, add the supported ABI profile, target-machine
  initialization/selection and toolchain architecture/runtime/startup handling.
  Do not enable a triple merely by removing its rejection.
- [x] Compile, link and run scalar, usize, array/string and metadata programs in
  the selected 32-bit environment. Verify C integer/size_t/vararg and startup
  boundaries, explicit int<64> behavior, and controlled failure paths.
- [x] Inspect generated assembly for representative size/index/copy loops and
  explicit 64-bit arithmetic. Record the target and optimization configuration;
  avoid fixed register-allocation or timing promises.

Done when: execution evidence exists for that 32-bit target, or this step remains
explicitly incomplete with the missing environment recorded. Keep current
64-bit Windows/Linux/macOS profiles working; make no untested execution claims.

Implementation record (2026-11-01):

- NativeCABI accepts the selected i686 Windows MSVC target through X86 and
  WINDOWS_ILP32, with explicit 32-bit pointer/size/C-int/C-long facts and 64-bit
  long long/double. Other 32-bit OS/ABI combinations remain rejected. The existing
  X86 LLVM backend registers both x86 widths; module layout validation still
  precedes bodies and prevents retargeting emitted modules.
- ToolchainResolver selects x86 CRT/SDK paths, machine flags and the matching
  MSVC linker. Both Windows profiles use wide startup. Windows API declaration
  and call instructions explicitly use stdcall on x86; CRT calls remain cdecl.
  Static/dynamic CRT and LLD/MSVC, console/GUI combinations execute successfully.
- The CLI accepts --target TRIPLE so the current compiler can select the real
  x86 target; omitting it retains LLVM's native default. Supported-target and
  matching-toolchain validation remain mandatory. Target selection is shared
  by executable and object modes and resolved before semantic analysis.
- A real float32 rounding test caught generic x86 excess x87 precision. The
  selected x86 backend uses +sse2 for declared-width arithmetic. Internal
  emitAssembly uses the same target-machine settings as object emission.
- Five WindowsX86Test cases verify ABI/IR and PE32 headers, real scalar/usize/
  fixed-64/floating/array/string/metadata execution, runtime/linker/subsystem
  variants, controlled failures, CLI selection and inspection artifacts.
  Existing ABI, installed-layout and toolchain tests include x86. Unicode tests
  now execute malformed/valid UTF-16 and allocation-balance fixtures as real
  x86 code, and BufferCountsTest executes overflow guard fixtures at both widths.
- The permanent examples/USizeX86.co compiles through the CLI and runs with
  Unicode arguments, printing 32 and PEC: 0. Generated IR/assembly/object artifacts
  live under build/usize-x86. [Validation and assembly evidence](usize-x86-validation.md)
  records exact code-generation settings, observed native loops versus explicit
  64-bit carry operations, prerequisites and reproduction commands.
- Focused execution/ABI/layout/toolchain/startup checks pass. The full
  `gradlew.bat :Compiler:test --offline --console=plain` run passed all 621 tests
  across 45 suites with zero failures/errors/skips. Whitespace and skill/
  documentation link checks passed. Actual 32-bit execution is Windows x86
  on this Windows x64 host; foreign IR/object checks are not foreign execution.
  At this milestone final integration remained step 7. Other 32-bit targets
  remain outside the migration's supported scope.

### 7. Complete integration and update project knowledge

- [x] Run the meaningful focused suites after their respective steps; after
  integration run `gradlew.bat :Compiler:test --offline --console=plain` and the
  selected 32-bit checks. Address new failures without accumulating diagnostics.
- [x] Check target isolation, provider chaining, overloads, numeric conversions,
  array shape/copy semantics, buffered lifetimes and first-error behavior across
  both widths. A smaller target must still support explicit 64-bit integers.
- [x] Reconcile this checklist, semantics, development status and the historical
  compiler-data-access note. Record actual target coverage and validation limits.

Done when: the primitive, migrated provider contracts and internal layouts agree,
the required checks pass, and documentation distinguishes implemented behavior
from remaining target support. No isize, addr or pointer feature is implied.

Integration record (2026-11-01):

- Reviewed target plumbing, integer identity, provider recipes, metadata layout,
  allocation arithmetic and addressing against the implemented 32/64-bit ABIs.
  Remaining wide operations are fixed-width numeric values and their C ABI
  promotions, the cold %llu startup error argument, and the unregistered legacy
  raw extent recipe; public
  measurement results and native count loops have the selected target width.
- Three new USizeIntegrationTest cases combine previously separate coverage.
  One parsed program is compiled/executed for Windows x64, Windows x86, then
  x64 again. It checks measurement overloads competing with fixed unsigned
  integers, deferred receivers and assignment lists, metadata chains, recursive
  independent copies, returned-text lifetimes, empty maximum shapes and explicit
  wide casts. Executable machine headers and runtime width output are checked.
- CLI fixtures on both widths report exactly one detected error before invoking
  an invalid linker or creating output. They cover strict result identity,
  read-only providers, invalid receivers/metadata, extent overflow and early
  name-resolution failure. First detected does not mean earliest in source
  order across different compiler passes; diagnostic accumulation was not added.
- Runtime fixtures on both widths evaluate left/right deferred operands once
  in order, terminate at the controlled division failure and skip later code
  and normal PEC output. Focused integration/provider/target/usize/diagnostic
  checks pass; no production compiler change was needed for this step.
- Current contracts and historical phase notes are reconciled in the skill,
  semantics, development status and compiler-data-access note. The latter has
  a current contract table preceding clearly marked historical phase snapshots.
- Focused checks passed, followed by the full
  `gradlew.bat :Compiler:test --offline --console=plain` run: 624 tests across
  46 suites, zero failures/errors/skips. The portable examples/USize.co compiled
  through the CLI for native x64 and explicit Windows x86 and ran successfully
  on both, printing 1, usize and PEC: 0. Whitespace and documentation link checks
  passed. No production source changes were needed for step 7.
- The seven-step migration is complete. Native
  execution coverage is Windows x64 and SSE2 Windows x86 on the Windows x64
  host. Foreign 64-bit IR/object checks and synthetic layouts remain separate
  evidence, not native Linux/macOS execution or other 32-bit target support.
