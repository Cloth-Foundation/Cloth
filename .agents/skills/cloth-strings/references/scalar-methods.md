# Scalar-indexed string methods

## Accepted design and implemented signatures (2026-11-03)

The user accepted zero-based Unicode scalar positions and proceeding with the
next string-method batch using the proposed extraction/search/value contracts.
This extends the [ordinary member-call foundation](member-calls.md).

| Call | Result | Behavior |
| --- | --- | --- |
| `text.charAt(usize index)` | `char` | Read the scalar at `index`; require `index < text::size`. |
| `text.substring(usize start, usize end)` | `string` | Independent contents from inclusive `start` to exclusive `end`; require `start <= end <= text::size`. |
| `text.indexOf(string needle)` | `usize?` | First matching scalar position, or null. |
| `text.lastIndexOf(string needle)` | `usize?` | Last matching scalar position, including overlapping matches, or null. |
| `text.toChars()` | `char[]` | Independent mutable array of the string's Unicode scalar values, in order. |

Positions agree with `char` and string `::size`: `"A😀B"` contains three scalars,
`charAt(1)` is `'😀'` and `substring(1, 2)` is `"😀"`. A combining mark has its own
scalar position; positions are neither UTF-8 bytes nor displayed graphemes.
Strings remain validated UTF-8; scalar extraction never splits a multibyte scalar.
Case sensitivity, exact sequence matching and embedded-NUL behavior match the
existing queries. No normalization or case folding is introduced.

Implementation policies within that accepted contract:

- Index/count parameters use exact target `usize`, consistent with `::size`.
  Contextual integer literals work; typed `int`, fixed unsigned integers and
  `isize` need an explicit cast. Negative or out-of-range literals retain C3004.
- Empty needles match at every scalar boundary: `indexOf("")` returns present
  zero, and `lastIndexOf("")` returns present `text::size`, including for empty text.
  Nonempty needles larger than the remaining bytes yield no match.
- Empty slices are valid at every boundary, including
  `substring(text::size, text::size)`. An empty string converts to a zero-length
  `char[]`. Character access at the end is invalid, even for empty text.
- Executed invalid bounds raise the controlled runtime error with source
  filename/span. All receiver/argument effects finish in order before the method
  checks bounds. Short-circuited operations execute no guards. There is no
  constant-string bounds evaluator; type/literal checks still happen statically.
- Each method has one positional signature. No optional parameters, named labels,
  char search overloads, direct string `[]` access, or string mutation is added.

Nullable search results obey ordinary Cloth rules: new nullable bindings require
an explicit annotation even for a literal receiver. A match is not assumed present
without a proof. Elvis remains lazy and counts retain target `usize` identity.

```cloth
fn main() {
    def text := "A😀B"
    def<usize?> position = text.indexOf("😀")
    if (position != null) println(text.charAt(position))
    println(text.substring(1, 3))
    def chars = text.toChars()
    chars[1] = 'x' // text still contains the original emoji.
}
```

The full maintained example is [StringScalars.co](../../../../examples/StringScalars.co).

## Compiler ownership and invariants

- `StringAttachments` registers all five methods using the same hidden receiver
  and built-in contracts as the original four queries. `PrimitiveAttachments`
  specializes immutable definitions by native count width; `ExpressionTyping`
  and `NullFlowAnalysis` explicitly select the compilation target. Native default
  lookup remains available for callers/tests. No reusable AST or definition caches
  a host-width `usize` as a foreign target type, and no definition holds LLVM handles.
- `ExpressionTyping.memberCallType` supplies exact count contexts before binding
  arguments. Search results are nullable; the pre-typing presence pass must not
  mark them present. Legacy snippets retain their unsupported-nullability guard
  for search results; charAt/substring/toChars and ordinary source units use
  the shared member path. Existing intrinsic operand diagnostics remain fatal
  during outer overload trials.
- [EmitStringAccess](../../../../Compiler/src/main/kotlin/cloth/emitter/types/primitives/EmitStringAccess.kt)
  owns scalar-position traversal, UTF-8 decoding, extraction and scalar-indexed
  search. It borrows per-emission services/source location via
  `BuiltinEmissionContext`; the global registry holds no emitter state.
