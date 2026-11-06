# String attachments and value member calls

The [scalar-method extension](scalar-methods.md) adds `charAt`, `substring`,
`indexOf`, `lastIndexOf` and `toChars`. This record retains the original four-query
contract and shared member-call foundation; use that extension for current
position semantics, target-dependent signatures and buffered/nullable results.
The [literal editing extension](editing-methods.md) adds `replace`, `replaceFirst`,
`removePrefix` and `removeSuffix` with checked independent string results.
The [Unicode whitespace extension](whitespace-methods.md) adds `trim`, `trimStart`,
`trimEnd` and `isBlank`, with pinned classification across every target.
The [storage cleanup](../../cloth-buffer-lifetimes/references/string-member-storage.md)
preserves the contracts below while eliding immutable operand byte copies and
admitting opted-in calls to closed arena/stack cleanup proofs.

## Confirmed supported contract (2026-11-03)

The user approved implementing these four string methods together with the typed
attachment registry and ordinary member-call groundwork:

```cloth
fn main() {
    def text := "Hello, Cloth 😀\0!"
    println("".isEmpty())
    println(text.contains("Cloth"))
    println(text.startsWith("Hello"))
    println(text.endsWith("😀\0!"))
}
```

All four calls return `bool`. `isEmpty()` takes no arguments. `contains(string)`,
`startsWith(string)` and `endsWith(string)` each take one positional string.
Matching follows existing string equality: exact Unicode scalar sequences,
case-sensitive, without normalization or case folding. Embedded NULs are data.
An empty search string matches every string, including the empty string; a
nonempty search string cannot match an empty string or a shorter receiver.
These are queries and do not modify the receiver.

Evaluate the receiver once, then written arguments once from left to right.
Preserve the received value before a later captured write can change its source,
including a string extracted from an array. An early return/jump in the receiver
or an argument stops the operation and skips later operands. Short circuits skip
member calls normally, while all source remains statically checked.

Calls accept expression receivers: literals, bindings, ordinary call results,
array elements, parenthesized conditionals and concatenations. Postfix member,
index and provider syntax composes in source order. A subsequent call on a result
still requires a registration for that result's type: `"x".contains("x").isEmpty()`
parses, then fails because `bool` has no `isEmpty` method.

Lookup uses the receiver type and method name; lexical names do not shadow
members. These methods are not unqualified global functions. `string.isEmpty()`
does not call a static method. `text::contains(...)` retains the separate,
currently unsupported state-call meaning. Bare field access/method values,
named method arguments, optional method parameters, member overloads and class
methods remain deferred. Integer methods are now covered by the
[integer attachment extension](../../cloth-numerics/references/integer-attachments.md).

Arguments must have the exact required string type. No implicit text adaptation
or `str` conversion applies. Nullable strings require existing presence proofs
or an explicit fallback before use as receivers/arguments. Unproven nullable
receivers have no matching registered method (C3071); unproven nullable arguments
fail C3049. Proven non-null payload reads use the existing nullable lowering.

Unknown or unavailable methods use C3071 / UNKNOWN_MEMBER, located at the method
name. Arity uses C3023 and counts only explicit arguments. Argument mismatch uses
C3049 at the argument, and named arguments use C3065. Type receivers retain
C3046 and state calls retain C3047. Intrinsic operand errors retain their own
diagnostics even inside an overloaded outer call. Stop at the first detected
error; do not accumulate errors or proceed to output/linker work after failure.

## Implementation ownership and invariants

- [PrimitiveAttachment](../../../../Compiler/src/main/kotlin/cloth/builtin/attachments/PrimitiveAttachment.kt)
  owns typed per-receiver registration and the `PrimitiveAttachments` lookup.
  Declaring a named entry registers it; there is no second hand-maintained method
  list. Duplicate names for one receiver are rejected. Published lookup lists
  are read-only. Registrations use exact receiver types and one signature per
  method; integer families now specialize lazily, while overloads remain deferred. Definitions
  are specialized by native count width and selected using the compilation target.
  Primitive providers share inherited `valueMethod(name, parameters,
  returns) { context, values -> ... }`, which explicitly opts into the read-only,
  independent-result contract. Keep method names, signatures and bodies together
  in named registrations; `register` retains unknown storage as its default.
- [StringAttachments](../../../../Compiler/src/main/kotlin/cloth/builtin/attachments/StringAttachments.kt)
  owns the original four queries and the linked method extensions. Each definition builds on `BuiltinParameter`,
  `BuiltinOverload`, `BuiltinDefinition` and `BoundBuiltinCall`; the hidden receiver
  is its first internal parameter. Registry definitions hold no LLVM handles or
  per-compilation state. Integer registrations and the representative name/result
  catalog are owned by the integer extension. Float definitions and shared-name
  handling for non-continuing receivers are covered by the
  [float extension](../../cloth-numerics/references/float-attachments.md).
  The [boolean extension](../../cloth-numerics/references/bool-attachments.md)
  uses the same declaration and lookup path for its single `toInt` entry.
  The [character extension](character-attachments.md) adds six Unicode predicates
  and code-point `toInt` through the same provider style and target-independent lookup.
  [Simple character casing](character-case.md) adds char-returning `toUpperCase`
  and `toLowerCase` registrations to that same provider.
