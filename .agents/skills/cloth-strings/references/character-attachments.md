# Character attachments

Integrated 2026-11-04. The [confirmed contract](semantics.md#character-attachments-design-confirmed-2026-11-04)
owns the seven-method batch in a separate `CharAttachments`, matching existing
primitive registration style. Maintained example:
[CharAttachments.co](../../../../examples/CharAttachments.co).
The [character casing extension](character-case.md) adds `toUpperCase()` and
`toLowerCase()` to the same provider, bringing its supported method count to nine.

| Method | Result | Pinned Unicode 17.0.0 definition |
| --- | --- | --- |
| `isWhitespace()` | bool | White_Space, identical to string trimming |
| `isLetter()` | bool | General_Category L: Lu, Ll, Lt, Lm, Lo |
| `isDigit()` | bool | General_Category Nd, decimal digits |
| `isLetterOrDigit()` | bool | Letter or decimal digit |
| `isUpperCase()` | bool | Binary Uppercase property |
| `isLowerCase()` | bool | Binary Lowercase property |
| `toInt()` | signed int32 | Unicode code point, not a parsed digit value |

All calls have no explicit arguments. Classification works on one Unicode scalar,
including supplementary characters; surrogate code points are not char values.
Combining marks are not Letters, but a combining mark can have the Lowercase
property. Circled capital A is uppercase without being a Letter. Titlecase Letters
may satisfy neither case predicate. Superscript digits are not decimal digits.
`'7'.toInt()` is 55; NUL is 0; U+10FFFF is 1114111, all representable in signed
int32. Ordinary character arithmetic, ordering and numeric casts remain deferred;
simple case conversion is owned by the linked extension. No normalization, locale selection or numeric parsing
is introduced.

## Registration, values and diagnostics

- [CharAttachments](../../../../Compiler/src/main/kotlin/cloth/builtin/attachments/CharAttachments.kt)
  declares each method in a named value through inherited `valueMethod`. The
  predicate helper matches the string provider style; `CharPredicate` identifies
  backend operations without a second method-name table. `PrimitiveAttachments.CHAR`
  is target-independent and enters exact receiver lookup and the read-only catalog.
- [BuiltinEmissionContext](../../../../Compiler/src/main/kotlin/cloth/emitter/types/BuiltinEmissionContext.kt)
  borrows the classification emitter per call; char-to-int returns the validated
  i32 scalar directly while semantic typing assigns the distinct int32 result.
  Do not reinterpret the receiving context as an implicit widening or make this
  method authorize ordinary `char -> int` casts.
- Receiver values evaluate once, before consumption. Captured/deferred reads,
  function results, array elements, nullable payload proofs/fallbacks and ordered
  outputs use ordinary value paths. Short circuits/returns skip execution while
  written source remains statically checked. Shared `toInt` names on bool/char
  retain non-continuing receiver behavior through the existing catalog lookup.
- No new diagnostic code or runtime error is required. Existing C3023 arity,
  C3065 named-argument, C3071 unavailable-member and exact-result/type errors
  retain original spans and intrinsic errors during overload probes. First-error
  CLI failure happens before object/linker work; no recovery is added.
- All methods opt into read-only, independent-result storage. They allocate no
  managed buffers and do not retain/publish operands. Existing closed proofs
  reclaim temporary char-array producers after scalar/discarded consumers and
  repeated conditions. Unknown/captured publication remains conservative.

## Data and emission

[UnicodeCharacterProperties](../../../../Compiler/src/main/kotlin/cloth/data/UnicodeCharacterProperties.kt)
loads the immutable, validated
[range resource](../../../../Compiler/src/main/resources/cloth/unicode/17.0.0/character-ranges.txt).
Properties have 684 Letter ranges / 145672 scalars, 72 Nd ranges / 770 scalars,
660 Uppercase ranges / 2006 scalars and 677 Lowercase ranges / 2595 scalars.
Version, source SHA256 values and license metadata are retained in the resource.
Whitespace reuses `UnicodeWhiteSpace.ranges`, the existing 25-scalar contract.
Never classify source values with JVM/C Unicode/locale predicates.

[EmitCharMember](../../../../Compiler/src/main/kotlin/cloth/emitter/types/primitives/EmitCharMember.kt)
installs module-local immutable i32 endpoint arrays and internal binary-search
helpers on demand. No LLVM handle enters a registry/data singleton. Each helper
maintains a half-open index interval, loads only an in-range midpoint, and returns
membership through unsigned scalar comparisons. Existing helpers/tables are reused
within a module. Letter-or-digit ORs the two scalar predicates without another data
table. Classification has logarithmic range lookup and no native classification
library dependency; emitted executables do not read UCD files at runtime.

### Reproducible Unicode resources

[The D generator](../../../../UnicodeGenerator/UnicodeGenerator.d)
validates all five local input hashes before writing anything. It coalesces sorted
Letter/Nd/general-case ranges into the production text table, preserves the
[Unicode License V3](../../../../Compiler/src/main/resources/cloth/unicode/17.0.0/LICENSE.txt),
and writes compressed, complete official input files for the independent test
oracle. The casing extension also emits a simple-mapping table and compressed
UnicodeData fixture. Production needs only the small text tables/license; tests use full input
fixtures with checked decompressed hashes.

Inputs:

- [DerivedGeneralCategory.txt](https://www.unicode.org/Public/17.0.0/ucd/extracted/DerivedGeneralCategory.txt)
- [DerivedCoreProperties.txt](https://www.unicode.org/Public/17.0.0/ucd/DerivedCoreProperties.txt)
- [PropList.txt](https://www.unicode.org/Public/17.0.0/ucd/PropList.txt)
- [UnicodeData.txt](https://www.unicode.org/Public/17.0.0/ucd/UnicodeData.txt)
- [LICENSE.txt](https://www.unicode.org/license.txt), exact snapshot hash pinned in the generator

Run from the repository root with DMD on `PATH`.

```powershell
dmd -run UnicodeGenerator/UnicodeGenerator.d "path/to/unicode-inputs"
```

The generator performs no downloads. Normal compilation/tests require no network.
The D port reproduced all seven checked-in artifacts byte for byte with DMD
2.113.0 on Windows x64. Updating
Unicode semantics requires explicit review and new inputs/hashes/evidence, not
following the latest host runtime or upstream release automatically.

## Verification

`CharAttachmentsTest` independently parses the complete pinned UCD inputs into
expected property masks, checks input SHA256 values, then executes generated
classifiers against every one of the 1,112,064 valid Unicode scalars on Windows
x64/MSVC and x86/MSVC. This covers assigned/unassigned/noncharacter scalars,
Unicode 17 additions, all range boundaries, supplementary letters/digits, whitespace
and differing case/Letter membership. It does not use production ranges or JVM
Unicode predicates as the expected oracle.

Source tests cover every method, signed int32 identity through U+10FFFF, once-only
receiver effects, saved values, nullable proofs/fallbacks, ordered returns,
recursion/deferred reads, array elements and chained bool/integer methods. Existing
character arithmetic/cast restrictions remain enforced. Tests check skipped code,
early-return receivers, original labels, intrinsic outer-overload errors and CLI
first-error rejection before output/linker work.

Maintained example and parameterized all-method fixtures verify IR and nonempty
objects across all nine supported profiles. Native Linux/macOS/ARM64 execution
remains untested for this feature. The poisoned-free character consumer probe in
`ArrayTemporaryCleanupTest` verifies bounded producer allocations for 1,000 loop
iterations and while/do-while conditions, preexisting arrays and final balance.

```powershell
.\gradlew.bat :Compiler:test --tests cloth.CharAttachmentsTest --tests cloth.BoolAttachmentsTest --tests cloth.CharactersTest --tests cloth.StringAttachmentsTest --tests cloth.StringWhitespaceTest --tests cloth.StringMemberStorageTest --tests cloth.ArrayTemporaryCleanupTest --tests 'cloth.Nullability*' --offline --console=plain --no-daemon
```

Passed 147 tests across 11 suites in 36 seconds, zero failures/errors/skips.
The full command `.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon`
passed 1,283 tests across 100 suites in 5m 30s, zero failures/errors/skips.
The [development record](../../cloth-language/references/development.md#character-attachments-2026-11-04)
retains the milestone and target limits. Direct skill metadata, relative links and
whitespace checks pass. The optional Python validator remains unavailable because
PyYAML is missing. Gradle uses the existing external cache.
