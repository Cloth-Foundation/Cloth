# Full Unicode string casing

## Requested API and implemented behavior (2026-11-07)

The user requested implementing string `toUpperCase()` and `toLowerCase()`.
Both methods take zero explicit arguments and return an independent `string`.
The implementation policy is Unicode 17.0.0 default full casing, independent of
the host locale, OS or JVM Unicode version. The existing simple `char` methods
and their mapping data retain their behavior.

```cloth
fn main() {
    println("Straße".toUpperCase()) // STRASSE
    println("İ".toLowerCase())     // i followed by U+0307
    println("ΟΣ".toLowerCase())    // ος
    println("ΟΣΑ".toLowerCase())   // οσα
    println('ß'.toUpperCase())     // ß: the char method still returns one scalar
}
```

Maintained example: [StringCase.co](../../../../examples/StringCase.co).

- Use the full mappings from UnicodeData plus the unconditional SpecialCasing
  overrides. A mapping may expand to multiple scalars: sharp s and ligatures can
  produce ASCII sequences, while dotted capital I lowercases to two scalars.
  Unmapped scalars, emoji, digits, embedded NULs and empty contents are preserved.
- Lowercase U+03A3 to final sigma only when the original text has a cased
  predecessor and no cased successor, ignoring intervening Case_Ignorable
  scalars. Consume those ignorable sequences before testing Cased; the properties
  overlap (notably U+0345). Context is determined from original input scalars,
  never from previously converted output.
- Evaluate the receiver once and keep its received contents stable throughout
  conversion. Ordinary nullable presence proofs/fallbacks, skipped-code checks,
  early returns and intrinsic diagnostics apply. Results retain ordinary string
  value independence through stored copies, arrays and outgoing results.
- No locale arguments, locale tailoring, titlecase, case folding, normalization,
  automatic case-insensitive comparison or string mutation is introduced. In
  particular, Unicode scalar counts and byte lengths may change. Casing need
  not round-trip or preserve a normalization form.

