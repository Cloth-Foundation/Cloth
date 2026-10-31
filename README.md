Cloth is a modern, strongly-typed object-oriented language aimed at application and library development. It sits in a 
familiar space for Java and Kotlin developers while carrying a light C++ influence through primary constructors and 
named destructors. The language has explicit object creation and destruction, without GC or programmer-visible pointers,
references, or `mut` syntax. Its most distinctive feature is first-class state
machines embedded directly in classes, allowing objects to declare named states and transition between them with 
explicit syntax.

## Functions and program entry

Source files contain declarations. Executable statements belong inside bodies,
and an executable must declare exactly one `main`:

```cloth
fn<int> main() {
    return square(5) - 25
}

fn<int> square(int value) = value * value
```

Functions support forward calls, overloads, direct and mutual recursion, and
mutable parameters (`final int value` makes a parameter immutable). Brace bodies
require explicit returns; `=` bodies return their expression implicitly. Omitting
the return annotation means `void`. Non-void functions cannot fall through.
Arguments and returns use strict types, with contextual integer literals and
contextual floating literals at call sites. Overloads prefer the default `int`
or `float` literal type when an exact match exists.

A void `main` exits with status 0; a signed integer `main` supplies the process
exit status. Signed integer widths 8, 16, 32 and 64 are accepted. The current
native adapter sign-extends narrow results to C `int` and retains the low bits of
wider results, following Cloth's explicit integer-cast rules.
`main(string[] args)` receives Unicode arguments in their original order,
excluding the executable name. Startup converts Windows UTF-16 arguments to
UTF-8 and validates UTF-8 arguments on Linux/macOS. The first invalid argument
produces a startup error before Cloth's `main` runs, including parameterless
`main()`. Empty arguments and original Unicode sequences are preserved.
Expression statements do not print automatically. The executable examples check
their results and return 0 on success or 1 on failure; `examples/Functions.co`
demonstrates forward calls and recursion.

Strings can be passed to and returned from functions, including local
concatenations and interpolated results:

```cloth
fn<string> greeting(final string name) = #"Hello, {name}!"
```

Parameters borrow immutable string bytes during the call; rebinding a parameter
only changes its local binding. Returned bytes are copied into storage owned by
the caller, so they survive the callee's return and later calls. Each invocation
releases its temporary return buffers on exit. This is internal value storage;
it does not change Cloth's explicit object creation/destruction rules or add GC.
`examples/StringFunctions.co` demonstrates returned strings and recursion.

Arrays support literals, inferred or explicit sizes, zero-based reads, function
parameters and returns, and nested dimensions:

```cloth
fn<int> last(int[3] values) = values[2]
fn<int> main() {
    def<int[]> values = [1, 2, 3] // int[3]
    def flags := [true, true, false] // bool[3]
    def<int[2][3]> matrix = [[1, 2, 3], [4, 5, 6]]
    return last(values) + matrix[1][2] - 9
}
```

Arrays are rectangular: all rows/subarrays must have matching dimensions,
including when rows come from functions. Known mismatches are compile-time
errors; unknown dimensions are checked at runtime. Replacing a row or plane
must preserve its shape. Jagged arrays are reserved for a future collection type.
Explicit sizes require exactly that many initializers. Unsized array parameters
accept rectangular values with matching element types and rank, of any shape.
Every array binding preserves its initial shape, including when `[]` dimensions
come from a function result. For example, `def<int[]> a = make()` keeps the length
of that first result; later assignments must have the same length. This applies
to every dimension. Known mismatches fail at compile time; dimensions unknown
to the compiler are captured at initialization and checked at runtime. An array
parameter similarly preserves the shape received for that invocation. `[]` in
a function's parameter or return signature still permits different shapes on
different calls.
Array returns copy their storage, including nested arrays and string contents,
before the callee exits. Invalid indices produce a runtime error. `main(string[] args)` receives
the command-line arguments after the executable name. `examples/Arrays.co`
demonstrates these features. Element assignment supports `values[0] = 10` and
`matrix[1][2] = 10`. Array bindings and function arguments copy recursively;
mutating a parameter leaves the caller's array unchanged. Return and assign
the modified array to apply its changes: `values = change(values)`. Assigning
an unsized result to a sized destination validates its dimensions at runtime.
`examples/ArrayAssignment.co` demonstrates copied arrays and returned updates.
For arrays, `:=` and `final` provide binding-only immutability. With
`def a := [1, 2]`, `a[0] = 10` is allowed, but `a = [10, 2]` is rejected.
The same rule applies to final array parameters and nested element/row writes;
ordinary type and shape checks still apply. Each function argument remains an
independent recursive copy, including when its parameter is `final`.
The compiler can reuse the independent storage of a fresh array result instead
of copying it again into a binding, argument or subarray. Values read from
existing bindings still receive independent snapshots. This optimization keeps
all shape checks, evaluation order and invocation cleanup rules intact.
Temporary array buffers can be released early when the compiler statically
proves that evaluation cannot leave a surviving dependency on them. Discarded
results and temporary arrays consumed by scalar/void expressions qualify when
their evaluation is proven safe. Uncertain cases retain function-lifetime
storage, including borrowed string/subarray results and captured writes.
Cloth does not and will not have a GC. This optimization uses no reference
counting and leaves explicit class-object destruction unchanged.
Array equality and indexed compound assignments remain unimplemented.
Element inference is independent of element order: with an `int<64>` binding
`n`, both `[1, n]` and `[n, 1]` infer `int<64>[2]`. Already-typed values must
match exactly; differing widths, signedness or integer/float types require
explicit casts. Literal-only arrays keep their existing defaults and range
checks. Inference also considers typed elements in later rows of nested arrays.
Console I/O is separate future library work. The smaller code
examples below are body fragments.

