# Generic types Stage 2: closed types and bounded instantiation

This is the historical closed-type milestone; [Stage 3](stage-3.md) records
subsequent callable/flow integration. [Stage 4](stage-4.md) owns the current
native execution boundary.

Stage 2 implements target-scoped closed class/struct identities and substituted
field, base and declared callable types. The [contract](semantics.md) owns the
language rules; [Stage 1](stage-1.md) records the preceding syntax/name milestone.
Callable body checking, overload/flow specialization and native generic programs
remain later work. Compilation reaches C4033 only after the closed-type pass.

## Supported analysis

This source has complete closed types in `ClosedTypeAnalyzer`; compiling it as a
program still stops at C4033:

```cloth
typedef<struct> Slot<T> { T value }
typedef<class> Box<T> (def<T?> value ?: null) {
    pub mt<T?> get() = value
}
fn take(Slot<int> first, Slot<string> second, Box<int?> maybe) {}
```

The two Slot owners share their original declaration AST and have different
nominal identities and field types. Box's field, primary constructor parameter
and declared get result each contain one `int?` carrier. Direct `int??` remains a
syntax error. An unused generic owner remains open; this stage does not check its
arbitrary value expressions with symbolic T.

[ClosedTypeAnalyzer](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/ClosedTypeAnalyzer.kt)
reuses `FileBindings` and `GenericNameModel` from the same
target-aware analysis. Its interner keys the original nominal declaration
identity plus ordered exact normalized ValueTypes. It does not key on display
text, native width or a generated source name. Primitive default/explicit widths
normalize through `TypeResolver`; signedness, usize/isize, nullable positions,
array extents and nominal identities remain exact. Class argument invariance
coexists with ordinary ancestry between closed owners such as
`Derived<int> : Base<int>`.

Closed `CClass`/`CStruct` descriptors retain `genericDefinition`, `typeArguments`
and the original `declaration`. Their field objects/types and class base links
belong to the closed descriptor. No AST cloning, source concatenation or shared
template mutation is involved. Source and metadata spellings include arguments;
metadata uses each argument's canonical spelling. Equal display strings from
different private declarations still denote different types.

The shared `TypeResolver` accepts a substitution callback for parameters and named
applications, retaining its existing numeric, void and target-sized array checks.
It wraps substituted values in the written array/nullability order. Applying `?`
to an already nullable actual value reuses that carrier. `T?` with an array-valued
T wraps the array rather than changing element nullability. Unknown extents are
retained, not inferred from a specialization's initializer.

[ClosedTypeModel](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/ClosedTypeModel.kt)
exposes completed instances, ordinary nominal descriptors,
top-level function signatures, per-owner reference facts and substituted named
type receivers. Each instance has a separate environment, fact map and declared
signature list, including primary/secondary constructors, destructors and methods.
Defaults/local annotations/receivers bind to that same environment. Bodies,
default expressions and inferred callable results are not type-checked here.
An empty declared result list still has its original meaning for the later body
checker. Existing `int<auto>`/`float<auto>` operator annotations remain explicit
`ClosedTypePattern.OperatorWidth` patterns until call specialization supplies the
width; ordinary closed annotations are `Exact(ValueType)`.

Template names resolve in their defining file. Actual parameter types retain
their caller identity when substituted into private helpers. Repeated/circular
import routes reuse one closed instance. Same-spelled private helpers in two
libraries remain distinct. This implements type identity/substitution, without
new import alias syntax or public generic signature leak checking.

## Worklist, recursion and controlled limits

Instances move through `DECLARED`, `RESOLVING` and `COMPLETE`. Registration and
interning happen before dependent fields/signatures are discovered. A same-key
class requirement reuses its provisional identity before charging a budget step.
The discovery worklist keeps an explicit requiring chain; it does not recursively
expand owner definitions on the JVM stack. Complete field graphs precede
inheritance and struct-cycle validation, after which all returned records are
marked complete.

The established cycle rules still apply: class field identity stops inline
traversal, inheritance cycles fail with C3088, and struct cycles through struct,
array or nullable fields fail with C3080. Final/non-class bases remain invalid.
Cycle validation is iterative. Diagnostic messages use declaration names without
expanding large argument display trees while reporting a cycle or final base.

| Guard | Boundary | Diagnostic |
| --- | --- | --- |
| New generic dependencies | 64 new instances along a requiring chain succeed; the 65th fails. Same-key reuse consumes no extra step. | C3112 / GENERIC_DEPENDENCY_LIMIT |
| Distinct generic instances | 4,096 distinct class/struct keys succeed; the 4,097th fails. Repeated routes, ordinary types and open templates consume no instance. | C3113 / GENERIC_INSTANCE_LIMIT |
| Argument structure after substitution | 256 generic application layers succeed; the 257th fails before interning. Arrays/nullable wrappers preserve the contained argument depth. | C3114 / GENERIC_SUBSTITUTION_NESTING |

