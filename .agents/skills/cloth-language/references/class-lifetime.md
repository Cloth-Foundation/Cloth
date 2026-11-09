# Basic class execution and lifetime

Stage 3 landed on 2026-11-05. It executes basic classes with direct fields,
ordinary methods, constructor delegation and explicit deletion. The source rules remain in the shared language
record. [Class construction](semantics.md#class-declarations-and-constructors) ·
[Object lifetime](semantics.md#object-lifetime-and-nullability) ·
[Class implementation plan](../../../../todo/basic-classes-plan.md)

## Representation and lowering

Interfaces reuse the class identity and checked slot lookup. Dispatch checks
the receiver after supplied arguments and after static omission expressions.
The initialized class tag selects the class implementation or fallback body.
Interface deletion uses the concrete destructor chain.
[Interface argument effects](../../cloth-interfaces/references/stage-5.md#dispatch-and-lifetime) ·
[Interface lifetime](../../cloth-interfaces/references/stage-4.md#membership-and-deletion)

- A class identity is a private 64-bit carrier with a 32-bit slot index and
  a 32-bit generation. A module-local table uses a free list and checked native
  allocation sizes. Reusing a slot preserves the new generation, and an
  exhausted generation retires its slot. The table retains its peak capacity
  for reuse. Each explicit deletion frees that instance's storage. Nullable
  presence is a separate flag. [Class representation](../../../../Compiler/src/main/kotlin/cloth/data/CClass.kt) ·
  [Instance table](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitClassRuntime.kt)
- Supplied operands are evaluated in written order. Defaults run in parameter
  order before the selected constructor proceeds. Secondary delegation invokes
  the primary constructor once, and the primary allocates and initializes the
  instance before the secondary body receives `self`. Header fields precede
  body fields in source order. A non-continuing default contributes no incoming
  edge to the supplied argument path. [Callable lowering](../../../../Compiler/src/main/kotlin/cloth/emitter/callables/EmitFunctions.kt)
- Method calls capture receiver identity before operands, check liveness before
  argument effects, and check again at method entry and after defaults. Field
  writes check the captured identity again after the right-hand side. Slot
  addresses are resolved again after code that can move the table or delete the
  object. [Expression lowering](../../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitExpression.kt)
- Deletion marks the slot as destroying before invoking its destructor.
  Direct destructor `self` carries a separate permission that follows calls
  on `self`. Passing or copying an identity carries only the slot and
  generation. Release looks up the identity again after the destructor, so
  it can release the correct slot even if the destructor grew the table.
  Absent nullable deletion skips lookup and destructor execution. [Instance table](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitClassRuntime.kt)

## Diagnostics and scope

- C3092 reports provable invalid accesses, including local alias copies and
  joins where all continuing paths establish deletion. A do-while with no
  backedge joins its break exits with any exit through the trailing test.
  Ordered result reception stops evaluating positions after an operand exits.
  Unknown identities and effects use the runtime check. Repeated loops and
  deferred evaluation conservatively discard mutable alias facts.
  [Lifetime analysis](../../../../Compiler/src/main/kotlin/cloth/semantic/flow/ClassLifetimeAnalysis.kt)
- The runtime reports `Class object is no longer live` and exits with status 1
  for an invalid lookup. Access through ordinary aliases during destruction,
  stale access after slot reuse, and recursive deletion use the same failure
  path. [Runtime test fixtures](../../../../Compiler/src/test/kotlin/cloth/ClassLifetimeTest.kt)
- Stage 3 added execution for direct scalar, nullable and class-valued fields.
  Stage 4 integrates buffered fields, aggregate values and presence proofs.
  [Class values](class-values.md)

## Verification

The [standard-library Stage 4 boundary](../../cloth-toolchains/references/standard-library-stage-4.md)
reuses these operations for an imported owning-collection fixture.
StandardLibraryRuntimeTest verifies node/field cleanup with poisoned frees, slot
reuse, deterministic malloc/realloc failures and ordinary alias diagnostics.
Windows x64/x86 execute those probes; all nine profiles emit verified startup
objects, and the GNU Linux x64 fixture also links/runs under Ubuntu WSL.

The final focused Windows run passed 407 tests across 36 suites with zero
failures, errors or skips. Class programs executed on Windows x64 and x86, including
constructor order, stale slot reuse and growth during destruction. Additional
native host probes checked operand revalidation and destructor alias access.
The lifecycle fixture produced verified LLVM IR and objects on all nine
supported target profiles. The foreign-profile checks cover emission.
[ClassLifetimeTest](../../../../Compiler/src/test/kotlin/cloth/ClassLifetimeTest.kt)

The full Windows run passed 1,377 tests across 118 suites in 5m 37s with zero
failures, errors or skips before the follow-up for non-continuing defaults.

```powershell
.\gradlew.bat :Compiler:test --tests 'cloth.Class*Test' --tests 'cloth.Struct*Test' --tests 'cloth.Function*Test' --tests 'cloth.OptionalArguments*Test' --tests 'cloth.Nullability*Test' --tests 'cloth.MultiReturn*Test' --tests cloth.CallResolutionTest --tests cloth.TargetLayoutTest --tests cloth.DoWhileTest --tests cloth.ArrayTemporaryCleanupTest --offline --console=plain --no-daemon
.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon
```
