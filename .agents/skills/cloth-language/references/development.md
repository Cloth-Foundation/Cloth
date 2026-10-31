# Cloth development status

## Current work

### Fixed initial array shapes (2026-10-31)

The user confirmed that every array-valued binding preserves its initial shape,
including omitted dimensions initialized from function results. The existing
semantic checker rejects mismatches in known dimensions. Unknown dimensions
are captured from the initialized value and checked before each whole-binding
replacement, recursively across all dimensions. Equal element counts do not
permit a different rectangular shape. Same-shape returned updates remain valid
and continue copying recursively by value.

`EmitBindings` retains the initial descriptor as an immutable SSA shape template,
independent of the binding's mutable storage slot. `EmitExpression` compares
unknown binding dimensions before copying the replacement. Fully known types
keep their existing static checks and runtime destination constraints. This adds
no allocations or separate shape-storage lifetime. Branches, shadowed bindings,
captures and parameters use the correct binding identity and initial descriptor.
Unknown dimensions below empty axes retain the existing internal -1 policy;
known dimensions following empty axes remain fixed and checked.

Function signatures retain their declared contracts: `int[]` can accept or
return different lengths on separate calls. Each parameter binding preserves
its received shape for that invocation; each receiving local preserves its
own initial result. Function bodies are not inspected to invent static sizes
at call sites. Deferred function-block declarations still execute on every
read; only a stored result captures an initial array shape.

Fourteen `ArrayBindingShapeTest` cases cover compile-time versus runtime checks,
literal and returned initializers, copies and returned updates, parameters and
varying return shapes, partial extents, matrices and higher-rank equal-count
reshapes, branches/shadowing/captures, repeated function-block reads, empty axes,
once-only source evaluation and legacy snippets. A counted/poisoned allocation
test covers repeated unknown-shape returned replacements and normal/early exits.
README, confirmed semantics and `examples/ArrayAssignment.co` document the rule.

Verified on 2026-10-31: focused binding-shape and assignment tests passed,
followed by the complete `gradlew.bat :Compiler:test --offline --console=plain
--no-daemon` suite: 436 tests, zero failures/errors/skips. The suite includes
the expanded array-assignment CLI example and counted/poisoned storage checks.
`git diff --check` passed. Native execution was verified on Windows; the change
uses shared LLVM shape checks without target-specific backend code.

### Order-independent array inference (2026-10-31)

The user confirmed that all elements must participate in compile-time array
element inference before contextual literals receive their types. Already-typed
values retain exact width, signedness and primitive type; conflicts require
explicit casts. Literal-only arrays retain existing defaults and range rules.
This resolves the audit's `[n, 1]` versus `[1, n]` discrepancy without adding a
widest-type search, implicit numeric conversion or runtime element-type changes.

Shared `ExpressionTyping` now discovers fixed element constraints before the
ordinary source-ordered typing pass. Explicit element annotations take priority.
Integer literals, contextual literal arithmetic and unary expressions can defer
their type until constraints are available. Nested literal rows contribute typed
leaf constraints recursively. Calls, casts, indexing, ordinary bindings and
function-block reads contribute their established result types. Conditional
result branches contribute constraints with their own lexical scopes and local
binding types; non-result statements and enclosing return contracts still check
normally. No diagnostic recovery/accumulation is used. Existing overload forks
continue isolating rejected candidate facts, and default literal preference stays
in force. Inference checks compile-time facts only; runtime elements still evaluate
once, left to right, including function blocks and early returns.

Existing floating-literal policy is retained: floating literals default to
`float` in ordinary bindings/returns; call arguments can receive floating-width
context. Array discovery does not make a `float` literal or a typed `float`
binding implicitly convert to `float<64>` in an ordinary inferred binding.
Compatible known/unknown dimensions still merge under the rectangular contract.
An older rectangular regression that expected `[[1], [wide]]` to fail was updated
to use two differently typed bindings, preserving the intended exact-type check.

`examples/Arrays.co` demonstrates both element orders and a typed value in a
later row guiding earlier literal rows. README and confirmed semantics record
the rule. The later fixed-initial-shape work above settles the `[]` binding-size
policy. The array length API, equality and copy optimizations remain separate
audit items.

Verified on 2026-10-31: the focused inference tests passed, followed by the full
`gradlew.bat :Compiler:test --offline --console=plain --no-daemon` suite: 421 tests,
zero failures/errors/skips. Eighteen `ArrayInferenceTest` cases cover every integer
width/signedness and anchor position, rejected typed numeric conflicts, explicit
annotations/casts, literal defaults and range failures, existing floating literal
policies, nested rows and typed row values, literal arithmetic/unary expressions,
conditional branch/local binding constraints, calls/indexing/function blocks,
overload isolation/default preference, once-only runtime evaluation, early returns,
contextual empty rows, incompatible conditional branches and the shared legacy
snippet pipeline. Existing rectangular, function/return and counted/poisoned
storage-lifetime checks also passed. The normal CLI test executes the expanded
array example. `git diff --check` passed. Native execution was verified on Windows;
this change is in the shared semantic checker and introduces no target-specific
backend paths.

### Rectangular array contract (2026-10-30)

The user confirmed rectangular arrays for current Cloth, with jagged arrays
reserved for a future collection type. All rows/subarrays at each level must
have the same dimensions recursively, including values created or returned by
functions. Unsized dimensions are unknown sizes, not permission for jagged rows.
Replacing a row/plane must preserve that subarray's complete shape. The later
fixed-initial-shape work above extends this stability to whole-value rebinding,
including dimensions inferred from function results.

`ExpressionTyping` merges compatible known/unknown array extents while preserving
exact primitive element types. Differing known row dimensions still fail with
C3038; a literal row and a function-produced row with a compatible shape can now
coexist in either order. Scalar numeric inference/conversion rules are unchanged.

The private LLVM array descriptor now contains the data pointer followed by one
i64 extent per dimension. One-dimensional arrays and UTF-8 strings retain their
two-field representations. Full shape metadata survives copies, parameters,
conditional/block values and function returns, including dimensions following
an empty axis: an erased `int[0][3]` value cannot satisfy `int[0][4]`.
Indexed subarrays inherit their parent's shape constraints, including dimensions
learned from a sibling row of an immediately indexed literal temporary.
`EmitArray` checks every sibling shape during literal evaluation, even for an
immediately indexed temporary. `EmitStringArena` validates/normalizes dimensions
against declared contracts and parent shape before recursively copying children.
`EmitExpression` validates a replacement subarray against the existing target
shape before storing, preventing shape changes through unsized matrix parameters
or function-returned values. Matching replacements keep the existing independent
value-copy semantics. Runtime mismatches use the controlled error path and stop
evaluation of subsequent rows.

Empty-dimension implementation policy: a dimension with no runtime witness below
an empty axis and no declared extent is represented internally by -1. Declared
context can constrain it; explicitly known extents remain known through erasure.
Replacement preserves the target's shape, including unconstrained empty tails.
The sentinel is internal metadata, never an array length that can be indexed.
No GC, reference qualifiers or new source syntax are introduced.

The array audit's rectangular/jagged inconsistency and its known/unknown row
mixing issue are resolved by this work. Other findings below remain separate.

Verified on 2026-10-30: focused rectangular-array, assignment and counted/poisoned
storage-lifetime checks passed, followed by the full `gradlew.bat :Compiler:test
--offline --console=plain --no-daemon` run: 403 tests, zero failures/errors/skips.
Fourteen `RectangularArraysTest` cases cover compile-time ragged rejection,
mixed literal/function rows, runtime rejection in bindings/arguments/returns and
immediately indexed temporaries, recursive copy isolation, row/plane replacement,
higher-rank shape mismatches, preserved dimensions below empty axes, context for
unwitnessed empty dimensions, zero-length rows, complete-value rebinding,
evaluation stopping on the first mismatched row, and empty temporary subarray
reads/replacements. The existing `examples/Arrays.co` CLI test now exercises
rectangular matrices built from returned rows. Updated the prior row-replacement
diagnostic assertion to expect the new rectangular-shape guard. `git diff --check`
passed. Native execution was verified on the current Windows host; implementation
uses the shared LLVM backend and target ABI helpers without platform-specific code.

### Array behavior audit (2026-10-30)

This is a historical audit snapshot; the rectangular contract above supersedes
its shape-related observations and the previously unsettled shape decision.
The later order-independent inference work also supersedes its first-element
inference observations, and fixed-initial-shape work supersedes its differing
literal/function binding-stability observations.

Audit only: compiler semantics were not changed. The complete 389-test suite
passed alongside two temporary audit drivers (391 JUnit cases total, zero
failures/errors/skips). The drivers ran 26 semantic/native behavior probes and
a native counted-allocation experiment on Windows. Reproducible source, LLVM IR,
`observations.tsv`, `allocations.txt` and the archived `ArrayAuditProbeTest.kt`
are under ignored `build/array-audit/`. The temporary driver was moved out of
the normal test tree after execution; its observations are not new contracts.

Confirmed-contract checks passed for recursive binding/parameter isolation,
return-and-reassign updates, exact known shapes, contextual empty arrays,
zero-length inner dimensions, bounds guards (including unsigned 64-bit maximum
and empty-array access), and runtime shape validation at sized parameter and
return boundaries. Existing counted/poisoning tests also passed.

Reproduced surprises and gaps:

- Shape consistency: `def<int[][]> a = [[1], [2, 3]]` is rejected, but
  `[row(0), row(1)]` succeeds when `row` returns `int[]` and those same row
  values. Its inferred type is `int[2][]`, and `a[1][1]` returns 3. A matrix
  obtained through an `int[][]` function can also have one row replaced with
  a different-length array. Known versus erased extents currently determine
  whether jagged values can be constructed. Rectangular versus jagged semantics
  have not been confirmed; this is an implementation-policy inconsistency to
  resolve before presenting a uniform multidimensional-array contract.
- Element inference: with `def<int<64>> n = 2`, `[1, n]` is rejected while
  `[n, 1]` infers `int<64>[2]`. A row-returning call mixed with an identical
  literal row is also rejected because the known/unknown row types differ.
  The first-element policy is documented as provisional, not confirmed design.
- Binding extents: `def<int[]> a = [1, 2]` retains `int[2]` and rejects
  `a = [3]`; replacing the initializer with an unsized `make()` returning the
  identical values yields `int[]` and permits that rebinding. This follows
  current static information, but the declaration/signature meaning of `[]`
  and the intended array-size stability should be made explicit.
- Length access is absent: `.length`, `.size`, and `len(args)` are all rejected.
  Generic code cannot query a received array's extent, including checking
  whether command-line arguments are empty before indexing. Length/count and
  iteration are expected collection facilities, not implemented contracts yet.
- The existing Windows Unicode argv defect remains reproducible: the argument
  `é中😀` compares unequal to the same Cloth string literal; the ASCII `Cloth`
  control succeeds. Continue tracking the cross-platform fix in
  `todo/unicode-command-line-arguments.md`; this audit did not settle invalid
  native-byte policy or establish execution on Linux/macOS.
- Final bindings remain element-mutable: `def a := [1]; a[0] = 10` (with
  newline statement separators) succeeds. This follows the present binding-only
  policy, but differs from immutable collection values in Swift; explicitly
  distinguish binding immutability from immutable array contents.
- Performance: `def a = [1, 2, 3]` followed by `def b = identity(a)` produces
  four native heap-copy allocations in a straight-line scalar-returning sample:
  declaration, argument snapshot, returned result, and result binding. This is
  not a value-semantics failure. Current eager copies and invocation-long
  retention need optimization before efficiency is comparable to mature
  collection implementations. No general performance benchmark was performed.
