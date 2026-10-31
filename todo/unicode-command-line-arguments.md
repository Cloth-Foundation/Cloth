# Unicode command-line arguments across all targets

Status: implemented; native macOS execution verification remains open.
Cross-platform requirement confirmed 2026-10-30; invalid-input policy confirmed
2026-10-31.

Windows, Linux and macOS are all required targets. Preserve one Cloth contract,
`main(string[] args)`, with the necessary native adapters for each platform.
Completing a Windows fix alone does not establish support on the other targets.

## Original reproduced gap

The chunk 8 review passed `é中😀` as an argument on the current Windows host.
Comparing `args[0]` against the identical source literal returned status 1; the
ASCII control returned 0. The original adapter treated native narrow argv bytes
as Cloth UTF-8 without conversion. The Unicode startup adapters replace it.

## Implemented behavior

- Windows: native `wmain` receives UTF-16 arguments. `WideCharToMultiByte`
  with `CP_UTF8` and `WC_ERR_INVALID_CHARS` validates and converts each argument.
  Converted buffers live through the Cloth main call and are released afterward.
  GUI linking selects `wmainCRTStartup`; console linking discovers `wmain`.
  The ordinary `main` startup remains available for internal snippets/test drivers.
- Linux/macOS: native `main(argc, argv)` uses a shared UTF-8 validator. It rejects
  isolated continuations, overlong sequences, surrogate encodings, values above
  U+10FFFF, invalid continuation bytes and truncated sequences. It reads only
  through the first NUL and leaves valid bytes unchanged.
- Keep the existing portable Cloth main signatures, argument order, exclusion
  of the executable name, exit-status rules and storage lifetime guarantees.
- All arguments are checked in order before entering either `main()` or
  `main(string[] args)`. The first invalid argument produces one diagnostic
  identifying its one-based user-argument position and expected native encoding,
  then exits with status 1. No later argument is read and Cloth main is not called.
  Valid U+FFFD, BOMs, combining sequences and noncharacters remain valid data;
  there is no replacement, normalization or locale-dependent fallback.

## Verification required

- [x] Implement the shared argument-encoding contract on all three targets.
- [x] Native Windows execution, including static/dynamic CRT, console/GUI
  subsystem and LLD/MSVC linking.
- [x] Native Linux execution: emitted ELF objects linked against Ubuntu's C
  runtime, with 51 valid/malformed-argument checks, including both main forms.
- [ ] Native macOS execution. Verified LLVM IR for x86-64/ARM64 Darwin and emitted
  Mach-O fixtures; shared Unix adapter logic executes in host tests. These checks
  do not establish native macOS execution.
- [x] Cover ASCII, accented text, CJK and supplementary Unicode characters.
- [x] Cover no arguments, empty arguments, spaces, quoting and multiple arguments.
- [x] Verify argument order and preservation of the original text.
- [x] Cover malformed UTF-8 and UTF-16, first-error behavior and stopping before main.
- [x] Keep existing main forms and native exit statuses working.
- [x] Verify normal-exit buffer cleanup and Windows startup/linking configurations.

`UnicodeArgumentsTest` emits native-architecture Linux and macOS fixtures under
ignored `build/unicode-startup/`. On the corresponding OS, execute:

```sh
python3 Compiler/src/test/resources/unicode-arguments/verify-unix.py
```

The host tests also verify startup IR across Windows x86-64/ARM64, Linux
GNU/musl x86-64/ARM64 and Darwin x86-64/ARM64 profiles. Use fixtures matching the
native architecture; no cross-architecture runtime execution has been claimed.

Host verification on 2026-10-31: all 446 tests passed using the successfully
compiled Unicode compiler/test classes. A fresh isolated source build also
passed 55 startup/Unicode/string-lifetime tests, excluding only the unrelated
unfinished `cloth/data/PrimitiveTypes.kt` prototype. The ordinary full source
build encountered errors in that concurrently edited file; it was not modified
as part of this fix.

Implementation references:
[Microsoft wide startup](https://learn.microsoft.com/en-us/cpp/c-language/using-wmain?view=msvc-170),
[strict UTF-16 conversion](https://learn.microsoft.com/en-us/windows/win32/api/stringapiset/nf-stringapiset-widechartomultibyte),
and [Unicode UTF-8 well-formedness, Table 3-7](https://www.unicode.org/versions/Unicode17.0.0/core-spec/chapter-3/).

Track the independent printing/formatting guard in
[native-formatting-guards.md](native-formatting-guards.md).
