# Literal string editing methods

## Accepted contract (2026-11-04)

The user approved `replace`, `replaceFirst`, `removePrefix` and `removeSuffix`,
then explicitly chose Unicode scalar-boundary matches for empty replacement
searches. These methods extend the [ordinary member-call foundation](member-calls.md)
and [scalar string model](scalar-methods.md).

| Call | Result | Behavior |
| --- | --- | --- |
| `text.replace(string old, string replacement)` | `string` | Replace all left-to-right, non-overlapping literal matches. |
| `text.replaceFirst(string old, string replacement)` | `string` | Replace the first literal match only. |
| `text.removePrefix(string prefix)` | `string` | Remove one matching prefix, otherwise preserve the contents. |
| `text.removeSuffix(string suffix)` | `string` | Remove one matching suffix, otherwise preserve the contents. |

All four methods return independent string values and leave receiver/argument
values unchanged. Matching is exact and case-sensitive over validated Unicode
sequences, including embedded NULs. No regex, replacement escapes, capture-group
expansion, normalization or case folding is introduced. Replacement text is
literal and is never rescanned. Consume each matched original substring before
looking for the next match, so `"aaa".replace("aa", "b")` is `"ba"`, while
`"aaaa".replace("aa", "a")` is `"aa"`.

Empty search behavior was selected directly by the user:

```cloth
"A😀B".replace("", "-")      // "-A-😀-B-"
"A😀B".replaceFirst("", "-") // "-A😀B"
"".replace("", "x")          // "x"
```

Every scalar boundary matches, including the start and end; never split UTF-8
bytes or a supplementary scalar's encoding. A combining mark retains its own
scalar boundary. Empty replacement text removes matched original text. An empty
prefix/suffix removes no contents. Boundary removal occurs once, not repeatedly:
`"aaaa".removePrefix("a")` is `"aaa"`. A missing match returns an independent
copy of the original contents.

The receiver evaluates once, then written arguments once, left to right, with
value snapshots before later captured writes. The internal measure/fill passes
must not reevaluate expressions or duplicate side effects. An early return/jump
skips later operands and the operation. Short circuits skip runtime work while
all source remains statically checked.

Each method has one exact positional signature. Nullable receivers/arguments
need an existing presence proof or explicit fallback. No implicit printable/text
adaptation, char overloads, named arguments or optional parameters are added.
Existing arity C3023, argument C3049, label C3065 and intrinsic-expression
diagnostics remain first-error failures before output/linker work.

Minimal example:

```cloth
fn main() {
    def text := "Cloth 😀 Cloth"
    println(text.replace("Cloth", "Thread"))
    println(text.replaceFirst("Cloth", "Thread"))
    println(text.removePrefix("Cloth "))
    println(text.removeSuffix(" Cloth"))
}
```

Maintained example: [StringEditing.co](../../../../examples/StringEditing.co).

## Compiler ownership and invariants

- `StringAttachments` declares the four named registrations; declaration also
  publishes them. They reuse the existing typed hidden receiver, strict string
  parameters and `BoundBuiltinCall` result checks. `BuiltinEmissionContext`
  borrows [EmitStringEdit](../../../../Compiler/src/main/kotlin/cloth/emitter/text/EmitStringEdit.kt)
  for the current module/call/source location. Reusable registry definitions
  retain no LLVM handles or per-emission mutable state.
- Boundary removal uses existing bounded prefix/suffix predicates, selects a
  bounded removal length before subtraction, then allocates/copies exactly one
  independent result. Empty and missing affixes follow that same copy path.
- Replacement uses one shared traversal emitted twice: measure, allocate once,
  then fill. Measure checks every added output segment through `EmitMemory.add`,
  enforcing selected-target count and signed pointer-index limits before any
  result allocation or replacement-payload access. There is no growing-prefix
  concatenation and no unchecked expanded-count multiplication/subtraction.
- Both passes visit the same immutable snapshots with the same deterministic
  matching rules. That establishes each fill offset/copy within the measured
  allocation. Fill performs no user code or transformations on replacement text.
  Empty outputs still receive a valid one-byte allocation and a zero byte count.
