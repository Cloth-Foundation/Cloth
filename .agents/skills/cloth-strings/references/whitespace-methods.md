# Unicode whitespace string methods

## Accepted contract (2026-11-04)

The user requested `trim`, `trimStart`, `trimEnd` and `isBlank`, and explicitly
selected the Unicode `White_Space` property with a pinned version for consistent
behavior on every target. This implementation pins Unicode **17.0.0** using
[PropList.txt](https://www.unicode.org/Public/17.0.0/ucd/PropList.txt).
Version pinning is a language contract; an update needs deliberate review rather
than inheriting a newer JVM, C runtime, platform locale or Unicode release.

| Call | Result | Behavior |
| --- | --- | --- |
| `text.trim()` | `string` | Remove leading and trailing White_Space scalars. |
| `text.trimStart()` | `string` | Remove only leading White_Space scalars. |
| `text.trimEnd()` | `string` | Remove only trailing White_Space scalars. |
| `text.isBlank()` | `bool` | True for empty text or text consisting entirely of White_Space scalars. |

The three trim methods return independent string values, including unchanged and
empty contents. They leave the receiver untouched and preserve internal whitespace,
exact Unicode sequences and embedded NULs. There is no normalization, case folding
or line-ending conversion. A combining mark and a supplementary scalar remain
complete scalars; trimming never operates on individual UTF-8 bytes.

The pinned property contains 25 scalars, expressed as these ten closed ranges:
U+0009–U+000D, U+0020, U+0085, U+00A0, U+1680, U+2000–U+200A,
U+2028–U+2029, U+202F, U+205F and U+3000. NUL, U+001C–U+001F,
U+180E, U+200B ZERO WIDTH SPACE, U+2060 WORD JOINER and U+FEFF BOM
are not White_Space. They remain content and make `isBlank()` false.

Each method has one zero-argument positional signature. No custom trim characters,
predicates, locale flags, named arguments or `trimLeft`/`trimRight` aliases are
introduced. Nullable receivers need an existing presence proof or explicit
fallback. Results are non-nullable and participate in ordinary result-presence
facts. The receiver evaluates once; short circuits and early returns/jumps skip
the method normally. All source remains statically checked and errors stop at
the first detected diagnostic before output/linker work.

```cloth
fn main() {
    def text := "　Cloth 😀 " // U+3000 at start; U+00A0 at end.
    println(text.trim())
    println(" \t\n".isBlank()) // true
    println("".isBlank())      // true
    println("\0".isBlank())    // false
}
```

Maintained example: [StringWhitespace.co](../../../../examples/StringWhitespace.co).
For receiver-call rules use [member calls](member-calls.md); for UTF-8 scalar
boundaries and independent results use [scalar methods](scalar-methods.md).

## Compiler ownership and invariants

- [UnicodeWhiteSpace](../../../../Compiler/src/main/kotlin/cloth/data/UnicodeWhiteSpace.kt)
  owns the pinned version and immutable closed ranges. It is shared compiler data,
  not a host whitespace predicate. No locale-dependent C classification or JVM
  `Character.isWhitespace`/`isSpaceChar` is used for Cloth behavior.
- `StringAttachments` declares the four typed registrations. `BuiltinEmissionContext`
  borrows [EmitStringWhitespace](../../../../Compiler/src/main/kotlin/cloth/emitter/types/primitives/EmitStringWhitespace.kt)
  for this module/call/source location. Definitions remain reusable without LLVM
  handles or per-compilation mutable state.
- `EmitStringAccess.decode` supplies the existing full-scalar UTF-8 decoder with
  an explicit live-scalar-start precondition. The leading scan checks byte bounds
  before decoding, advances over whitespace and stops at content or byte end.
  Empty strings execute no byte loads in the scan. `isBlank` compares the stopping
  offset with byte length and creates no result/scratch buffer; normal receiver
  values remain protected through the storage contract and existing producer frames.
- `trimStart` uses that leading offset and the original byte end. `trimEnd` scans
  forward from zero, remembering the end of the last content scalar. `trim` starts
  that forward scan at the leading offset. All-whitespace/empty inputs keep the
  ending boundary equal to the starting boundary. Internal whitespace remains
  inside the copied interval. No speculative reverse UTF-8 reads occur.
- Loop phis use the actual post-decoder predecessor blocks. Classification emits
  fixed 32-bit scalar comparisons against the pinned ranges. Byte offsets, lengths,
  copied sizes and checked allocations use the selected target count width.
  The boundaries prove `start <= end <= source bytes` before subtraction/GEP.
- The result interval is copied once into independent checked stack storage;
  empty results allocate a valid byte while retaining zero length. Existing
  snapshots protect receiver storage until consumption. Outgoing function,
  deferred and ordered results copy recursively into caller-owned storage before
  frame release. The later
  [storage cleanup](../../cloth-buffer-lifetimes/references/string-member-storage.md)
  elides immutable operand byte copies and admits proven member regions to arena
  and stack reclamation. Unknown effects remain conservative; no borrowed view,
  GC or arbitrary-loop bounded-storage guarantee is added.

## Verification

Focused command:

```powershell
.\gradlew.bat :Compiler:test --tests cloth.StringWhitespaceTest --tests cloth.StringAttachmentsTest --tests cloth.StringScalarMethodsTest --tests cloth.StringEditingTest --tests cloth.ArrayTemporaryCleanupTest --offline --console=plain --no-daemon
```

Passes 102 tests across five suites in 27 seconds, zero failures/errors/skips:
12 whitespace, 14 attachment, 13 scalar-method, 12 editing and 51 lifetime tests.
The whitespace oracle lists all 25 expected Unicode property scalars independently
of production ranges and host classification APIs. Native cases cover every
property scalar, adjacent code points, excluded lookalikes, all-whitespace/empty
strings, NULs, internal spaces, combining marks and supplementary/maximal scalars.
Other checks verify once-only receivers, short circuits, nullable proofs/result
presence, legacy snippets, early returns/loop jumps, exact signature errors,
intrinsic errors inside outer overload probes, recursion, ordered returns,
arrays/chaining and 10,000-scalar whitespace runs. The CLI keeps first-error C3023
before object/linker work.

The poisoned-free whitespace-result probe verifies saved trims, released source
frames, independent ordered outputs and deferred captured publication. The
receiving frame exits with zero outstanding allocations. The maintained example
executes with exact output and zero status on Windows x64/MSVC and x86/MSVC.
A shared parameterized fixture with all four methods and ordered results verifies
IR and nonempty objects across all nine supported profiles: Windows x86/x64/ARM64,
Linux x64/ARM64 GNU and musl, macOS x64/ARM64. This is not native execution evidence
for Linux, macOS or Windows ARM64.

Full regression command:

```powershell
.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon
```

Passes 1,238 tests across 95 suites in 5m 8s, zero failures/errors/skips.
Gradle used the existing cache outside the sandbox. Skill metadata and relative
file links pass direct checks. The optional `quick_validate.py` attempt cannot
start because its Python environment lacks PyYAML; no packages were installed.
The [integration milestone](../../cloth-language/references/development.md#unicode-whitespace-string-methods-2026-11-04)
retains the accepted contract and published verification scope.
