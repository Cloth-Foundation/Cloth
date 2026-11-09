# Interface Stage 2 graphs and conformance

Stage 2 completed on 2026-11-08. Closed-type analysis gained interface
inheritance and class conformance. At this stage C4035 guarded the value and
call typing boundary. [Current Stage 3 boundary](stage-3.md) ·
[Implementation plan](../../../../todo/interfaces-plan.md) ·
[Confirmed design](design.md)

```cloth
typedef<interface> Read<T> { mt<T> read() }
typedef<interface> Extended<T> : Read<T> {}

typedef<class> Box<T> (def<T> value) : Extended<T> {
    override: mt<T> read() = self.value
}

fn take(Box<int> box, Read<int> view) {}
```

The closed catalog interns `Read<int>` once. Both conformance records for
`Box<int>` select its marked class method. A direct `Read<string>` clause would conflict
with the inherited `Read<int>` application even if their signatures were
identical.

## Graphs and type identities

- The original parent graph is checked before instantiation, including unused
  templates and cycles whose type arguments would grow at each step. Import
  cycles use the existing source graph rules.
- An application uses its original interface declaration and exact ordered
  arguments. The existing interner normalizes widths and nullable wrappers,
  preserves nominal and array identities, and enforces its dependency, nesting
  and instance bounds. The symbolic pass uses opaque owner parameters in a
  separate catalog, and closed applications are checked after substitution.
- Each class and interface has at most one application per original
  declaration in its full interface set. Class base conformance contributes to
  that set. Repeating the same application in direct clauses is an error.
  Identical applications through different paths merge. Competing applications
  retain source labels for the introducing clauses and their paths. Distinct
  private declarations with the same spelling retain separate identities.
  [Closed type analyzer](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/ClosedTypeAnalyzer.kt) ·
  [Conformance pass](../../../../Compiler/src/main/kotlin/cloth/semantic/interfaces/InterfaceConformanceAnalyzer.kt)

## Contract and body selection

- Requirement identity uses the method name and ordered exact parameter
  types, including nullability and array extents. Duplicate declarations at
  one owner fail after substitution. Overlapping requirements must agree on
  ordered results. Omitted results and a single `void` normalize to the same
  contract.
- A matching method declared by the implementing class requires `override:`
  even if a fallback exists. A marked method with no matching requirement or
  with different results fails. Matching uses ordinary methods. The class
  method can be private, and its parameter labels, optionality and argument
  defaults belong to its own direct call contract.
- For a new requirement, the nearest matching inherited method is eligible
  when it is public and has exact results. An inherited private implementation
  remains eligible for the original requirement it already satisfies. This
  includes a child interface view that inherits that same requirement.
  A marked subclass method replaces the selected class target.
- When a class target is absent, select the most-specific applicable interface
  fallback. A diamond shares its ancestor body. Unrelated competing defaults
  require a class implementation. A signature-only redeclaration that would
  remove an inherited fallback reports C3121 while re-abstraction remains open.
- A child interface uses its most-specific static declaration. Distinct
  inherited declarations with the same required parameter labels can share
  a static contract. Different labels or independent omission providers
  require a child redeclaration to choose its own contract. Each parent view
  retains its original contract.
  [Immutable records](../../../../Compiler/src/main/kotlin/cloth/semantic/interfaces/InterfaceModel.kt) ·
  [Static call rules](design.md#static-interface-call-contracts-confirmed-2026-11-08)

The records expose interface parents, the transitive ancestor set and static
members. A closed class maps each implemented interface and resolved
requirement to a source-linked class method or interface fallback. Stage 3
consumes these records for values, conversions, call binding and checking both
kinds of defaults. [Stage 3](stage-3.md)

## Diagnostics

| Code | Stage 2 condition |
| --- | --- |
| C3118 | Invalid header or repeated direct clause |
| C3119 | Interface inheritance cycle |
| C3120 | Competing applications of the same interface declaration |
| C3121 | Incompatible inherited results or static call contracts, or a signature-only redeclaration of a default |
| C3122 | Missing class implementation or fallback |
| C3123 | Missing or unmatched `override:`, or incompatible implementation results |
| C3124 | Competing interface fallback bodies |
| C4035 | Historical Stage 2 boundary before interface value and call typing |

All diagnostics stop on the first error. Substituted duplicate methods retain
C3083, and the shared generic resource limits retain their existing codes.
[Interface diagnostics](../../../../Compiler/src/main/kotlin/cloth/diagnostics/InterfaceErrors.kt)

## Verification

The offline run passed 263 tests across 28 suites in 1m 9s, including 19
interface tests. The native-free generic and import tasks also passed 86
and 36 tests respectively. All tasks reported zero failures, errors or skips.

```powershell
.\gradlew.bat :Compiler:genericSyntaxTest :Compiler:importSemanticsTest :Compiler:test --tests 'cloth.Interface*Test' --tests 'cloth.Class*Test' --tests 'cloth.Generic*Test' --tests cloth.ImportSemanticsTest --tests cloth.OptionalArgumentsTest --tests cloth.CallResolutionTest --offline --console=plain --no-daemon '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'
```

Interface tests verify conformance records, both forms of fallback, class and
interface inheritance, exact signatures, generic substitution and limits,
source labels and same-spelled identities in different files. The native-free
import fixture proves canonical diamond reuse and a conflicting substitution
through the same cyclic import graph. Compiler request tests check conformance
and C4035 before any artifact is created. Class and generic regressions run on
Windows x64 and x86 and verify IR and object emission on all nine supported
profiles. [Conformance tests](../../../../Compiler/src/test/kotlin/cloth/InterfaceSemanticsTest.kt) ·
[Import tests](../../../../Compiler/src/test/kotlin/cloth/ImportSemanticsTest.kt) ·
[Request tests](../../../../Compiler/src/test/kotlin/cloth/InterfaceNamesTest.kt)
