# Standard library compiler bindings and read-only files

`Path` and `pathOf(string)` are ordinary source declarations in
[`cloth.io.Path`](../../../../Standard-Library/cloth/io/Path.co). A path is an
independent struct value containing the supplied spelling. Opening delegates
relative path and separator interpretation to the target platform.

[`cloth.io.File`](../../../../Standard-Library/cloth/io/File.co) exports the final
`File` class and `FileError` enum. `File::new(Path)` copies the value into a
private field. `File::new(string)` constructs a `Path` and delegates. Both
create an unopened object. `file.open()` returns `null` on success or a typed
error. Failed opens can be retried. Opening an open object returns
`AlreadyOpen` and preserves its cursor.

`file.read(usize maxBytes)` returns ordered `unsigned int<8>[]?, FileError?`
results. Declare both nullable destination types at reception. A positive read
yields an independent byte chunk, `null, null` at EOF, or `null` and an error.
Zero on an open file yields a present empty array and no error. Reads on an
unopened file return `NotOpen`. A CRT read error yields `ReadFailed`, including
when the failing native read supplied partial bytes. The stream error remains
set until the file is deleted. `file::delete` consumes the stream, attempts
`fclose` once, and releases the object. Aliases share the cursor and use the
ordinary checked class lifetime. Returned chunks can outlive the file.

~~~cloth
using cloth.io.File: File, FileError

fn<isize> main(string[] args) {
    if (args::size != 1) return 1
    def file = File::new(args[0])
    if (file.open() != null) {
        file::delete
        return 2
    }
    def<unsigned int<8>[]?, FileError?> bytes, error = file.read(4096)
    file::delete
    if (error != null) return 3
    if (bytes != null) println(bytes::size)
    return 0
}
~~~

[`cloth.text.Utf8`](../../../../Standard-Library/cloth/text/Utf8.co) exports
`decodeUtf8(unsigned int<8>[])`. It validates counted bytes and returns an
independent string or `null` for malformed UTF-8. An empty input decodes to
the empty string. NUL and original line endings remain data.

## Binding declarations

Write `= intrinsic "operation.id"` as a contextual body in a registered
standard-library function or ordinary method of a registered final class.
`intrinsic(...)` remains ordinary function-call syntax. The defining source
must be reached through the selected library module at the registered relative
path. This applies to compatible explicit library selections as well.

[Source graph discovery](../../../../Compiler/src/main/kotlin/cloth/source/SourceGraphLoader.kt)
keeps selected library origins by source identity across physical deduplication.
[Binding checks](../../../../Compiler/src/main/kotlin/cloth/semantic/LibraryIntrinsics.kt)
validate original declarations, including unused templates, then match exact
resolved parameter and ordered result types. The checker resolves the actual
`FileError` case identities. Invalid origins, unknown or duplicate operation
IDs, and mismatched declarations stop at one C3136 diagnostic. The compiler
records the binding against the resolved callable and uses ordinary import
visibility, overload resolution, and receiver checks. Add future registered
operations here and supply their target lowering. A stateless operation such
as UTF-8 decoding uses the same binding path.

[Binding lowering](../../../../Compiler/src/main/kotlin/cloth/emitter/runtime/EmitLibraryIntrinsics.kt)
uses normal argument and return transport. The decoder uses the counted UTF-8
validator and copies through the caller's result arena. The private stream
slot extends only the validated final File layout. Its ordinary storage prefix
preserves field addressing. Release runs after the source destructor and
before instance storage is freed. [File emitter](../../../../Compiler/src/main/kotlin/cloth/emitter/runtime/EmitFileIO.kt)

The native read path uses binary CRT streams. On Linux and macOS, `fopen`
receives the original UTF-8 spelling. On Windows, `_wfopen` receives UTF-16
converted with the target Win32 ABI. Empty spellings and embedded NUL return
`InvalidPath`. Open failures map the target CRT's missing and permission error
codes to `NotFound` and `PermissionDenied`. Other failures return
`OpenFailed`. `fread` uses target-sized counts and reports only its live
bytes. `ferror` takes precedence over a chunk or EOF. The existing frame
cleanup releases temporary paths and read buffers after transferring results.
[Microsoft CRT open](https://learn.microsoft.com/en-us/cpp/c-runtime-library/reference/fopen-wfopen?view=msvc-170) ·
[GNU libc block I/O](https://sourceware.org/glibc/manual/latest/html_node/Block-Input_002fOutput.html)

The current public slice is blocking sequential reads. Future writing,
seeking, checked close, and lexical path operations need their own contracts.
Use the [Bootstrap driver record](../../cloth-bootstrap-lexer/references/port.md)
for file-mode integration.

## Verification 2026-11-11

- The focused Compiler rerun passed 23 tests across `FileIOTest`,
  `ImportPublicationTest`, `BootstrapLexerTest`, `ClassLifetimeTest`, and
  `StandardLibraryRuntimeTest`. On Windows x64 and x86, both constructors,
  path snapshots, Unicode names, raw binary bytes, empty reads, EOF and
  recoverable failures passed. The injected partial read discarded its data,
  latched the failure and closed exactly once through an `Object` alias.
  Binding diagnostics covered regular and misplaced declarations, unknown and
  duplicate IDs, and signature mismatches. The earlier broad Compiler run
  stopped after a linker failure in the initial test probe. The focused
  rerun passed with private probe symbols.
- Bootstrap matched Kotlin token JSON for a file whose UTF-8 sequence crosses
  a chunk boundary, with stdin held open. The existing differential suite
  matched 126 inputs on each Windows profile. File mode and native startup
  emitted verified IR and objects on all nine ABI profiles. A foreign
  `--emit object` compile succeeded with a missing linker configured.
- The GNU Linux x64 object linked with Ubuntu WSL's C driver and lexed all eight
  Bootstrap sources through relative paths. The same executable passed Unicode
  path, missing file and directory read-failure checks.
- `:Shuttle:check` passed 66 integration tests and 27 configuration tests.
  The native launcher built Bootstrap and lexed a source file through a
  positional path. The installed-context test exported the new library sources
  from a disposable pinned commit and ran a program that opened and decoded
  bytes from that bundle. Release packaging of the main workspace still
  requires a standard-library commit and its corresponding gitlink.

[File I/O tests](../../../../Compiler/src/test/kotlin/cloth/FileIOTest.kt) ·
[Bootstrap publication tests](../../../../Compiler/src/test/kotlin/cloth/ImportPublicationTest.kt) ·
[Installed snapshot test](../../../../Shuttle/native/src/jvmTest/kotlin/shuttle/ToolchainBundleTest.kt)
