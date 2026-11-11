# Cloth generic types: Stage 0 contract

The user confirmed the first generic slice through six focused choices on
2026-11-08. These rules describe the complete intended first slice.
[Stage 1](stage-1.md) implements parsing and lexical name identities.
[Stage 2](stage-2.md) adds closed types, substituted fields/base links/declared
signatures, nullable normalization and controlled instantiation.
[Stage 3](stage-3.md) implements symbolic declaration and closed callable/flow
checking, public signature visibility and isolated body facts.
[Stage 4](stage-4.md) executes closed native values, storage and exact metadata.
[Stage 5](stage-5.md) supplies the public standard-library List using these rules.
Collection protocols and Bootstrap migration remain separate.
[Stage 6](stage-6.md) completes integration/publication, with a
[user guide](../../../../docs/generics.md), [caller audit](caller-audit.md) and
[separate follow-ups](../../../../todo/generic-follow-ups.md). It adds no further
generic capability to this confirmed first slice.
The [plan](../../../../todo/generic-types-plan.md) stages that work.
[Acceptance fixtures](acceptance-fixtures.md) make the contract reviewable;
[Stage 0](stage-0.md) records the existing compiler baseline.

## First slice and syntax

Support named generic **classes and structs**, with explicit ordered type arguments
and invariant closed identities. For example:

```cloth
typedef<struct> Slot<T> { T value }
pub typedef<class> Box<T> (def<T> value) {
    pub mt<T> get() = value
}
```

Application uses `Box<int>`, `Slot<string>` and nested forms such as
`Box<Box<int[]>>`. Constructor/type receivers include `Box<int>::new` and
`Box<int>::type`. Numeric width suffixes such as `int<129>`, declaration-kind
headers such as `typedef<class>`, method return annotations and ordered result
lists keep their own grammar and meaning.

The argument list is required whenever a generic name is used as a value type,
including inside its owner. `Box::new(1)` does not infer T; a bare Box parameter
is not a concrete type. Importing the template name for later explicit application
is allowed. Constructor/destructor declaration names and implicit `self` retain
their existing owner syntax; they are not omitted-argument applications.

`Box<Child>` does not convert to `Box<Parent>` just because Child derives from
Parent. Ordinary class ancestry of concrete owners is separate from variance in
their arguments. Ordered argument identities, fixed/growable extents, nullability,
signedness and usize/isize identity are retained exactly.

Constraints, generic interfaces, generic functions and method-owned type
parameters, argument inference, variance and type-argument defaults are later
work. Generic enums, states and higher-kinded parameters also remain outside
this slice. Preserve the intended constrained examples in
[Test.co](../../../../examples/Test.co), including `Entity<T: Entity<T>>` and
`IHealth<T: Number>`; the first slice does not erase or implement those features.

## Initial constraint scope

The initial constraint design allows one optional bound per type parameter.
A bound can name `Number`, a class, or an interface, as in
`T: Number`, `T: Entity<T>`, and `T: IHealth<float>`. Combined bounds and
their syntax are follow-up work.

[GEN-1 Stage 1](constraints-stage-1.md) implements parsing and name resolution
for these forms. [GEN-1 Stage 2](constraints-stage-2.md) checks satisfaction
during declaration checking and specialization.

## Number constraint design

The bare name `Number` in a bound selects the built-in constraint. Other
type references resolve in the ordinary type namespace.

`Number` is a built-in constraint that Cloth's primitive numeric types
satisfy automatically. It grants explicit numeric casts and same-type
operators that every admitted type supports. In `IHealth<T: Number>`,
`0 -> T` uses the cast capability. With two operands of type `T`, a generic
body can use operators such as `+` and `<`. A literal used as a `T` operand
needs an explicit cast, as in `value + (1 -> T)`. The cast follows the
ordinary numeric rules for the concrete type.

`Number` also grants ordinary numeric formatting. `value::str`,
`println(value)` and string interpolation use the concrete numeric type's
formatter. [Output semantics](../../cloth-output/references/semantics.md)
[Number bound implementation](number-bound-operations.md)

## Class bound design

