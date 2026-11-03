# Cloth nullability: confirmed semantics

These user decisions were moved intact from the shared semantics reference.
Read the [parent skill](../SKILL.md) for current implementation boundaries,
source ownership and verification. Intended buffered/class behavior remains
separate from executable scalar support. Optional examples below use the
2026-11-02 user-selected `opt<T>` / `final opt<T>` spelling; their omission and
nullable-value behavior is unchanged.

## Nullable values and optional parameters

- On 2026-11-01 the user accepted the distinction between nullable values and
  optional arguments: `T?` permits null, while `opt` permits omission at a call.
  The accepted nullable binding example is `def<string?> nullableStr = null`.
- Ordinary types are non-nullable by default: `T` requires a value and `T?`
  permits null. This does not imply automatic initialization. The nullable model
  extends across ordinary primitives, strings, arrays, metadata and future class
  objects; implementation coverage may be staged.
- The accepted conceptual memory model is presence information plus payload
  storage, without a separate heap allocation solely for nullability. A nullable
  scalar can reserve its value storage while absent and needs no manual wrapper
  cleanup. Class payloads identify separately constructed objects. Underlying
  buffer cleanup and explicit class destruction retain their existing rules.
  Clearing an object binding to null does not delete its object or invalidate
  other aliases. Exact LLVM carriers, padding and size remain implementation
  choices; the plan's nullable-int size example is not an ABI guarantee.
- The user confirmed that `void?` is invalid in every type position: void
  represents no result and has no present payload value. In particular,
  `fn<void?> something() { return null }` is invalid. Ordinary void functions
  retain their existing return contract; null is not a void result.
- The user chose `opt` with a required `?:` fallback for optional parameters,
  for example `opt<string> name ?: "Jack"`. A parameter can be both optional and
  nullable, for example `opt<string?> name ?: null`.
- A parameter fallback applies when its argument is omitted. Explicitly passing
  null supplies a value and does not select that omission fallback; the parameter
  must be nullable to accept it. Optionality does not weaken a non-null type.
- The user explicitly intends `nameAndAge(25)` to supply age and use the name
  fallback for `fn<string, int> nameAndAge(opt<string> name ?: "Jack", int age)`.
  Optional parameters may precede required ones. Named arguments such as
  `nameAndAge(name="Mike", age=25)` are intended to select parameters explicitly,
  and the mechanism must be reusable for future methods and constructors.
- The user requested a standalone [nullability plan](../../../../todo/nullability-plan.md)
  before a separate optional-argument plan. That initial request did not request
  compiler implementation. The plan separates these accepted rules from proposed type grammar,
  inference, lifting, flow proofs, overload rules, expression-level Elvis,
  native representation details and implementation stages. Those details are not
  confirmed merely by inclusion in the plan.
- The user subsequently requested the standalone
  [optional-argument plan](../../../../todo/optional-arguments-plan.md). It records
  the accepted syntax and examples above, plus proposed deterministic mapping,
  named-call rules, declaration-owned defaults, evaluation order, overload ranking,
  shared call plans and staged implementation. The user later requested its
  implementation and accepted the recommended ordinary-call contract. Current
  [optional semantics](../../cloth-optional-arguments/references/semantics.md) and
  [implementation guidance](../../cloth-optional-arguments/SKILL.md) own those
  rules; future state/constructor dispatch remains deferred.
