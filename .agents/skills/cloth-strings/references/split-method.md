# Literal delimiter string splitting

## Requested API and supported behavior (2026-11-06)

The user requested `split(char)` in `StringAttachments`, then added
`split(string)` for multi-character delimiters. Both return `string[]` through
the existing [ordinary member-call contract](member-calls.md).

```cloth
fn main() {
    def pieces = "one,two,,three,".split(',')
    println(pieces) // [one, two, , three, ]
    println("A😀B😀".split('😀')) // [A, B, ]
    println("a\0b".split('\0')) // [a, b]
    println("one::two::::three::".split("::")) // [one, two, , three, ]
    println("A😀B".split("")) // [, A, 😀, B, ]
}
```

The maintained [StringAttachments.co](../../../../examples/StringAttachments.co)
includes these calls. The receiver is `string`; the sole explicit argument must
be exact `char` or `string`, and the result is non-nullable `string[]`. No implicit
integer or character-array adaptation applies. Existing nullable presence proofs
or fallbacks are required before using nullable receivers or delimiters.

Implementation policies within the requested API:

- Match the exact Unicode scalar, case-sensitively, without normalization or
  grapheme grouping. Supplementary characters are single delimiters; combining
  marks are individual scalars. Embedded NUL is ordinary content or a delimiter.
- A nonempty string delimiter matches its entire literal Unicode sequence,
  left to right without overlap. Consume the whole match before looking again:
  `"aaaaa".split("aa")` produces `["", "", "a"]`. Regex punctuation is literal.
- Remove every delimiter and preserve pieces in source order, including leading,
  consecutive and trailing empty pieces. Empty input with a char or nonempty
  string delimiter produces `[""]`; a missing delimiter produces one piece
  containing the original contents. The result always has one more element than
  the number of consumed delimiter matches.
- An empty string delimiter matches every Unicode scalar boundary, including
  start/end, consistent with existing empty-search string methods. Preserve the
  leading/trailing empty pieces: `"A😀B".split("")` produces
  `["", "A", "😀", "B", ""]`, and `"".split("")` produces `["", ""]`.
  These are scalar boundaries, not UTF-8 bytes, UTF-16 units or graphemes.
- Evaluate the receiver once before the delimiter argument, also once. Captured
  rebinding during argument evaluation cannot change the received contents.
  Early returns and short circuits retain ordinary member-call behavior.
- Return independent array slots and string contents. Ordinary fixed receiving
  shapes, argument snapshots, recursive result copies, deferred reads and foreach
  snapshots apply. Piece lengths may differ because elements are strings, not
  rectangular character-array rows.

No regular expressions, multiple delimiters, optional limit, empty-piece removal
option, named arguments or mutation is introduced. Wrong arity uses C3023; an
argument of neither char nor string uses C3049, listing both accepted types;
named arguments use C3065.
Unknown/type receivers retain ordinary member diagnostics. Compilation still
stops at the first error, before output or linker work.

## Compiler ownership and invariants

- [StringAttachments](../../../../Compiler/src/main/kotlin/cloth/builtin/attachments/StringAttachments.kt)
  declares `SPLIT` through inherited `valueMethod`, with exact char and string
  `BuiltinOverload` entries and `CArray(ValueType.STRING)` results. Both opt into
  the read-only, independent-result storage contract; no definition stores LLVM
  state. `PrimitiveAttachment` publishes immutable overload lists under one name,
  rejects duplicate parameter signatures and prepends the hidden receiver to
  each signature. Single-signature `overload`/`parameters` accessors remain for
  monomorphic attachments; registry-wide consumers must inspect `overloads`.
- `ExpressionTyping.memberCallResults` narrows overload candidates by explicit
  arity and exact argument binding. It supplies context common to all remaining
  signatures, preserves intrinsic operand errors, and commits the selected body
  and conversions into `BoundBuiltinCall`. A never-returning argument may use a
  representative viable signature because it invokes no body; all written
  arguments are still checked. Split's disjoint char/string overloads introduce
  no contextual numeric overload policy. `NullFlowAnalysis` checks every return
  signature before proving the member result non-null.
- [BuiltinEmissionContext](../../../../Compiler/src/main/kotlin/cloth/emitter/types/BuiltinEmissionContext.kt)
  lends per-call services to
  [EmitStringSplit](../../../../Compiler/src/main/kotlin/cloth/emitter/text/EmitStringSplit.kt).
  Parsing and buffered result transport use the existing paths; primitive
  overload binding and pre-typing presence account for both signatures.
- The emitter first counts matches, checking count additions before allocating
  target-sized string descriptor storage with `EmitMemory.stack`. It allocates
  a separate checked byte buffer containing a private copy of the source, then
  walks those identical immutable contents to fill every descriptor. Each piece
  points only into that private buffer; no result borrows operand bytes. A valid
  allocation backs empty input and zero-length pieces, including a final empty
  descriptor at the byte-buffer end. Retained delimiter bytes in private storage
  are inaccessible through the piece descriptors.