## Function blocks

An explicitly typed binding can name a parameterless computation:

```cloth
fn<int> main() {
    def value = 10
    def<int> x = {
        return value * 2
    }

    def saved := x // Executes once here and stores 20.
    value = 30
    return x + saved - 80 // Executes x again, producing 60.
}
```

Declaration does not execute the block. Each evaluated read executes it again,
using the current values of surrounding bindings. `consume(x)` passes the
produced value; `x + x` executes twice, left to right. Short-circuit evaluation
skips unused occurrences. Blocks require an explicit return type and follow
ordinary brace-body return checks; `return` exits only that block. Explicit
`def<void>` blocks can run effects without producing a value.

Both `=` and `:=` declarations are accepted; neither caches a block's result.
Assignment or updates to block bindings are currently rejected while replacement
semantics remain undecided. Blocks are local computations, and their names
produce results rather than escaping as callbacks. `examples/FunctionBlocks.co`
demonstrates repeated reads, saved values, argument order and 64-bit results.

## Compound assignments

```cloth
def x = 19
x += 1
x++
x--
```

`+=`, `-=`, `*=`, `/=`, and `%=` update mutable bindings using the ordinary
arithmetic and assignment rules. The result must match the binding's fixed type.
String `+=` concatenates two strings. Standalone `x++` and `x--` add or subtract
one from a numeric binding; prefix updates and updates inside expressions are
not allowed. `//` continues to start a line comment.

`x ** y` raises `x` to the power `y`. Powers associate right and bind above unary
signs and multiplication: `2 ** 3 ** 2` is `512`, and `-2 ** 2` is `-4`.
Integer powers keep their integer type and reject negative exponents. Floating
operands follow the ordinary promotion rules; `2.0 ** -2` yields `0.25`.

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
`examples/Characters.co` checks Unicode character operations in a runnable `main`.

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
backslashes. Native string storage uses UTF-8 and preserves embedded `\0` bytes.

Literal bytes use constant program storage; local concatenations and interpolation
use buffers for the creating function invocation. Return values copy their bytes
into caller-owned heap buffers, reclaimed when that caller exits. Bindings store
the bytes' address and length internally, and previous copies survive reassignment.
The current implementation copies every string return and retains received return
buffers until invocation exit, including discarded results. This favors a simple
lifetime rule over copy/allocation optimization. String ordering,
indexing, and nullable strings are not implemented yet.
Run `examples/Strings.co` to try it.

Interpolated strings use `#"...{expression}..."`:

```cloth
def name := "Cloth"
def count = 20
#"Hello, {name}! The answer is: {count}"
```

Holes accept expressions, including nested interpolation and value-producing
conditionals. Each expression runs once, left to right. Strings, characters,
booleans, and all supported integer and floating widths can be interpolated;
numbers use decimal formatting with round-trip precision. Use `{{` and
`}}` for literal braces. Ordinary string escapes still apply to the surrounding
text; unprefixed strings keep braces literally. Interpolation results use the same
local buffer lifetime as concatenation. `examples/EX.co` checks the interpolated
result `The answer is: 400.200012`.

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
Run `examples/Conditionals.co` to try both forms; it checks `It is unsigned!`.

## Compiler diagnostics

Compiler errors are structured diagnostics rendered by Yvette. Reports include a
stable code, the source filename, labelled source ranges, and help where relevant.
For example, `"The answer is: " + 20` reports `C3017` for string-plus-integer concatenation,
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
& .\build\strings.exe
$LASTEXITCODE # 0 when the example's checks pass
```

Use `./gradlew` on Linux or macOS. The compiler selects its native target from
LLVM's default target triple and validates an explicit C ABI profile. Linking
resolves an installed toolchain for that ABI before generating code.
Successful compilation exits with status 0, independently of the generated
program's later exit status.

To emit an object module without requiring `main` or invoking a linker:

```powershell
.\gradlew.bat :Compiler:run --args="--emit object examples/Functions.co build/functions.o"
```

The default mode is `executable`; `object` defaults to a `.o` output when no
output path is given. Object mode validates all function bodies but adds no
startup adapter. It does not yet provide a library archive, exported symbols or
a cross-module ABI; function symbols still have provisional internal linkage.

Build options can appear before or after the source and output arguments:

```powershell
.\gradlew.bat :Compiler:run --args="examples/Strings.co build/strings --runtime dynamic"
```

| Option | Meaning |
| --- | --- |
| `--emit executable\|object` | Select executable startup/linking or an object module without an entry point. |
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
