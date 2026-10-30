Cloth is a modern, strongly-typed object-oriented language aimed at application and library development. It sits in a 
familiar space for Java and Kotlin developers while carrying a light C++ influence through primary constructors and 
named destructors. The language deliberately rejects pointers, references, and mutability annotations, treating memory 
management as the compiler’s concern rather than the programmer’s. Its most distinctive feature is first-class state 
machines embedded directly in classes, allowing objects to declare named states and transition between them with 
explicit syntax.

## Character bindings

`char` stores one Unicode scalar in 32 bits, including supplementary characters:

```cloth
def<char> letter = 'n'
def original := letter
letter = '😀'
letter != original
```

Character bindings support inference, copies, equality, and the usual mutable
`=` / immutable `:=` rules. Literals accept `\n`, `\r`, `\t`, `\0`, `\\`, `\'`,
and `\"` escapes. Characters remain distinct from numbers and booleans.
`examples/Characters.co` is a runnable example; its final character prints as UTF-8.

## String bindings

```cloth
def<string> greeting = "Hello, Cloth!"
def original := greeting
greeting = "Unicode: é 中 😀"
def welcome := greeting + "!"
welcome == "Unicode: é 中 😀!" && original != greeting
```

Strings support explicit types, inference, copies, reassignment, and immutable
bindings. `+` joins two strings into a new string; `==` and `!=` compare their
complete contents and return `bool`. Operands must both be strings; there are no
implicit number or character conversions. Comparisons are exact, including NULs,
case, and Unicode scalar sequences, without Unicode normalization.
Ordinary double-quoted literals accept the same escapes as characters.
Triple-quoted raw literals preserve their contents, including newlines and
backslashes. Native output uses UTF-8 and preserves embedded `\0` bytes.

Literal bytes currently use constant program storage; concatenation results use
local buffers for the current executable scope. Bindings store the bytes' address
and length internally, and previous copies survive reassignment. String ordering,
interpolation, indexing, and nullable strings are not implemented yet.
Run `examples/Strings.co` to try it.

## Conditionals

An `if` expression supplies the selected branch's value:

```cloth
def<unsigned int> a = 10
def<string> description = if (a is unsigned int) {
    "is unsigned"
} else if (a > 25) {
    "is above 25"
} else { ":(" }

if (a > 0) {
    description = description + "!"
}

description
```

The current compiler requires a final `else` for value-producing chains. Each
branch ends with an expression, and every result must have the same type.
Standalone `if` statements have no implied value and may omit `else`. Conditions
must be booleans; only the selected branch runs. Branch-local bindings stay in
their branch, while assignments to enclosing mutable bindings persist.
Primitive `is` checks compare exact types, including integer signedness and
numeric widths. Class and state checks remain future compiler work.
Run `examples/Conditionals.co` to try both forms; it prints `It is unsigned!`.

## Compiler diagnostics

Compiler errors are structured diagnostics rendered by Yvette. Reports include a
stable code, the source filename, labelled source ranges, and help where relevant.
For example, `examples/EX.co` reports `C3017` for string-plus-integer concatenation,
labelling both operands with their types. Assignment errors also label the original
binding declaration; duplicate and immutable bindings show both relevant locations.

| Codes | Category |
| --- | --- |
| `C0000–C0099` | Command-line arguments, source files, and output paths |
| `C1000–C1999` | Lexical errors |
| `C2000–C2999` | Syntax errors |
| `C3000–C3999` | Semantic errors and unsupported types |
| `C4000–C4999` | Native targets, toolchains, LLVM, and linking |
| `C9000–C9999` | Internal compiler failures |

Definitions live in `Compiler/src/main/kotlin/cloth/diagnostics`. Compiler code
calls a predefined factory with tokens, spans, and type information rather than
formatting an exception message. `DiagnosticCode` is the code registry; published
codes must not be reused for different problems. `ClothDiagnostic` implements
Yvette's diagnostic contract, and `DiagnosticReporter` owns terminal rendering.

Expected failures go to stderr and exit with status 1. Internal compiler failures
exit with status 2. Add `--debug` for stack traces and retained exception causes.
Cloth deliberately fails at the first detected error, without error recovery or
multi-error reporting. Semantic checks run before
toolchain discovery and output creation. Gradle's `run` task additionally reports
its own task failure when the compiler exits unsuccessfully.

Reports use the exact source text compiled, preserve EOF and multiline locations,
and expand tabs to four-column stops for display. Colours and terminal links use
Yvette's terminal detection, including `NO_COLOR`; the reporter falls back to ASCII
drawing characters when stderr's encoding cannot represent the Unicode theme.
Yvette 0.2.0 still has limitations around wide and combining Unicode display
characters. Source positions remain one-based UTF-16 with exclusive ends inside
Cloth and convert to Yvette's zero-based ranges at the rendering boundary.
Diagnostics emitted by native executables (such as an invalid executed numeric
cast) continue to use the existing native runtime reporting path.

## Building and linking

Compile a source file with the Gradle application task:

```powershell
.\gradlew.bat :Compiler:run --args="examples/Strings.co build/strings"
```

Use `./gradlew` on Linux or macOS. The compiler selects its native target from
LLVM's default target triple and validates an explicit C ABI profile. Linking
resolves an installed toolchain for that ABI before generating code.

Build options can appear before or after the source and output arguments:

```powershell
.\gradlew.bat :Compiler:run --args="examples/Strings.co build/strings --runtime dynamic"
```

| Option | Meaning |
| --- | --- |
| `--linker PATH` | Override the linker executable or C linker driver. Paths containing spaces are single arguments. |
| `--linker-driver lld\|msvc\|cc` | Select generic LLD in Windows link flavor, an MSVC-compatible command interface (`link.exe` or `lld-link`), or a Unix GCC/Clang linker driver. |
| `--runtime default\|static\|dynamic` | Choose C runtime linkage. The default is static CRT on Windows and the driver default on Linux/macOS. Static system CRT linking on macOS is rejected. |
| `--subsystem console\|windows` | Select a Windows console or GUI executable. Both use Cloth's generated `main` through CRT startup. Other platforms accept `console` only. |
| `-L DIR`, `--library-path DIR` | Add a library search directory; repeat to add more. |
| `-l NAME`, `--library NAME` | Link an additional named library; repeat to add more. |
| `--sysroot DIR` | Select a Linux sysroot or macOS SDK. |
| `--msvc-tools DIR` | Select a versioned MSVC tools root containing `lib/x64` or `lib/arm64`. |
| `--windows-sdk DIR` | Select a Windows SDK root containing `Lib/<version>`. |
| `--windows-sdk-version VERSION` | Pin the Windows SDK library version. |

Long options accept `--option=value` too. Use `--` before positional filenames
that start with a dash. From Kotlin, the same choices are available through
`LLVMEmitter(LinkOptions(...))`.

Windows defaults to bundled LLD and discovers the matching MSVC and Windows SDK
libraries using the development environment or Visual Studio's `vswhere` utility.
Linux/macOS use `CC` or `cc`, verify its reported target, and query its library
search paths. macOS uses `SDKROOT` or `xcrun` for SDK discovery. Compiler drivers
are used for linking and toolchain queries; Cloth source is compiled directly
through LLVM. Compilation remains native only.