- Array equality, indexed compound/update operators and trailing literal commas
  remain unsupported; untyped empty arrays require context. These are capability
  gaps or deliberate provisional choices, rather than failures of confirmed
  copy/index semantics. Runtime-size construction and array iteration are also
  not implemented by the current grammar.

Comparison used primary language documentation. Go fixed arrays are values
whose lengths belong to their types; Go slices instead share backing arrays:
https://go.dev/doc/effective_go#arrays . Swift Array has value behavior and
copy-on-write optimization; immutable collection constants prevent element
mutation: https://docs.swift.org/swift-book/LanguageGuide/ClassesAndStructures.html
and https://docs.swift.org/swift-book/LanguageGuide/CollectionTypes.html . Java
and C# arrays are reference types, and Kotlin array `val` bindings permit element
mutation: https://docs.oracle.com/javase/specs/jls/se26/html/jls-10.html ,
https://learn.microsoft.com/en-us/dotnet/csharp/language-reference/language-specification/arrays ,
https://kotlinlang.org/docs/arrays.html . Rust fixed arrays include their length
in the type and enforce initialization/bounds in safe access:
https://doc.rust-lang.org/reference/types/array.html . C++ `std::array<T,N>`
provides fixed, contiguous storage and a size API:
https://eel.is/c++draft/array.overview . These comparisons support Cloth's chosen
value-copy model without importing reference sharing, pointer syntax, numeric
conversions, implicit initialization, or other languages' object lifetime rules.

### Indexed assignment implementation

Indexed element assignment and independent array value copies are implemented.
`ElementAssignmentStatement` parses `arr[index] = value`, including nested targets,
and participates in name resolution, strict element/index/shape checking and flow
analysis. `EmitArray.checkedAddress` shares the existing bounds guard with writes.
The entire target evaluates once, left to right, and each dimension is checked
before evaluating the RHS. The RHS evaluates once; stores preserve bool byte
storage and every supported primitive representation. Returns nested in target
indices or the RHS stop the remaining evaluation and emit no store.

The user confirmed that copied arrays and function arguments are independent,
recursively. Earlier parameter rules guaranteed local rebinding only; the old
array descriptors still shared backing storage. The emitter now copies arrays
at binding declarations/reassignments, each call argument (before subsequent
arguments run), subarray insertion/replacement, and block/conditional value
boundaries. Chained target indexing reads the original nested storage, while
extracting a row into a new binding copies it. `values = change(values)` applies
a returned update; calling `change(values)` alone cannot alter the caller.
Existing function-return copies and frame cleanup remain in place. Copies use
the existing invocation arena and recursive size/overflow/allocation guards;
replaced/discarded snapshots remain allocated until that invocation exits.
This is eager copying with no COW, GC or new programmer-visible qualifiers.

Initial implementation policies: unknown array extents can satisfy a known-size
binding, parameter, return or subarray assignment with recursive runtime size
checks during copying. Known incompatible extents remain compile-time errors.
Declared extents are retained; omitted extents infer from the initializer where
known. This enables assigning unsized function results back to inferred-size
bindings safely. Invalid runtime extents terminate with a size-mismatch error.
`:=`/`final` retain binding-only immutability: element writes are allowed but the
binding cannot be reassigned. Only plain indexed `=` is implemented; indexed
compound assignment and increment/decrement remain separate work. The legacy
snippet emitter now uses an ordinary function frame to release its array copies.

Verified on 2026-10-30: the focused indexed-assignment and storage-lifetime tests
passed, followed by all 389 tests in `gradlew.bat :Compiler:test --offline
--console=plain --no-daemon`, with no failures, errors or skips. Eighteen
`ArrayAssignmentTest` cases cover parsing/spans, strict types and dimensions,
all primitive storage representations, independent bindings and parameters,
returned updates, nested construction/extraction/replacement, argument snapshots,
once-only target/RHS order, recursive string-array lifetimes, block/conditional
temporary results, final bindings, interrupted writes, bounds failure precedence,
runtime shape validation, unsupported indexed compounds, legacy snippets and
the normal CLI example in `examples/ArrayAssignment.co`. An added counted and
poisoning allocator test verifies independent modified values and balanced
cleanup across normal/early exits, recursion and discarded results. The full
run initially found a stale settings assertion; it now matches the existing
`examples/FunctionBlocks.co` default. `git diff --check` passed. Native execution
was verified on Windows; implementation uses the existing shared LLVM/ABI paths.