Source nesting still uses Stage 1's C2012 guard. Closed-argument normalization
precedes key lookup; newly discovered dependencies carry their requiring owner's
chain after arguments have been resolved. Nested argument keys already interned
while resolving an application are reused. Limit errors retain the requiring
application, template and distinct dependency source labels as one diagnostic.
No host overflow, diagnostic accumulation or partial native output is substituted
for these guards.

`DeclarationAnalyzer` invokes the closed-type pass for generic programs before
the C4033 boundary. Wholly non-generic compilation retains the existing
`NamedTypes` and body pipelines. Legacy standalone `TypeResolver`/`SignatureType`
APIs still reject applications without the new closed-type context. Later stages
must consume these closed owners/facts rather than erase their arguments or store
specialization facts on the shared declaration AST.

## Verification (2026-11-08)

The focused run passes 42 tests in six suites (23s), with zero failures/errors/skips:
[GenericClosedTypesTest](../../../../Compiler/src/test/kotlin/cloth/GenericClosedTypesTest.kt) (11),
[GenericInstantiationLimitsTest](../../../../Compiler/src/test/kotlin/cloth/GenericInstantiationLimitsTest.kt) (5),
[GenericClosedImportsTest](../../../../Compiler/src/test/kotlin/cloth/GenericClosedImportsTest.kt) (4),
[GenericClosedLayoutsTest](../../../../Compiler/src/test/kotlin/cloth/GenericClosedLayoutsTest.kt) (1),
GenericNamesTest (14) and GenericStageBoundaryTest (7).
The separate `genericSyntaxTest` task passes
45 tests in five suites with native binary jars removed and JavaCPP loading
disabled. These are repeated task executions, not 45 additional unique tests.

The tests cover normalized/exact keys, invariant arguments and concrete ancestry,
all admitted type families, nullable/array substitution, separate signature/body
annotation facts, defaults and receiver types, preserved ASTs, provisional class
recursion, existing layout cycles, all three exact resource boundaries, target
isolation and defining-file imports/private helpers. The maintained imported List
fixture produces the three List and three private slot instances without adding
a public standard-library source or checking/executing its method bodies.

LLVM type shapes for closed fields, nullable wide slots, string/count storage and
inherited class storage are checked on all nine ABI profiles. This is native LLVM
type construction, not generic IR, objects or program execution. The existing
boundary suite still verifies C4033 before object output on all nine profiles and
executes ordinary compatibility programs on Windows x64/x86.

Eight CLI probes cover C4033 (ordinary and recursive closed types), C3112, C3113,
C3114, C3080, C3011 and C3109. Each returns 1 with one expected diagnostic and no
output artifact. Focused XML, per-suite hashes and CLI logs/summaries are archived
under `build/generic-types-stage2/`.

The final full regression passes in 9m 29s:

```powershell
.\gradlew.bat :Compiler:test :Compiler:genericSyntaxTest :Compiler:importSemanticsTest :Shuttle:test --offline --console=plain --no-daemon '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'
```

| Task | Suites | Tests | Result |
| --- | ---: | ---: | --- |
| Compiler test | 172 | 1,874 | Fresh execution; zero failures/errors/skips. |
| Compiler genericSyntaxTest | 5 | 45 | Fresh native-free execution; zero failures/errors/skips. |
| Compiler importSemanticsTest | 1 | 33 | Fresh native-free execution; zero failures/errors/skips. |
| Shuttle test | 7 | 57 | Fresh execution; zero failures/errors/skips. |

The final source/test changes precede that run. All copied final XML matches its
original SHA-256; `full-summary.json` records task statuses, timestamps and hashes.
The two native-free tasks repeat existing suites. They are not additional unique
Compiler tests. The 22 new tests comprise four new suites and one new boundary
test. Focused evidence remains separately archived.

All seven changed skills pass the bundled skill-creator validator using temporary
pinned PyYAML 6.0.3. Publication checks cover 15 documents, 817 local links,
99 heading anchors, balanced fences, whitespace and 15 stage source/build files.
`publication-validation.json` records hashes and verifies six checked generic
tasks (Stages 0-2), with the array plan still at 34 and public List absent.
Temporary probes, validator scripts and dependencies are removed before completion.

The public List gate, symbolic declaration checking, callable/flow specialization,
public signature visibility, native generic code/metadata emission, constraints,
generic interfaces/functions/method parameters and Bootstrap migration remain
outside this stage.
