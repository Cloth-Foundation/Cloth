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
- Indexing, content mutation, strings escaping into future objects/containers,
  and explicit dynamic string lifetime APIs remain unresolved. See
  [development status](../../cloth-language/references/development.md) for internal storage and implemented
  string function boundaries; that representation does not define future
  object lifetime rules or add programmer-visible ownership syntax.