- The char walk reuses `EmitStringAccess.decode`; the string walk uses bounded,
  length-aware `memcmp` through `EmitString.bytesEqual`, advancing mismatches by
  complete scalars with `nextScalarOffset`. Nonempty comparisons require enough
  remaining bytes before pointer arithmetic/reads. Matches advance by the full
  delimiter byte length. Empty matches record one boundary, then consume a live
  scalar or finish at the end, preventing infinite loops and end reads. Both
  measure/fill walks share these rules. Counts/offsets and allocation guards use
  the selected ABI, including 32-bit targets.
- Existing member-result freshness handles the string array. Adoption consumes
  freshness once; ordinary copies and outgoing arrays recursively copy each
  nested string before callee cleanup. Existing closed temporary proofs reclaim
  safe discarded/scalar consumers; captured publication retains conservative
  frame storage. The [buffer contract](../../cloth-buffer-lifetimes/SKILL.md) and
  [member cleanup](../../cloth-buffer-lifetimes/references/string-member-storage.md)
  remain authoritative; no broader loop cleanup rule is added.

## Verification

### String delimiter extension (2026-11-06)

The focused run passes 151 tests across seven suites in 36 seconds, with zero
failures, errors or skips:

```powershell
.\gradlew.bat :Compiler:test --tests cloth.StringSplitTest --tests cloth.StringAttachmentsTest --tests cloth.StringScalarMethodsTest --tests cloth.StringMemberStorageTest --tests cloth.ArrayTemporaryCleanupTest --tests cloth.CallResolutionTest --tests cloth.NullabilityTypingTest --offline --console=plain --no-daemon
```

`StringSplitTest` now includes 182 nonempty string-delimiter cases against an
independent literal-regex oracle, alongside the 204 char cases. A separate
code-point oracle covers empty delimiters, including empty input, supplementary
scalars, combining marks and NUL. Selected overloads retain exact argument types;
nullable proofs, captured writes, early returns and shape rules still apply.
The new poisoned-free probe uses dynamically returned source/delimiter strings,
ordered string-array results, empty-boundary results, copies and publication.
The maintained example and discarded-result stress test execute both overloads
and empty delimiters on Windows x64/x86. The shared parameterized result fixture
verifies IR and nonempty objects on all nine profiles. Linux/macOS/Windows ARM64
split execution remains untested.

The final isolated full compiler regression passes 1,470 tests across 132 suites
in 6m 44s, with zero failures, errors or skips, including all 13 split tests and
all seven enum-diagnostics tests:

```powershell
.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon
```

Five changed skill metadata blocks and 544 relative file links pass direct
validation. An initial broad run encountered five enum-diagnostics assertion
failures during concurrent changes to shared compiler classes; all 13 split
tests passed in that run. The final regression run additionally used a temporary
Gradle init script setting `:Compiler`'s build directory to
`tmp/split-string-build/Compiler`, keeping its classes and reports independent
of other workspace builds. Both validation scripts, generated classes and reports
were removed after recording the results.

### Initial character delimiter integration (2026-11-06)

The focused run passes 107 tests across five suites (10 split, 14 attachment,
13 scalar-method, four storage and 66 lifetime), with zero failures, errors or
skips, in 33 seconds.

```powershell
.\gradlew.bat :Compiler:test --tests cloth.StringSplitTest --tests cloth.StringAttachmentsTest --tests cloth.StringScalarMethodsTest --tests cloth.StringMemberStorageTest --tests cloth.ArrayTemporaryCleanupTest --offline --console=plain --no-daemon
```

The full compiler regression passes 1,459 tests across 131 suites, with zero
failures, errors or skips, in 6m 31s:

```powershell
.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon
```

[StringSplitTest](../../../../Compiler/src/test/kotlin/cloth/StringSplitTest.kt)
covers a 204-case literal-regex oracle with explicit retained trailing empties,
all UTF-8 widths, supplementary scalars, combining marks, NULs and punctuation.
Other cases cover exact signatures, nullable proofs, effects, early exits,
recursive and ordered results, copies, deferred reads, foreach, shapes and
first-error CLI rejection. The portability fixture verifies IR and objects for
all nine target profiles; the discarded-buffer stress fixture targets Windows
x64/x86. `StringAttachmentsTest` executes the maintained example on those two
targets. The poisoned-free split probe in `ArrayTemporaryCleanupTest` covers
nested string lifetimes, copies, captured publication and repeated consumers.
The matrix, effects, results, shape and poisoned-free probes execute on native
Windows x64/MSVC. The maintained example and the 300-iteration stress program
execute on both Windows x64/MSVC and x86/MSVC. The parameterized result fixture
verifies LLVM IR and emits nonempty objects for Windows x86/x64/ARM64, Linux
x64/ARM64 GNU and musl, and macOS x64/ARM64. This does not establish native
split execution on Linux, macOS or Windows ARM64.

Both runs used a temporary Gradle init script to keep their test reports
separate from another build in the shared workspace; the script and reports were
removed after recording the results. Skill metadata and 479
relative file links were checked directly. The optional `quick_validate.py`
cannot start because the available Python lacks PyYAML; no packages were added.