A class bound such as `T: Entity<T>` makes the bound's public members
available directly in the generic body. A value of type `T` can call
`value.isAlive()` through that bound and keeps its exact type `T`.
Receiving a class or interface value uses ordinary widening from a proven
nominal bound, including when passing an argument or initializing a binding.
[Class member lookup](../../cloth-language/references/class-inheritance.md#supported-slice)

A class-backed interface type satisfies `T: Object` when it can implicitly
widen to `Object`. The type argument retains its exact interface type.
[Interface widening](../../cloth-interfaces/references/stage-6.md#object-operations-and-proofs)

Calls through the bound select an overload during declaration checking and
keep that selection when specializing `T`. The selected call contract supplies
argument labels and defaults. Virtual calls dispatch to the applicable override.
[Class virtual dispatch](../../cloth-language/references/class-inheritance.md#ordinary-class-virtual-dispatch-design)

A class bound permits explicit `value::delete` on a value of type `T`.
Ordinary class lifetime checks and destructor dispatch apply.
[Class lifetime](../../cloth-language/references/semantics.md#object-lifetime-and-nullability)

A class-bound `T` supports ordinary class equality. The `==` and `!=`
operators use checked class dispatch. For `===`, declaration checking selects
an overload through the bound and specialization keeps that selection. When no
overload matches through the bound, `===` compares instance identities.
[Class equality](../../cloth-language/references/class-equality.md)

A class-bound `T` supports ordinary class formatting. `value::str`, output,
and interpolation dispatch through the checked receiver to the formatter for
its initialized class.
[Class formatting](../../cloth-language/references/class-formatting.md)

A call to `T::new(...)` requires a separate guarantee that every admitted `T`
has the constructor signature the call uses. The syntax for that guarantee
is open.

## Interface bound design

An interface bound such as `T: IHealth<float>` admits the exact closed
interface, interfaces that extend it, and classes that conform to it directly
or through inheritance. Conformance preserves the bound's exact type arguments.
[Interface conformance](../../cloth-interfaces/references/design.md#one-application-per-interface-declaration)

An interface-bound value supports explicit deletion, equality, and formatting
under the ordinary rules for a class-backed interface view. The value keeps
its exact type `T`.
[Interface value operations](../../cloth-interfaces/references/stage-6.md)
[GEN-1 Stage 3](constraints-stage-3.md)

## Checking bound satisfaction

An application of a constrained generic must establish that its type
arguments satisfy the corresponding bounds. For example, a generic declaration
using `IHealth<T>` must prove `T: Number` when `IHealth` declares `N: Number`.
Check that proof during declaration checking, including for unused declarations.

A symbolic argument proves a requirement through its own declared bound.
Specialization checks the exact substituted argument using numeric membership
or ordinary class and interface widening. `Player` satisfies `T: Entity<T>`
through the declared `Entity<Player>` base. A subclass used as `T` must prove
the application with that subclass as its exact argument.
[Stage 2 proof rules](constraints-stage-2.md)

## Parameter scope

An owner parameter is visible throughout that class/struct's header and body:
fields, constructor inputs, base type uses, initializers, methods, defaults,
operators, local annotations, captures and result lists use the same parameter
identity. Scope does not imply that a particular deferred feature is implemented.

Type lookup prefers the nearest owner parameter before outer/file/imported type
names. A parameter may shadow an outer type. Its identity belongs to its owning
declaration and position, not just the spelling T. Separate owners' T parameters
are different symbols. Duplicate owner parameters and a parameter matching the
owner's name, such as `Box<Box>`, are errors.

Ordinary value names remain separate from type lookup. A value parameter named T
does not replace the type parameter T in an annotation. Built-in keyword grammar
and existing value shadowing rules are unchanged. Expression receivers retain
existing value-first provider lookup; type applications/annotations remain type
positions. This slice adds no nested type or generic method syntax.

## Declaration and closed-body checking

Check templates when declared, even when no closed use exists. Resolve names,
check structure and validate bodies with each unconstrained type parameter treated
as an unknown exact type. Do not defer undefined names or invalid operations until
an arbitrary later instantiation.

Unconstrained T permits operations justified for an arbitrary admitted type:
receiving/storing/copying a T value, passing it to a T position, returning it,
forming supported enclosing types and universal type metadata. It does not imply
numeric operators, equality, printing, arbitrary members, class construction or
class deletion. A template using those operations needs a separately designed
constraint/capability facility; selecting int later does not repair an invalid
unconstrained declaration.

After substitution, check each required closed instance again with actual types,
flow facts and receiving shapes. Keep declaration facts, overload trials and
closed-specialization facts separate. Neither symbolic checking nor one successful
instance may donate layout, presence, metadata or lifetime assumptions to another.

Existing explicit receiving rules still apply. In a generic body, `def<T> copy =
value` supplies a receiving type when T may contain nullability; generic support
does not silently permit unproven inferred nullable bindings. A closed body may
need additional shape/capability checks after substitution. Argument evaluation,
defaults, buffered snapshots, ordered results and explicit class lifetime retain
their existing rules.

## Admitted arguments and nullable substitution

Accept ordinary first-class value types: primitive scalar identities, strings,
typeinfo, enums, classes, structs, nullable types, arrays with fixed/growable
extents, target-sized integers and nested closed generic types. Closed executable
instances contain no unsolved parameters. Open dependent uses such as `Slot<T>`
are allowed inside an owner that declares T.

Reject void, a bare generic template and omission-only `opt<T>` wrappers as value
arguments. Internal null-literal/bottom sentinels are not source type arguments.
Optional parameter omission remains distinct from nullable values.

Nullable substitution is idempotent at the wrapper being applied. With T = int?,
the written `T?` resolves to int?, without a second nullable carrier. With T =
int[], `T?` is a nullable array. With T = int?[], `T?` adds outer array nullability
and preserves nullable elements. Preserve suffix positions and C dimension order
when wrapping an array-valued parameter.

Directly written `int??` and duplicate nullable markers remain syntax errors.
This is normalization after substitution, not permission to spell duplicate
markers or weaken payload proofs. A bare T need not be non-nullable; it does not
grant nullable-specific operations that are invalid for other admitted arguments.

## Visibility and imports

A public generic declaration can use private implementation helpers in private
storage and bodies. It cannot expose a private named helper through a public
field, constructor input, method parameter or result, including through enclosing
array/nullable/generic types. A primary constructor parameter is part of the public
signature even when its initialized field is private.
Public bounds follow the same rule, including nominal types inside their
type arguments.

Check public surfaces in the symbolic template's defining context. A public
generic class exposes its effective inherited public fields, methods and
operators. Check their types after substitution into each base, using the
ordinary member lookup rules for shadowing. Owner parameters are valid
signature types. A caller may instantiate a public template with a caller-private
type wherever that actual type is visible.

Import the original template identity with the existing selection syntax. This
adds no import alias syntax. Repeated graph routes to the same declaration and
arguments reuse one closed identity. Same-spelled private declarations from
different source files remain distinct. Template-private names keep their
definition-file meaning after application in another file, including cycles.

## Value identity, storage and List

Closed instances are nominal: identity combines original declaration identity and
ordered normalized exact arguments. Scope instance/layout/code caches to their
target-aware analysis; equal native widths or displayed names do not merge types.
Canonical metadata includes closed arguments without dropping nullable positions
or explicit extents. Metadata equality uses semantic identity rather than text.

Keep ordinary value ownership after substitution. Strings, arrays and structs
receive independent recursive value buffers; class values copy identity. Generic
syntax introduces no GC, programmer-visible references, boxing requirement,
implicit class deletion or List-specific compiler operation.

The [confirmed List requirements](../../cloth-arrays/references/growable-arrays.md#collection-integration-requirements-confirmed-2026-11-08)
remain: independent array-element lengths, an independent collection copy and a
checked rectangular `toArray()` conversion. The concrete struct-slot proof is
already available. Generic class/struct support and real imported public List
tests still precede closing that delivery gate. Indexing/iteration protocols and
Bootstrap ownership migration stay separate.

## Selected implementation policies

These are Stage 0 implementation choices under the authorized plan, not additional
user-chosen language syntax. Preserve their deterministic behavior and record any
later measured adjustment with its boundary tests.

- Use target-scoped interning and monomorphized concrete lowering. Reuse the
  existing callable ABI, layout and value-copy services after substitution.
- Register a provisional closed identity before resolving its dependent members.
  A same-key recursive class reuses that identity. Existing inline-struct and
  inheritance cycle checks still apply.
- Initially limit a chain of newly required closed instances to 64 and distinct
  closed instances per compilation to 4,096. Resolve/intern an existing key before
  charging another instance or depth step. Count class and struct instances;
  exclude open templates and ordinary non-generic types. Retain a dependency chain
  even with iterative worklist processing.
- Bound nested generic argument syntax/substitution structure at 256 levels before
  recursing further. This is not a new numeric-width or ordinary array-rank limit.
- On a limit failure, stop with one diagnostic at the requiring application and
  labels for the originating template/dependency chain. Never substitute host
  stack overflow, unbounded expansion, partial native output or error accumulation.

Initial diagnostic summaries are `Generic instantiation dependency limit exceeded
(64)`, `Too many distinct generic instances (4096)` and `Generic type argument
nesting limit exceeded (256)`. Select the relevant summary for the first guard
that fails, with the source labels above. These summaries are implementation
policy; they do not introduce multiple diagnostics or new runtime exceptions.

Stage 1 implements the 256-level source argument guard as C2012, unsupported
parameter/function/enum syntax as C2011, name checks as C3108-C3111, and the
compilation boundary as C4033. Stage 2 implements the 64-step/4,096-instance guards
as C3112/C3113, and the 256-layer post-substitution guard as C3114, before that
boundary. Stage 3 moves C4033 to native validation and adds separate symbolic
expansion bounds as C3115/C3116 and public signature exposure as C3117. Its
record owns the symbolic width and bounded display policies. Stage 4 admits closed
native models, retaining C4033 for symbolic/unresolved contexts. Its C4034 policy
rejects generic canonical metadata expansion above 1,048,576 UTF-8 bytes before
flattening the argument DAG; exact identity/text are not truncated. Stage 0 changes no
production compiler behavior and supplies no generic execution evidence.
