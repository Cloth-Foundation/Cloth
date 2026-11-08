# String concatenation and rebinding storage (2026-11-07)

## Defect and scope

Bootstrap's ordinary per-character JSON escaping reproduced a native stack
overflow for a 3,072-scalar lexeme. The compiler emitted a variable-sized
alloca for every concatenation. A rebound string kept the newest descriptor,
but every older prefix remained on the native stack until the function returned:
one-byte appends retained 1 + 2 + ... + n bytes. Existing cleanup deliberately
rejected buffered publication, so it could not reclaim this loop safely.

The user requested a compiler fix and explicitly asked that ongoing
standard-library work remain untouched. Bootstrap's recursive 64-scalar
formatting workaround has been removed. No string builder, collection, I/O or
standard-library API is added here. String concatenation retains exact UTF-8
contents, strict operands, once-only left-to-right evaluation and independent
value semantics.

~~~cloth
fn<string> grow(int count) {
    def result = ""
    for (def i <- 0..count) result = result + "x"
    return result
}
~~~

## Implementation and invariants

- Production concatenations allocate checked bytes in the physical function's
  temporary heap arena rather than accumulating dynamic native stack buffers.
  Standalone backend probes without a function frame retain their explicit
  scratch-storage path. Existing allocation failure messages/checks are retained.
- Rebound string and nullable-string locals/parameters own separate replaceable
  arenas. The selected binding identities come from resolved assignment targets,
  including positional/broadcast stores. Class/receiver fields retain their
  existing storage path.
- An owning store copies the received value before releasing its previous chain.
  This preserves self-assignment. Arena slots are initialized in function entry,
  including conditionally reached declarations, and released at every normal
  function exit after outgoing results have copied to caller storage.
- Reads that can outlive a later store snapshot into expression/frame storage.
  Saved bindings, arrays, structs, function arguments, captured-block results and
  positional receptions therefore cannot refer to a freed string owner.
- Scalar providers and direct eager/read-only consumers may borrow a descriptor
  only until synchronous consumption. A left operand or member operand snapshots
  first if later operands can publish/rebind. Class-backed reads keep their
  existing snapshot guards; arbitrary user formatting is not classified as safe.
- Proven-safe owning declarations/stores reclaim their arena scratch after the
  destination copy. Loop proofs also admit these string stores, while other
  buffered/indexed/captured publication remains conservative. The existing shared
  jump/return cleanup handles crossed regions. A value-producing statement keeps
  its expression result alive instead of releasing it as a discarded store.
- Non-user providers are analyzed through their resolved recipe, skipping
  non-executable type receivers. Elvis checks both alternatives conservatively.
  Unknown operators, mutating captures and user formatters remain outside the
  closed non-publication proof.

This is deterministic compiler-managed value storage, not tracing, reference
counting, automatic class destruction or a new language ownership facility.
Repeated immutable appends still copy growing contents: this fix bounds safe
live storage, not total copying time. Unproven publication can retain arena
buffers until frame exit; arbitrary-loop bounded memory is not promised.

## Source routing

[EmitString](../../../../Compiler/src/main/kotlin/cloth/emitter/text/EmitString.kt)
chooses arena storage for production concatenations.
[EmitStringArena](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitStringArena.kt)
owns checked aligned allocation, copy-before-release replacement and arena release.
[EmitFunctionFrame](../../../../Compiler/src/main/kotlin/cloth/emitter/callables/EmitFunctionFrame.kt)
owns persistent binding arenas and final cleanup.
[EmitBindings](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitBindings.kt)
attaches them to resolved rebound string bindings.
[EmitFunctions](../../../../Compiler/src/main/kotlin/cloth/emitter/callables/EmitFunctions.kt)
selects those bindings and supplies the production temporary arena.
[EmitExpression](../../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitExpression.kt),
[EmitStatement](../../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitStatement.kt)
and [ArrayTemporaryAnalysis](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/ArrayTemporaryAnalysis.kt)
preserve snapshots, safe borrowing and cleanup barriers.

## Verification

[StringConcatenationStorageTest](../../../../Compiler/src/test/kotlin/cloth/StringConcatenationStorageTest.kt)
first reproduced the failing growing-loop program. Its native Windows x64/x86
cases now cover growing UTF-8/NUL strings, a single 1.4 MB concatenation,
captured rebinding, earlier arguments/operands, saved arrays/structs,
self-assignment, nullable strings, parameter rebinding, positional stores,
while/do-while and loop jumps. It verifies IR and emits objects for all nine
existing Windows/Linux/macOS profiles.

[ArrayTemporaryCleanupTest](../../../../Compiler/src/test/kotlin/cloth/ArrayTemporaryCleanupTest.kt)
poisons released storage and checks zero outstanding allocations. New probes
bound a 4,096-append loop below 128 KiB of live heap storage and a safe string
store inside a separately publishing array loop below 192 KiB. Existing captured
receiver and member probes now assert the replaceable-owner allocation peaks,
including constant live storage for 5,000 string replacements.

[BootstrapLexerTest](../../../../Compiler/src/test/kotlin/cloth/BootstrapLexerTest.kt)
passes all 124 differential inputs with ordinary unchunked formatting, including
the original long lexeme and all eight Bootstrap source files.
[StringsTest](../../../../Compiler/src/test/kotlin/cloth/StringsTest.kt) keeps the
short-circuit allocation-placement invariant for the new arena call.

The focused run passes 80 tests without failures/errors/skips. The final complete
Compiler check passes in 7m 46s: 1,636 tests across 148 suites, plus 23 isolated
syntax tests, 46 isolated resolution tests and 33 native-disabled semantic tests.
All have zero failures, errors or skips. The full run also retains regressions
for terminating provider receivers and target-aligned allocator headers.

~~~powershell
.\gradlew.bat :Compiler:check '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process' --no-build-cache --no-configuration-cache --offline --console=plain --no-daemon
~~~

The observed command additionally used a task-local build-directory init script
and project cache to isolate concurrent workspace builds.

The current Bootstrap GNU Linux x64 startup object is linked with cc under
Ubuntu WSL. Five native runs pass: sample text, interpolation, the first numeric
error, the original 3,072-scalar raw lexeme (including exact lexeme JSON) and
tokenizing Bootstrap's unchunked formatter source. Native ARM64/macOS/musl
execution remains untested; those profiles have IR/object evidence.

Four changed skills pass metadata validation, and publication checks cover seven
documents, 243 local links and 16 anchors. Standard-library source, import
selection, packaging and their concurrently edited history remain untouched.
