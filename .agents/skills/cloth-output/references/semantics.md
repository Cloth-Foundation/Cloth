# Cloth output and built-in functions: confirmed semantics

These decisions were recorded in the shared Cloth semantics reference.
User-confirmed rules govern intended behavior; historical implementation
notes and unresolved choices retain their original qualifications. Read
the parent skill for current implementation boundaries and source/test routing.

## Built-in functions

- On 2026-10-31 the user requested built-in `print` and `println` functions
  and confirmed that both are available without an import. The
  historical `use Console.IO.println` import has been removed from
  `examples/Test.co`.
- A source-level `printf` is not part of this requested design; string
  interpolation supplies composed messages. This does not reserve the name or
  prohibit a user-defined function named `printf`.
- The user approved a central `BuiltinFunctions` object whose named, typed
  fields call `register(name, factory)`. Registration creates a definition,
  adds it to the common collection and returns that same definition. Each
  function owns its argument contracts and LLVM emission behavior. Adding a
  built-in should require its implementation and one registration declaration,
  without function-name switches throughout the compiler.
- The initial implementation supports `print(value)`, `println(value)` and
  `println()`, all returning `void`. `print` writes text without adding a
  newline; `println` adds a newline, and its zero-argument form writes a blank
  line. `print()` and calls with multiple values are arity errors. Interpolation
  composes messages containing multiple values.
- Present printable payloads use their value-receiver `str` provider, returning
  `string`, and keep ordinary expression typing and literal defaults. This
  is an internal parameter constraint rather than a boxed source type or a
  general implicit conversion to string. Formatting matches interpolation and
  `::str`, including canonical `typeinfo` text. Array values use the recursive
  contract below; class value formatting remains deferred.
- Ordinary lexical lookup takes precedence over built-ins. A local or parameter
  binding shadows a built-in even in call position. A file-level function group
  shadows the entire same-named built-in group; a mismatched call does not fall
  back to the built-in. Built-in names are case-sensitive ordinary identifiers.
- Arguments evaluate once before conversion and output. If argument evaluation
  returns from the enclosing function, the built-in performs no conversion,
  output or added newline on that path. Calls retain ordinary void-function
  and return contracts. Programs continue to have no implicit last-value output.
- Initial output uses length-aware UTF-8 text through buffered native stdout,
  preserving embedded NULs and treating percent signs as ordinary text. Newline
  uses the native text-stream convention (CRLF on Windows, LF on Unix). There is
  no explicit flush or atomic-write guarantee. Detected native write failures
  terminate through the controlled runtime-error path; already-written bytes
  are not rolled back. Native Windows console/code-page behavior and failures
  delayed until a buffered flush still need separate verification and design.

## Recursive array text

- On 2026-11-02 the user confirmed recursive array contents for printing,
  interpolation and `::str`, while explicitly leaving `::addr` deferred.
  This adds array value text independently of unsafe memory handles and the
  separately proposed `toString()` method.
- The current formatting policy surrounds each array with `[` and `]` and
  separates elements with `, `. Empty arrays produce `[]`; nested empty rows
  produce `[[], []]`. For `values = [[1, 2], [3, 4]]`, `println(values)`,
  `println(#"{values}")` and `println(values::str)` produce the same line,
  `[[1, 2], [3, 4]]`. Unsized function signatures use actual runtime lengths.
- Each element uses its existing value-receiver `str` provider, recursively.
  Supported leaves are fixed/target integers, floats, bool, char, string and
  typeinfo. Strings and characters preserve their original contents without
  added quotes or escaping, including Unicode, NULs, newlines and punctuation.
  This is readable contents text, not a serialization or round-trip format.
- `array::str` returns an ordinary string. Array type receivers such as
  `int[2]::str` are rejected; metadata text such as `int[2]::type::str` retains
  its canonical type spelling. Arrays gain the capability only when their
  elements support a string-producing value-receiver `str` provider.
- Receiver expressions execute once, in the established source order. Internal
  sizing/filling passes do not reevaluate expressions. A completed conversion
  preserves its text across later source-array writes and function returns.
  Early returns suppress conversion and output on that path. Existing array
  shape/bounds checks and first-error diagnostics remain in force.
- Byte counts use checked target-sized arithmetic, including brackets and
  separators; overflow terminates through the controlled runtime-error path
  before filling or printing. Temporary storage follows existing function-frame
  rules. Nullable arrays/elements retain their separate unsupported boundary;
  class formatting and raw memory access are not introduced by this feature.
