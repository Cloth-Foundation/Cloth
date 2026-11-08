# String member operand storage and temporary cleanup

## Scope (2026-11-04)

The user authorized cleanup of unnecessary string-member receiver copies and
conservative temporary retention. The public string API, matching rules,
Unicode version, evaluation order, first-error behavior and independent returned
values are unchanged. This is an internal storage optimization, not a borrowed-view
API, object lifetime change, reference counting or GC.

## Opt-in callable contract

`BuiltinStorageContract` in `BuiltinFunction.kt` defaults to `UNKNOWN`.
`READ_ONLY_INDEPENDENT_RESULT` explicitly promises that both the body and argument
adapters read operands without modifying/publishing/retaining them, and that
buffered results own independent storage. This concerns storage, not IO purity.
`PrimitiveAttachment.register` defaults to unknown. Implemented string methods,
including [split](../../cloth-strings/references/split-method.md), use inherited
`PrimitiveAttachment.valueMethod` to opt in, sharing the
same registration helper with the other primitive attachments. Future mutating, retaining,
borrowed-result or otherwise unproven methods must not use that helper.

`SemanticModel.memberCalls` retains the selected overload's contract alongside
existing argument/result facts. Neither the registry nor this contract stores
LLVM handles or mutable compilation state. Target-specialized signatures remain
unchanged. Global output built-ins retain their unknown contract.

## String operand copy elision

`EmitExpression.emitMemberCall` still evaluates the receiver and each written
argument exactly once, in order. It skips byte snapshots only for exact `string`
parameters of opted-in calls. Every other type/contract uses the existing
`snapshotValue` path. Array copies, nullable buffers, ordinary source-function
arguments/results and ordered-result copies are not generally elided here.

The proof relies on current string invariants:

- A loaded string descriptor is an SSA value containing its byte pointer/count.
  Rebinding the source or replacing an array element changes stored descriptors,
  not the previously loaded descriptor or immutable underlying bytes.
- The protected operand region (`temporaryDepth`) prevents nested expression
  cleanup before the method consumes its values. Any later operand's independent
  temporary region checkpoints storage after the earlier value was produced.
- Existing storage stays live until a proven temporary-region release or physical
  frame exit. Rebound strings now have separate replaceable owners under the
  [string storage correction](string-concatenation-storage.md). Their reads take
  independent snapshots unless later operands are proven not to publish/rebind.
  A descriptor borrowed for a read-only call therefore cannot be freed before
  consumption. Callee results copy before callee cleanup. A crossed-region
  return/jump also stops evaluation before any consumer could use released storage.
- The opted-in body consumes these values synchronously and returns independent
  buffers. Adding string mutation, user-controlled string destruction or retaining
  callbacks requires revisiting this proof, not silently inheriting elision.

Thus `text.isEmpty()` no longer copies all text bytes. The same applies to string
arguments with no later publication. Captured writes require an independent
operand snapshot before replacement. Captured publication can still block early
cleanup; immutable frame-backed operands retain their existing copy elision.

## Proven temporary regions

[Growable arrays Stage 2](../../cloth-arrays/references/growable-arrays-stage-2.md)
adds independent stored-index string snapshots and permits borrowing indexed
operands for read-only consumers when later operands have no publication.
Potential snapshot allocation participates in this same closed proof. Arena
allocation headers now include a resource callback word; historical byte metrics
below describe their original layout. Current probes use the updated header size.

`ArrayTemporaryAnalysis` admits opted-in members only when every receiver/argument
passes the existing closed non-publication proof. Unknown contracts, providers,
global built-ins and captured declarations/writes remain conservative unless an
existing proof explicitly handles them. The internal allocation flag is named
`buffers`, covering member stack/arena temporaries as well as arrays/wide values.