The external authority is [Unicode 17 default casing, section 3.13](https://www.unicode.org/versions/Unicode17.0.0/core-spec/chapter-3/),
including its possessive final-sigma context rules. Full mappings come from
[SpecialCasing.txt](https://www.unicode.org/Public/17.0.0/ucd/SpecialCasing.txt)
and the existing pinned UnicodeData input. Locale-specific conditional records
are excluded; the language-neutral Final_Sigma record is implemented explicitly.

## Compiler ownership and storage

- [StringAttachments](../../../../Compiler/src/main/kotlin/cloth/builtin/attachments/StringAttachments.kt)
  declares named `TO_UPPER_CASE`/`TO_LOWER_CASE` registrations with exact string
  receivers/results through inherited `valueMethod`. The read-only,
  independent-result contract permits ordinary operand copy elision and closed
  temporary reclamation. The char registrations remain separate and return char.
- [BuiltinEmissionContext](../../../../Compiler/src/main/kotlin/cloth/emitter/types/BuiltinEmissionContext.kt)
  lends per-call services to [EmitStringCase](../../../../Compiler/src/main/kotlin/cloth/emitter/text/EmitStringCase.kt).
  It measures converted UTF-8 bytes with checked target-sized additions, allocates
  one exact output buffer (valid storage even for empty output), then fills it
  using the identical protected original input. Every store fits the measured
  result; unused encoding bytes are never written past its end.
- [EmitString.scalarEncoding](../../../../Compiler/src/main/kotlin/cloth/emitter/text/EmitString.kt)
  shares the existing UTF-8 encoding calculation with character formatting,
  without allocating per-scalar scratch buffers. The formatting path keeps its
  original four-byte storage and descriptor semantics. UTF-8 decoding uses
  `EmitStringAccess` only at proven live scalar starts.
- [EmitStringCaseData](../../../../Compiler/src/main/kotlin/cloth/emitter/text/EmitStringCaseData.kt)
  installs private constant tables and internal binary-search helpers on demand
  per LLVM module. Full mapping records hold one to three scalars; missing entries
  return the input scalar. Separate Cased/Case_Ignorable helpers support context.
  Lookups load only bounded rows and retain no process-global LLVM handles.
- Lowercasing tracks the last original non-ignorable scalar's Cased bit. Only
  candidate sigmas inspect following original scalars, stopping at the first
  non-ignorable scalar or byte end. No context scan reads beyond the input.
- Independent returned text uses ordinary recursive caller-owned copies before
  callee cleanup. Known scalar/discarded consumers reuse the closed arena/stack
  proof; captured publication keeps conservative frame lifetime. No broader
  arbitrary-loop or ownership guarantee is added. See [buffer lifetimes](../../cloth-buffer-lifetimes/SKILL.md).

Arity/named argument/nullable receiver/type receiver/result diagnostics retain
their existing codes and spans. Compilation stops at the first detected error;
allocation guards keep the original call location and stop without normal PEC.

## Pinned data and the existing D generator

[UnicodeGenerator.d](../../../../UnicodeGenerator/UnicodeGenerator.d) extends the
existing generation pipeline, authenticating six input hashes before any output
write. The new input is SpecialCasing.txt, SHA256
`efc25faf19de21b92c1194c111c932e03d2a5eaf18194e33f1156e96de4c9588`.
The other inputs and the command are listed in the
[shared generation record](character-attachments.md#reproducible-unicode-resources).
No replacement generator or new generator language is used.

The additions are [full mappings](../../../../Compiler/src/main/resources/cloth/unicode/17.0.0/string-case-mappings.txt),
[context ranges](../../../../Compiler/src/main/resources/cloth/unicode/17.0.0/string-case-ranges.txt)
and the complete compressed official SpecialCasing test fixture. The resources
retain source/hash metadata and the existing Unicode License V3. Runtime loading
through [UnicodeStringCaseMappings](../../../../Compiler/src/main/kotlin/cloth/data/UnicodeStringCaseMappings.kt)
validates version, scalar bounds, sorted uniqueness, expansion bounds and merged
ranges; published data and nested target sequences are immutable.

Unicode 17 has 1,580 nonidentity full uppercase mappings, 1,488 lowercase mappings,
158 Cased ranges (4,632 scalars) and 464 Case_Ignorable ranges (2,794 scalars).
The maximum three-scalar record is an internal bound verified against this pinned
input, not a general future-Unicode language limit.

DMD 2.113.0 on Windows x64 regenerated all ten artifacts identically in two runs.
All seven preexisting character/classification/license/oracle artifacts retained
their exact hashes. Ordinary compiler builds and tests remain offline; emitted
programs need neither UCD files nor host casing libraries.

## Verification

The focused run passes 218 tests across 13 suites in 58 seconds, with zero
failures, errors or skips:

```powershell
.\gradlew.bat :Compiler:test --tests 'cloth.String*Test' --tests cloth.CharCaseTest --tests cloth.CharAttachmentsTest --tests cloth.ArrayTemporaryCleanupTest --offline --console=plain --no-daemon
```

[StringCaseTest](../../../../Compiler/src/test/kotlin/cloth/StringCaseTest.kt)
independently authenticates/parses complete official fixtures. Native helper
oracles check both full mappings and both context predicates for every one of
the 1,112,064 valid Unicode scalars on Windows x64/MSVC and x86/MSVC. Source calls
check every full mapping through actual UTF-8 conversion against independent
expected strings. The 135 context cases include overlapping Cased/Case_Ignorable
properties, punctuation, combining marks and 4,096-scalar ignorable sequences.
Other cases cover expansion/shrinkage, NULs, empty strings, exact types, child
locales, once-only effects, nullable proofs, early exits, recursive/ordered/deferred
results, copies, arrays, chaining and first-error CLI rejection.

The maintained example and discarded-result stress program execute on Windows
x64/x86. A shared parameterized two-result fixture verifies IR and nonempty
objects on all nine target profiles; native Linux/macOS/Windows ARM64 string
casing execution remains untested. The new poisoned-free probe in
`ArrayTemporaryCleanupTest` checks result lifetimes, arrays, captured publication,
saved values, repeated scalar consumers and while/do-while conditions. The
existing no-operand-copy allocation probe also covers both new string methods.
The final full compiler regression passes 1,557 tests across 139 suites in
6m 55s, with zero failures, errors or skips:

```powershell
.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon
```

Validation used a temporary Gradle init script for compiler output under
`tmp/string-case-final-build/Compiler`, plus
`--project-cache-dir tmp/string-case-gradle-cache`, keeping classes and execution
history independent of concurrent workspace builds. An initial 1,557-test full
run retained passing casing/character/lifetime suites but encountered 17
missing-class failures after its temporary compiler class directory was removed
during the run. The final fully isolated run resolves those failures without
unrelated compiler changes. Validation scripts, generated executables/classes,
caches, downloaded/unpacked inputs and reproduction scratch files were removed
after recording results. Three changed skill metadata blocks and 571 relative
file links passed direct validation; whitespace checks passed as well.