- Offset traversal stops at the requested scalar boundary or byte end, then
  guards before access. Every leading-byte load is dominated by a live scalar
  position. Continuation-byte loads execute only within that validated scalar's
  width. Decode accumulates all one-to-four UTF-8 bytes into 32-bit `char` without
  speculative out-of-bounds loads. Scalar and byte counters use target counts.
- Substrings copy the bounded byte interval into independent stack storage with
  a valid allocation for an empty string. `toChars` counts scalars, checks
  count-times-char-storage bytes through `EmitMemory.stack`, then fills independent
  aligned char storage and builds an ordinary one-dimensional array descriptor.
  These are internal compiler buffers, not objects, borrowed views or GC.
- Search handles empty needles first. Nonempty search visits scalar starts,
  checks remaining bytes before comparing, and shares `EmitString.bytesEqual`'s
  target-correct length-aware `memcmp` signature. `indexOf` stops at the first
  match; `lastIndexOf` retains the latest match while advancing one scalar, so
  overlaps remain visible. Absence uses the ordinary deterministic nullable
  carrier. Search has no scratch allocation and no promised linear-time bound.
- `EmitExpression.emitMemberCall` captures receiver/arguments before later
  operands, eliding immutable string byte copies under the
  [storage contract](../../cloth-buffer-lifetimes/references/string-member-storage.md).
  Fresh independent member array results enter ordinary result freshness
  tracking; adoption consumes that fact once. Stored copies, parameters, deferred
  returns, ordered results and escaping buffers use existing recursive copy/shape
  rules and caller-owned return storage before frame release. `ArrayTemporaryAnalysis`
  now admits opted-in bodies with proven operands; unknown effects remain conservative.
  Arbitrary repeated member calls still have no blanket bounded-storage guarantee.

## Verification evidence

Focused command:

```powershell
.\gradlew.bat :Compiler:test --tests cloth.StringScalarMethodsTest --tests cloth.StringAttachmentsTest --tests cloth.ArrayTemporaryCleanupTest --offline --console=plain --no-daemon
```

Passes 76 tests across three suites (13 scalar method, 14 original attachment,
49 lifetime), zero failures/errors/skips, in 22 seconds. The scalar and substring
matrix uses independent host code-point expectations, including UTF-8 width
boundaries, the scalar maximum, supplementary characters, combining marks,
NULs, quotes, backslashes and control characters. The search matrix verifies
144 first/last results including empty needles, overlapping matches, missing
substrings and scalar-versus-byte positions. It explicitly handles empty needles
instead of importing the host library's default reverse-search starting position.

Other cases cover strict signatures, explicit nullable bindings, presence proofs,
lazy fallback, contextual target counts and reused x86/x64 ASTs, once-only effects,
captured array-element writes, early exits, recursion, ordered results, shape/copy
independence, foreach and chained calls. Runtime bounds probes check first fatal
exit, original source location and no later effects/completion output. The CLI
check retains first-error C3004 and fails before object/linker work.
`ArrayTemporaryCleanupTest` adds a poisoned-free probe for returned substrings,
character arrays, copies, captured receiver updates and deferred blocks, with
zero outstanding allocations after the receiving frame exits.

Native example execution covers Windows x64/MSVC and x86/MSVC with exact output
and zero status. A shared fixture using all five new operations, parameters and
ordered results verifies IR and emits nonempty objects for all nine supported
profiles: Windows x86/x64/ARM64, Linux x64/ARM64 GNU and musl, macOS x64/ARM64.
This is not native execution evidence for Linux, macOS or Windows ARM64.

Full regression command:

```powershell
.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon
```

Passes 1,212 tests across 93 suites in 4m 59s, zero failures/errors/skips
(verification completed 2026-11-04). Gradle used the existing cache outside the
sandbox. Changed skill metadata and relative file links pass direct checks.
The optional `quick_validate.py` attempt could not start because its Python
environment lacks PyYAML; no packages were installed. The
[integration milestone](../../cloth-language/references/development.md#scalar-indexed-string-methods-2026-11-04)
retains the published scope and evidence.
