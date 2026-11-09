# Generic types Stage 0: contract and baseline

Status: complete (2026-11-08). This is the historical pre-implementation baseline;
[Stage 1](stage-1.md) records the subsequent syntax/name implementation. The
public List delivery gate in growable-array Stage 7 remains open.

The [confirmed contract](semantics.md) owns the first-slice rules. The
[acceptance fixtures](acceptance-fixtures.md) provide intended imported List,
private generic struct-slot, nullable substitution and first-error inputs.
They are deliberately documented as future inputs rather than runnable examples
or shipped library sources. At this baseline the compiler did not parse generic
owners.

## Decisions captured

The user selected all six recommended contracts through focused questions:

1. Generic classes and structs, explicit invariant arguments; constraints,
   generic interfaces/functions, variance and argument inference remain later.
2. Template bodies checked when declared with unknown exact parameters, and
   closed instances checked again after substitution.
3. Owner-wide lexical parameter scope, shadowing outer type names; duplicate
   parameters and an owner-name parameter are errors. Value lookup stays separate.
4. Nullable substitution normalizes one wrapper; directly written duplicate
   markers remain invalid.
5. Ordinary value types are admitted, retaining exact identities; void, bare
   generic templates and omission-only wrappers are not type arguments.
6. Private implementation helpers remain allowed in private storage but cannot
   leak through public generic signatures. Caller-private arguments remain valid
   where the caller can name them.

The selected initial implementation policies are target-scoped interning,
monomorphized lowering, provisional same-key class identities, 64 newly required
instances per dependency chain, 4,096 distinct closed instances per compilation
and 256 levels of generic argument structure. These choices are not new source
syntax or measured generic runtime guarantees. Their boundary guards/tests belong
to the implementing stages, with one controlled first diagnostic.

## Existing source boundary

- [ClassParser](../../../../Compiler/src/main/kotlin/cloth/parser/subs/ClassParser.kt)
  and [StructParser](../../../../Compiler/src/main/kotlin/cloth/parser/subs/StructParser.kt)
  do not retain generic owner parameter declarations.
- [TypeParser](../../../../Compiler/src/main/kotlin/cloth/parser/subs/TypeParser.kt)
  and [TypeReference](../../../../Compiler/src/main/kotlin/cloth/parser/type/TypeReference.kt)
  represent numeric widths and array/nullable suffixes, not named generic arguments.
- [FileBindings](../../../../Compiler/src/main/kotlin/cloth/semantic/declarations/FileBindings.kt),
  [NamedTypes](../../../../Compiler/src/main/kotlin/cloth/semantic/declarations/NamedTypes.kt)
  and [TypeResolver](../../../../Compiler/src/main/kotlin/cloth/semantic/declarations/TypeResolver.kt)
  resolve one non-generic nominal identity per named declaration, in its file context.
- [CNullable](../../../../Compiler/src/main/kotlin/cloth/data/CNullable.kt) rejects
  nested nullable payloads. Generic substitution must normalize the chosen wrapper
  before using that existing single-layer representation.
- [EmitTypeInfo](../../../../Compiler/src/main/kotlin/cloth/emitter/text/EmitTypeInfo.kt)
  keys current nominal metadata by source/declaration and type identity. Closed
  generic arguments must become part of that identity in later implementation.

The complete concrete StringList/RowList proof remains executable under
[growable-array Stage 7](../../cloth-arrays/references/growable-arrays-stage-7.md).
It is not evidence that the new generic fixtures already execute.

## Baseline command and results

```powershell
.\gradlew.bat :Compiler:test :Shuttle:test --offline --console=plain --no-daemon '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'
```

The baseline invocation succeeds in **8m 55s**:

| Project | Tests | Suites | Failures / errors / skips | Task status |
| --- | ---: | ---: | --- | --- |
| Compiler | 1,821 | 165 | 0 / 0 / 0 | Executed in this run. |
| Shuttle | 57 | 7 | 0 / 0 / 0 | Up to date; retained passing reports, no new Shuttle source inputs. |

