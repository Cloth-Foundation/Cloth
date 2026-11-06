# isize implementation plan

Status: all three steps implemented and verified (2026-11-01). The full
regression suite passes. Earlier CLI-default assertion failures are recorded
under their historical milestones below and resolved in step 3.

## Agreed direction

- The user requested a signed pointer-sized primitive `isize`, keeping `usize`
  unsigned and pointer-sized. Width comes from the compilation target, never
  the compiler host. Existing supported profiles use 32 or 64 bits; both types
  use the same `TargetLayout.sizeBits` source, which matches their pointer width.
- Preserve distinct source identity for isize, usize and fixed-width int even
  when LLVM representations match. Explicit int widths and plain int's 32-bit
  default retain their existing meanings.
- Follow existing signed integer numeric rules: wrapping arithmetic, signed
  comparisons/division/remainder, controlled division failures, low-bit integer
  narrowing and source-signedness-aware widening, and checked float-to-integer
  casts with truncation toward zero. Do not add implicit typed conversions.
- Metadata text is `isize`, including array forms such as `isize[3]`; width is
  available through `::type::width`. Min/max return exact isize. Measurements
  (`size`, `rank`, `extent`, `width`) remain exact usize; predicates remain bool.
- The user revoked the earlier usize-main decision. Intended entry returns are
  void, signed fixed-width int, or isize. Unsigned returns, including usize, are
  rejected by the entry policy implemented in step 2. Keep existing
  native exit-bit normalization and signed PEC formatting.
- Reuse current arrays/counts, checked indexing, allocation layouts and ABI
  profiles. Negative indices remain invalid. This introduces no pointer syntax
  or new pointer-arithmetic feature, and no new target/toolchain support.

## Steps and completion checks

### 1. Primitive and semantic contract

- [x] Add keyword/type syntax and target-based resolution using a distinct
  `CISize` descriptor over `CInteger`.
- [x] Preserve signed bounds, contextual literals, strict type identity,
  explicit conversion typing, array element typing and overload selection.
- [x] Preserve canonical metadata text, exact bound providers, unsigned
  measurements and target isolation for emitted provider constants.
- [x] Complete focused verification and record its result below.

Implementation record:

- `token/Keywords.kt`, `parser/subs/TypeParser.kt` and
  `semantic/declarations/TypeResolver.kt` recognize isize annotations, casts, type receivers,
  function parameters/results and array element types. Explicit isize width
  suffixes and unsigned modifiers are rejected by existing diagnostics.
- `data/CISize.kt` supplies signedness and canonical spelling. Shared CInteger
  behavior supplies bounds, wrapping, providers and LLVM representation.
- `data/ProviderResult.kt` applies its existing target-width invariant to isize
  constants as well as usize results.
- `ISizeTest` covers 64/32/64 reuse of one parsed source, signed literal limits,
  exact identity and overload selection, explicit cast typing, static division
  failures, provider contracts and verified LLVM metadata/signatures on both
  Windows widths. These checks do not establish full native numeric integration.

Verification: `gradlew.bat :Compiler:test --tests cloth.ISizeTest --tests
cloth.USizeTest --tests cloth.SharedIntegersTest --offline --console=plain`
passed 20 tests across 3 suites (7 new isize tests), zero failures/errors/skips.
The full `gradlew.bat :Compiler:test --offline --console=plain` run completed 633
tests across 47 suites: 632 passed, one failed, zero errors/skips. The failure is
`CompilerSettingsTest.existing positional CLI and default source remain available`:
CompilerSettings defaults to examples/Fib.co, while its test expects
examples/Builtins.co. Both sides of that mismatch are already present in commit
cc32806; neither file was changed for isize. This prevents claiming a green full
suite and remains separate from the passing primitive checks.

Whitespace and skill frontmatter/placeholder checks passed; 43 local Markdown
links resolve across the five updated skill/reference files. The standard skill
validator could not run because the available Python environments lack PyYAML.
No Python packages were installed.

### 2. Integration audit and main correction

- [x] Audit signed arithmetic, comparisons, division/remainder overflow, casts,
  formatting, functions, arrays, checked indices and target-specific providers.
- [x] Replace CUSize with CISize in the executable entry policy and diagnostics;
  replace the previous usize-main acceptance regressions with rejection checks.
  Preserve signed fixed-int/void entry support and both argument forms.
- [x] Verify native exit normalization and signed PEC output for isize main.

Implementation and audit record:

- `semantic/ProgramAnalyzer.kt` now accepts CISize, void and signed fixed-width
  CInt returns. CUSize and every fixed unsigned width are rejected for executable
  main, for both parameter forms and both native widths. Library functions named
  main continue to follow ordinary function rules.
- C3037 in `diagnostics/EntryErrors.kt` now names isize in its message and help.
  USizeTest and ProgramEntryTest replace the superseded usize-main acceptance
  expectations; CLI checks verify one located entry error before output/linking.
- Existing signed paths require no backend rewrite. `EmitExpression` chooses
  signed comparisons from descriptor signedness and plain wrapping arithmetic;
  `EmitIntegerDivision` checks zero and minimum/-1 before sdiv/srem; `EmitPower`
  checks signed negative exponents. Static division checks already use CInteger.
- `EmitCast` uses CInteger bounds, source-signedness-aware extension, truncation,
  checked fptosi and signed integer-to-float conversion. NativeCABI printing and
  EmitString formatting accept CInteger and choose signed formats/promotions.
