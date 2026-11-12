# GEN-6 Stage 1 · Generic virtual declarations

Class methods with `virtual:` and `override:` can declare method-owned type
parameters. Both declarations use `pub`. The override matches the type
parameter count, value parameter types, and ordered result types. Substitute
the declaring class arguments and compare method parameters by position.
The override can give those positions its own names.
[Generic virtual override example](generic-functions.md#generic-virtual-overrides)

The base declaration owns bounds, labels, and defaults. The override writes
its type parameter names and required value parameters. Its body is checked
with the inherited bounds even when the method is unused. For example, if
`Entity<E>` declares `T: Named<E>` and `Player` extends `Entity<int>`, the
override's corresponding parameter can use `Named<int>` operations.

Inherited bounds resolve in the base declaration's scope with its exact
owner arguments. A constructor guarantee keeps its labels and defaults,
including private helpers from the defining file. A descendant of an
override inherits the original virtual declaration's contract.

C3133 reports an invalid generic virtual declaration or override. It covers
a missing or ambiguous base match, result mismatches, missing visibility or
prefix, and bounds or defaults redeclared on an override. Stage 1 used
C4033 as the boundary for selected calls. Stage 2 specializes the
virtual base contract and executes the selected body.
[GEN-6 Stage 2](generic-virtual-stage-2.md)

## Compiler ownership

ClassParser admits method type parameter lists after either prefix.
VirtualMethods matches generic templates after the class ancestry is known.
GenericSignatureKeys compares parameters and results using shared ordinal
slots and the exact enclosing type arguments.
[ClassParser](../../../../Compiler/src/main/kotlin/cloth/parser/subs/ClassParser.kt) ·
[VirtualMethods](../../../../Compiler/src/main/kotlin/cloth/semantic/declarations/VirtualMethods.kt) ·
[GenericSignatureKeys](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/GenericFunctionSignatures.kt)

ClosedTypeAnalyzer checks symbolic virtual bodies after matching the base
contract. The base and override's type parameter symbols refer to the same
arguments in an override's body view. The ordinary bound checks and
constructor-guarantee binding then apply. GenericBodyViews keeps inherited
constructor defaults in the base class's scope.
[ClosedTypeAnalyzer](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/ClosedTypeAnalyzer.kt) ·
[GenericBodyViews](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/GenericBodyViews.kt)

## Verification

On 2026-11-12 with Java 25, the Windows run passed 209 native-free generics
tests, 113 import tests, and 657 focused Compiler tests, with zero failures
or skips. The documented example compiled through the CLI with an entry point
that constructed and deleted a `Player`.

GenericMethodTypingTest covers matching through specialized ancestors,
inherited numeric, nominal, nullable, array, and constructor bounds, imported
defaults, and the generic virtual call diagnostic. GenericMethodEmissionTest
and VirtualMethodsTest verify ordinary generic overloads and virtual dispatch
on Windows x64 and x86. The virtual regression fixture also emits objects for
the nine supported profiles.
[GenericMethodTypingTest](../../../../Compiler/src/test/kotlin/cloth/GenericMethodTypingTest.kt) ·
[GenericMethodEmissionTest](../../../../Compiler/src/test/kotlin/cloth/GenericMethodEmissionTest.kt) ·
[VirtualMethodsTest](../../../../Compiler/src/test/kotlin/cloth/VirtualMethodsTest.kt)