Shuttle's up-to-date status is distinct from a fresh execution. Stage 0 does not
claim it reran those 57 tests. All Compiler suites execute, including the 19
collection proof tests added in the preceding stage.

XML reports are archived under `build/generic-types-stage0/compiler-test-results/`
and `shuttle-test-results/`. `test-summary.json` records task status, suite totals
and SHA-256 hashes verified against source XML on copying. The
`source-input-hashes.json` manifest captures 396 Compiler/Shuttle Kotlin source
files; every hash remains unchanged after the run. No compiler/test implementation,
Bootstrap, standard-library or intended Test.co source is changed for this stage.

## Named preservation targets

The full run includes these specific regression entry points for future stages:
43 suites containing 571 tests, extracted from the full run rather than rerun as
a separate focused command. Exact group counts/names are archived in
`build/generic-types-stage0/preservation-targets.json`.

| Area | Suites / tests | Baseline suites |
| --- | --- | --- |
| Grammar and suffixes | 8 / 99 | ClassParsingTest, StructParsingTest, FunctionParsingTest, NullabilityParsingTest, MultiReturnParsingTest, ImportParsingTest, ProviderSyntaxTest, ArbitraryIntegerWidthsTest. |
| Exact types, scopes and calls | 9 / 124 | PrimitiveTypesTest, DeclarationScopesTest, ClassSemanticsTest, StructSemanticsTest, CallResolutionTest, StructCallResolutionTest, StructMethodTypingTest, FunctionReturnsTest, FunctionBlocksTest. |
| Imports and nominal context | 4 / 71 | ImportResolutionTest, ImportSemanticsTest, ImportIntegrationTest, ImportPublicationTest. |
| Typeinfo and providers | 5 / 45 | TypeInfoTest, ProviderResultsTest, ProviderIntegrationTest, BufferProvidersTest, NullableTypeProvidersTest. |
| Arrays and nullable values | 8 / 106 | ArraysTest, ArrayInferenceTest, ArrayBindingShapeTest, RectangularArraysTest, NullableBuffersTest, NullableRowsTest, GrowableArrayTypingTest, GrowableArrayReceptionTest. |
| Class/struct values and ownership | 7 / 107 | ClassLifetimeTest, ClassInheritanceTest, ClassValueIntegrationTest, StructEmissionTest, StructIntegrationTest, StructMethodEmissionTest, ArrayTemporaryCleanupTest. |
| Collection acceptance foundation | 2 / 19 | ArrayBackedCollectionTest and ArrayBackedCollectionStorageTest, including private slots, independent lengths, checked export, 1,000-cycle reuse and poisoned buffer release. |
| Shared compiler consumer | 7 / 57, up to date | Existing Shuttle suites; generic integration must preserve the manifest-independent compiler request API. |

Existing collection/array/struct fixtures execute on Windows x64/x86 and verify
IR plus objects across all nine ABI profiles in this full baseline. Native foreign
target coverage is limited to each existing suite's stated evidence. No target
has generic native or generic object evidence at Stage 0; the generic acceptance
sources remain unimplemented language inputs.

All five changed skills pass the full skill-creator validator with temporary
pinned PyYAML 6.0.3. Publication checks pass for 12 documents, 719 relative links
and 96 anchors, including balanced fences, final newlines and whitespace. They
also recheck all 396 source hashes and archived baseline XML hashes. Evidence is
stored in `build/generic-types-stage0/skill-validation.txt` and
`publication-validation.json`.

Both Stage 0 tasks are checked. Later generic stages, public List delivery and
growable-array Stage 8 remain unchecked. Temporary validation scripts/dependencies
are removed before completion. Stage 0 establishes a confirmed contract and
existing-compiler evidence; it adds no generic compiler or runtime support.
