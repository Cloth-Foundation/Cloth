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

## Explicit process termination

On 2026-11-09 the user requested early process termination with an integer
status and an optional string message. `exit(status)` and `exit(status, message)`
are available without an import. The status accepts signed and unsigned fixed-width
integers of any supported width, `isize`, and `usize`. Integer literals use
ordinary expression typing. Invalid arity, non-integer statuses and non-string
messages produce the ordinary built-in call diagnostics.

```cloth
fn stop(usize status) {
    exit(status)
}

fn fail() {
    exit(1, "Something went wrong.")
}
```

The call evaluates its arguments once in source order and terminates the process
from any calling function. The terminating path produces no value and contributes
no continuing branch to a conditional. `exit` follows ordinary built-in shadowing.

The native status boundary sign-extends narrow signed values and zero-extends
narrow unsigned values. Equal-width values preserve their bits and wider
values retain their low native C int bits. The operating system interprets the
resulting process status. Before terminating, `exit` writes a supplied message
to stdout using the same length-aware UTF-8 output as `print`, then prints
`PEC: N` once. The report uses the same signed native C int value and newline
format as a return from `main`. Its leading newline separates it from the
message. C runtime termination preserves output written before the call.

## Project entry selection

The [compiler request contract](../../cloth-imports/references/compiler-api.md#entry-selection)
extends entry selection for the source graph: only the selected primary
file contributes executable main candidates. Its unique main obeys the existing
signature/startup rules above. Other loaded files' main declarations remain
ordinary callables; they do not add process entry points. Object mode selects a
primary source without requiring any main. [Import Stage 6](../../cloth-imports/references/stage-6.md)
implements this policy without changing the native startup or completion path.
