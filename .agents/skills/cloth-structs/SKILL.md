---
name: cloth-structs
description: Design or implement Cloth named structs, contextual aggregate construction, fields, independent value copies and struct integration with existing value contexts.
---

# Cloth structs

Basic structs are complete (2026-11-05). Start with the maintained
[Structs.co](../../../examples/Structs.co) example and the
[publication and verification record](references/publication.md).
Structs execute in local storage, function arguments and results, deferred
blocks and array foreach loops.
Use the [syntax record](references/syntax.md) for parsing, the
[semantic record](references/semantic-analysis.md) for type checking, the
[local storage record](references/local-storage.md) for layout and stored writes,
and the [function and traversal record](references/function-integration.md)
for private transport, shape retention, cleanup and verification.
Ordinary struct methods use the same callable pipeline as functions.
[StructMethods.co](../../../examples/StructMethods.co) demonstrates receiver
lookup, mutation, defaults and temporary calls. See the
[method publication record](../cloth-functions/references/method-publication.md).

Read [the struct contract](references/semantics.md) for accepted syntax,
semantics and scope. Use the [basic structs plan](../../../todo/basic-structs-plan.md)
for stage boundaries, source routing and verification targets.

## Preserve these boundaries

- Import [Stage 5](../cloth-imports/references/stage-5.md) registers struct identity
  before resolving fields. Global catalogs use declaration identity and each
  annotation resolves in its defining file, including through circular imports.
  Inline value-layout cycles still fail. Finalize fields before consuming layout
  or buffered-value facts; cached simple-name lookup cannot represent graph types.
- Each struct declaration defines a distinct type. Match struct values by
  declaration identity even when field lists are identical.
  [Generic Stage 2](../cloth-generics/references/stage-2.md) extends this key with
  ordered exact closed arguments in a target-scoped catalog. Each closed struct
  has its own substituted fields and retains the original AST. Struct cycles
  through arrays/nullable wrappers still fail before layout consumption.
- A type and a variable can share a spelling. Type annotations and ordinary
  variable expressions resolve their names independently. For `Position::type`,
  check the local variable first and use the struct type if no variable is in scope.
  A function can also share a struct's name. Keep ordinary callable lookup and
  local shadowing in call contexts.
- Struct copies are independent values, recursively including nested strings,
  arrays and structs. Class-valued fields copy object identity. Class objects
  retain their explicit lifetime when a containing struct is copied or cleaned
  up. [Class values](../cloth-language/references/class-values.md)
- `:=` and `final` prevent replacing the bound struct, but permit field changes.
  Nested stored field/index writes update the containing value directly; a
  separate struct binding receives an independent copy. Field assignments through
  temporary struct results are compile-time errors, including evaluated deferred
  results. Store the result first; a deferred name is not stored struct data.