- The source byte offset is always at a scalar boundary and no greater than its
  byte length. Check remaining bytes before a nonempty `memcmp`, sharing
  `EmitString.bytesEqual`'s target-correct C ABI signature. A consumed nonempty
  match advances by its validated full string byte length. Nonmatches and empty
  matches advance through `EmitStringAccess.nextScalarOffset`, only after proving
  a live original scalar exists. Empty matches at the final boundary finish.
  `replaceFirst` copies the untouched tail directly after its first match.
- Loop phis use actual predecessors after checked-add guard blocks. Counts,
  addresses, copied lengths and allocations use the selected target `usize`.
  Results use ordinary string descriptors and recursive caller-owned return
  copies through functions, deferred blocks, arrays and ordered results.
  Existing `EmitExpression` operand protection remains in force. The later
  [storage cleanup](../../cloth-buffer-lifetimes/references/string-member-storage.md)
  elides immutable byte copies and reclaims proven member regions; unknown effects
  remain conservative. No GC, borrowing API, string mutation or arbitrary-loop
  bounded-memory guarantee is introduced.
- Search is a straightforward bounded scan; no linear-time complexity promise is
  made. The result is measured independently of whether it grows or shrinks, so
  valid shrinking transformations do not fail on a larger intermediate size.

## Verification

Focused command:

```powershell
.\gradlew.bat :Compiler:test --tests cloth.StringEditingTest --tests cloth.StringAttachmentsTest --tests cloth.StringScalarMethodsTest --tests cloth.ArrayTemporaryCleanupTest --tests cloth.BufferCountsTest --offline --console=plain --no-daemon
```

Passes 97 tests across five suites in 26 seconds, zero failures/errors/skips:
12 editing, 14 attachment, 13 scalar-method, 50 lifetime and 8 count tests.
The independent oracle checks 240 replacement combinations and 90 affix removals,
including empty strings/needles/replacements, overlaps, missing/full matches,
supplementary and combining scalars, NULs and literal dollar/backslash contents.
An additional case checks literal regex-like text and no replacement rescanning.
Native expansion/deletion checks process 16,384 original scalars.

Other tests verify strict argument contracts, nullable proofs/result presence,
intrinsic diagnostics inside overload probes, once-only receiver/old/replacement
effects, captured array-element updates, early exits, short circuits, recursion,
ordered results, array storage and chained calls. First-error CLI rejection
retains C3049 before object/linker work. The previously expanded maintained
`StringAttachments.co` example is preserved and its output expectation now
includes the user's scalar-method demonstration and character-array output.

`ArrayTemporaryCleanupTest` adds a poisoned-free probe for edited results from
released receiver/argument frames, independent saved/ordered results, deferred
captured publication and empty-boundary replacement. The receiving frame exits
with zero outstanding allocations. `BufferCountsTest` adds three native probes
using inaccessible synthetic replacement payloads: all-match growth, first-match
growth plus tail, and empty-boundary growth. Windows x64/x86 reject overflow with
a controlled located error before allocation or any replacement-payload read.

The maintained example executes with exact output and zero status on Windows
x64/MSVC and x86/MSVC. A shared parameterized fixture using all four methods and
ordered results verifies IR and emits nonempty objects for all nine supported
profiles: Windows x86/x64/ARM64, Linux x64/ARM64 GNU and musl, macOS x64/ARM64.
This does not establish native execution on Linux, macOS or Windows ARM64.

Full regression command:

```powershell
.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon
```

Passes 1,225 tests across 94 suites in 5m 17s, zero failures/errors/skips.
Gradle used the existing cache outside the sandbox. Changed skill metadata and
relative file links pass direct checks. The optional `quick_validate.py` attempt
cannot start because its Python environment lacks PyYAML; no packages were
installed. The [integration milestone](../../cloth-language/references/development.md#literal-string-editing-2026-11-04)
retains the published contract and verification scope.
