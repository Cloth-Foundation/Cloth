# Cloth program entry: confirmed semantics

These decisions were recorded in the shared Cloth semantics reference.
User-confirmed rules govern intended behavior; historical implementation
notes and unresolved choices retain their original qualifications. Read
the parent skill for current implementation boundaries and source/test routing.

## Program entry and executable statements

- Executable statements are allowed only inside bodies, not directly at file
  scope. File-level declarations remain distinct from executable statements.
- Executable programs start in `main`. Libraries do not require a `main`.
- `main` may take no parameters or one `string[] args` parameter. Either
  parameter form may return `void`, `isize` or signed `int` at any supported integer width:
  `int<8>`, `int<16>`, `int<32>`, or `int<64>`. Plain `int` retains its default
  32-bit width, and an omitted return annotation means `void` as usual.
  For example, `fn main()`, `fn<void> main(string[] args)`,
  `fn<int> main()`, `fn<int<64>> main(string[] args)` and `fn<isize> main()`
  are valid headers. On 2026-11-01 the user revoked the earlier usize-main
  extension in favor of signed isize. All unsigned return types, including
  usize, are outside the entry contract. Step 2 of the
  [isize plan](../../cloth-language/references/isize-implementation.md) implements this compiler entry policy.
- Each executable must have exactly one entry-point `main`. Defining both
  `main()` and `main(string[] args)` is a compile-time error; startup does not
  select between entry-point overloads.
- The 2026-11-03 nullable buffer integration also admits `string[]? args`,
  including `opt<string[]?> args ?: null`. Startup always supplies a present
  validated argument array, even when empty; ordinary calls may omit an optional
  argument or supply null. Other entry parameter restrictions remain unchanged.
  See the [buffer contract](../../cloth-nullability/references/buffers.md).
- An integer returned by `main` becomes the program's process exit status.
  Normal completion of a void `main`, including a bare `return`, produces
  process exit status `0`.
- On 2026-10-31 the user confirmed the completion format `PEC: N`, where PEC
  means Process Exit Code. After the entry-point `main` returns, the executable
  prints `PEC: N`, where `N` is the normalized native exit status, and returns
  that same status.
  Void completion reports `0`. The message is preceded and followed by a
  newline, so it remains separate from explicit `print` output. Calls to `main`
  inside Cloth code do not independently report completion. Startup/runtime
  errors that terminate before the entry-point call returns retain their error
  output without a normal-completion message.
- At the native exit-code boundary, signed results narrower than the target's
  native exit-code integer are sign-extended. Wider results retain the low bits,
  following Cloth's explicit integer-cast rules. In particular, an `int<64>`
  result outside the native exit-code range is truncated rather than producing
  a runtime error. The user confirmed low-bit truncation on 2026-10-30.
- An isize main uses that same native C int exit boundary. Equal-width values
  preserve their bits and wider values retain the low native bits; PEC reports
  the resulting signed C int. A negative isize result such as -2 therefore
  reports PEC: -2. Native operating-system process status interpretation still
  applies; this adds no checked exit-range failure or pointer-width exit status.
- These rules were confirmed by the user on 2026-10-30. Tests use complete
  function declarations and ordinary executable startup.
  See [development status](../../cloth-language/references/development.md) for implemented Unicode argument-array
  construction and target-specific execution evidence.
