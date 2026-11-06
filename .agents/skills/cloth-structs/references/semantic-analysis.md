# Struct semantic analysis (Stage 2)

Integrated 2026-11-04. The [accepted contract](semantics.md) defines the
language behavior. Stage 2 checks programs and records plans for lowering.
The [Stage 3 storage record](local-storage.md) owns the current runtime scope.

```cloth
pub typedef<struct> Point { int x, int y }
typedef<struct> Cell { Point? position, int[2] samples }

fn<typeinfo> update(final Cell cell) {
    if (cell.position != null) {
        cell.position.x += 1
    }
    return cell.position::type
}
```

## Nominal types and contextual literals

- [NamedTypes](../../../../Compiler/src/main/kotlin/cloth/semantic/declarations/NamedTypes.kt)
  collects names before resolving fields or function signatures. It builds one
  `CStruct` per declaration and reuses it in arrays, nullable values, fields and
  calls. Duplicate names, void fields, unknown field types, omitted dimensions
  and dependency cycles stop analysis. A cycle through an array or nullable
  wrapper receives a single diagnostic with field labels.
- [ExpressionTyping](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/ExpressionTyping.kt)
  uses the expected nominal type for a literal. Each declared field supplies
  context for its expression, including nested literals, contextual numbers,
  arrays and null. Count and type mismatches carry source labels. Fixed extents
  use ordinary array checking. A source array with unknown extents carries a
  shape obligation through the field's declared type for lowering.
- [FunctionAnalyzer](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/FunctionAnalyzer.kt)
  checks literal arguments in the existing isolated candidate models. Each
  candidate supplies its own expected type. The selected candidate commits its
  nested literal, field and assignment facts. Distinct nominal contexts that
  fit equally well remain ambiguous. The same expected type propagation works
  for defaults, named arguments, conditional values, deferred returns and
  positional result reception.
- Named `Point::type` can describe the declaration directly. A visible local
  or parameter with that name wins ordinary value lookup. Functions with the
  same spelling still resolve in call position. Nominal identity is retained in
  metadata, including nullable and array types described by typed values.

## Stored places and flow

- Field reads resolve the field descriptor and declared type. A write resolves
  a stored root binding and ordered field and index steps. The
  [place plan](../../../../Compiler/src/main/kotlin/cloth/semantic/StructPlans.kt)
  retains each original index expression once and the declared destination type.
  Assignment accepts the field type, and compound operators use ordinary
  arithmetic checks. Fields of final bindings remain writable. Writes through
  temporary or deferred struct results fail, and earlier parameter fields in a
  default follow the existing read-only policy.
- [NullFlowAnalysis](../../../../Compiler/src/main/kotlin/cloth/semantic/flow/NullFlowAnalysis.kt)
  tracks stable paths by binding identity, field names and constant indices.
  Assignment to a path clears proofs for that path and descendants. Unknown
  write indices conservatively overlap any index at that position. A dynamic
  read index supplies no stable field proof. Unrelated sibling writes preserve
  a proof. Joins, repeated loop paths and transitive deferred writes use the
  same invalidation rule. Deferred bodies can establish their own checks.
- A field with a valid presence proof uses its payload for ordinary reads.
  `::type` still describes the declared field type, and nullable scalar
  `::size` keeps its storage meaning. Field values of type `typeinfo` carry
  unknown represented-type facts through aggregate storage. An indexed read
  alone supplies no presence proof for a nullable receiving binding.

## Diagnostics and emission boundary

C3078 rejects duplicate struct names. C3079 locates invalid field declarations,
accesses or values. C3080 explains recursive field type cycles. C3081 reports
missing literal context or wrong field counts. C3082 rejects writes without
a stored root. Existing strict type, array, nullability, equality and provider
diagnostics apply where relevant. Whole-struct equality, printing and size
queries retain the agreed deferred scope.

At Stage 2, valid source units with structs reached C3077 during emission
validation, before LLVM lowering, toolchain lookup or output creation. Stage 3
introduced [local execution](local-storage.md), and
[Stage 4](function-integration.md) completes source function and traversal support.
The production body fixtures use source units with struct declarations.

## Verification

All 171 focused tests passed on Windows. This includes the struct parser,
semantic, call, flow and emission gate suites and regressions for declaration
scopes, overloads, optional arguments, multiple returns, nullable values and
rows, type metadata and do-while flow. The CLI gate test checks status 1, one
diagnostic and no output creation. The full compiler suite passed all 1,320
tests with zero failures or skips.

```powershell
.\gradlew.bat :Compiler:test --tests 'cloth.Struct*Test' --tests cloth.DeclarationScopesTest --tests cloth.CallResolutionTest --tests cloth.OptionalArgumentsTest --tests cloth.MultiReturnTypingTest --tests cloth.NullabilityRulesTest --tests cloth.NullableRowsTest --tests cloth.TypeInfoTest --tests cloth.DoWhileFlowTest --offline --console=plain --no-daemon
```

Full regression command

```powershell
.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon
```

Struct verification at this stage covers source semantics and the boundary
before native emission.
