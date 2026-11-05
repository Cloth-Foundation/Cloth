# Simple character casing

Integrated 2026-11-04 in the existing separate `CharAttachments` provider.
The [confirmed contract](semantics.md#character-case-conversion-design-confirmed-2026-11-04)
owns the semantics; maintained example: [CharCase.co](../../../../examples/CharCase.co).

```cloth
fn main() {
    println('é'.toUpperCase()) // É
    println('Σ'.toLowerCase()) // σ
    println('ß'.toUpperCase()) // ß: no simple uppercase mapping
    println('İ'.toLowerCase()) // i: one scalar
}
```

Both methods take zero explicit arguments and return exactly `char`. Mappings are
pinned to Unicode 17.0.0 simple uppercase/lowercase fields; missing entries return
the receiver unchanged. This includes digits, emoji, NUL, unassigned scalars and
characters that only have expanding full mappings. Results are independent of
locale, neighboring characters, OS settings and JVM/native Unicode versions.
No string methods, expansions, final-sigma context, locale overrides, folding,
normalization or titlecase method are introduced. Simple conversion is not
guaranteed to round-trip or to produce a value satisfying a case predicate.

## Registration, values and diagnostics

[CharAttachments](../../../../Compiler/src/main/kotlin/cloth/builtin/attachments/CharAttachments.kt)
declares named `TO_UPPER_CASE` and `TO_LOWER_CASE` values using inherited
`valueMethod`, matching the existing string/integer/float/bool registration style.
Exact receiver lookup keeps these methods exclusive to chars. Results remain
chars through bindings, calls, arrays, ordered/deferred/recursive returns,
nullable presence proofs/Elvis fallback, chaining and compiler-data type queries.

Receivers evaluate once. Ordinary early returns and short circuits apply while
skipped source stays statically checked. Existing arity, named-argument,
unavailable-member, type-receiver and return-type diagnostics keep their original
spans and intrinsic errors through overload probes. No new error code/runtime
failure is required. CLI compilation stops at the first diagnostic before object
emission/linking; unmapped values do not fail.

Both registrations are read-only with independent scalar results. They allocate
no managed buffers and retain no receiver storage. Existing closed temporary
proofs reclaim fresh char-array producers after consumption, including repeated
conditions. Saved results and preexisting arrays survive cleanup. Unknown or
captured publication retains the ordinary conservative lifetime policy; see
[member storage cleanup](../../cloth-buffer-lifetimes/references/string-member-storage.md).

## Pinned data and emission

[UnicodeCharacterCaseMappings](../../../../Compiler/src/main/kotlin/cloth/data/UnicodeCharacterCaseMappings.kt)
loads the versioned, immutable
[mapping resource](../../../../Compiler/src/main/resources/cloth/unicode/17.0.0/character-case-mappings.txt).
It validates the version header, mapping kind, scalar bounds, nonidentity entries
and strictly increasing source keys. There are 1,505 uppercase and 1,488 lowercase
entries. The original classification tables and semantics are unchanged.

[BuiltinEmissionContext](../../../../Compiler/src/main/kotlin/cloth/emitter/types/BuiltinEmissionContext.kt)
routes calls to [EmitCharCase](../../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitCharCase.kt).
It installs module-local private constant i32 source/target arrays and internal
binary-search helpers on demand, reusing each helper within the module. Search
maintains a half-open interval, loads only a bounded midpoint and returns the
original scalar on an empty interval. No LLVM handle is cached in shared data or
the registry. Executables need no UCD files, JVM casing API, CRT casing functions
or locale library at runtime.

The [offline D generator](../../../../UnicodeGenerator/UnicodeGenerator.d)
authenticates all five inputs before writing; the
[classification record](character-attachments.md#reproducible-unicode-resources)
owns the shared command, remaining inputs and license instructions. Casing uses
fields 12/13 (zero-based) of the complete official
[UnicodeData.txt](https://www.unicode.org/Public/17.0.0/ucd/UnicodeData.txt), governed by
[UAX #44 for Unicode 17](https://www.unicode.org/reports/tr44/tr44-36.html).
Its SHA256 is `2e1efc1dcb59c575eedf5ccae60f95229f706ee6d031835247d843c11d96470c`.
Full/contextual/locale mappings from SpecialCasing are deliberately excluded.
The production resource retains source/hash metadata and
[Unicode License V3](../../../../Compiler/src/main/resources/cloth/unicode/17.0.0/LICENSE.txt).
Tests retain the complete compressed official input, independently checking its
decompressed hash. Ordinary builds and tests are offline.
All seven generated resources reproduced byte for byte in a second run; the five
original classification artifacts retain their previous hashes. Downloaded
scratch inputs were removed after this check.

## Verification

`CharCaseTest` independently parses the raw UnicodeData fixture and executes both
production mappings for every one of the 1,112,064 valid Unicode scalars on
Windows x64/MSVC and x86/MSVC. Its ordered sequential oracle differs from the
production binary search and never reads the generated production mappings as
expected values. It checks supplementary, unassigned/noncharacter and unmapped
scalars, every mapping boundary and all Unicode 17 additions.

Source tests cover exact char identity, receiver effects, snapshots, array
elements, nullable proofs/fallbacks, ordered/deferred/recursive results, early
returns/short circuits, type-query/chained calls, strict located diagnostics and
first-error CLI rejection. Explicit cases include sharp s, dotted/dotless I,
sigma/final sigma, ligatures, titlecase letters, emoji, digits and NUL; child
process language settings are varied without changing global test state.
The poisoned-free casing probe in `ArrayTemporaryCleanupTest` preserves saved
scalar results and arrays while bounding producer storage over 1,000 iterations
and while/do-while conditions, with final allocation balance.

The maintained example and a parameterized two-result fixture verify IR and
nonempty objects for all nine supported profiles. The example executes on both
Windows targets. Native Linux/macOS/ARM64 execution remains untested.

```powershell
.\gradlew.bat :Compiler:test --tests cloth.CharCaseTest --tests cloth.CharAttachmentsTest --tests cloth.CharactersTest --tests cloth.BoolAttachmentsTest --tests cloth.StringAttachmentsTest --tests cloth.ArrayTemporaryCleanupTest --tests 'cloth.Nullability*' --offline --console=plain --no-daemon
```

Passed 140 tests across 10 suites, zero failures/errors/skips. The full command
`.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon` passed
1,292 tests across 101 suites in 5m 37s, zero failures/errors/skips.
Changed skill metadata, relative links and whitespace checks pass directly.
The optional Python validator remains unavailable because PyYAML is missing;
Gradle used the existing external cache. The
[development milestone](../../cloth-language/references/development.md#simple-character-casing-2026-11-04)
retains this evidence and target limits.