Local function blocks are implemented. `def<Type> name = { ... }` declares a
parameterless computation; each evaluated read executes it again. The explicit
annotation is its return contract. There is no declaration-time execution or
automatic cache. An ordinary binding initialized from its name saves the result.
See the confirmed [function-block semantics](semantics.md#function-blocks),
`functionBlocks` in `../../../../examples/Test.co`, and the runnable
`../../../../examples/FunctionBlocks.co`.

`Parser.parseBinding` recognizes block initializers and reuses the function body
AST inside `FunctionBlockExpression`. Declaration resolution gives each body a
child scope; captures refer to visible enclosing binding identities and read their
current values. The body cannot see its own newly declared binding or later
locals. `BodyTyping` checks the block under its own return contract, including
unused blocks. Its returns do not contribute to the enclosing function's flow.
Existing diagnostics C3030-C3033 label block return failures; C3041 rejects a
missing annotation and C3042 rejects assignment/updates to block bindings.

Native emission expands a fresh body evaluation at each read, with separate local
storage and a block-specific return merge. Both `=` and `:=` declarations are
accepted; neither memoizes. Explicit `<void>` blocks support ordinary void-body
return rules and effect-only reads. Normal argument order, overload selection,
short circuits and strict numeric/result types remain in force. String and array
temporaries use the physical enclosing function's existing storage lifetime;
results returned from that function follow the ordinary deep-copy and cleanup
paths. No escaping closure representation, new public ABI or GC is introduced.
Future block replacement rules and escaping capture lifetimes remain open design
questions. The internal legacy snippet analyzer is not the function-block pipeline;
use source units containing function declarations.

Verified on 2026-10-30: the focused function-block tests and the full
`gradlew.bat :Compiler:test --offline --console=plain --no-daemon` suite passed.
The full suite reports 370 tests with no failures, errors or skips. Nineteen new
`FunctionBlocksTest` cases cover syntax and spans, mandatory types, strict return
contracts and first-error diagnostics, scope/captures, declaration-time deferral,
saved results, argument order and overloads, short circuits, nested returns,
recursion, all primitive representations, void blocks, buffered results and the
normal CLI example. A twentieth test in `StringFunctionTest` uses the existing
counted/poisoning allocator to verify buffered block results and balanced cleanup
across repeated reads, discarded values, recursion and early enclosing-function
returns. Native execution is verified on the current Windows host;
implementation uses the shared LLVM backend without new platform-specific ABI
paths. `git diff --check` passed.

The array-interpolation guard is implemented. Shared expression typing now
rejects unsupported hole values with source-located C3040 before toolchain
discovery, output creation or LLVM emission. Integer/float/bool/char/string
interpolation and non-continuing holes with nested returns remain supported.
Array literals, bindings, parameters, nested arrays, subarrays and array-returning
calls are checked even in skipped branches and overload arguments. Formatting
output and a future `toString()` API remain deferred; see the completed
[formatting guard TODO](../../../../todo/native-formatting-guards.md).

Verification: the focused `StringInterpolationTest` run and the full
`gradlew.bat :Compiler:test --offline --console=plain --no-daemon` suite passed.
The full suite reports 350 tests, no failures, errors or skips. Four new tests
cover semantic rejection, exact source labels, fail-fast CLI rendering,
toolchain/output ordering, and native interpolation/return regressions on the
current Windows host. `git diff --check` passed.

Cross-platform requirement reaffirmed on 2026-10-30: Windows, Linux and macOS
must remain first-class targets with the same Cloth feature contracts. Native
startup/encoding adapters may differ by platform. Unicode argv work is tracked
in [the cross-platform argument TODO](../../../../todo/unicode-command-line-arguments.md).
This requirement does not establish native verification on all three systems;
current execution evidence below remains Windows-only. Invalid native argument
encoding policy is still unresolved.

Function implementation chunk 8 (array values and function boundaries) is
implemented. Array literals `[1, 2, 3]`, sized/unsized annotations in bindings,
parameters and returns, inferred element types and extents, zero-based indexed
reads, and nested arrays now parse, type-check and emit native code. The user's
exact examples are covered, including diagnostics for both too few and too many
initializers. `int[2][3]` uses C dimension order and nested literals such as
`[[1, 2, 3], [4, 5, 6]]`. Known sizes are retained in inferred bindings and
function signature keys. C3038 reports invalid construction, sizes or element
types; C3039 reports invalid indexing operand/index types. Compilation remains
fail-fast with one diagnostic.

Initial implementation policies (not separately confirmed language decisions):

- Unsized parameter/return dimensions accept matching element types of any
  extent, recursively. Known extents must match sized contracts exactly; the
  later element-assignment work adds runtime validation of unknown extents. A binding
  initialized from a known-size value retains its size even with `[]` in its
  annotation. An unsized function result retains its unknown static extent.
  Unsized contextual return contracts also allow conditional branches to yield
  different sizes; without such context, branch result types must still match.
- Literal-inferred known dimensions are homogeneous and rectangular; the first element supplies
  context for later integer literals. Numeric bindings are never implicitly
  converted. Call context reaches array elements, including floating literals;
  ordinary floating binding/return rules retain their previous behavior.
- `[]` needs contextual element typing; `def<int[0]> empty = []` and
  `fn<int[]> empty() = []` work. Inferred `def empty = []` is rejected.
- Exact default types/shapes win overload resolution; otherwise a unique match
  accepting ordinary array element types is preferred before literal adaptation.
  Distinct explicit array extents can distinguish overloads.
- All supported signed/unsigned integer widths may index an array. Invalid
  negative or past-end indices terminate through a controlled runtime error.
  Bounds are checked before deriving/dereferencing an element address.
- Literals and indexed operands evaluate once in source order. Returns nested
  in elements or indices stop subsequent evaluation. The later element-assignment
  work replaces descriptor-sharing transfers with recursive value copies and
  implements indexed writes. Array equality remains unimplemented.
- Native startup constructs `string[]` from `argc`/`argv`, excluding the
  executable name. The adapter borrows native narrow-string bytes and lengths;
  platform-specific command-line Unicode transcoding is not implemented.

At chunk 8, `EmitArray` stored a `{ ptr, i64 }` descriptor with storage-typed elements,
including byte storage for bools and descriptors for strings/nested arrays.
Local literals use stack buffers. Array-returning functions receive the same
private caller-arena parameter as string-returning functions. `EmitStringArena`
now recursively copies array storage and all nested string bytes into the caller's
arena, guarding size multiplication and allocation overflow. `EmitFunctionFrame`
copies the outgoing value before releasing the current invocation's buffers.
This remains temporary value storage, with no GC or changes to object lifetime
rules. Native array parameters and returns no longer report C4030.

`examples/Arrays.co` demonstrates construction, indexing, multidimensional
arrays and function calls/returns. README describes the executable support.

Verified on 2026-10-30: `gradlew.bat :Compiler:test --offline --console=plain
--no-daemon` passed all 346 tests, with no failures, errors or skips. The 18 new
`ArraysTest` cases cover the user's declarations, size inference and mismatches,
strict element types and immutability, malformed syntax and source spans,
sized/default-element overload selection, C dimension order, native primitive
storage, nested string-array returns across recursion, source evaluation order,
interrupted elements/indices, empty arrays, unsigned indexed-operand context,
different-size conditional returns, controlled bounds failures, command-line
arguments, library emission and the normal CLI example. An additional counted
and poisoning allocator test checks deep copies and balanced cleanup on normal,
early and discarded array-return paths, null allocations and multiplication
overflow before allocation. Updated prior capability tests now expect supported
array object/startup emission. A stale settings assertion was aligned with the
existing `examples/StringFunctions.co` default. `git diff --check` passed.
Native execution is verified on the current Windows host.

Review audit on 2026-10-30: the existing full compiler suite passed again using
`gradlew.bat :Compiler:test --offline --console=plain --no-daemon`. Additional
temporary probes found follow-up gaps that are not covered by those passing tests:

- Array interpolation, including `fn main() { #"{[1]}" }`, passed semantic
  checking but fails with C9000 (`Array printing is not implemented`) during
  emission. Unsupported interpolation operands need a source-located semantic
  diagnostic unless array formatting is separately designed and implemented.
  The user deferred native type-formatting design and requested a dedicated
  guard TODO: [native formatting guards](../../../../todo/native-formatting-guards.md).
  This guard is now implemented as C3040; see the current-work record above.
- Native `main(string[] args)` did not preserve the argument `é中😀` on the
  current Windows host: comparison against the same source literal returned
  status 1; the ASCII argument control returned 0. This reproduces the previously
  documented native narrow-string/Unicode-transcoding limitation. Windows argv
  construction needs lossless Unicode input converted to Cloth UTF-8 storage.
- There is no source-level array length query; `args.length` reports C2003.
  A length/count contract remains needed before programs can safely inspect
  variable-length command-line input without assuming a minimum argument count.
  Element mutation and array equality remain explicitly deferred design work.

No implementation fixes were made during this review. Probe observations are
saved in the ignored `build/review-chunk8/observations.txt`; the temporary probe
test source was removed after execution.

## Earlier milestone snapshots

The following records describe prior chunks. Array limitations in those records
are superseded by chunk 8 above; consult the current source map as well.

Function implementation chunk 7 (string function boundaries and return lifetimes)
is implemented. Native functions now accept and return the existing immutable
UTF-8 `{ ptr, i64 }` string descriptor. Parameters borrow the caller's bytes for
the duration of a call; reassignment still changes only the local binding.
String return values, including literals, borrowed parameters, local concatenations
and interpolation buffers, are copied before the callee returns. They remain
valid across later calls, reassignment, direct/mutual recursion and early returns.
String semantic types and overload rules are unchanged; C4030 now guards array
boundaries only.

Initial internal storage policy: a string-returning function has an additional
private LLVM parameter pointing to its caller's return-buffer list. At each
return it allocates and copies bytes into that list. Each invocation that
receives string results has its own lazily introduced head slot, initialized in
entry even when first used in a branch. `EmitStringArena` generates module-local
copy/release helpers, uses the target C ABI's size_t for malloc sizes, checks
size-addition overflow and null allocation, and releases buffers with free.
An allocation failure is a controlled runtime error rather than an unchecked
null dereference. Empty strings retain a valid address and zero byte length.

`EmitFunctionFrame` joins every normal exit at one return block. It merges
actual return predecessors, copies an outgoing string into the caller's storage,
then releases the current invocation's received results before returning.
Void fallthrough, bare/void-expression returns, scalar results and returns
nested inside expressions/call arguments all use this path. Runtime failures
terminate the process through the existing error path; they do not unwind frames.
Argument snapshots still evaluate once, left to right, before the callee runs.

This temporary value-storage policy adds no GC, reference counting, automatic
object destruction, programmer-visible ownership qualifiers or object lifetime
API. Local concatenation/formatting buffers still use their creating stack frame;
only returned strings require heap-backed copies. Every string return currently
copies, including literals and forwarded arguments, and received buffers live
until their receiving invocation exits, including ignored/discarded results.
This trades allocation/copy efficiency for a simple invocation lifetime rule;
zero-copy/escape optimization and strings stored in future objects or
containers remain separate work. The descriptor and hidden argument are a
provisional internal ABI, not a public C or cross-module export contract.

`examples/StringFunctions.co` exercises returned interpolation/concatenation and
recursive construction through the normal CLI with silent status checks. The
README and unsupported-boundary guidance now describe string support. Arrays,
including native construction for `main(string[] args)`, remain chunk 8 work.

Verified on 2026-10-30: `gradlew.bat :Compiler:test --offline --console=plain
--no-daemon` passed all 327 tests with no failures, errors or skips. The 17 new
`StringFunctionTest` cases cover borrowed parameters/rebinding, final parameters,
literal/raw/empty/Unicode/NUL returns, local concatenation and single-hole
interpolation, retained copies across subsequent calls, argument snapshots/order,
direct/mutual recursion, conditional return merges, interrupted arguments,
overloads, runtime-name collisions, object emission and dynamic CRT linkage.
Allocation wrappers count live buffers and poison bytes before free; normal,
early, scalar, void and discarded-result exits leave no outstanding allocations.
Injected null allocations and oversized internal descriptors exercise controlled
runtime failure and overflow rejection before allocation. Skipped code allocates
no return buffers, and array capability errors still precede output creation.
After adding branch-only allocation and pre-allocation early-return cases to the
cleanup coverage, a focused rerun of all 17 string tests also passed. The full run
also executed `examples/StringFunctions.co` through the CLI. Native execution was
verified on the current Windows host; other platforms' execution is not claimed.
`git diff --check` passed.

Function implementation chunk 6 (program entry and CLI migration) is implemented.
`Parser.parse()` now returns a declaration `SourceUnit`; the CLI runs the complete
function pipeline and starts an executable through its selected Cloth `main`.
File-level executable statements are rejected. Internal `parseSnippet`,
`compileSnippet` and `emitSnippet` helpers retain isolated body-fragment tests
and their result-printing instrumentation, without exposing snippet compilation
as a production source-file mode.

`ProgramAnalyzer` validates executable versus library/object mode on top of
`FunctionAnalyzer`. Executables require exactly one `main`, accepting no parameters
or one `string[]` parameter, and void or signed int8/16/32/64 returns. Parameter
names and `final` do not affect entry selection. Library mode requires no entry
and treats `main` as an ordinary function name. New entry diagnostics are C3034
(missing main, with an EOF label), C3035 (multiple entry declarations), C3036
(invalid parameters) and C3037 (invalid return type). Same-signature duplicate
functions still fail earlier with C3019. Declaration/body/entry and boundary
capability errors precede linker discovery and output creation; compilation
continues to stop at the first error.

`EmitEntryPoint` emits the native C `main` adapter and invokes the selected,
mangled Cloth function. Void completion returns zero. Integer results become
the native status: narrower signed results are sign-extended to C `int`; wider
results retain their low bits. The user confirmed low-bit truncation rather than
a runtime error for out-of-range int64 statuses on 2026-10-30. Host operating
systems may further constrain the status observed by a parent process. Native
programs do not implicitly print expression results.

`LLVMEmitter.compile(SourceUnit, Path)` emits and links executables;
`emitExecutable(SourceUnit)` produces verified startup IR. `emit(SourceUnit)`
still emits a declaration module without startup. CLI `--emit executable|object`
defaults to executable; object mode performs full function checking and object
emission without requiring main or discovering a linker. Its default filename
has a `.o` suffix. This is not yet an archive/shared-library or export ABI:
function symbols retain chunk 5's provisional internal linkage.

Runnable examples now contain explicit `main` declarations and verify their
calculations using process status 0/1. `examples/Functions.co` adds forward calls,
factorial and mutual recursion. CLI example tests check successful execution and
empty output. No console I/O syntax or library was invented. `examples/Test.co`
remains the broad design example, not a fully supported source file.

The `main(string[] args)` signature is semantically valid, but native array
construction remains chunk 8 work. It currently fails with C4030 before output
creation, as do other array function boundaries. String function boundaries and
return lifetimes are now supported by chunk 7 above. Chunk 6 introduced no heap
or object lifetime behavior.

Verified on 2026-10-30: the focused `ProgramEntryTest`, `CompilerSettingsTest`
and `DiagnosticsTest` run passed all 36 tests. The 24 new entry tests cover
declaration-only source parsing, missing/duplicate/invalid main diagnostics,
valid signature forms and signed widths, library entry independence, native
startup IR, silence without implicit printing, void and integer exit statuses,
wide low-bit truncation, narrow sign extension, recursion, source snapshots/EOF,
errors before output/linker discovery, CLI path handling, object output/defaults,
and unsupported argument arrays. The full
`gradlew.bat :Compiler:test --offline --console=plain --no-daemon` run passed all
310 tests with no failures, errors or skips, including the migrated runnable
examples and all prior function, scalar/string, diagnostics and toolchain tests.
Native execution was verified on the current Windows host; execution on other
platforms is not claimed. `git diff --check` also passed.

Function implementation chunk 5 (LLVM functions, calls and recursion) is
implemented through `LLVMEmitter.emit(SourceUnit)` and
`LLVMEmitter.compileObject(SourceUnit, Path)`. Both run the full function
analyzer and verify generated LLVM. Declaration modules do not synthesize a
native `main` or choose a Cloth entry point; object emission works without main
and does not invoke a linker. Chunk 6 above adds CLI startup separately.
Native tests compose a separate LLVM runner through an internal module
hook; its output printing and zero process status are test instrumentation, not
Cloth startup or implicit output behavior.

`EmitFunctions` declares every LLVM signature before emitting bodies, using the
selected function symbols from semantic analysis. Overloads have distinct,
deterministic module-local names (`cloth.fn.<index>.<name>`) and internal linkage,
avoiding native runtime-name collisions. This provisional naming does not define
cross-module exports or a C interop ABI. Direct, forward and mutual calls now
execute natively. Arguments are evaluated once, left to right, and their values
are captured before entering the callee; parameter reassignment changes only
that invocation's storage.

Scalar bindings are keyed by stable symbols in function emission, preserving
parameter/local/branch shadowing. Each function has independent parameter/local
storage, with scalar allocas inserted in its entry block and initializers/stores
left at their source execution points. Boolean storage remains one byte with
i1 values. All signed/unsigned integer widths, float16/32/64, bool, char and void
boundaries are supported. Float16 calls pass/return their raw i16 bits internally
and bitcast at the boundary, avoiding external half-conversion ABI helpers while
preserving Cloth's half type and rounding behavior.

Expression emission distinguishes void/non-continuing evaluation from values.
Explicit and implicit returns, bare/void-expression returns, and `-> void` now
emit without invented numeric placeholders. Emission stops after a terminator,
including returns nested in operands, initializers or call arguments, so later
operands/arguments and the callee are skipped when required. Conditional/logical
merges include only actual continuing predecessors; returning paths add no phi
input or extra branch. Merge blocks with no predecessors end in `unreachable`.
Simple boolean facts from semantic flow analysis prune infeasible alternatives
only after evaluating the condition/left operand, preserving its side effects.
Nested casts, strings and power guards retain their actual LLVM predecessor blocks.

Native string boundaries are now supported by chunk 7 above; array boundaries
remain unsupported pending their storage chunk. C4030 reports an unsupported
boundary type with its source span before output creation. Function-local strings,
interpolation and string operators remain supported; returned bytes are copied
into caller storage before the creating invocation exits. Chunk 5 itself added
no heap, GC, object lifetime mechanism or automatic object cleanup.

Verified on 2026-10-30: focused `FunctionEmissionTest` passed all 26 tests using
`gradlew.bat :Compiler:test --tests cloth.FunctionEmissionTest --offline
--console=plain --no-daemon`. Coverage includes verified IR/object modules without
entry synthesis, overload targets, forward calls, factorial/Fibonacci/mutual
recursion, parameter isolation, once-only argument ordering/snapshots, every
integer width/signedness boundary, booleans/Unicode characters, floating precision
and binary16 rounding/ABI, entry allocations and shadowing, early-return phi
predecessors, nested/non-continuing operands/arguments, short circuits and boolean
proofs, void/discard emission, skipped unreachable code, cast guards, local strings
across recursion, runtime-name collisions, unsupported boundaries before output,
and dynamic-CRT power calls. Native execution was verified on the current Windows
host; other platforms' native function execution is not claimed.
The full `gradlew.bat :Compiler:test --offline --console=plain --no-daemon` run
then passed all 286 tests with no failures, errors or skips, including the existing
semantic, native scalar/string, diagnostics, toolchain and CLI regressions.

Function implementation chunk 4 (return validation and control flow) is
implemented through `FunctionAnalyzer.analyze(SourceUnit)` or
`FunctionAnalyzer.analyze(DeclarationModel)`. This combines the implemented
declaration, call and return stages and returns `FunctionSemanticModel` with
`controlFlow` metadata. The public `CallAnalyzer()` remains a stage 3 entry point
without return validation, so earlier-stage tests and signature-only body
fixtures retain their meaning. This checker does not introduce a CLI mode or
itself emit native functions; chunk 5 above provides declaration-based emission.

The full checker enforces omitted/explicit void returns, bare early returns,
explicit returns in braces, implicit `=` returns, exact result types and
contextual integer literals. Void functions may return void expressions, as
newly confirmed by the user (`return cleanup()` or `return calculate() -> void`).
They cannot return a produced value, including through an `=` body without an
explicit discard. Typed numeric results require casts to change width, signedness
or kind; floating return literals retain existing binding rules, while the
contextual floating parameter rules from chunk 3 remain intact. Return context
still does not select overloads. Non-void brace bodies cannot fall through, and
their final expressions do not implicitly return.

`ControlFlowModel` tracks continuing and returning paths for expressions,
statements and function bodies. It handles ordered evaluation, nested conditionals,
returns in initializers/call arguments/interpolation, and short circuits. Simple
boolean literals, negation and logical combinations can prove branch reachability;
arbitrary arithmetic comparisons, binding values and callee termination remain
conservative. It does not attempt to prove recursive calls terminate or diverge.
Boolean facts and flow summaries use identity caches to avoid repeated traversal.

Value-producing conditional branches that return from the enclosing function do
not also need a final value expression. Continuing branches retain the existing
final-expression and compatible-result rules. An internal `ValueType.Never`
marks expressions with no continuing result; it has no Cloth syntax and is not
a new programmer-visible type. This allows all-returning branches to compose
inside calls, assignments, arithmetic and other existing expressions without
inventing a value or claiming fallthrough. LLVM value/storage/printing helpers
guard this internal type; chunk 5's emitter now handles flow without requesting
a value or storage for non-continuing expressions.

Initial implementation policy: unreachable statements and skipped branches remain
name/type/return checked, and otherwise valid unreachable code is accepted without
a new warning or error. `=` conditional bodies are checked as expressions rather
than statement conditionals, so their continuing branches must supply compatible
final expressions, including void expressions where appropriate. The existing
final-else rule for value expressions is retained when they can continue. These
details do not settle broader future unreachable-code or conditional design.

New structured diagnostics are C3030 (bare return requires a value), C3031
(produced value in void function), C3032 (return type mismatch), and C3033
(non-void body can reach its closing brace). They label the returned expression
or closing brace and the function's declared return contract. Return-expression
errors inside argument probes stay fatal instead of becoming overload mismatches.
Compilation still stops at the first detected error; no recovery or diagnostic
accumulation is added.

Verified on 2026-10-30: the focused `FunctionReturnsTest`, `CallResolutionTest`,
`DeclarationScopesTest`, `FunctionParsingTest` and `DiagnosticsTest` run passed
all 103 tests. The 30 new return/flow tests cover both body forms, void expressions
and discards, exact result types, every integer width/signedness boundary,
conditional path coverage, nested/non-continuing expressions, short circuits,
constant boolean facts, long logical chains, checked unreachable code, recursive
calls, probe diagnostics, overload preference preservation, array/string type
identity, source spans/CRLF, first-error rendering, analyzer reuse and earlier
stage boundaries.
The final full `gradlew.bat :Compiler:test --offline --console=plain --no-daemon`
run passed all 260 tests with no failures, errors or skips, including existing
native and CLI regressions. Native execution was verified on the current Windows
host. Native scalar function emission is now implemented by chunk 5 above.

The CLI now uses chunk 6's declaration and entry pipeline. Native scalar function
calls/returns are available through chunk 5, and string boundaries through chunk 7.
Argument-array construction remains a subsequent chunk; semantic array return
checking does not establish runtime array storage support.

Function implementation chunk 3 (call resolution and argument validation) is
implemented through `CallAnalyzer.analyze(SourceUnit)` or
`CallAnalyzer.analyze(DeclarationModel)`. It resolves primitive signature types
and semantic array identities, then checks function bodies using chunk 2's stable
symbols. `FunctionSemanticModel` records parameter/local types, expression and
statement types, and the selected `FunctionSymbol` for each call. Forward, direct
and mutual calls use declared return types without recursively analyzing the
called body's return paths. Unknown nominal types still receive an unsupported
type diagnostic; class/type declaration resolution is a later feature.

Calls first filter overloads by argument count, then prefer an exact match for
the arguments' ordinary types. Thus integer literals prefer default `int`, and
floating literals prefer default `float` (`float<32>`), independently of source
order. Without a whole-argument exact match, candidates are checked with their
parameter types as literal context. A unique viable candidate wins; two viable
candidates are ambiguous, and none produces a no-matching-call error. Integer
and floating literals receive only their own numeric kind of context. Already
typed bindings, casts and call results never implicitly change type to fit a
parameter. Return types and surrounding assignment/return expectations do not
select overloads. Initial implementation policy for multiple arguments: prefer
a whole exact match, otherwise require one viable candidate; there is no summed
conversion score or preference based on the number of contextual matches.

The user confirmed contextual floating literals at call sites as well as default
`float` preference. They retain original decimal text until a width is selected;
range checks use that width, including float16 overflow and literals beyond the
default float32 range that fit float64. The declaration parser therefore defers
floating literal range checks until typing. The legacy snippet parser retains
its existing default-float range diagnostics. This change supplies floating
parameter context; it does not broaden the existing floating literal rules for
binding assignments or return values. Integer literal context remains unchanged.

Overload probes use separate type/binding/call maps; only the selected probe's
facts are committed. A nonviable candidate does not construct a diagnostic or
leave literal types, branch-local types or nested call selections behind. Probe
rejection is a private control signal, not compiler error recovery or diagnostic
collection. Fatal call failures still throw one structured diagnostic immediately.
New codes are C3023 (argument count), C3024 (no match), C3025 (ambiguity), C3026
(void used as a value), C3027 (function used as a binding value), C3028 (floating
literal range) and C3029 (invalid void parameter/binding/array element type).
Call diagnostics label the call and relevant declarations.

`ExpressionTyping` and `TypeResolver` now share existing primitive expression,
binding, assignment and conditional typing rules with the legacy analyzer.
Calls can compose with expressions, inferred bindings, conditionals, interpolation
and casts; skipped code is checked. Standalone calls may discard values, and the
declaration checker supports explicit `-> void`. Void cannot supply an argument
or binding value. Array element/rank identity supports strict parameter passing,
but array storage, indexing and command-line argument construction are not
implemented; existing LLVM/C printing paths explicitly guard unsupported arrays.

The CLI now uses chunk 6's declaration and entry pipeline. Return-contract/flow checks are
available through chunk 4's `FunctionAnalyzer`, and function emission through
chunk 5 for scalar/void boundaries and chunk 7 for strings. Entry validation is
implemented by chunk 6; command-line argument construction remains pending. The stage 3 entry point
alone does not perform return checks or execute functions; chunk 5 enforces the
confirmed once-only evaluation order without changing AST argument order.

Verified on 2026-10-30: the focused `CallResolutionTest`, `DeclarationScopesTest`,
`FunctionParsingTest` and `DiagnosticsTest` run passed all 73 tests. The 29 new
call tests cover default int/float preferences, contextual widths, ambiguity,
numeric bounds, strict typed arguments/casts, arity, nonnumeric types, multiple
arguments, return-context independence, nesting, recursion, probe isolation,
contextual arithmetic, arrays, discards/void, skipped code, signature diagnostics,
deferred return/entry checks, source locations and analyzer reuse. The full
`gradlew.bat :Compiler:test --offline --console=plain --no-daemon` run then passed
all 230 tests with no failures, errors or skips, including existing numeric,
string, native and CLI regressions. Native execution was verified on the current
Windows host, without claiming function code generation.

Function implementation chunk 2 (declaration collection and lexical scopes) is
implemented through `DeclarationAnalyzer.analyze(SourceUnit)`. It collects all
function signatures and overload groups before resolving any bodies. Signature
keys include ordered parameter types, normalizing default numeric widths (so
`int` and `int<32>` conflict), while preserving signedness, explicit widths,
array rank and nominal type names. Return types, visibility, parameter names and
`final` do not distinguish overloads. These are structural declaration keys;
type names and widths are not yet semantically resolved or validated.

`DeclarationModel` records identity-based symbols for functions, parameters and
local bindings, lexical scopes, name uses, assignment/update targets, and visible
overload groups for calls. Every function gets its own scope beneath file scope;
parameters and outermost body locals share it. Nested conditional branches get
separate child scopes and may shadow enclosing bindings. Locals become visible
after their initializer is resolved, preserving the snippet analyzer's existing
behavior; a shadow's initializer can read the enclosing declaration. Branch
locals do not escape to sibling conditions/bodies, outer scopes or other functions.
The user confirmed one namespace: nearer bindings shadow functions even in call
position, producing a not-callable error rather than bypassing the binding.

The resolver checks duplicate function signatures/parameter/local names, unknown
names, non-callable binding calls, attempts to assign to functions, and immutable
binding/final-parameter writes. It traverses current expression and statement
forms, including nested calls, interpolation and skipped branches. Diagnostics
stop immediately and preserve original declaration/use spans; new codes are
C3019 (duplicate function), C3020 (unknown name), C3021 (not callable) and C3022
(assignment requires a binding). Final-parameter diagnostics reuse C3002 with
parameter-specific guidance. Calls retain their whole overload group without
argument-count filtering, type validation or overload selection in this stage;
the chunk 3 checker above handles those later. Direct and mutual
recursion now resolve at the name level, without claiming native function execution.

The declaration pipeline powers the CLI; internal snippet test helpers remain separate.
Call argument/type validation and overload selection are now available through
chunk 3, return checks through chunk 4, and scalar function emission through
chunk 5, and entry-point validation through chunk 6. Invoking only the chunk 2 analyzer may still leave
type-invalid calls/returns or both `main` forms; later stages enforce those contracts.

Verified on 2026-10-30: the focused `DeclarationScopesTest`, `FunctionParsingTest`
and `DiagnosticsTest` run passed all 44 tests using `gradlew.bat :Compiler:test
--tests cloth.DeclarationScopesTest --tests cloth.FunctionParsingTest --tests
cloth.DiagnosticsTest --offline --console=plain --no-daemon`. The 20 new
declaration/scope tests cover forward/direct/mutual calls, overload groups and
canonical duplicate signatures, parameter/local duplicates, branch shadowing and
isolation, initializer visibility, symbol identity for reads/writes, function-name
shadowing, immutability, skipped-code traversal, analyzer reuse, deferred later
checks, source spans/CRLF and first-error behavior.
The full `gradlew.bat :Compiler:test --offline --console=plain --no-daemon` run
then passed all 201 tests with no failures, errors or skips, including existing
native and CLI regressions. Native execution was verified on the current Windows
host; this does not imply function code generation is implemented.

Function implementation chunk 1 (declaration AST and body parsing) is implemented.
`Parser.parseDeclarations()` returns a `SourceUnit` containing function
declarations, separate from the existing executable-snippet `ASTStructure`.
It parses optional `pub`, omitted or explicit return annotations, typed parameters
with optional leading `final`, brace bodies, and `= expression` bodies. Omitted
return annotations remain absent in the AST, with the confirmed meaning of void.
Function-signature types preserve numeric widths/signedness and unsized `[]`
suffixes, including `main(string[] args)`, without implementing array values.

Function and conditional branches share brace-body parsing. `ReturnStatement`
preserves bare and value returns; `ExpressionBody` remains distinct from a brace
body's final expression. Named calls preserve argument order, nest inside ordinary
expressions and interpolation, and retain their closing-parenthesis source spans.
Function, parameter, body, return, and array-suffix spans retain the source snapshot,
including CRLF input. `final` is now a keyword. Word operators `for`/`while`/`do`
require identifier boundaries, fixing names such as `format` and `doubleValue`
that were previously split into operator prefixes.

The declaration entry point rejects file-level executable statements and nested
`fn`/`mt` declarations. It currently supports only `fn` declarations, not the
broader class/module/import grammar in `Test.co`. Parameter and argument lists
allow empty lists and multiline contents; trailing commas are currently rejected.
These list-format details are initial parser choices, not separately confirmed
language decisions. Return statements are recognized only within function bodies.

`Parser.parse()` and the CLI now use declarations, following chunk 6's entry-point
migration. The distinct `SourceUnit` type cannot be
passed accidentally to the existing snippet analyzer/emitter. Declaration
collection and lexical resolution are now available through the chunk 2 API
above, typed calls through chunk 3, and return validation through chunk 4.
Scalar function LLVM emission is available through chunk 5. Syntactically valid but semantically invalid functions can still
be parsed independently of those stages. Internal snippet test instrumentation
synthesizes a native runner; declaration modules add startup only when explicitly
compiled as executables. The confirmed [function contract](semantics.md) remains the
design authority. Local dynamic string buffers remain in their creating function;
chunk 7 implements safe string returns by copying into caller-owned storage.

Verified on 2026-10-30: the focused `FunctionParsingTest` run passed all 15 tests
using `gradlew.bat :Compiler:test --tests cloth.FunctionParsingTest --offline
--console=plain --no-daemon`. Coverage includes declaration/body structure,
visibility, mutable/final parameters, numeric and array signature syntax,
multiline lists/comments, nested statements/conditionals/calls/interpolation,
return boundaries, precedence, explicit void casts, deliberately deferred semantic
checks, malformed syntax and declaration contexts, source spans/CRLF/EOF,
first-error reporting, and identifier boundaries for word operators.
The full `gradlew.bat :Compiler:test --offline --console=plain --no-daemon` run
then passed all 181 tests with no failures, errors, or skips, including the 15
new parser tests and existing native/CLI regressions. The first full run exposed
a stale `StringInterpolationTest` expectation: committed `examples/EX.co` then
printed `The answer is: 400.200012`, not `The answer is: 20`. Updating that test's
expected output resolved the failure; the example and backend were unchanged.
Native execution was verified on the current Windows host.

Compound assignments `name += value`, `-=`, `*=`, `/=`, and `%=` now parse as
ordinary local binding assignments whose values use the corresponding binary
operation. Standalone `name++` and `name--` add or subtract a same-type one,
including floating types without promotion of half values to float32 bindings.
Existing exact binding-type, mutability, contextual integer literal, signedness,
width, and arithmetic rules apply; string `+=` uses ordinary concatenation.
The target's current value is read before the right operand, which runs once.
Only simple local binding targets are currently implemented. Update operators
are standalone postfix statements only, as explicitly confirmed by the user.
`x ** y` exponentiation is implemented. Integer results retain their operand
type and use exponentiation by squaring with ordinary wrapping multiplication,
preserving uint64 bits without a floating-point conversion. Direct negative
integer exponent literals report C3018; other negative integer exponents raise
a controlled runtime error. Both operands run once, left to right. Floating
and mixed powers use ordinary promotion, C `powf`/`pow`, and half result rounding.
Initial choices not separately confirmed as design: powers associate right,
bind above unary signs and multiplication, and zero to the zero power is one.
Existing matching integer width/signedness requirements apply. `//` remains a comment.
`examples/EX.co` uses `x += 1`; chunk 6 now checks its result through main status.

Verified on 2026-10-30: `gradlew.bat :Compiler:test --offline --console=plain
--no-daemon` passed all 166 tests with no failures, errors, or skips. The 14 new
`AssignmentOperatorsTest` cases cover compound precedence and types, mutable
and immutable targets, unsigned wrapping, string concatenation and copies,
target/RHS evaluation order, branch scopes, numeric updates including half
storage, rejection of prefix/expression updates, power grouping, all integer
widths/signedness, exact uint64 results, float promotion and special values,
static and runtime negative-exponent failures, nested control flow, short
circuits, and dynamic CRT power calls. At that stage CLI tests verified EX.co output.
Native execution was verified on the current Windows host.

String interpolation is implemented for `#"Hello, {name}!"`. The original
`#"The answer is: {x}"` example produced `The answer is: 20` in the snippet
runner; the current `examples/EX.co` checks its updated calculation inside main.
The lexer emits text and expression delimiters with original
source spans, recursively lexing holes through the ordinary scanner. Nested
interpolation, quoted/raw strings and characters, comments, casts, arithmetic,
and value-producing conditionals work inside holes. Embedded expressions are
checked normally, even in skipped code, and evaluated once, left to right.
Ordinary string `+` remains restricted to two strings.

Initial implementation choices, not separately confirmed language decisions:
`{{` and `}}` escape literal braces; unescaped `}` is C1012 and an unclosed
hole is C1011. Surrounding text uses ordinary quoted-string escapes and cannot
contain literal line breaks; hole expressions may span lines. There is no raw
interpolated-string form or custom format-specifier syntax. All currently
supported primitive values can be embedded. Strings preserve their UTF-8 bytes
and lengths, characters encode one scalar including NUL, booleans become
`true`/`false`, and numbers use the same ABI-aware decimal formats and precision
as native result printing. Numeric formatting uses C `snprintf` with a checked
64-byte buffer, enough for every supported integer and `%g` float representation.
Generated text uses the existing local string-buffer lifetime; this does not
settle dynamic/escaping string lifetime design. Empty text segments are skipped.

Verified on 2026-10-30: the full compiler suite passed 149 tests using
`gradlew.bat :Compiler:test --offline --console=plain --no-daemon`. After adding
three final regression cases, the focused `--tests cloth.StringInterpolationTest`
run passed all 14 interpolation tests with no failures, errors, or skips. Coverage
includes source spans, malformed syntax and Unicode, strict/skipped-code checks,
all numeric widths and signedness, native formatting precision, UTF-8 boundaries,
NULs, braces/escapes, nested strings/comments/conditionals, once-only evaluation
order, short circuits, reassignment/copies, large buffers, first-error behavior,
the C ABI declaration, static/default and dynamic CRT execution, and EX.co via CLI.
Native execution was verified on the current Windows host.

The user selected structured compiler diagnostics through Yvette 0.2.0. The
`cloth.diagnostics` package now defines stable codes, source labels, predefined
lexer/syntax/semantic/CLI/toolchain/backend factories, and one graphical reporter.
`ClothDiagnostic` implements Yvette's `Diagnostic` directly; syntax and semantic
exception types remain available as structured subclasses. Jansi supplies the
renderer dependency, and prettier4j 0.3.1 is explicit because Yvette's public API
exposes `Doc` but declares prettier4j only as a runtime dependency.

The source snapshot travels with token spans. Expressions, statements, literal
nodes, types, conditional bodies, and binding declarations preserve locations.
Internal positions are one-based UTF-16 with exclusive ends; the renderer converts
to zero-based Yvette ranges and expands tabs at four-column stops. Its source
adapter preserves CRLF input and empty final lines, including EOF labels.
Yvette's wide/combining Unicode display-width limitations remain. The reporter
uses terminal detection for colours/links and falls back to ASCII drawing when
stderr's encoding cannot represent the Unicode theme.

String-plus-integer expressions report C3017, label both operands, and explain
that concatenation requires two strings. Assignment, duplicate, and immutable
binding failures include the original declaration. Lexer, parser, semantic,
CLI/file, ABI, toolchain, linker, and LLVM failures use the shared diagnostics.
Internal compiler invariants use C9xxx; low-level type-constructor assertions stay
internal and are converted at the CLI boundary if reached during compilation.
`runCompiler` returns a status for embedding/tests; main exits unsuccessfully for
errors. Expected failures return 1, internal failures return 2, and `--debug` shows
retained causes and stack traces. The CLI defaults to `examples/EX.co`, preserving
the user's selected source. Semantic checks run before target/toolchain resolution
or output creation. Compilation stops at the first detected error by explicit
user decision and Cloth philosophy. Error recovery and multi-error collection
must not be introduced; they are not pending features. Multiple source labels
may explain the same error. Native runtime cast and string-length errors retain
their existing C ABI reporting path.

Verified on 2026-10-30: `gradlew.bat :Compiler:test --console=plain` passed
all 138 tests with zero failures, errors, or skips. Nine diagnostic tests cover the
EX.co rendering snapshot, code uniqueness, severity/help, operand and declaration
ranges, literal/composite spans, CRLF/tabs, trailing-newline and empty-source EOF,
multiline lexical failures, CLI file/argument failures, opt-in debug traces,
semantic errors preceding an invalid linker, and Windows ASCII fallback. Existing
language tests now assert structured source locations instead of message suffixes;
ABI/toolchain tests check diagnostic codes. The conditional CLI example's stale
expected output was corrected to its existing `It is unsigned!` result. The EX.co
CLI also rendered C3017 and exited with status 1, without a compiler stack trace.

The user selected `if` / `else if` / `else` conditionals next and requested
implementation. Inline and multiline chains, nested conditionals, boolean
conditions, branch scopes, semantic checks, and LLVM emission are implemented.
A conditional can initialize or reassign a binding and compose with ordinary
expressions. A standalone conditional executes its branch without an implied
value or automatic last-value output; it may omit `else` and have empty branches.
Conditions run once in order until the first true condition, and only the
selected body executes. Skipped conditions and bodies are still type checked.

The initial implementation uses these rules for details not separately settled
by the user: a value-producing chain requires a final `else`; every branch must
end with an expression, which supplies its value; and all branch results must
have exactly the same type, including numeric width and signedness. Declared
integer binding types provide context for branch-result integer literals, using
the existing literal rules. There is no implicit branch-result numeric promotion.
Branches have separate lexical scopes, may shadow enclosing names, and may
assign enclosing mutable bindings. Local names do not escape into sibling
conditions, sibling branches, or the enclosing scope. These initial choices
are recorded as implementation behavior, not additional confirmed user decisions.

Primitive `is Type` checks are implemented, including `a is unsigned int`, exact
numeric widths/signedness, and `bool`/`char`/`string` targets. The operand is
evaluated once even when its type is statically known. Checks use comparison
precedence, below casts/arithmetic and above equality/logical operators. Class
runtime type checks, state checks, and class-type narrowing remain unimplemented.
`../../../../examples/Conditionals.co` is runnable and checks `It is unsigned!`;
the user's original initializer in `../../../../examples/Test.co` is covered by
a native test, without claiming that the rest of that design file compiles.

The user selected string definitions after characters. Explicit `def<string>`
and inferred string bindings, mutable `=` / immutable `:=` declarations, copies,
reassignment, parenthesized literals, and identity casts are implemented. The
user then selected equality and concatenation: string `==` / `!=` and `+` are
implemented with strict string operands, ordinary precedence, and once-only
left-to-right operand evaluation. Equality returns `bool`; concatenation returns
`string` without changing either input.
Ordinary double-quoted literals decode `\n`, `\r`, `\t`, `\0`, `\\`, `\'`, and
`\"`; unknown escapes, line breaks, unterminated strings, and isolated UTF-16
surrogates are rejected. Triple-quoted raw strings preserve their contents,
including newlines, quotes, and backslashes, while validating Unicode scalars.

LLVM string values and binding storage currently use `{ ptr, i64 }`: a pointer
to UTF-8 bytes and their byte length. Literal bytes use private constant storage
for the program's lifetime. Concatenation checks length-addition overflow before
allocating a fresh local byte buffer, then copies both operands with LLVM memcpy
intrinsics. These buffers last for the creating function, including separate
recursive invocations. Function returns now copy into caller-owned buffers through
chunk 7; strings escaping into future objects remain separate work. Copying a binding
copies its descriptor; reassignment does not change previous copies. There is
no heap allocation for local concatenations; chunk 7 uses malloc/free for return
buffers without adding GC, reference counting or language-level object destruction.
This is an internal implementation choice, not a decision about dynamic string
APIs or language-level object lifetime. Internal test result output walks the stored
length through ABI-typed `putchar`, preserving Unicode, empty strings, embedded
NULs, and percent signs. Equality checks lengths before ABI-typed C `memcmp`
using the profile's unsigned pointer-width `size_t`, and compares exact contents
without Unicode normalization or case folding. Empty strings and NULs work in
both operators. Comparison branches compose with nested short-circuit logic;
skipped concatenations are not evaluated. A shared `EmitRuntimeError` emits
existing numeric-cast failures and new concatenation length-overflow failures.
String ordering, other arithmetic, logical operations on strings, cross-type
conversions outside interpolation, indexing, and nullable annotations are not implemented.
Unsupported operations are rejected
before LLVM emission. `../../../../examples/Strings.co` demonstrates bindings and
checks `Unicode: é 中 😀`; it also demonstrates concatenation, equality, and
copies preserved across reassignment. Runnable examples and CLI regression paths use
the repository's `examples` directory.

The user selected `char` next and confirmed 32-bit Unicode scalar storage.
Explicit `def<char>` and inferred bindings, immutable and mutable declarations,
copies, reassignment, parenthesized literals, character `==`/`!=`, and identity
casts are implemented. Characters are distinct from numbers and booleans;
arithmetic, ordering, logical operators on characters, cross-type comparisons,
and casts to/from other types are rejected pending language design. `unsigned`
and width parameters are not accepted for `char`.

The lexer accepts a single Unicode scalar between single quotes, including a
UTF-16 surrogate pair in the host's source string, and rejects isolated
surrogates, empty/multiple-scalar literals, and raw line breaks. Existing escapes
`\n`, `\r`, `\t`, `\0`, `\\`, `\'`, and `\"` are decoded. String scanning now
correctly uses double quotes; its previous single-quote check had shadowed the
character scanner. LLVM character values and local storage use `i32`. Native
test-result character output emits UTF-8 bytes through ABI-typed `putchar` calls,
independently of the C locale and native `wchar_t` width. `../../../../examples/Characters.co`
demonstrates the feature and checks `😀` inside main.

As of 2026-10-29, the user selected explicit integer bindings as the first
compiler milestone after arithmetic. It is implemented and verified: `def<int>`
with mutable `=` or immutable `:=`, binding reads in expressions, and reassignment
with `=`. The user then selected type inference, now implemented and verified:
`def x = 10` and `def y := x + 2` infer from their initializers, and reassignment
must satisfy the original inferred type. Current expression types allow inferred
`int`, `float`, `bool`, `char`, and `string` bindings. Explicit `def<float>` bindings have now also been
implemented and verified, alongside `def<int>` and inferred declarations, with
the same assignment checks and mutable/immutable binding behavior.
Reusable parsing of delimited contents is an explicit implementation requirement.

Binding rules, object deletion, state dispatch, class/state
signature collisions, initial-state construction, state re-entry, absence of exit
hooks, and payload binding for running methods have been settled; see
[confirmed semantics](semantics.md).

Reusable top-level states, their host contracts, declaration and attachment
syntax, attached initial-state selection, and automatic external-transition
permission for attached public states are also confirmed in that reference.

State calls now use `object::method(...)`; ordinary instance method calls use
`object.method(...)`. `../../../../examples/Test.co`'s attack call has been updated accordingly.

The user selected boolean bindings next. Explicit `def<bool>` and inferred
boolean bindings are implemented and verified, including `true`/`false`, reads,
copies, mutable reassignment, strict types, and immutable bindings. The user
subsequently selected one byte per boolean for now, replacing shared-byte bit
packing. LLVM expression values use `i1`; each local binding has its own `i8`
allocation. Stores zero-extend to a canonical byte value of 0 or 1; loads truncate
back to `i1`. Assignments directly store the new byte without loading its previous
contents. Fields, arrays, atomics, and foreign-interface layouts remain future work.

Prefix boolean negation is implemented and verified: `!` accepts only `bool`
operands and emits logical inversion of an `i1` value. It supports literals,
binding reads, repeated negation, parentheses, initializers, and assignments.
The user next selected `&&` and `||` with short circuiting, now implemented and
verified for `bool` operands. Precedence is `!` above `==`/`!=` above `&&` above `||`; binary
logical operators associate left to right, and parentheses override precedence.
The emitter uses conditional branches and `i1` phi values so the right operand
is evaluated only when needed. Nested expressions use their actual ending
blocks as phi predecessors. Both operands are checked before code generation.
The user next selected `==` and `!=` for booleans, then integers and floats;
all are implemented and verified. Equality returns `bool`, evaluating left then
right without short circuiting. Equality operators have equal precedence and
associate left to right. Boolean/numeric comparisons are rejected.
Integer and boolean comparisons emit `LLVMBuildICmp` with `LLVMIntEQ` or
`LLVMIntNE`. Float comparisons emit `LLVMBuildFCmp` with `LLVMRealOEQ` for `==`
and `LLVMRealUNE` for `!=`: exact value equality, signed zeros equal, and NaN
unequal to every value including itself. Infinities compare by sign and value.
Mixed `int`/`float` comparisons follow existing arithmetic promotion: convert the
integer to the current 32-bit float before comparing. Large integers can round
in that conversion (for example, `16777217 == 16777216.0` is `true` under this
policy), while `int`/`int` equality remains exact. This conversion is specific to
expressions; strict binding assignment types are unchanged.

The user then selected `<`, `>`, `<=`, and `>=` for integers and floats, now
implemented and verified. All require two numeric operands and return `bool`;
booleans are rejected. Integer predicates are signed (`SLT`, `SGT`, `SLE`, `SGE`),
and float predicates are ordered (`OLT`, `OGT`, `OLE`, `OGE`), so every ordering
comparison with NaN is false. Signed zeros compare equal for `<=` and `>=`, and
infinities follow their numeric ordering. Mixed comparisons use the same float32
promotion described above. Arithmetic binds above ordering, ordering above
equality, equality above `&&`, and `&&` above `||`; prefix unary signs bind
above these operators but below exponentiation. Ordering operators associate left to right; mathematical
chains such as `1 < x < 3` are rejected because the first result is `bool`.
Use `1 < x && x < 3` for a range check.

The user then selected unsigned integer integration. `def<unsigned int>` uses
32 bits by default; explicit `int<32>`, `unsigned int<32>`, `int<64>`, and
`unsigned int<64>` bindings are implemented. Width syntax reuses the generic
delimiter parser. Unsigned types survive inferred copies, arithmetic results,
assignments, comparisons, and final output. Literals take integer context from
typed operands or binding declarations/assignments and must fit that type;
otherwise they default to signed 32-bit. Named bindings never change type to
fit a context. Signed/unsigned and different-width integer expressions require
explicit conversion, and binding assignments retain exact type checks.
Unsigned arithmetic wraps modulo the selected width, as confirmed by the user.
Unsigned division/remainder and ordering emit `udiv`/`urem` and unsigned `icmp`
predicates; float promotion uses `uitofp` for unsigned inputs. Native output uses
the appropriate signed/unsigned format, extending 8/16-bit values to 32 bits for
the native variadic calling convention. `unsigned` on another type is
rejected. `../../../../examples/Unsigned.co` demonstrates the supported integration.

Explicit `->` casts are now implemented between all eleven numeric types: signed
and unsigned 8/16/32/64-bit integers and 16/32/64-bit floats. The user confirmed integer
wrapping/truncation, float-to-integer truncation toward zero with an error on
invalid results, and keeping booleans separate from numbers. The user also
confirmed that `1 + 2 -> float` casts the entire sum. Cast precedence sits below
arithmetic and above ordering, and chains associate left to right.
`CastExpression` retains the operator and target type. Its operand is checked
independently of the target and surrounding assignment; default integer literals
remain signed 32-bit inside casts. Larger integer literals still require an
appropriate typed binding before conversion.

`EmitCast` emits integer truncation or source-signedness-aware extension,
signed/unsigned integer-to-float conversion, and float widening/narrowing.
Float-to-integer conversions branch through ordered range checks before
`fptosi`/`fptoui`; invalid executed casts print a diagnostic with the cast's
location and exit with status 1. Checks permit fractions whose truncation fits,
including small negative fractions converting to unsigned zero. Checks occur
inside the operand's runtime path, so short circuiting skips invalid casts.
Identity casts, including `bool -> bool`, preserve the value. Boolean/numeric
casts are rejected before emission.

`ValueType` represents integer and floating families with validated width data,
plus a distinct boolean type. Numeric and boolean spellings derive from `Keywords`;
the semantic resolver uses the same keywords and supported-width sets. Explicit
widths use the required `<number>` syntax already handled by `TypeParser`.
Signed and unsigned `int<8>`, `int<16>`, `int<32>`, and `int<64>` are supported;
unannotated integers still default to signed 32-bit.

The remaining compiler type audit centralizes integer bounds, floating format
metadata, literal overflow thresholds, and display precision in `ValueType`.
Integer literal parsing derives its maximum width from the supported integer
widths, and emission passes decimal text to LLVM rather than narrowing through a
JVM `Long`. Floating literals retain decimal text, validate against the default
floating type's exact overflow threshold, and let LLVM round directly to the
semantic type. Parsing no longer fixes literals to a JVM float32 representation.
Diagnostics and emitted boolean text use `Keywords` or the resolved type spelling.

`ValueType.FloatingFormat` declares IEEE half, single, and double widths and
exponent sizes once; supported floating widths derive from those definitions.
`ValueType` also provides canonical `HALF`, `SINGLE`, and `DOUBLE` types alongside
the default `FLOAT`, and distinguishes logical `bits` from binding `storageBits`.
Booleans have one logical bit and eight storage bits; numeric storage uses the
numeric width. These language definitions do not depend on JVM type sizes.

`LLVMTypes` only maps semantic value/storage representations to LLVM constructors.
Its floating format mapping is exhaustive, so introducing a format requires an
explicit backend mapping. It defines no types, storage-width policies, C ABI
rules, or arithmetic promotion rules.

The user selected explicit target ABI profiles without adding a Clang frontend.
`NativeCABI` is now an instance selected from LLVM's normalized target triple,
with Windows LLP64, Linux LP64, and Darwin LP64 profiles. Initial supported
architectures are x86-64 and AArch64 (including Apple's `arm64` spelling): Windows
requires the MSVC environment, Linux requires GNU or musl, and macOS accepts Darwin
or macOS versioned OS names. LLVM normalizes abbreviated triples first, including
its default MSVC environment for a Windows triple without an explicit environment.
Unsupported architectures, OSes, and environments receive a diagnostic containing
the original/normalized triple and supported targets; there is no profile fallback.
This includes x32/ILP32, Android, MinGW, iOS, and bare-metal targets.

Profiles declare C int/long/long-long and pointer widths, double format, and
executable format. Windows uses a 32-bit long; Linux/macOS use a 64-bit long.
These are explicit ABI facts, not automatic discovery or a derivation from JVM or
Cloth default widths. Profile sources are Microsoft's
[LLP64 model](https://learn.microsoft.com/en-us/windows/win32/winprog64/abstract-data-models)
and [type ranges](https://learn.microsoft.com/en-us/cpp/cpp/data-type-ranges), the
[x86-64 psABI scalar types](https://gitlab.com/x86-psABIs/x86-64-ABI/-/blob/master/x86-64-ABI/low-level-sys-info.tex),
[AAPCS64](https://github.com/ARM-software/abi-aa/blob/main/aapcs64/aapcs64.rst), and
[Apple's 64-bit data model](https://developer.apple.com/library/archive/documentation/Darwin/Conceptual/64bitPorting/transition/transition.html).

`LLVMEmitter` resolves the native ABI once before generating IR and before creating
output files. It records that normalized triple in the module, uses the selected
C int type for the entry point and library declarations, and passes the ABI into
`EmitCast` for runtime error calls. Object generation uses the same triple and
checks the profile's pointer width against LLVM's target data layout. Printing
argument selection and C conversion specifiers belong to the selected ABI;
display precision derives from shared floating metadata and uses ordinary floating
conversions. Output suffixes and linker selection follow the profile's executable
format; the CLI leaves suffix selection to the emitter. Compilation remains native
only; matching profiles for other targets is not a cross-compilation interface.

The user then selected separation of linker/toolchain configuration from process
execution. `LinkOptions` carries executable/driver overrides, default/static/dynamic
runtime linkage, console/Windows subsystem, ordered user library directories and
libraries, sysroot, MSVC tools root, Windows SDK root, and optional SDK version.
`CompilerSettings` exposes these through CLI options while retaining the two
positional source/output arguments and default source. Long options accept separate
or `=` values; `-L`/`-l` are repeatable, and `--` permits dash-prefixed filenames.
The README documents the supported options.

`ToolchainResolver` resolves and validates these options for the selected ABI before
IR or output-directory creation. Windows uses bundled generic LLD by default or an
explicit MSVC-compatible driver. It discovers MSVC through `VCToolsInstallDir` or
`vswhere` and Windows SDKs through SDK environment variables or the Windows Kits
installation. Tools/SDK versions sort numerically. It selects x64/ARM64 library
directories from the ABI architecture and resolves CRT, compatibility, and kernel32
libraries to actual files. The chosen paths become explicit `/libpath` arguments
and the link process's `LIB` environment. Static CRT remains the default; dynamic
CRT selects the corresponding import libraries. Machine/subsystem flags are
explicit, and GUI output selects `mainCRTStartup` to initialize the CRT before
calling Cloth's generated `main`. Microsoft LINK's help/no-input status is handled
without treating arbitrary failed probes as successful.

Unix resolution uses `--linker`, `CC`, or `cc`, identifies GCC/Clang, and validates
the driver's reported target architecture, OS profile, and Linux GNU/musl
environment against LLVM's selected ABI. Clang receives the selected target triple;
fixed-target GCC does not receive Clang-specific options. Library paths come from
explicit options, sysroot/SDK, and driver search-directory queries. Linux resolves
its math runtime, including a static archive for static linkage; macOS resolves
the SDK's System library and obtains the SDK from `--sysroot`, `SDKROOT`, or `xcrun`.
Unsupported driver/subsystem/runtime/SDK combinations and missing target files
fail during resolution. This does not add a Clang frontend or a cross-target CLI.

`LinkerConfiguration` holds the resolved executable, flags, runtime files, library
paths, user libraries, environment, and target identity, and constructs an argument
list without shell quoting. `NativeLinker` only executes that command and checks
its result. Default configurations are cached per target within the compiler
process; explicit options resolve independently. Toolchain probes have bounded
waits. The implementation follows the
[LLD Windows interface](https://lld.llvm.org/windows_support.html),
[MSVC CRT choices](https://learn.microsoft.com/en-us/cpp/c-runtime-library/crt-library-features),
[CRT entry points](https://learn.microsoft.com/en-us/cpp/build/reference/entry-entry-point-symbol),
and [GCC target/search queries](https://gcc.gnu.org/onlinedocs/gcc/Developer-Options.html).
The software half arithmetic working type is selected by `EmitHalf.arithmeticType`;
semantic expression promotion remains in `ValueType.commonFloating`.
`EmitBindings` retains the semantic type instead of reconstructing boolean storage
rules from LLVM widths. `EmitCast` uses the model's integer bounds and tests whether
the exclusive lower bound is representable instead of special-casing 64 bits.
Half conversion masks and thresholds derive from the shared floating metadata,
and binding storage follows the widths declared in `ValueType`.

`float<16>`, `float<32>`, and `float<64>` are supported in binding annotations and
cast targets. Two floats use the wider format, including half results for two
half operands. Mixing with integers uses at least float32; the presence of a
64-bit float promotes mixed numeric expressions to double. Decimal literals still start as
float32, and widening them cannot recover digits already rounded. Explicit casts
are required for float-width changes in binding assignments. All floating widths
support storage, arithmetic, negation, comparisons, and native printing.
`EmitHalf` converts IEEE binary16 values through integer bit operations, with
round-to-nearest-even for float32/64 inputs (including subnormals, signed zero,
infinities, and NaN). It converts double directly to half to avoid double rounding.
Half bindings use LLVM `half` storage; half arithmetic computes in float32 and
rounds back to half. This avoids LLVM half runtime helpers absent from the current
Windows link setup. Native printing widens half/float32 to double.
`../../../../examples/Casts.co` demonstrates widening before arithmetic and evaluates to `4294967294`.
`../../../../examples/Test.co` remains a broader design example: primitive `int::MAX` lookup and other
unsupported declarations in that file are not implemented by this milestone.

The user selected character and then string bindings after numeric widths.
The user then selected string equality and concatenation. The milestone after
these string operators has not been chosen. Do not treat the order
of open questions or missing features as an approved plan.

## Implementation snapshot

Inspected from repository source through 2026-10-30; recheck before relying on it:

- The compiler is Kotlin/JVM in `Compiler`, built with Gradle. Its module declares
  Kotlin 2.4.20, JDK toolchain 25, and Bytedeco LLVM bindings.
- `Compiler/src/main/kotlin/cloth/Entry.kt` wires source loading, lexer, parser,
  and native LLVM compilation. The CLI settings currently default to `examples/FunctionBlocks.co` and accept
  `[source.co] [output] [--emit executable|object]`, preserving source paths and using a separate output.
- `cloth/lexer/Lexer.kt`, `cloth/token/Keywords.kt`, and `Operators.kt` contain
  broader lexical machinery. Recognizing a token does not establish parser,
  type-checker, or emitter support for that language feature.
- `cloth/parser/Parser.kt` uses `parse()` / `parseDeclarations()` as declaration entry points,
  producing `SourceUnit` and the function/parameter/body AST in
  `cloth/parser/declaration/Declarations.kt`. Internal `parseSnippet()` and function bodies
  parse expression statements, explicit or inferred binding
  declarations, simple and compound assignment statements, standalone postfix
  `name++`/`name--` updates, and `if` chains
  with brace-delimited branch statement lists. Statements currently require
  newline separation (or a closing branch brace/EOF). A chain accepts `else` on
  the same line or after newlines/comments without consuming a subsequent
  unrelated statement's separator.
  `BindingDeclaration.type` is nullable to represent an omitted annotation.
  `cloth/parser/subs/ExpressionParser.kt` handles integer and float literals,
  boolean, character, ordinary/interpolated/raw string literals, binding names, parentheses, unary `+`/`-`/`!`, and binary `+`, `-`, `*`, `/`, `%`, `**`, `&&`, `||`, `==`, `!=`, `<`, `>`, `<=`, `>=`
  with precedence, plus `-> Type` casts between arithmetic and comparison
  precedence levels, primitive `is Type` checks at comparison precedence, and
  conditional expressions, named function calls with comma-separated arguments,
  array literals and chained indexed reads. Calls have separate name and typed
  resolution stages and native scalar, string and array emission.
- `cloth/parser/subs/DelimitedParser.kt` provides a generic delimiter wrapper
  with caller-supplied content parsing, including angle and parenthesis helpers.
  Binding type annotations, parenthesized expressions/conditions, and branch
  braces use this wrapper;
  recursive callbacks can handle nested delimiters. `TypeParser` reads an optional
  `unsigned` modifier, a type name, and an optional nested numeric width.
  Bindings and function headers enable sized `[N]` and unsized `[]` suffixes.
  In a cast, only a complete `<number>` suffix is consumed as a width; other
  following `<` tokens remain available for ordering comparisons.
  General generic type arguments are not implemented.
- `cloth/semantic/DeclarationAnalyzer.kt` collects declaration signatures and
  resolves function-body names through `DeclarationModel`, using the symbols
  and lexical scopes in `cloth/semantic/symbols`. This path consumes `SourceUnit`
  separately from the legacy snippet analyzer and does not select overloads or
  type-check expressions yet.
- `cloth/semantic/CallAnalyzer.kt` and `FunctionSemanticModel.kt` resolve typed
  calls on top of `DeclarationModel`, using shared `ExpressionTyping.kt` and
  `TypeResolver.kt`. The selected callee is recorded independently of its return
  context; primitive numeric width/signedness and array rank remain strict.
  Contextual floating literals are enabled for call arguments, while legacy
  snippet binding behavior is retained. Return-flow checks are available through
  `FunctionAnalyzer`; native scalar function emission is implemented through
  the declaration-based LLVM entry points.
- `cloth/semantic/FunctionAnalyzer.kt` enables the full implemented function
  checker, adding return contracts and `ControlFlowModel` completion facts for
  function emission. Earlier declaration/call APIs retain their stage
  boundaries.
- `cloth/semantic/ProgramAnalyzer.kt` applies executable entry rules separately
  from library validation. `EmitEntryPoint` adds native startup only for executable
  emission. The CLI defaults to executable mode; object mode requires no main
  and does not invoke a linker.
- `cloth/semantic/SemanticAnalyzer.kt` checks the executable scope and nested
  branch scopes before LLVM emission. It rejects unknown names, duplicate declarations,
  immutable reassignment, and assignment type mismatches. An omitted annotation
  infers signed/unsigned 8/16/32/64-bit integers, 16/32/64-bit floats, `bool`, `char`, or `string` from the initializer once, preserving that type on all
  later assignments. Current assignment checks require exact types; no new
  numeric binding conversions were introduced. Integer literals can take an integer
  context without converting named bindings. A `SemanticModel` records each
  expression and statement's language type for emission, using identity keys.
  Explicit signed/unsigned 8/16/32/64-bit integers, 16/32/64-bit floats, `bool`, `char`, and `string` annotations are implemented;
  other explicit type names receive an unsupported-feature diagnostic. Casts
  and primitive `is` checks resolve their target through the same type resolver
  as binding annotations. Standalone conditionals have `void` statement type;
  value-producing chains require exhaustive, compatible branch results.
  Boolean/numeric conversions and arithmetic on booleans are rejected before emission; prefix `!` requires a boolean operand and returns `bool`. `&&` and `||` require boolean operands. Equality accepts two booleans, two characters, two strings, or two numbers, including mixed int/float values, and returns `bool`. Ordering comparisons require two numbers and return `bool`.
  Bindings become available after their initializer is checked;
  forward/self reads are currently rejected. This is implementation coverage,
  not a decision about declaration resolution for the broader language.
- `cloth/emitter/types/EmitFunctions.kt` declares every scalar/void function
  signature before emitting bodies. `LLVMEmitter.emit(SourceUnit)` and
  `compileObject(SourceUnit, Path)` use `FunctionAnalyzer` and this emitter;
  they generate verified declaration modules without synthesizing a native
  entry point. Scalar storage uses stable symbols and entry allocas, calls use
  selected symbols, and returns/merges respect continuing control flow. String
  parameters/returns work through chunk 7; array parameters/returns work through
  chunk 8, including recursive copies of nested arrays and string elements.
- `EmitFunctionFrame` joins normal exits, copies outgoing strings and arrays into
  the caller's return storage, and releases the current frame's received buffers.
  `EmitStringArena` emits checked malloc/copy and free helpers using the native
  size_t ABI. Local formatting/concatenation buffers remain on the creating stack.
- `cloth/emitter/LLVM.kt` and `cloth/emitter/types/EmitExpression.kt` emit LLVM
  code for arithmetic, boolean negation (`LLVMBuildNot` on `i1`), short-circuit
  logical expressions (conditional branches and phi values), boolean equality and numeric equality/ordering
  comparisons (`icmp` on `i1`/`i8`/`i16`/`i32`/`i64` or `fcmp` on `float`/`double`), casts
  through `EmitCast`, binding reads, and conditional chains. Conditional value
  merges use phi nodes with each branch's actual ending block, including nested
  expressions and runtime guards. Statement conditionals have no value phi.
  Integer signedness comes from semantic types, since LLVM integer types do not
  encode it. `EmitBindings` allocates typed local
  storage and emits loads/stores (with a separate byte per boolean and conversion
  between `i1` values and `i8` storage); `EmitStatement` handles declarations and
  assignments. Branch emission restores lexical binding names without undoing
  stores to enclosing bindings. Internal snippet test instrumentation prints the
  last value when requested; production programs use explicit main exit status
  and do not implicitly print values.
- `../../../../examples/Math.co` is the arithmetic example; `../../../../examples/Bindings.co` demonstrates explicit and
  inferred integer and float bindings and checks `76.25`. It includes
  commented examples of invalid type-changing and immutable reassignments. `../../../../examples/Booleans.co` demonstrates boolean inference, explicit types, copies, negation, short-circuit logic, boolean/numeric equality and inequality, numeric ordering, and reassignment, and checks a true result.
  `../../../../examples/Unsigned.co` demonstrates unsigned widths, wrapping, copies, division, float
  promotion, and comparisons, and checks a true result.
  `../../../../examples/Casts.co` demonstrates numeric conversions and checks `4294967294`.
  These examples have explicit main functions, return 0/1 for pass/fail, and do
  not print implicitly. `../../../../examples/Functions.co` exercises recursion.
  `../../../../examples/Test.co` describes the broader target
  language; it is not currently an end-to-end compiler test.
- Verified on 2026-10-30: the full compiler suite passed all 129 tests with no
  failures, errors, or skips using
  `gradlew.bat :Compiler:test --offline --console=plain --no-daemon`.
  All 15 new `ConditionalsTest.kt` cases also passed in a focused run. Coverage
  includes inline/multiline and nested chains, malformed syntax, exhaustive
  value branches, strict condition/result types, skipped-code checking, branch
  scopes and shadowing, mutability, all current primitive storage types,
  expression composition, actual LLVM predecessor blocks after nested guards,
  once-only condition evaluation, skipped alternatives/bodies, primitive `is`
  checks with operand side effects, absent output from standalone conditionals,
  the user's exact unsigned initializer, and the runnable conditional CLI example.
- Verified on 2026-10-30: 114 test cases checked for string equality and
  concatenation. The full run passed 113 cases and found a stale default-source
  assertion; after updating it to the current `examples/Strings.co` default,
  all three settings tests passed in a focused rerun. Operator tests cover strict
  types, precedence, immutable bindings, exact content equality, differing
  lengths, Unicode and normalization distinctions, empty strings, NULs, raw text,
  chained/nested concatenation, copied values, self-reassignment, short circuits,
  generated length guards and ABI declarations, and native concatenation growth
  across stack pages. The full run also passed all numeric-cast error tests
  after their failure emission was shared with concatenation guards.
- Verified on 2026-10-29: the full compiler suite passed all 106 tests across
  `BindingsTest.kt`, `UnsignedIntegersTest.kt`,
  `CastsTest.kt`, `NumericWidthsTest.kt`, `NativeCABITest.kt`,
  `CompilerSettingsTest.kt`, `ToolchainResolverTest.kt`, `CharactersTest.kt`, and
  `StringsTest.kt`. The full run used `--no-daemon` and a temporary init script
  under `build` to isolate test reports after Gradle reported corrupt/missing
  files in its default report directory. Standard focused string, character,
  and settings tests also passed. String tests cover literal decoding, raw
  text/newline preservation, Unicode validation, strict binding types and
  mutability, copies across reassignment, rejected operators/conversions, and
  byte-exact native UTF-8 output including empty strings, NULs, and percent signs.
  Character tests cover literal/escape decoding, string-quote separation, malformed
  and isolated-surrogate rejection, exact 32-bit storage, strict binding types,
  immutability, rejected operators/conversions, native copies and equality, and
  byte-exact UTF-8 output for literals and loaded bindings across every encoding
  boundary through U+10FFFF, including NUL, escapes, and emoji.
  Tests cover syntax, nested delimiter callbacks, semantic diagnostics, native
  arithmetic/assignment/copy behavior, inferred integer and float bindings,
  explicit floats with strict type and immutability checks, fixed inferred types
  on reassignment, mixed arithmetic, and CLI source/output paths. CLI tests
  compile and run all seven runnable examples from `examples` (`76.25`, `6689`,
  `true`, `true`, `4294967294`, `😀`, and `Unicode: é 中 😀`). New boolean
  checks cover syntax, strict types, arithmetic rejection, immutable bindings,
  separate byte allocations at 1/2/8/9/16/17 boolean bindings with interleaved
  integers and floats, native independent reassignment and copied values, and
  literal/assignment output. Negation checks cover prefix parsing, malformed
  operands, bool-only typing, nested negation, native literal and binding
  inversion, assignment, snapshots, and lexer separation of negation, equality, inequality, and assignment.
  Logical-operator tests cover malformed syntax, precedence and associativity,
  strict operand types (including skipped operands), generated branches that
  bypass the right operand's load, native truth tables using bindings, nested
  left/right logical expressions, negation, assignments, snapshots, and later
  numeric statements. Equality tests cover syntax, precedence, left associativity,
  boolean/numeric mixing rejection, fixed binding types, immutability, native truth
  tables for literals and bindings, negation, snapshots, assignments, and nested
  short-circuit expressions on either side of comparisons. Numeric equality
  tests cover exact integer values and boundaries, float values, signed zeros,
  infinities, NaN on either side, mixed promotion in both directions (including
  float32 rounding), arithmetic precedence, bool result storage and immutability,
  and short-circuit guards that skip integer division by zero. Ordering tests
  cover each operator for less/equal/greater cases, signed integer boundaries,
  float and mixed values, signed zeros, infinities, NaN on either side, float32
  rounding, precedence, malformed syntax, bool result storage and immutability,
  rejected bool operands and comparison chains, and guarded division by zero.
  Unsigned tests cover modifier and nested-width parsing, literal range checks,
  signedness and width preservation through inference/copy/reassignment, rejected
  implicit conversions, invalid modifiers, immutable bindings, unsigned LLVM
  instructions, native 32/64-bit boundary output, wrapping arithmetic, exact
  unsigned division/remainder/ordering/equality, positive unsigned float promotion
  and float32 rounding, short-circuit guards, independent storage alongside all
  existing primitive types, and signed 64-bit arithmetic/printing regressions.
  Cast tests cover syntax, agreed precedence and associativity, width/comparison
  disambiguation, exact binding rules, all 121 numeric source/target pairs, integer
  wrapping and source-signedness-aware widening, float32/64 precision and mixed
  promotion, float-to-integer boundary fractions and rounding, controlled errors
  for out-of-range values/NaN/infinities, bool separation and identity casts,
  runtime guards inside nested short circuits, copies, and generated conversion
  instructions and error paths. Float64 tests also cover arithmetic, negation,
  remainder, comparison, infinities, NaN, signed zero, storage, and printing.
  Numeric-width tests cover signed/unsigned 8/16-bit literal bounds, wrapping,
  storage, copies, printing, signed/unsigned arithmetic and comparisons, explicit
  conversions, strict binding types, keyword-derived spelling, width validation,
  half arithmetic and mixed promotion, and small float-to-integer range guards.
  Half conversion checks compare native results against JDK IEEE binary16
  conversions around normal/subnormal, tie, signed-zero, and overflow boundaries;
  double-to-half tests check direct rounding at ties and the overflow threshold.
  Default floating literal tests also cover exact decimal midpoint rounding,
  values immediately around overflow, and preservation of rounded values through
  inferred copies and native comparisons.
  ABI profile tests cover the architecture/OS/environment selection matrix,
  Windows versus Unix long widths, executable formats, LLVM triple normalization,
  versioned Darwin/macOS/MSVC spellings, unsupported targets and ABI variants, and
  the native emitted module's triple and C declarations. Native execution is
  verified on the current host; other platforms' profile selection is tested
  without claiming cross-platform executable verification.
  Toolchain tests use installed-directory fixtures and probe results for Windows,
  GNU/musl Linux mismatch detection, and macOS. They cover numeric version selection,
  architecture-specific runtime paths, static/dynamic choices, GUI CRT startup,
  SDK/sysroot selection, driver compatibility, explicit overrides, library ordering,
  paths containing spaces, CLI parsing, missing dependencies, and rejection before
  output directories are created. Native Windows tests additionally build and run
  programs with the dynamic CRT, GUI subsystem, and actual Microsoft LINK driver;
  the existing native suite exercises default static-CRT linking through LLD.
  CLI smoke checks also compiled `../../../../examples/Unsigned.co` with default and dynamic runtime
  options; both executables printed `true`.
  The test directory is now
  exempted from the broad test-directory ignore rule so tests can be tracked.
  `gradlew.bat :Compiler:run --args="Bindings.co" --offline --console=plain`
  also succeeded before examples were moved; that source now lives at
  `examples/Bindings.co`.

Repository-relative paths above identify current implementation locations; they
are not language specification boundaries.

## Open design questions

The following have not yet been settled. Ask about them when relevant, keeping
the user's preference for one question at a time:

- Array length/count API and iteration syntax; array equality semantics.
- Whether value-array `:=`/`final` should retain the current binding-only policy
  or also forbid element mutation. Current behavior is documented, not silently
  changed by the comparison with other languages.
- Type-narrowing behavior after reassignment and outside successful `if` branches.
- Function-block capture escape/lifetime rules beyond reading surrounding locals
  while their enclosing scope is active; whether blocks can be stored or escape
  as computations rather than being evaluated to their results.
- Assignment to a function-block binding: replacing its body, assigning a value,
  and whether `:=` may make the computation binding immutable.
- Default equality semantics for class instances: identity or field values.
- Whether there are restrictions on individual source-to-target transition paths.
  The confirmed phrase "permitted states" means class-supported states, not an
  approved transition-graph restriction mechanism.
- Whether state payloads can escape through stored or returned bindings, and
  their lifetime after the originating method finishes.
- The exact diagnostics/error model for invalid-state calls and deleted-object access.
- Deleting an already-invalid binding or intentionally null value.
- Character ordering, arithmetic, and conversions to/from numeric types.
- String indexing, content mutation, and dynamic/escaping string
  construction/lifetime rules beyond the current literal-backed bindings.
- Whether a value-producing `if` requires a final `else` in all cases.
- Branch result type compatibility/inference for value-producing `if` chains.
- How a value-producing branch selects its result when it contains multiple
  statements (for example, whether its last expression supplies the value).

This list is a set of unresolved areas, not an exhaustive specification checklist
or a set of required features.
