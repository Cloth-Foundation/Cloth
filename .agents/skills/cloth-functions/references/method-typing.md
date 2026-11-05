# Struct methods Stage 3 typing and receiver flow

Stage 3 integrates semantic checking for ordinary struct methods on
2026-11-05. The [method contract](semantics.md#user-defined-methods) owns
source behavior. The following source unit type-checks.

~~~cloth
typedef<struct> Meter {
    int value;

    mt<Meter, int> add(opt<int> amount ?: value) {
        value += amount
        return self, value
    }
}

fn<Meter, int> update(Meter meter) = meter.add()
~~~

## Implementation

- [CallAnalyzer](../../../../Compiler/src/main/kotlin/cloth/semantic/CallAnalyzer.kt)
  uses the shared source overload selector for explicit and implicit method
  calls. The receiver's nominal owner supplies candidates. Explicit argument
  placement, contextual typing, default omission and ordered results use the
  same contracts as functions. Candidate trials copy method call facts along
  with other expression facts and commit the selected trial.
- A resolved method carries a separate receiver plan. Stored receiver paths
  share the typed field/index place representation used for field writes.
  Function and deferred block results use a temporary receiver plan. An
  implicit field symbol points into `self` and carries the declared field
  type for ordinary reads, assignments and updates.
- Method bodies and defaults use the ordinary body checker. Defaults may
  read `self` fields, including by implicit lookup. A user-defined call on a
  stored path rooted in read-only `self` or an earlier parameter is rejected.
  Calls on independent temporary structs and existing primitive operations
  follow their ordinary contracts.
- [NullFlowAnalysis](../../../../Compiler/src/main/kotlin/cloth/semantic/NullFlowAnalysis.kt)
  gives explicit and implicit receiver field paths the same identity. A
  stored call checks presence facts before and after its arguments, including
  nullable ancestors of the selected receiver. It can invalidate proofs
  about fields below the receiver, including across deferred calls and loop
  backedges. The pass preserves the receiver's own presence and disjoint
  paths. It uses the resolved declaration type when available and otherwise
  conservatively considers the method groups that share the name.
- Metadata retains definite facts for independent lexical slots. Struct field
  metadata stays unknown as a represented type, while field types and
  metadata providers retain their ordinary static checking.

## Diagnostics and verification

C3062 rejects a receiver whose arguments invalidate a presence proof required
to bind `self`. C3066 rejects calls through a read-only default receiver.
At Stage 3, C3085 stopped typed units at emission validation.
[Stage 4](method-execution.md) retires that boundary and executes struct methods.
The CLI still reports one error before creating output.

The selected Windows regression run passed 511 tests across 44 suites,
with zero failures, errors or skips. `StructMethodTypingTest` covers nominal
overload selection, result lists, default scope, stored and temporary plans,
nullable receiver and ancestor checks, deferred and loop effects, and metadata.
The selection also exercises existing native and cross-target fixtures for
ordinary functions and basic structs. Method coverage is semantic.

~~~powershell
.\gradlew.bat :Compiler:test --tests 'cloth.Struct*Test' --tests 'cloth.Function*Test' --tests cloth.CallResolutionTest --tests cloth.DeclarationScopesTest --tests 'cloth.OptionalArguments*Test' --tests 'cloth.MultiReturn*Test' --tests cloth.DestructuringTest --tests cloth.ProgramEntryTest --tests cloth.BuiltinFunctionsTest --tests 'cloth.*AttachmentsTest' --tests 'cloth.Nullability*Test' --tests cloth.NullableBuffersTest --tests cloth.NullableRowsTest --tests cloth.TypeInfoTest --tests cloth.NullableTypeProvidersTest --tests cloth.DiagnosticsTest --tests cloth.DoWhileFlowTest --tests cloth.ArbitraryIntegerWidthsTest --offline --console=plain --no-daemon
~~~
