# Generic types Stage 3: callable and flow integration

This record preserves Stage 3's verification boundary. [Stage 4](stage-4.md) now
executes the closed models, including the examples below; C4033 remains only for
symbolic/unresolved native contexts.

Stage 3 checks generic declarations with unknown exact parameters, then checks
the required closed methods, constructors, destructors, defaults and bodies.
The [contract](semantics.md) owns the confirmed semantics;
[Stage 2](stage-2.md) retains the closed-type baseline. Native generic values,
code and metadata emission remain Stage 4. Public List delivery remains Stage 5.

## Supported checking

This complete source now passes `FunctionAnalyzer`. Building/running it still
stops at C4033 before native output:

```cloth
typedef<class> Box<T> (def<T> value) {
    pub mt<T> get() = value
    pub mt<T, T[]> pair(T input, opt<T> other ?: input) {
        def<T> deferred = { return other }
        return deferred, [value, input]
    }
}
fn main() {
    def number = Box<int>::new(1)
    def word = Box<string>::new("hello")
    def first = number.get()
    def second = word.get()
    number::delete
    word::delete
}
```

Methods retain ordinary exact arguments, optional/named placement, defaults,
ordered results, explicit nullable receptions and completion checks. Generic
struct aggregate contexts, array mutations/foreach, captures, operators, class
ancestry and explicit lifetime use the existing semantic pipelines. Arguments
remain invariant; a concrete instantiation does not repair an invalid template.

For example, an unused `Box<T>` method with `value + 1`, `value == other`,
`println(value)`, an arbitrary member, or `T::new`/`value::delete` fails without
a corresponding capability. Universal `T::type`, exact T copies/receivers/results
and supported enclosing array/nominal types are available. `T?` keeps ordinary
presence rules and normalizes when closed T is already nullable. It does not grant
unknown T a storage-size or equality provider. Inferred `def copy = value` remains
invalid when T could contain nullability; `def<T> copy = value` supplies context.

## Semantic ownership and source identity

[GenericSemanticAnalysis](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/GenericSemanticAnalysis.kt)
uses separate file bindings, catalogs, symbols and semantic models for declaration
and closed checking. `CTypeParameter` represents an unknown exact owner parameter:
identity includes its declaration/position, it may contain nullability, and it has
no numeric, printing, member or class capability. It cannot enter native lowering.
The symbolic pass includes unused templates and all ordinary source name checks.
The actual closed pass retains Stage 2's exact target-scoped identities.

The existing semantic tables key expression/scope facts by AST identity.
[GenericBodyViews](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/GenericBodyViews.kt)
therefore creates separate source-linked AST views for each owner. It copies
annotations, bodies, defaults, captures, operators and loop identities while
preserving original tokens/spans. It does not rewrite user source or mutate the
template AST. `viewOf`/`originalOf` retain the correspondence. CClass/CStruct
descriptors still own canonical closed identity/fields/base links and retain their
original declarations; body views do not introduce replacement nominal types.

Per-view type-reference facts supply complete substituted ValueTypes to the
shared TypeResolver. Signature keys retain exact ValueTypes rather than flattening
argument display names. Distinct closed overloads coexist; substitutions that
make two declarations identical still produce the normal duplicate diagnostic.
Candidate trials retain their existing isolated fact/commit behavior. Operator
inference and width-specialization bodies cannot donate facts to another owner.

Owner parameters remain separate from value names, including bare `is T` targets
and type receivers. Presence analysis reads substituted parameter/local/result,
cast, deferred-result and generic class-check types instead of assuming raw T
spelling determines nullability or class identity. Completion, invalidated captures,
nullable payload reads, metadata and deleted-object facts stay with their views.
Private access belongs to the original class declaration across its instances;
value type compatibility still uses the exact closed identity.

Imports retain the original defining-file contexts for private helper types and
functions. A caller-private actual argument retains its own identity. The
maintained imported List fixture fully checks its three List owners and three
private slot owners, including copies and rectangular array export, without
shipping or executing a public standard-library List.

## Visibility, resources and boundaries

C3117 / PRIVATE_GENERIC_SIGNATURE rejects private helpers in public generic
fields, constructor parameters, method parameters/results and operator selectors.
Public struct methods inherit their owner's visibility. Inferred public operator
results are checked on the canonical symbolic owner; this avoids rejecting a
client's private actual type supplied through an otherwise valid T signature.
Private storage remains allowed. Existing import privacy errors remain unchanged.