- Write public declarations as `pub typedef<struct> Name { ... }`. Omit `pub`
  for private visibility. Unmarked fields inherit the enclosing struct's
  visibility. Mark a field `priv` to limit reads and writes to methods on the
  declaring struct. A brace literal can initialize it in the defining file.
  [Struct visibility](references/semantics.md#established-syntax-and-visibility)
  Type members use `mt`. A `fn` declaration in a type body is a syntax error
  (C2007 in a struct). See [method syntax and scope](../cloth-functions/references/method-syntax.md)
  for struct methods and receiver lookup.
- For struct-typed bindings, field-value lists construct immediately; statement
  bodies with explicit returns remain deferred blocks executed on every read.
  Keep braces for both and preserve existing non-struct blocks, including void
  blocks. A single-field literal such as `{ 7 }` requires no trailing comma.
  Empty structs are valid. With an expected zero-field struct type, `{}`
  constructs a value of that type. Field declarations and literals may span
  lines, require commas between entries, and reject trailing commas. Literals
  supply exactly one value per field in declaration order. A literal requires
  an expected struct type, such as an annotated binding, a known parameter type
  or a declared return type. The field list alone cannot infer the struct type.
  Calls use ordinary overload ranking with candidate parameter contexts. Equal
  matches are ambiguous. Evaluate fields once from left to right and preserve
  each field's value before later expressions run.
- Separate value reads from stored field/index places. Reading a copied nested
  value cannot be the implementation of a write to the original stored field.
- Basic structs require acyclic field types. Reject direct and indirect field
  type cycles, including paths through array and nullable wrappers.
- [Growable arrays Stage 1](../cloth-arrays/references/growable-arrays-stage-1.md)
  accepts unsized struct array fields in typing and records their mutation
  receivers. [Stage 2](../cloth-arrays/references/growable-arrays-stage-2.md) executes
  those fields through copies, calls, methods and nested class-backed paths.
  Nullable array fields have separate inline pointer-free witnesses, retained
  through absence and copied with the containing value. Only an unsized outer
  count may change; explicit extents and established inner shape still constrain
  reception. Whole-struct replacement receives incoming witnesses.
  [Stage 3](../cloth-arrays/references/growable-arrays-stage-3.md) permits such
  structs as elements of appendable one-dimensional local/parameter arrays.
  Protected element snapshots preserve buffers and absent-field witnesses through
  movement. Inline field writes and ordinary struct receivers follow the selected
  array value through capacity movement.
  [Stage 4](../cloth-arrays/references/growable-arrays-stage-4.md) executes array-field
  append/reserve on nested fields, array-element structs and `self`, using the
  existing array owner instead of copying/repacking the containing value. Scoped
  observations preserve selected reads/writes/receivers across callee growth;
  class-backed paths still replay checked current storage after replacement.
  [Stage 5](../cloth-arrays/references/growable-arrays-stage-5.md) shifts inline
  struct bytes and hidden witnesses together with their owned slot heads.
  Removed structs receive independent buffers/witnesses before their source slot
  is freed. Clearing/removing a containing element invalidates selected child
  buffers of every rank, including fixed array fields, before later access.
  [Stage 6](../cloth-arrays/references/growable-arrays-stage-6.md) executes
  rectangular field mutation, including `self`. Witnesses learned by mutation
  survive field null, whole-struct copying, removal of the containing struct,
  return transport and class-owner destruction.
  [Stage 7](../cloth-arrays/references/growable-arrays-stage-7.md) proves concrete
  struct-slot collections with independently sized array fields. Each slot keeps
  its own field shape; outer insertion/removal transfers its value/witnesses.
  This motivates generic structs for List storage.
  [Generic Stage 1](../cloth-generics/references/stage-1.md) adds struct parameter
  syntax and lexical name identities; Stage 2 adds closed fields/layout descriptors.
  [Generic Stage 3](../cloth-generics/references/stage-3.md) checks symbolic and
  closed methods/operators with per-owner source-linked views and exact identities.
  Public methods inherit struct visibility, including public signature leak checks.
  [Generic Stage 4](../cloth-generics/references/stage-4.md) executes closed
  struct slots through class fields, typed relocation and independent arguments/
  results. Absent nullable array fields retain complete witnesses after copies,
  removal and source destruction. Finalized descriptors memoize buffered-value
  classification to avoid repeated traversal of shared closed field DAGs.
- A null check can prove a stored field present when later access resolves the
  same unchanged path. Assigning the field or replacing a containing struct
  invalidates the proof. Use the existing nullable payload access and metadata rules.
- Declare same-type `operator ==` for whole-struct equality. See
  [struct and enum operators](../cloth-language/references/value-operators.md)
  for `!=`, operand snapshots, and nullable comparisons.
- Local storage holds struct fields inline. Borrow addresses for projections
  and copy inline bytes and live nested buffers at value transfer boundaries.
  Resolve a write's stored path and read its old compound operand before the RHS.
  Field layout and array stride use the target data layout.
- Recursive string, array and shape copies must survive caller/callee cleanup.
  Indirect transport does not establish buffer ownership or safe early cleanup.
- Keep the first detected error as one structured diagnostic, with source labels
  as needed. Unsupported features must fail deliberately, not via internal errors.

For implementation interactions, load [buffer lifetimes](../cloth-buffer-lifetimes/SKILL.md),
[arrays](../cloth-arrays/SKILL.md), [functions](../cloth-functions/SKILL.md),
[nullability](../cloth-nullability/SKILL.md) or
[compiler data](../cloth-compiler-data/SKILL.md) as appropriate.

Each integrated stage must update this skill and its references with actual
support, diagnostics, tests and target evidence under
[feature integration](../cloth-feature-integration/SKILL.md). The
[Stage 5 record](references/publication.md) tracks the full regression suite,
native and foreign-target evidence, and the handoff to class design.