- [MemberCallExpression](../../../../Compiler/src/main/kotlin/cloth/parser/expression/MemberCallExpression.kt)
  and `ExpressionParser.parseUnary` own ordinary dot-call syntax. Preserve receiver,
  supplied argument order/labels and original spans independently of lexical
  calls, provider access and state calls.
- `ExpressionTyping.memberCallType` binds the receiver and explicit arguments,
  keeping errors intrinsic during enclosing overload probes. `SemanticModel.memberCalls`
  retains committed bound calls; `FunctionSemanticModel.commit` copies these
  facts with other trial facts.
- `DeclarationAnalyzer`, `ControlFlowModel`, `NullFlowAnalysis` and
  `MetadataAnalysis` traverse the receiver before arguments. Preserve captured
  write invalidation, presence proofs and non-fallthrough completion. Pre-typing
  presence analysis proves a member result present only when every registered
  method with that name returns a non-nullable value.
- `EmitExpression.emitMemberCall` captures each received value before the next
  operand and protects temporary storage until the operation consumes it. Opted-in
  read-only methods use immutable string descriptors without redundant byte copies.
  `BuiltinEmissionContext` borrows string services for this emitted call.
  `ArrayTemporaryAnalysis` admits those methods only when operand effects satisfy
  the closed non-publication proof; unknown contracts/effects retain frame lifetime.
  Future result kinds must extend freshness and lifetime handling
  deliberately; string, char-array and nullable-count results are integrated by
  [the scalar extension](scalar-methods.md).
- [EmitString](../../../../Compiler/src/main/kotlin/cloth/emitter/text/EmitString.kt)
  emits length-aware predicates and shares its target-correct C `memcmp` signature
  with string equality. Empty needles exit before reads; longer needles exit
  before offset subtraction/pointer arithmetic. Prefix/suffix comparisons use a
  bounded slice. `contains` advances byte offsets through the last valid start,
  without incrementing past that last start. Valid UTF-8 permits byte matching
  without changing exact scalar-sequence semantics. Search allocates no scratch
  text; received value snapshots use existing frame storage. The initial search
  is a bounded straightforward scan, not a promised linear-time algorithm.

## Verification

Maintained example: [StringAttachments.co](../../../../examples/StringAttachments.co).
Focused suite: `StringAttachmentsTest`.

```powershell
.\gradlew.bat :Compiler:test --tests cloth.StringAttachmentsTest --offline --console=plain --no-daemon
.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon
```

The final focused command also includes `--tests cloth.StringsTest` and passes
30 tests (14 attachment tests and 16 existing string tests), with zero failures,
errors or skips. The full compiler run passes 1,197 tests across 92 suites in
5m 9s, with zero failures/errors/skips. The final metadata-effect test and
canonical diagnostic spelling cleanup were followed by that 30-test focused run
(18 seconds). The initial 12-test attachment run also passed. The native
matrix checks 430 predicates against host string expectations, including empty,
overlapping, long, case-sensitive, decomposed/accented, supplementary and NUL
inputs. Other cases cover typed registration identity/read-only publication,
parser spans/chaining, strict rejections, intrinsic outer-probe errors,
captured array-element writes, once-only receiver/argument effects, short circuits,
nullable proofs/result presence, early returns, metadata write invalidation,
first-error CLI rejection and composed receivers in loop conditions.

The example executes with exact expected output and zero status on Windows
x64/MSVC and x86/MSVC. The shared parameterized predicate fixture verifies LLVM
IR and emits nonempty objects for all nine target profiles: Windows x86/x64/ARM64,
Linux x64/ARM64 GNU and musl, and macOS x64/ARM64. This is not new native execution
evidence for Linux, macOS or Windows ARM64. See the
[integration milestone](../../cloth-language/references/development.md#string-attachments-and-member-call-groundwork-2026-11-03).
Skill metadata and relative file links were checked directly. The optional
`quick_validate.py` invocation could not run because its Python lacks PyYAML;
no environment packages were installed.

## Deferred API inventory

The earlier mockup also listed `toUpperCase`, `toLowerCase`,
`trimLeft`, `trimRight`, `isNumeric`,
`isAlpha`, `isAlphanumeric` and `toValue`. These remain proposals for strings, not callable
string entries. Character `toUpperCase`/`toLowerCase` are implemented separately
under [the simple character casing contract](character-case.md).
String Unicode case/numeric/letter classification policies, parsing
grammar/failure and type-valued arguments need separate decisions. Scalar indices
and the five extraction/search/conversion methods are now covered by
[the accepted extension](scalar-methods.md).
Literal replacement and prefix/suffix removal are covered by
[the editing extension](editing-methods.md).
`trim`, `trimStart`, `trimEnd` and `isBlank` are covered by
[the whitespace extension](whitespace-methods.md); the old left/right mock names
are not aliases.

The integer mockup's `abs`, `clamp`, `signum`, `toStringRadix`, `leadingZeros`
and `trailingZeros` are now implemented across every integer identity under the
[integer attachment contract and evidence](../../cloth-numerics/references/integer-attachments.md).
Static calls, class visibility/dispatch, method overrides and `toString()` remain
outside this work.