- Following implementation review on 2026-11-01, the user approved tightening the
  nullability plan and proceeding toward scalar implementation after Stage 0.
  Nullable array bindings capture unspecified dimensions from their first present
  value under the existing shape rules; assigning null does not reset that shape
  constraint. Nullable rows now follow the subsequently confirmed
  [rectangular row contract](../../cloth-arrays/references/semantics.md#nullable-rectangular-rows),
  with execution maintained in the [row record](../../cloth-arrays/references/nullable-rows.md).
- Mutable captured bindings do not inherit presence facts from the declaration
  site of a deferred function block. Its body must establish its own proof or use
  a stable ordinary snapshot; a later execution may see a changed capture.
  Declaring a block executes none of its eventual capture effects. Recursive
  nullable buffer transfers and retained shape constraints must be verified before
  nullable strings/arrays become executable.
- The user explicitly chose payload overload preference after a presence check:
  with `int? x`, inside `if (x != null)`, `f(x)` prefers `f(int)` over `f(int?)`.
  The proven-present expression uses the payload type for that selection, while
  the binding remains declared nullable. Provider and other Stage 0 contracts
  are not settled by this overload decision.
- The user confirmed rejection of null comparisons against ordinary non-nullable
  values. Both `==` and `!=`, with null on either side, are compile-time errors
  for those values. A presence proof does not change a nullable binding's declared
  type, so repeated presence tests on that binding remain nullable tests.
- The user confirmed payload inference after a presence check: with `int? x`,
  inside `if (x != null)`, `def copy = x` infers `int`. It stores a once-evaluated
  payload snapshot; later changes to `x`, including null assignment, do not change
  the copied value or its type. An explicitly nullable receiving annotation
  remains nullable even when the initializer is proven present. This refines the
  earlier default rule about initializer nullability. The user's later governing
  rule requires an explicit nullable type for an unproven nullable copy.
- The user confirmed nullable-to-payload equality without a presence proof:
  `int? n` supports `n == 5` and `n != 5`, including reversed operands. An absent
  value compares unequal to the present operand; a present value uses ordinary
  payload equality. Existing once-only, left-to-right evaluation still evaluates
  both operands even when one is null. This adds no equality for unsupported
  payload types or new implicit numeric conversions.
- The user also confirmed equality between nullable values of the same comparable
  payload type without presence proofs: both null compare equal, exactly one null
  compares unequal, and two present values use ordinary payload equality. Both
  operands still evaluate once in source order, with no absent payload access or
  new equality for otherwise unsupported types.
- The user confirmed default numeric literal preference before nullable lifting:
  with overloads `f(int?)` and `f(int<64>)`, `f(25)` selects `f(int?)`. A typed
  integer still cannot implicitly change width. Preserve existing whole-argument
  overload preference and ambiguity for incomparable matches; this adds no
  general conversion-scoring system.
- The user adopted lazy expression-level `left ?: fallback`. Evaluate the left
  once; if present, use its payload and skip the fallback. If null, evaluate and
  use the fallback once. This selects on value absence, separately from `opt`
  parameter fallbacks, which select only on argument omission. Remaining Elvis
  typing and initial implementation boundaries are tracked in Stage 0.
- The user confirmed right associativity and low precedence for expression
  Elvis: `a ?: b ?: c` means `a ?: (b ?: c)`; comparisons and logical operators
  bind more tightly. Thus `count ?: 0 == 1` means `count ?: (0 == 1)`, while
  `(count ?: 0) == 1` compares the selected integer. Commas still delimit
  separate arguments or results.
- The user confirmed a nullable-source requirement for expression Elvis:
  reject an ordinary non-nullable left operand such as `5 ?: 0`, without
  implicitly lifting it into a nullable type. A declared nullable binding may
  still use Elvis when flow proves it present; its fallback is skipped on that
  path and its declared binding type remains nullable.
- The user confirmed Elvis result nullability for compatible payloads: `T? ?: T`
  produces `T`; `T? ?: T?` and `T? ?: null` produce `T?`. For `int? n`, `n ?: 0`
  therefore has type `int`, while `n ?: anotherNullableInt` and `n ?: null` have
  type `int?`. Cloth's strict payload typing remains in force.
- The user confirmed that ordinary casts do not unwrap unproven nullable values:
  `n -> int` for `int? n` is rejected without a presence proof. Establish presence
  with a guard or select a non-null fallback with `?:` first; ordinary payload
  cast rules then apply. This adds no implicit runtime null-unwrapping cast.
  Existing `-> void` discards remain distinct because discarding a value does
  not access its payload. The later nullable-target cast decision below extends
  conversion without allowing unproven access to a non-nullable target.
- The user confirmed that nullable binding metadata follows its declared type:
  for `int? x`, `x::type` describes `int?` even inside `if (x != null)`. A payload
  snapshot inferred there, such as `def copy = x`, has its own non-null `int`
  type and corresponding `copy::type`. A presence proof does not rewrite the
  source binding's metadata. Ordinary once-only value-receiver evaluation remains
  in force without reading an absent payload.
- The user confirmed that operations needing a nullable payload require static
  proof or a non-null fallback, including arithmetic, ordering and `::str`.
  Unproven payload access is a compile-time error. Printing is the explicit
  exception: `print` and `println` may accept nullable values without proof,
  render absence as the lowercase text `null`, and use ordinary formatting when
  present. Arguments still evaluate once, and existing output/newline rules hold.
  This does not expose an unchecked nullable `::str` provider. The later buffer
  integration supplies recursive array text; class formatting remains deferred.
  Null tests, agreed nullable equality,
  Elvis and declared-type metadata retain their absence-aware behavior.
- The user also confirmed absence-aware interpolation without a presence proof:
  an absent nullable hole contributes the text `null`, so
  `println(#"value={x}")` can print `value=null`. Present holes use normal payload
  formatting. Preserve once-only, source-order hole evaluation, early returns and
  string lifetime rules. This applies to interpolation itself and does not remove
  the proof requirement from explicit nullable `::str` expressions.
- The user confirmed direct bare-null formatting: `print(null)`, `println(null)`
  and `#"{null}"` render `null` without a typed nullable binding or payload-type
  context. This does not establish an ordinary null-only source type. The user
  retained the requirement for an explicit nullable annotation on `def x = null`.
- The user rejected automatic nullable inference for conditionals with a null
  branch. `def n = if (flag) { 5 } else { null }` requires an explicit nullable
  type; reversing the branches does not change that requirement. All-null
  conditionals also require explicit typing. A binding annotation such as
  `def<int?>` supplies that type. The user confirmed that declared return types
  and parameter types can also supply the explicit nullable destination context,
  without an intermediate typed local. For example,
  `fn<int?> pick(bool flag) = if (flag) { 5 } else { null }` has an explicit
  return contract. Competing nullable overload contexts remain ambiguous. This
  overrides the plan's earlier recommendation to infer `T?` from a present branch
  and null.
- The user also requires an explicit nullable destination for conditional results
  when a branch already has a known nullable type. With `int? existing`,
  `def n = if (flag) { existing } else { 5 }` is rejected without that context;
  `def<int?>`, declared nullable returns and declared nullable parameters can
  provide it. Branch order does not change the rule. Presence-proven payload
  occurrences retain their confirmed non-null type.
- The user stated the governing rule: "Cloth will never infer/assume null."
  Do not introduce nullable types from absent alternatives or automatic default
  initialization. This also supersedes the proposal to infer nullable array
  elements from mixed null/present literals: those need explicit nullable element
  context. The user confirmed that every new nullable binding needs an explicit
  nullable type, even when copying an already declared nullable value or receiving
  a function's declared nullable result. Reject unannotated nullable inheritance;
  apply the requirement to named reception slots and types with nullable elements
  as well. Discards create no binding. This does not undo non-null payload
  inference after a valid presence proof, ordinary non-null type inference or
  absence-aware formatting/equality.
- The user confirmed presence-preserving nullable-target casts. For `int? n`,
  `n -> int<64>?` preserves null when absent and applies the ordinary numeric
  cast when present, including its range/NaN checks. A plain value may be cast
  to a nullable target using the same payload conversion and then marked present.
  A bare null cast such as `null -> int?` obtains its explicit type from the
  target. Non-numeric casts retain ordinary exact identity restrictions; no
  implicit numeric conversions or unproven unwraps are introduced.
- For the implemented scalar types, the user confirmed that `is` compares static
  expression types, including nullability, independently of runtime presence.
  With an unproven `int? x`, `x is int?` is true and `x is int` is false.
  A presence-proven ordinary read instead has type int, so `x is int` is true
  there and `x is int?` is false. `x::type` still describes the declared int?.
  A successful test of the nullable type does not establish payload presence.
  General class/state runtime checks remain separate intended language work.
  The user confirmed that bare `null is int?` is an error: `is` checks an
  established expression type and does not supply a payload type to bare null.
  Use a typed source such as `(null -> int?) is int?`, which is true. This does
  not change nullable-binding inference or introduce an implicit unwrap.
- The user confirmed that nullable metadata exposes its payload's numeric and
  array properties while preserving exact nullable identity and canonical text.
  `int?::type::isnumeric` is true and `int?::type::width` is 32, but its metadata
  differs from `int::type`. Invalid payload queries retain ordinary compile-time
  errors when known and controlled runtime errors after opaque calls. This does
  not itself admit nullable array values; their later executable integration is
  recorded in [buffer integration](buffers.md).
- The user confirmed full nullable scalar storage size through type and value
  `::size`, returning usize and including presence storage and target alignment
  padding. `int?::size` and a declared `int? x`'s `x::size` agree while absent
  and after presence proofs. Value receivers still evaluate once. Nullable
  string/array value sizes retain content/count meanings and require presence;
  the buffer integration now executes their ordinary payload providers after proof.
- The user confirmed fallback-supplied context for bare-left Elvis. Without a
  destination annotation, `null ?: 5` has plain int type and `null ?: "Jack"`
  has string type. A nullable fallback still requires an explicit nullable type
  for a new binding; `null ?: null` needs explicit payload context. This does
  not permit inference of nullable conditional results or automatic nullability.
- The scalar slice is executable for integer/float/bool/char/typeinfo payloads,
  with the confirmed rules above. Nullable strings, whole arrays and scalar/string
  elements now execute under the [buffer contract](buffers.md). Nullable rows
  execute under their rectangular contract. Nullable-target scalar casts, scalar `is`, metadata inspection, scalar
  storage sizes and fallback-supplied bare-null Elvis are implemented. The
  current proof subset covers stable local/parameter null tests, short-circuit
  composition, continuing joins, early returns, assignments and deferred effects;
  it does not promise arbitrary predicate inference. The runnable example is
  [NullableScalars.co](../../../../examples/NullableScalars.co); the follow-up
  rules are demonstrated in [NullableRules.co](../../../../examples/NullableRules.co). See development
  status for verification evidence and implementation boundaries.

- On 2026-11-03 the user selected C# nullability placement: `string?[]` has
  nullable elements, `string[]?` is a nullable array, and `string?[]?` permits
  both. A trailing array marker applies to the whole array (`int[][]?`);
  an earlier array marker denotes nullable rows (`int[]?[]`). The user explicitly
  kept nullable rows gated. Cloth's existing C dimension order, rectangularity,
  independent copies and explicit nullable destinations remain in force.
  See [buffer integration](buffers.md) for concrete behavior and ownership.

- Later on 2026-11-03 the user confirmed Cloth rectangular rows rather than
  C# jagged row shapes. Null rows supply no shape witness, the first present row
  fixes unknown inner dimensions, and all present rows must match recursively.
  Null assignments never reset those constraints. Explicit extents apply before
  presence; a present empty row supplies a zero-length witness. The
  [array-owned contract](../../cloth-arrays/references/semantics.md#nullable-rectangular-rows)
  owns this design. The user subsequently requested its implementation; the
  [row integration record](../../cloth-arrays/references/nullable-rows.md) owns
  the executable scope and verification.

- The user subsequently requested nullable type-provider integration, including
  `int?::max`. Nullable type receivers now expose instance-independent payload
  providers: numeric min/max return the plain exact numeric payload type, and
  fixed-size nullable array type size returns usize. Nullable-owned type metadata
  and scalar storage size take precedence. This adds no unchecked nullable value
  payload access. The [provider-owned contract](../../cloth-compiler-data/references/nullable-providers.md)
  retains exact behavior, source ownership and rejection boundaries.

