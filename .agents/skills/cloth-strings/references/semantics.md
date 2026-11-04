# Cloth strings and characters: confirmed semantics

These decisions were recorded in the shared Cloth semantics reference.
User-confirmed rules govern intended behavior; historical implementation
notes and unresolved choices retain their original qualifications. Read
the parent skill for current implementation boundaries and source/test routing.

## Characters and storage

- The user selected `char` as the next storage type and confirmed that each value
  represents one full Unicode scalar stored in 32 bits, including supplementary
  characters such as `'😀'`. Surrogate code points are excluded.
- Character declarations use single-quoted literals, as in `../../../../examples/Test.co`:
  `def<char> x = 'n'`. Inferred declarations and mutable `=` / immutable `:=`
  bindings follow the ordinary binding rules.
- One scalar need not be an entire displayed grapheme: a base letter followed
  by a combining mark contains multiple scalars and cannot be one `char` literal.
- Character arithmetic, ordering, and conversions to other types have not been
  designed. See development status for the supported operations and diagnostics.

## Strings

- On 2026-11-04 the user approved `trim`, `trimStart`, `trimEnd` and `isBlank`,
  and explicitly selected a pinned Unicode White_Space property. The
  [whitespace contract](whitespace-methods.md) pins Unicode 17.0.0, defines empty
  strings as blank, and owns boundary-only trimming, independent results,
  preserved internal contents and exclusions such as NUL/zero-width space/BOM.

- On 2026-11-04 the user approved literal `replace`, `replaceFirst`, `removePrefix`
  and `removeSuffix`, and explicitly selected scalar-boundary empty-search
  matches. [The editing contract](editing-methods.md) owns non-overlapping
  replacement, literal replacement contents, once-only boundary removal,
  independent values, exact argument/matching rules and checked output growth.

- The user subsequently accepted zero-based Unicode scalar positions and the
  next five-method batch. [The scalar-method contract](scalar-methods.md) owns
  `charAt`, exclusive-end `substring`, nullable-position `indexOf`/`lastIndexOf`
  and independent `toChars`, including implementation policies for exact `usize`
  parameters, empty-search boundaries and controlled runtime bounds failures.

- The four string attachments and value member calls approved on 2026-11-03 are
  owned by [the member-call contract](member-calls.md): `isEmpty()`,
  `contains(string)`, `startsWith(string)` and `endsWith(string)` return bool,
  preserve exact case-sensitive contents/NULs and evaluate the receiver before
  positional arguments. Empty search strings match. The record distinguishes
  the implemented registry/call path from deferred methods and class design.

- The user requested interpolated strings using `#"Hello, {name}!"`, primarily
  to make `examples/EX.co` valid. Braced expressions contribute text to a string.
  This does not change the strict string operand requirement for ordinary `+`.
  See development status for initial formatting and brace-escaping choices;
  those details have not been separately confirmed as language design decisions.
- The native type-printing/conversion contract remains under design. On
  2026-10-30 the user mentioned a possible `toString()` method and requested
  documenting missing guards while deferring that design. The user subsequently
  specified that, if introduced, `toString()` must be available on every data
  type with a non-overridable implementation, and on future classes with
  overridable behavior. This requirement does not authorize implementing it now
  or settle the general member/override model, its relationship to interpolation,
  or array formatting syntax. The historical unsupported-array guard is recorded
  in [the formatting TODO](../../../../todo/native-formatting-guards.md).
- The user authorized the temporary unsupported-array interpolation diagnostic
  while deferring `toString()` and array text formatting. This is a compiler
  capability guard, not a permanent ban on array interpolation in the language.
- On 2026-11-02 the user confirmed recursive contents for array interpolation,
  printing and `::str`, superseding that temporary restriction for supported
  arrays. Read [the output contract](../../cloth-output/references/semantics.md#recursive-array-text)
  for formatting and once-only evaluation. `::addr`, class formatting and the
  proposed `toString()` API remain deferred.
- The user selected string definitions after character support. Explicit
  `def<string>` and inferred declarations such as `def hello := "Hello World!"`
  follow the ordinary fixed-type binding and `=` / `:=` rules shown in
  `../../../../examples/Test.co`.
- The user then requested string equality and concatenation. `==` and `!=`
  compare two strings by contents and return `bool`; `+` concatenates two strings
  and returns a new string value. Both operands are evaluated once, left to
  right. `+` uses ordinary addition precedence and left associativity, above
  equality. String operators do not implicitly convert numbers, booleans, or
  characters to strings. Existing copies are preserved when a binding is
  reassigned to a concatenation result.
- Current equality compares exact Unicode scalar sequences (including embedded
  NULs), implemented as length-aware UTF-8 byte comparison. It performs no case
  folding or Unicode normalization.
- Direct bracket indexing, content mutation, strings escaping into future objects/containers,
  and explicit dynamic string lifetime APIs remain unresolved. See
  [development status](../../cloth-language/references/development.md) for internal storage and implemented
  string function boundaries; that representation does not define future
  object lifetime rules or add programmer-visible ownership syntax.