Stage 2's closed 64-step/4,096-instance budgets and 256 argument layers remain.
Symbolic checking has separate bounded expansion: C3115 /
GENERIC_TEMPLATE_DEPENDENCY_LIMIT for 64 symbolic dependency steps and C3116 /
GENERIC_TEMPLATE_INSTANCE_LIMIT for 4,096 symbolic keys. These are Stage 3
implementation policies, not extra source syntax or charges against the closed
instance quota. Even an unused expanding template fails with one controlled
diagnostic. Same-key reuse still precedes budget charging.

Existing inferred numeric-width operators retain call-time width/range checking.
Their generic declaration check uses the widest supported integer/float format
(65536/64 bits), leaving owner T opaque. Each requested actual width is checked
separately. Human-facing generic type display is iterative and bounded at 4,096
UTF-16 units, with an ellipsis that preserves surrogate pairs; it cannot expand
a shared argument DAG without a bound. Canonical metadata and type identity are
separate and are not truncated by that display policy.

`DeclarationAnalyzer` prepares checked symbolic declarations and the closed body
views. `FunctionAnalyzer` now returns successful generic semantic models.
`EmitFunctions.validate` owns C4033, after semantic and entry checks and before
native output/link work. Standalone legacy type/signature APIs without a generic
context retain their application guard. No generic IR/object/executable support
is claimed. Constraints, generic interfaces/functions/method parameters, variance,
argument inference and Bootstrap migration remain deferred.

## Verification (2026-11-08)

The final focused run passes 94 tests across nine suites (28s), with zero
failures/errors/skips. It covers the four new generic suites, the updated stage
boundary, closed types, class inheritance, deferred blocks and optional arguments.
The native-free generic task passes 77 tests across nine suites, and the separate
native-free import task passes 33. Their repeated suites are not additional
unique Compiler tests.

The new suites are
[GenericCallableTypingTest](../../../../Compiler/src/test/kotlin/cloth/GenericCallableTypingTest.kt) (13),
[GenericCallableFlowTest](../../../../Compiler/src/test/kotlin/cloth/GenericCallableFlowTest.kt) (9),
[GenericOverloadTypingTest](../../../../Compiler/src/test/kotlin/cloth/GenericOverloadTypingTest.kt) (7), and
[GenericCallableImportsTest](../../../../Compiler/src/test/kotlin/cloth/GenericCallableImportsTest.kt) (3).
They check unused-template failures, two simultaneous owners, nullable substitution,
defaults/results/captures, loops, invalidated presence proofs, class lifetime,
overload trials, operators/widths, metadata isolation, private scopes/surfaces,
source AST preservation, first-error order and bounded argument display. Exact
symbolic quota tests accept 64 dependencies/4,096 symbolic keys, reject the next
symbolic key, and independently accept 4,096 closed keys alongside symbolic work.

Ten CLI probes check valid semantic programs reaching C4033, recursive class
reuse, unknown names, unconstrained operations, visibility, invalidated proofs,
invariance, both symbolic limits and deleted aliases. Every probe returns 1 with
one expected diagnostic and no artifact. Focused XML, SHA-256 manifests and CLI
logs/summaries are archived under `build/generic-types-stage3/`.

The boundary suite rejects generic object output on all nine ABI profiles after
semantic checking. Its ordinary compatibility program executes on Windows x64/x86;
Stage 2's LLVM type-shape checks remain distinct from native generic execution.

The final full regression passes in 8m 54s on the final production/test sources:

```powershell
.\gradlew.bat :Compiler:test :Compiler:genericSyntaxTest :Compiler:importSemanticsTest :Shuttle:test --offline --console=plain --no-daemon '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'
```

| Task | Suites | Tests | Result |
| --- | ---: | ---: | --- |
| Compiler test | 176 | 1,906 | Fresh execution; zero failures/errors/skips. |
| Compiler genericSyntaxTest | 9 | 77 | Up to date from the final focused native-free run; zero failures/errors/skips. |
| Compiler importSemanticsTest | 1 | 33 | Up to date from the final focused native-free run; zero failures/errors/skips. |
| Shuttle test | 7 | 57 | Fresh execution; zero failures/errors/skips. |

The four new suites add 32 tests; the existing boundary suite now accepts generic
semantic models and checks C4033 at emission. The Unicode display regression is
included in the final passing run. `full-summary.json` records exact task statuses,
timestamps and per-suite hashes; archived XML copies match their originals.
Native-free tasks repeat existing suites rather than adding unique Compiler tests.

All 11 changed skills pass the bundled validator with temporary pinned PyYAML
6.0.3. Publication checks cover 20 documents, 954 local links, 117 heading anchors,
balanced fences, whitespace and 28 stage source/build files. The manifest verifies
eight checked generic tasks (Stages 0-3), while the array plan stays at 34 and
public List remains absent. Temporary probes, scripts and dependencies are removed
before completion. No Bootstrap or standard-library source is migrated.
