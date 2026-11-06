# Basic class typing

Stage 2 landed on 2026-11-05 and covers semantic analysis of basic classes. The
[class declaration and constructor rules](semantics.md#class-declarations-and-constructors)
and [object lifetime rules](semantics.md#object-lifetime-and-nullability) define
the source behavior. See [Stage 1 syntax](class-syntax.md) and the
[basic classes plan](../../../../todo/basic-classes-plan.md).

## Name and type resolution

- [NamedTypes](../../../../Compiler/src/main/kotlin/cloth/semantic/declarations/NamedTypes.kt)
  registers class and struct names together. Class types have nominal identity
  before fields resolve. A class value ends inline layout recursion, so a
  struct can contain a class value whose instance contains that struct.
  Inline struct cycles still fail.
- [DeclarationAnalyzer](../../../../Compiler/src/main/kotlin/cloth/semantic/declarations/DeclarationAnalyzer.kt)
  registers primary and secondary constructor signatures, instance fields,
  direct `mt` overloads and the optional destructor. Header fields precede
  ordered body initializers. Plain inputs are constructor parameters only.
  Defaults and delegation operands have no `self`. Field initializers can read
  only initialized fields through `self`. Passing the instance and using it as
  a method receiver require a fully initialized instance.
- Member access uses the declaring class for privacy. Lexical bindings take
  precedence over implicit `self` lookup. During a field initializer, an
  unqualified header parameter name selects the parameter, whose array value
  is independent of the initialized instance field. Access through `self`
  selects the field and allows reads only. Method candidates outside the owner
  include public overloads only. `::new` resolves the class name in the type
  namespace even when a local uses the same name.

## Typed call and receiver plans

- [FunctionAnalyzer](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/FunctionAnalyzer.kt)
  binds creation and delegation with the shared argument binder. A creation
  call selects one constructor and produces the class type. A secondary
  delegation clause binds only to the primary signature. Explicit `null`
  occupies its supplied slot. Omitted slots retain their default plans.
- [ClassModel](../../../../Compiler/src/main/kotlin/cloth/semantic/ClassModel.kt)
  records object identity receivers and field projections, including stored
  struct methods inside class fields. Ordinary struct places keep their own
  address-based model. Preserve the distinction through lowering.
- Deletion is a void operation. The typed plan identifies the class and its
  destructor and marks whether the operand can be absent. An absent nullable
  operand has no destructor work. The later
  [class equality record](class-equality.md) covers logical and identity comparisons.
  Stage 4 integrates conservative invalidation of field proofs through aliases.
  [Class values](class-values.md)
- C3087 through C3091 cover conflicting type names, invalid class members,
  private access, invalid lifecycle operands and partial instance use.
  Constructor calls preserve the shared arity, binding, type and ambiguity
  diagnostics. Stage 2 gated class emission with C3086. Stage 3 lifts the
  gate for its supported storage and lifecycle forms. [Class execution](class-lifetime.md)

## Verification

The Windows focused run passed 340 tests across 35 suites with zero failures,
errors or skips. [ClassSemanticsTest](../../../../Compiler/src/test/kotlin/cloth/ClassSemanticsTest.kt)
checks constructor choices, field order, recursive type identity, visibility,
receiver plans, nullable deletion plans and first-error diagnostics.
The full Windows run passed 1,370 tests across 117 suites in 5m 38s, with zero
failures, errors or skips.

```powershell
.\gradlew.bat :Compiler:test --tests 'cloth.Class*Test' --tests 'cloth.Struct*Test' --tests 'cloth.Function*Test' --tests 'cloth.OptionalArguments*Test' --tests 'cloth.Nullability*Test' --tests 'cloth.MultiReturn*Test' --tests cloth.CallResolutionTest --tests cloth.TypeInfoTest --tests cloth.NullableTypeProvidersTest --tests cloth.TargetLayoutTest --offline --console=plain --no-daemon
.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon
```

Stage 3 adds class allocation, method invocation, destructor execution and
stale-alias checks. [Class execution and lifetime](class-lifetime.md)