- `EmitFunctions`/`EmitBindings` use the descriptor's native-width LLVM type;
  arrays reuse checked signed indexing before native addressing and retain usize
  counts. Metadata and provider recipes retain the target/identity checks from
  step 1. No array representation or measurement-result type was changed.
- `EmitEntryPoint` already preserves low native C int bits when narrowing and
  sign-extends narrow signed results. Signed PEC `%d` formatting remains correct.
  ProgramEntryTest runs both main argument forms and expression/brace returns
  with zero, negative values, an explicitly converted usize measurement,
  isize min/max and positive/negative wide values truncated to native exit bits.
- Five ISizeBackendTest cases exercise verified IR for all seven supported
  triples, native Windows x64/x86 arithmetic/casts/formatting, function and array
  transport, metadata identity and unsigned measurements, controlled runtime
  division/remainder/cast/exponent/index failures, and CLI rejection of usize main.
- README entry guidance matches the policy. At step 2 user-edited Builtins.co
  and Fib.co were preserved with usize main and received C3037. Step 3 migrates
  only their main return annotations to isize; their bodies are preserved.

Verification: `gradlew.bat :Compiler:test --tests cloth.ISizeTest --tests
cloth.ISizeBackendTest --tests cloth.ProgramEntryTest --tests cloth.USizeTest
--offline --console=plain` passed all 50 tests across 4 suites, zero
failures/errors/skips. Foreign target checks verify IR only; native execution
was on Windows x64 and x86. The full `gradlew.bat :Compiler:test --offline
--console=plain` run completed 638 tests across 48 suites: 637 passed, one failed,
zero errors/skips. The sole failure is the same existing CompilerSettingsTest
Fib.co/Builtins.co default-path mismatch documented in step 1 and reconfirmed
against HEAD. Those settings/test files remain unchanged. Whitespace and manual
skill frontmatter/placeholder checks passed; 44 local links resolve across the
five updated skill/reference files. The standard skill validator still requires
the unavailable PyYAML dependency.

### 3. Cross-width validation and final records

- [x] Validate native Windows x64 and x86 execution at signed boundaries,
  wrapping/casts, exact type identity, provider chains, negative indices and
  accepted/rejected main contracts. Preserve foreign-target IR coverage.
- [x] Run the full regression suite, preserving first-error compilation.
- [x] Reconcile skill references and public documentation with verified support;
  mark completed milestones without treating remaining plans as implementation.

Implementation record:

- Four ISizeIntegrationTest cases cover target reuse, first-error compilation,
  runtime effect order and CLI execution of documented examples. One parsed
  program runs on Windows x64, x86 and x64 again; PE machine headers and printed
  width verify the selected target. Cases from steps 1/2 continue to cover signed
  boundaries, all supported target IR, casts/guards and entry normalization.
- The shared-AST program combines exact overload/provider identities, unsigned
  measurements, deferred blocks, declaration/assignment lists, stored block
  results, short-circuit skipping and outer-binding updates. It also checks
  once-only deferred nested indices, independent nested array copies, returned
  text lifetime, zero-length arrays with maximum unsigned inner extents, and
  negative 64-bit conversions with explicit fixed widths unchanged.
- CLI fixtures verify one first detected diagnostic, including the existing
  name-resolution-before-type-check ordering, before output/linking on both
  widths. Runtime fixtures verify ordered once-only operands and immediate
  controlled division-overflow, negative-index and float-cast failures without
  later effects or normal PEC output.
- New `examples/ISize.co` demonstrates signed differences/indices, unsigned
  measurements, explicit conversion, wrapping, metadata and signed main.
  Builtins.co and Fib.co only change their main return annotations to isize;
  CLI/native tests preserve their existing output and exit behavior on both
  widths. The default Fib.co program is usable under the new entry policy.
- CompilerSettingsTest's stale default-path expectation now matches the existing
  examples/Fib.co default. CompilerSettings and CLI behavior were not changed.
  This resolves the sole pre-existing full-suite failure from steps 1 and 2.
- README explains both target-sized primitives and the unchanged measurement
  contract. Compiler-data-access notes distinguish the isize follow-up from
  the historical usize-only migration.

Verification: `gradlew.bat :Compiler:test --tests cloth.ISizeIntegrationTest
--tests cloth.ISizeTest --tests cloth.ISizeBackendTest --tests
cloth.ProgramEntryTest --tests cloth.CompilerSettingsTest --offline
--console=plain` passed 47 tests across 5 suites, zero failures/errors/skips.
The full `gradlew.bat :Compiler:test --offline --console=plain` run passed all
642 tests across 49 suites, zero failures/errors/skips. Whitespace and manual
skill frontmatter/placeholder checks passed; 54 local links resolve across the
five skill/reference files plus README and compiler-data-access notes. Existing
example bodies were compared with HEAD to verify that only their main return
annotations changed. The standard skill validator remains unavailable without
PyYAML; no Python packages were installed.

All three stages are complete. Execution evidence is Windows x64 and Windows
x86/MSVC with the existing SSE2 baseline; the six supported 64-bit platform/
architecture combinations retain verified IR coverage. This does not establish
native Linux/macOS execution or support for additional 32-bit targets.

No second array-count migration or fresh ABI implementation is required.