Safe member calls mark potential temporary storage so producers are reclaimed
even when they yield a scalar query result or allocate only stack buffers.
`EmitExpression.emit` installs arena and stack checkpoints only when the result
is discarded or has no indirect storage. After consumption it releases the arena
suffix, then restores dynamic stack storage. Receiving constraints execute before
release; escaping string/array/wide results never cross that scalar/discard rule.
Fresh-array/result facts are restored to the checkpoint's preexisting set.

Existing range/foreach/while/do-while proofs now recognize these member effects.
Safe locals and scalar updates can reclaim complete iterations/conditions. Buffered
publication still blocks the enclosing loop proof; independently safe discarded
member statements can reclaim their own storage within such a loop. Break/continue
release crossed regions through the shared cleanup stack. Function/deferred returns
keep their existing outgoing-copy/ownership paths; no premature stack restore is
added on a non-continuing return path.

Independent substring, editing, trimming and character-array results are still
created. Stored/returned values preserve ordinary shape and copy rules. This does
not promise bounded storage for arbitrary loops, unknown consumers such as output
built-ins/providers, or repeated captured publication.

[Simple character casing](../../cloth-strings/references/character-case.md) is
another read-only independent scalar consumer under the same proof. Its
`ArrayTemporaryCleanupTest` probe checks saved char results, preexisting arrays,
1,000 repeated producer/mapping calls, while/do-while conditions and final live-byte
balance with poisoned frees. It adds no new cleanup rule or buffer ownership path.

[Full string casing](../../cloth-strings/references/string-case.md) uses the same
read-only contract with independent text results. Its shared scalar encoding
calculation introduces no per-scalar buffer allocation. The no-operand-copy probe
includes both methods; a separate poisoned-free probe checks outgoing strings,
arrays, captured publication and repeated scalar/condition consumers.

## Verification

The existing string/lifetime regression command passed after the core change:

```powershell
.\gradlew.bat :Compiler:test --tests cloth.ArrayTemporaryCleanupTest --tests 'cloth.String*Test' --offline --console=plain --no-daemon
```

The additional focused command passes 60 tests (56 lifetime and four storage
contract/portability tests), zero failures/errors/skips, in 23 seconds:

```powershell
.\gradlew.bat :Compiler:test --tests cloth.ArrayTemporaryCleanupTest --tests cloth.StringMemberStorageTest --offline --console=plain --no-daemon
```

Native poisoned-free allocation probes verify:

- A stored string incurs its one required producer allocation; all 17 member
  methods add zero operand-copy allocations. Returned char storage remains
  independent when mutated. Literal-only member queries also add no copies.
- 1,000 scalar member consumers release producer buffers while retaining a saved
  preexisting string; peak arena bytes stay at two producer allocations.
- While/do-while condition scans finish with zero live arena bytes and a peak of
  one small producer allocation across 101 calls.
- Captured rebinding preserves the old receiver descriptor and newly published
  string. A subsequent safe query reclaims only its own arena suffix.
- 5,000 discarded 4 KB results restore dynamic stack storage inside a loop whose
  buffered publication prevents whole-loop reclamation. Only deliberate published
  producer allocations remain live, and the receiving frame ultimately frees all.

`StringMemberStorageTest` verifies default/unknown contracts block reclamation,
captured publication blocks it even for known bodies, and shared cleanup/escaping
result IR and nonempty objects for all nine supported target profiles. The 5,000
iteration unsafe-outer-loop stress fixture executes on Windows x64/MSVC and
x86/MSVC. Allocation/poison counters execute on the native Windows x64 host;
Linux, macOS and Windows ARM64 retain IR/object evidence, not new native execution.

Full regression command:

```powershell
.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon
```

Passes 1,247 tests across 96 suites in 5m 10s, zero failures/errors/skips.
Gradle used the existing cache outside the sandbox. Changed skill metadata and
relative file links pass direct checks. The optional `quick_validate.py` attempt
cannot start because its Python environment lacks PyYAML; no packages were installed.
The [integration milestone](../../cloth-language/references/development.md#string-member-storage-cleanup-2026-11-04)
retains the published scope and evidence.
