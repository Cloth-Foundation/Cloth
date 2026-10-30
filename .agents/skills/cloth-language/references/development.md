# Cloth development status

## Current work

Compound assignments `name += value`, `-=`, `*=`, `/=`, and `%=` now parse as
ordinary local binding assignments whose values use the corresponding binary
operation. Standalone `name++` and `name--` add or subtract a same-type one,
including floating types without promotion of half values to float32 bindings.
Existing exact binding-type, mutability, contextual integer literal, signedness,
width, and arithmetic rules apply; string `+=` uses ordinary concatenation.
The target's current value is read before the right operand, which runs once.
Only simple local binding targets are currently implemented. Update operators
are standalone postfix statements only, as explicitly confirmed by the user.
`x ** y` exponentiation is implemented. Integer results retain their operand
type and use exponentiation by squaring with ordinary wrapping multiplication,
preserving uint64 bits without a floating-point conversion. Direct negative
integer exponent literals report C3018; other negative integer exponents raise
a controlled runtime error. Both operands run once, left to right. Floating
and mixed powers use ordinary promotion, C `powf`/`pow`, and half result rounding.
Initial choices not separately confirmed as design: powers associate right,
bind above unary signs and multiplication, and zero to the zero power is one.
Existing matching integer width/signedness requirements apply. `//` remains a comment.
`examples/EX.co` now uses `x += 1` and retains its output.

Verified on 2026-10-30: `gradlew.bat :Compiler:test --offline --console=plain
--no-daemon` passed all 166 tests with no failures, errors, or skips. The 14 new
`AssignmentOperatorsTest` cases cover compound precedence and types, mutable
and immutable targets, unsigned wrapping, string concatenation and copies,
target/RHS evaluation order, branch scopes, numeric updates including half
storage, rejection of prefix/expression updates, power grouping, all integer
widths/signedness, exact uint64 results, float promotion and special values,
static and runtime negative-exponent failures, nested control flow, short
circuits, and dynamic CRT power calls. Existing CLI tests verify EX.co output.
Native execution was verified on the current Windows host.

String interpolation is implemented for `#"Hello, {name}!"`. `examples/EX.co`
now uses `#"The answer is: {x}"` and compiles to a program printing
`The answer is: 20`. The lexer emits text and expression delimiters with original
source spans, recursively lexing holes through the ordinary scanner. Nested
interpolation, quoted/raw strings and characters, comments, casts, arithmetic,
and value-producing conditionals work inside holes. Embedded expressions are
checked normally, even in skipped code, and evaluated once, left to right.
Ordinary string `+` remains restricted to two strings.

Initial implementation choices, not separately confirmed language decisions:
`{{` and `}}` escape literal braces; unescaped `}` is C1012 and an unclosed
hole is C1011. Surrounding text uses ordinary quoted-string escapes and cannot
contain literal line breaks; hole expressions may span lines. There is no raw
interpolated-string form or custom format-specifier syntax. All currently
supported primitive values can be embedded. Strings preserve their UTF-8 bytes
and lengths, characters encode one scalar including NUL, booleans become
`true`/`false`, and numbers use the same ABI-aware decimal formats and precision
as native result printing. Numeric formatting uses C `snprintf` with a checked
64-byte buffer, enough for every supported integer and `%g` float representation.
Generated text uses the existing local string-buffer lifetime; this does not
settle dynamic/escaping string lifetime design. Empty text segments are skipped.

Verified on 2026-10-30: the full compiler suite passed 149 tests using
`gradlew.bat :Compiler:test --offline --console=plain --no-daemon`. After adding
three final regression cases, the focused `--tests cloth.StringInterpolationTest`
run passed all 14 interpolation tests with no failures, errors, or skips. Coverage
includes source spans, malformed syntax and Unicode, strict/skipped-code checks,
all numeric widths and signedness, native formatting precision, UTF-8 boundaries,
NULs, braces/escapes, nested strings/comments/conditionals, once-only evaluation
order, short circuits, reassignment/copies, large buffers, first-error behavior,
the C ABI declaration, static/default and dynamic CRT execution, and EX.co via CLI.
Native execution was verified on the current Windows host.

The user selected structured compiler diagnostics through Yvette 0.2.0. The
`cloth.diagnostics` package now defines stable codes, source labels, predefined
lexer/syntax/semantic/CLI/toolchain/backend factories, and one graphical reporter.
`ClothDiagnostic` implements Yvette's `Diagnostic` directly; syntax and semantic
exception types remain available as structured subclasses. Jansi supplies the
renderer dependency, and prettier4j 0.3.1 is explicit because Yvette's public API
exposes `Doc` but declares prettier4j only as a runtime dependency.

The source snapshot travels with token spans. Expressions, statements, literal
nodes, types, conditional bodies, and binding declarations preserve locations.
Internal positions are one-based UTF-16 with exclusive ends; the renderer converts
to zero-based Yvette ranges and expands tabs at four-column stops. Its source
adapter preserves CRLF input and empty final lines, including EOF labels.
Yvette's wide/combining Unicode display-width limitations remain. The reporter
uses terminal detection for colours/links and falls back to ASCII drawing when
stderr's encoding cannot represent the Unicode theme.

String-plus-integer expressions report C3017, label both operands, and explain
that concatenation requires two strings. Assignment, duplicate, and immutable
binding failures include the original declaration. Lexer, parser, semantic,
CLI/file, ABI, toolchain, linker, and LLVM failures use the shared diagnostics.
Internal compiler invariants use C9xxx; low-level type-constructor assertions stay
internal and are converted at the CLI boundary if reached during compilation.
`runCompiler` returns a status for embedding/tests; main exits unsuccessfully for
errors. Expected failures return 1, internal failures return 2, and `--debug` shows
retained causes and stack traces. The CLI defaults to `examples/EX.co`, preserving
the user's selected source. Semantic checks run before target/toolchain resolution
or output creation. Compilation stops at the first detected error by explicit
user decision and Cloth philosophy. Error recovery and multi-error collection
must not be introduced; they are not pending features. Multiple source labels
may explain the same error. Native runtime cast and string-length errors retain
their existing C ABI reporting path.

Verified on 2026-10-30: `gradlew.bat :Compiler:test --console=plain` passed
all 138 tests with zero failures, errors, or skips. Nine diagnostic tests cover the
EX.co rendering snapshot, code uniqueness, severity/help, operand and declaration
ranges, literal/composite spans, CRLF/tabs, trailing-newline and empty-source EOF,
multiline lexical failures, CLI file/argument failures, opt-in debug traces,
semantic errors preceding an invalid linker, and Windows ASCII fallback. Existing
language tests now assert structured source locations instead of message suffixes;
ABI/toolchain tests check diagnostic codes. The conditional CLI example's stale
expected output was corrected to its existing `It is unsigned!` result. The EX.co
CLI also rendered C3017 and exited with status 1, without a compiler stack trace.

The user selected `if` / `else if` / `else` conditionals next and requested
implementation. Inline and multiline chains, nested conditionals, boolean
conditions, branch scopes, semantic checks, and LLVM emission are implemented.
A conditional can initialize or reassign a binding and compose with ordinary
expressions. A standalone conditional executes its branch without an implied
value or automatic last-value output; it may omit `else` and have empty branches.
Conditions run once in order until the first true condition, and only the
selected body executes. Skipped conditions and bodies are still type checked.

The initial implementation uses these rules for details not separately settled
by the user: a value-producing chain requires a final `else`; every branch must
end with an expression, which supplies its value; and all branch results must
have exactly the same type, including numeric width and signedness. Declared
integer binding types provide context for branch-result integer literals, using
the existing literal rules. There is no implicit branch-result numeric promotion.
Branches have separate lexical scopes, may shadow enclosing names, and may
assign enclosing mutable bindings. Local names do not escape into sibling
conditions, sibling branches, or the enclosing scope. These initial choices
are recorded as implementation behavior, not additional confirmed user decisions.

Primitive `is Type` checks are implemented, including `a is unsigned int`, exact
numeric widths/signedness, and `bool`/`char`/`string` targets. The operand is
evaluated once even when its type is statically known. Checks use comparison
precedence, below casts/arithmetic and above equality/logical operators. Class
runtime type checks, state checks, and class-type narrowing remain unimplemented.
`../../../../examples/Conditionals.co` is runnable and prints `It is unsigned!`;
the user's original initializer in `../../../../examples/Test.co` is covered by
a native test, without claiming that the rest of that design file compiles.

The user selected string definitions after characters. Explicit `def<string>`
and inferred string bindings, mutable `=` / immutable `:=` declarations, copies,
reassignment, parenthesized literals, and identity casts are implemented. The
user then selected equality and concatenation: string `==` / `!=` and `+` are
implemented with strict string operands, ordinary precedence, and once-only
left-to-right operand evaluation. Equality returns `bool`; concatenation returns
`string` without changing either input.
Ordinary double-quoted literals decode `\n`, `\r`, `\t`, `\0`, `\\`, `\'`, and
`\"`; unknown escapes, line breaks, unterminated strings, and isolated UTF-16
surrogates are rejected. Triple-quoted raw strings preserve their contents,
including newlines, quotes, and backslashes, while validating Unicode scalars.

LLVM string values and binding storage currently use `{ ptr, i64 }`: a pointer
to UTF-8 bytes and their byte length. Literal bytes use private constant storage
for the program's lifetime. Concatenation checks length-addition overflow before
allocating a fresh local byte buffer, then copies both operands with LLVM memcpy
intrinsics. These buffers last for the current executable function; this
implementation applies to the compiler's current single-scope programs and
does not implement strings escaping through functions/objects. Copying a binding
copies its descriptor; reassignment does not change previous copies. There is
no heap allocation, GC, reference counting, or language-level object destruction.
This is an internal implementation choice, not a decision about dynamic string
APIs or language-level object lifetime. Last-value output walks the stored
length through ABI-typed `putchar`, preserving Unicode, empty strings, embedded
NULs, and percent signs. Equality checks lengths before ABI-typed C `memcmp`
using the profile's unsigned pointer-width `size_t`, and compares exact contents
without Unicode normalization or case folding. Empty strings and NULs work in
both operators. Comparison branches compose with nested short-circuit logic;
skipped concatenations are not evaluated. A shared `EmitRuntimeError` emits
existing numeric-cast failures and new concatenation length-overflow failures.
String ordering, other arithmetic, logical operations on strings, cross-type
conversions outside interpolation, indexing, and nullable annotations are not implemented.
Unsupported operations are rejected
before LLVM emission. `../../../../examples/Strings.co` demonstrates bindings and
prints `Unicode: é 中 😀`; it also demonstrates concatenation, equality, and
copies preserved across reassignment. Runnable examples and CLI regression paths use
the repository's `examples` directory.

The user selected `char` next and confirmed 32-bit Unicode scalar storage.
Explicit `def<char>` and inferred bindings, immutable and mutable declarations,
copies, reassignment, parenthesized literals, character `==`/`!=`, and identity
casts are implemented. Characters are distinct from numbers and booleans;
arithmetic, ordering, logical operators on characters, cross-type comparisons,
and casts to/from other types are rejected pending language design. `unsigned`
and width parameters are not accepted for `char`.

The lexer accepts a single Unicode scalar between single quotes, including a
UTF-16 surrogate pair in the host's source string, and rejects isolated
surrogates, empty/multiple-scalar literals, and raw line breaks. Existing escapes
`\n`, `\r`, `\t`, `\0`, `\\`, `\'`, and `\"` are decoded. String scanning now
correctly uses double quotes; its previous single-quote check had shadowed the
character scanner. LLVM character values and local storage use `i32`. Native
last-value character output emits UTF-8 bytes through ABI-typed `putchar` calls,
independently of the C locale and native `wchar_t` width. `../../../../examples/Characters.co`
demonstrates the feature and prints `😀`.

As of 2026-10-29, the user selected explicit integer bindings as the first
compiler milestone after arithmetic. It is implemented and verified: `def<int>`
with mutable `=` or immutable `:=`, binding reads in expressions, and reassignment
with `=`. The user then selected type inference, now implemented and verified:
`def x = 10` and `def y := x + 2` infer from their initializers, and reassignment
must satisfy the original inferred type. Current expression types allow inferred
`int`, `float`, `bool`, `char`, and `string` bindings. Explicit `def<float>` bindings have now also been
implemented and verified, alongside `def<int>` and inferred declarations, with
the same assignment checks and mutable/immutable binding behavior.
Reusable parsing of delimited contents is an explicit implementation requirement.

Binding rules, object deletion, state dispatch, class/state
signature collisions, initial-state construction, state re-entry, absence of exit
hooks, and payload binding for running methods have been settled; see
[confirmed semantics](semantics.md).

Reusable top-level states, their host contracts, declaration and attachment
syntax, attached initial-state selection, and automatic external-transition
permission for attached public states are also confirmed in that reference.

State calls now use `object::method(...)`; ordinary instance method calls use
`object.method(...)`. `../../../../examples/Test.co`'s attack call has been updated accordingly.

The user selected boolean bindings next. Explicit `def<bool>` and inferred
boolean bindings are implemented and verified, including `true`/`false`, reads,
copies, mutable reassignment, strict types, and immutable bindings. The user
subsequently selected one byte per boolean for now, replacing shared-byte bit
packing. LLVM expression values use `i1`; each local binding has its own `i8`
allocation. Stores zero-extend to a canonical byte value of 0 or 1; loads truncate
back to `i1`. Assignments directly store the new byte without loading its previous
contents. Fields, arrays, atomics, and foreign-interface layouts remain future work.

Prefix boolean negation is implemented and verified: `!` accepts only `bool`
operands and emits logical inversion of an `i1` value. It supports literals,
binding reads, repeated negation, parentheses, initializers, and assignments.
The user next selected `&&` and `||` with short circuiting, now implemented and
verified for `bool` operands. Precedence is `!` above `==`/`!=` above `&&` above `||`; binary
logical operators associate left to right, and parentheses override precedence.
The emitter uses conditional branches and `i1` phi values so the right operand
is evaluated only when needed. Nested expressions use their actual ending
blocks as phi predecessors. Both operands are checked before code generation.
The user next selected `==` and `!=` for booleans, then integers and floats;
all are implemented and verified. Equality returns `bool`, evaluating left then
right without short circuiting. Equality operators have equal precedence and
associate left to right. Boolean/numeric comparisons are rejected.
Integer and boolean comparisons emit `LLVMBuildICmp` with `LLVMIntEQ` or
`LLVMIntNE`. Float comparisons emit `LLVMBuildFCmp` with `LLVMRealOEQ` for `==`
and `LLVMRealUNE` for `!=`: exact value equality, signed zeros equal, and NaN
unequal to every value including itself. Infinities compare by sign and value.
Mixed `int`/`float` comparisons follow existing arithmetic promotion: convert the
integer to the current 32-bit float before comparing. Large integers can round
in that conversion (for example, `16777217 == 16777216.0` is `true` under this
policy), while `int`/`int` equality remains exact. This conversion is specific to
expressions; strict binding assignment types are unchanged.

The user then selected `<`, `>`, `<=`, and `>=` for integers and floats, now
implemented and verified. All require two numeric operands and return `bool`;
booleans are rejected. Integer predicates are signed (`SLT`, `SGT`, `SLE`, `SGE`),
and float predicates are ordered (`OLT`, `OGT`, `OLE`, `OGE`), so every ordering
comparison with NaN is false. Signed zeros compare equal for `<=` and `>=`, and
infinities follow their numeric ordering. Mixed comparisons use the same float32
promotion described above. Arithmetic binds above ordering, ordering above
equality, equality above `&&`, and `&&` above `||`; prefix unary signs bind
above these operators but below exponentiation. Ordering operators associate left to right; mathematical
chains such as `1 < x < 3` are rejected because the first result is `bool`.
Use `1 < x && x < 3` for a range check.

The user then selected unsigned integer integration. `def<unsigned int>` uses
32 bits by default; explicit `int<32>`, `unsigned int<32>`, `int<64>`, and
`unsigned int<64>` bindings are implemented. Width syntax reuses the generic
delimiter parser. Unsigned types survive inferred copies, arithmetic results,
assignments, comparisons, and final output. Literals take integer context from
typed operands or binding declarations/assignments and must fit that type;
otherwise they default to signed 32-bit. Named bindings never change type to
fit a context. Signed/unsigned and different-width integer expressions require
explicit conversion, and binding assignments retain exact type checks.
Unsigned arithmetic wraps modulo the selected width, as confirmed by the user.
Unsigned division/remainder and ordering emit `udiv`/`urem` and unsigned `icmp`
predicates; float promotion uses `uitofp` for unsigned inputs. Native output uses
the appropriate signed/unsigned format, extending 8/16-bit values to 32 bits for
the native variadic calling convention. `unsigned` on another type is
rejected. `../../../../examples/Unsigned.co` demonstrates the supported integration.

Explicit `->` casts are now implemented between all eleven numeric types: signed
and unsigned 8/16/32/64-bit integers and 16/32/64-bit floats. The user confirmed integer
wrapping/truncation, float-to-integer truncation toward zero with an error on
invalid results, and keeping booleans separate from numbers. The user also
confirmed that `1 + 2 -> float` casts the entire sum. Cast precedence sits below
arithmetic and above ordering, and chains associate left to right.
`CastExpression` retains the operator and target type. Its operand is checked
independently of the target and surrounding assignment; default integer literals
remain signed 32-bit inside casts. Larger integer literals still require an
appropriate typed binding before conversion.

`EmitCast` emits integer truncation or source-signedness-aware extension,
signed/unsigned integer-to-float conversion, and float widening/narrowing.
Float-to-integer conversions branch through ordered range checks before
`fptosi`/`fptoui`; invalid executed casts print a diagnostic with the cast's
location and exit with status 1. Checks permit fractions whose truncation fits,
including small negative fractions converting to unsigned zero. Checks occur
inside the operand's runtime path, so short circuiting skips invalid casts.
Identity casts, including `bool -> bool`, preserve the value. Boolean/numeric
casts are rejected before emission.

`ValueType` represents integer and floating families with validated width data,
plus a distinct boolean type. Numeric and boolean spellings derive from `Keywords`;
the semantic resolver uses the same keywords and supported-width sets. Explicit
widths use the required `<number>` syntax already handled by `TypeParser`.
Signed and unsigned `int<8>`, `int<16>`, `int<32>`, and `int<64>` are supported;
unannotated integers still default to signed 32-bit.

The remaining compiler type audit centralizes integer bounds, floating format
metadata, literal overflow thresholds, and display precision in `ValueType`.
Integer literal parsing derives its maximum width from the supported integer
widths, and emission passes decimal text to LLVM rather than narrowing through a
JVM `Long`. Floating literals retain decimal text, validate against the default
floating type's exact overflow threshold, and let LLVM round directly to the
semantic type. Parsing no longer fixes literals to a JVM float32 representation.
Diagnostics and emitted boolean text use `Keywords` or the resolved type spelling.

`ValueType.FloatingFormat` declares IEEE half, single, and double widths and
exponent sizes once; supported floating widths derive from those definitions.
`ValueType` also provides canonical `HALF`, `SINGLE`, and `DOUBLE` types alongside
the default `FLOAT`, and distinguishes logical `bits` from binding `storageBits`.
Booleans have one logical bit and eight storage bits; numeric storage uses the
numeric width. These language definitions do not depend on JVM type sizes.

`LLVMTypes` only maps semantic value/storage representations to LLVM constructors.
Its floating format mapping is exhaustive, so introducing a format requires an
explicit backend mapping. It defines no types, storage-width policies, C ABI
rules, or arithmetic promotion rules.

The user selected explicit target ABI profiles without adding a Clang frontend.
`NativeCABI` is now an instance selected from LLVM's normalized target triple,
with Windows LLP64, Linux LP64, and Darwin LP64 profiles. Initial supported
architectures are x86-64 and AArch64 (including Apple's `arm64` spelling): Windows
requires the MSVC environment, Linux requires GNU or musl, and macOS accepts Darwin
or macOS versioned OS names. LLVM normalizes abbreviated triples first, including
its default MSVC environment for a Windows triple without an explicit environment.
Unsupported architectures, OSes, and environments receive a diagnostic containing
the original/normalized triple and supported targets; there is no profile fallback.
This includes x32/ILP32, Android, MinGW, iOS, and bare-metal targets.

Profiles declare C int/long/long-long and pointer widths, double format, and
executable format. Windows uses a 32-bit long; Linux/macOS use a 64-bit long.
These are explicit ABI facts, not automatic discovery or a derivation from JVM or
Cloth default widths. Profile sources are Microsoft's
[LLP64 model](https://learn.microsoft.com/en-us/windows/win32/winprog64/abstract-data-models)
and [type ranges](https://learn.microsoft.com/en-us/cpp/cpp/data-type-ranges), the
[x86-64 psABI scalar types](https://gitlab.com/x86-psABIs/x86-64-ABI/-/blob/master/x86-64-ABI/low-level-sys-info.tex),
[AAPCS64](https://github.com/ARM-software/abi-aa/blob/main/aapcs64/aapcs64.rst), and
[Apple's 64-bit data model](https://developer.apple.com/library/archive/documentation/Darwin/Conceptual/64bitPorting/transition/transition.html).

`LLVMEmitter` resolves the native ABI once before generating IR and before creating
output files. It records that normalized triple in the module, uses the selected
C int type for the entry point and library declarations, and passes the ABI into
`EmitCast` for runtime error calls. Object generation uses the same triple and
checks the profile's pointer width against LLVM's target data layout. Printing
argument selection and C conversion specifiers belong to the selected ABI;
display precision derives from shared floating metadata and uses ordinary floating
conversions. Output suffixes and linker selection follow the profile's executable
format; the CLI leaves suffix selection to the emitter. Compilation remains native
only; matching profiles for other targets is not a cross-compilation interface.

The user then selected separation of linker/toolchain configuration from process
execution. `LinkOptions` carries executable/driver overrides, default/static/dynamic
runtime linkage, console/Windows subsystem, ordered user library directories and
libraries, sysroot, MSVC tools root, Windows SDK root, and optional SDK version.
`CompilerSettings` exposes these through CLI options while retaining the two
positional source/output arguments and default source. Long options accept separate
or `=` values; `-L`/`-l` are repeatable, and `--` permits dash-prefixed filenames.
The README documents the supported options.

`ToolchainResolver` resolves and validates these options for the selected ABI before
IR or output-directory creation. Windows uses bundled generic LLD by default or an
explicit MSVC-compatible driver. It discovers MSVC through `VCToolsInstallDir` or
`vswhere` and Windows SDKs through SDK environment variables or the Windows Kits
installation. Tools/SDK versions sort numerically. It selects x64/ARM64 library
directories from the ABI architecture and resolves CRT, compatibility, and kernel32
libraries to actual files. The chosen paths become explicit `/libpath` arguments
and the link process's `LIB` environment. Static CRT remains the default; dynamic
CRT selects the corresponding import libraries. Machine/subsystem flags are
explicit, and GUI output selects `mainCRTStartup` to initialize the CRT before
calling Cloth's generated `main`. Microsoft LINK's help/no-input status is handled
without treating arbitrary failed probes as successful.

Unix resolution uses `--linker`, `CC`, or `cc`, identifies GCC/Clang, and validates
the driver's reported target architecture, OS profile, and Linux GNU/musl
environment against LLVM's selected ABI. Clang receives the selected target triple;
fixed-target GCC does not receive Clang-specific options. Library paths come from
explicit options, sysroot/SDK, and driver search-directory queries. Linux resolves
its math runtime, including a static archive for static linkage; macOS resolves
the SDK's System library and obtains the SDK from `--sysroot`, `SDKROOT`, or `xcrun`.
Unsupported driver/subsystem/runtime/SDK combinations and missing target files
fail during resolution. This does not add a Clang frontend or a cross-target CLI.

`LinkerConfiguration` holds the resolved executable, flags, runtime files, library
paths, user libraries, environment, and target identity, and constructs an argument
list without shell quoting. `NativeLinker` only executes that command and checks
its result. Default configurations are cached per target within the compiler
process; explicit options resolve independently. Toolchain probes have bounded
waits. The implementation follows the
[LLD Windows interface](https://lld.llvm.org/windows_support.html),
[MSVC CRT choices](https://learn.microsoft.com/en-us/cpp/c-runtime-library/crt-library-features),
[CRT entry points](https://learn.microsoft.com/en-us/cpp/build/reference/entry-entry-point-symbol),
and [GCC target/search queries](https://gcc.gnu.org/onlinedocs/gcc/Developer-Options.html).
The software half arithmetic working type is selected by `EmitHalf.arithmeticType`;
semantic expression promotion remains in `ValueType.commonFloating`.
`EmitBindings` retains the semantic type instead of reconstructing boolean storage
rules from LLVM widths. `EmitCast` uses the model's integer bounds and tests whether
the exclusive lower bound is representable instead of special-casing 64 bits.
Half conversion masks and thresholds derive from the shared floating metadata,
and binding storage follows the widths declared in `ValueType`.

`float<16>`, `float<32>`, and `float<64>` are supported in binding annotations and
cast targets. Two floats use the wider format, including half results for two
half operands. Mixing with integers uses at least float32; the presence of a
64-bit float promotes mixed numeric expressions to double. Decimal literals still start as
float32, and widening them cannot recover digits already rounded. Explicit casts
are required for float-width changes in binding assignments. All floating widths
support storage, arithmetic, negation, comparisons, and native printing.
`EmitHalf` converts IEEE binary16 values through integer bit operations, with
round-to-nearest-even for float32/64 inputs (including subnormals, signed zero,
infinities, and NaN). It converts double directly to half to avoid double rounding.
Half bindings use LLVM `half` storage; half arithmetic computes in float32 and
rounds back to half. This avoids LLVM half runtime helpers absent from the current
Windows link setup. Native printing widens half/float32 to double.
`../../../../examples/Casts.co` demonstrates widening before arithmetic and evaluates to `4294967294`.
`../../../../examples/Test.co` remains a broader design example: primitive `int::MAX` lookup and other
unsupported declarations in that file are not implemented by this milestone.

The user selected character and then string bindings after numeric widths.
The user then selected string equality and concatenation. The milestone after
these string operators has not been chosen. Do not treat the order
of open questions or missing features as an approved plan.

## Implementation snapshot

Inspected from repository source through 2026-10-30; recheck before relying on it:

- The compiler is Kotlin/JVM in `Compiler`, built with Gradle. Its module declares
  Kotlin 2.4.20, JDK toolchain 25, and Bytedeco LLVM bindings.
- `Compiler/src/main/kotlin/cloth/Entry.kt` wires source loading, lexer, parser,
  and native LLVM compilation. The CLI settings currently default to `examples/EX.co` and accept
  `[source.co] [output]`, preserving source paths and using a separate output.
- `cloth/lexer/Lexer.kt`, `cloth/token/Keywords.kt`, and `Operators.kt` contain
  broader lexical machinery. Recognizing a token does not establish parser,
  type-checker, or emitter support for that language feature.
- `cloth/parser/Parser.kt` builds expression statements, explicit or inferred binding
  declarations, simple and compound assignment statements, standalone postfix
  `name++`/`name--` updates, and `if` chains
  with brace-delimited branch statement lists. Statements currently require
  newline separation (or a closing branch brace/EOF). A chain accepts `else` on
  the same line or after newlines/comments without consuming a subsequent
  unrelated statement's separator.
  `BindingDeclaration.type` is nullable to represent an omitted annotation.
  `cloth/parser/subs/ExpressionParser.kt` handles integer and float literals,
  boolean, character, ordinary/interpolated/raw string literals, binding names, parentheses, unary `+`/`-`/`!`, and binary `+`, `-`, `*`, `/`, `%`, `**`, `&&`, `||`, `==`, `!=`, `<`, `>`, `<=`, `>=`
  with precedence, plus `-> Type` casts between arithmetic and comparison
  precedence levels, primitive `is Type` checks at comparison precedence, and
  conditional expressions.
- `cloth/parser/subs/DelimitedParser.kt` provides a generic delimiter wrapper
  with caller-supplied content parsing, including angle and parenthesis helpers.
  Binding type annotations, parenthesized expressions/conditions, and branch
  braces use this wrapper;
  recursive callbacks can handle nested delimiters. `TypeParser` reads an optional
  `unsigned` modifier, a type name, and an optional nested numeric width.
  In a cast, only a complete `<number>` suffix is consumed as a width; other
  following `<` tokens remain available for ordering comparisons.
  General generic type arguments are not implemented.
- `cloth/semantic/SemanticAnalyzer.kt` checks the executable scope and nested
  branch scopes before LLVM emission. It rejects unknown names, duplicate declarations,
  immutable reassignment, and assignment type mismatches. An omitted annotation
  infers signed/unsigned 8/16/32/64-bit integers, 16/32/64-bit floats, `bool`, `char`, or `string` from the initializer once, preserving that type on all
  later assignments. Current assignment checks require exact types; no new
  numeric binding conversions were introduced. Integer literals can take an integer
  context without converting named bindings. A `SemanticModel` records each
  expression and statement's language type for emission, using identity keys.
  Explicit signed/unsigned 8/16/32/64-bit integers, 16/32/64-bit floats, `bool`, `char`, and `string` annotations are implemented;
  other explicit type names receive an unsupported-feature diagnostic. Casts
  and primitive `is` checks resolve their target through the same type resolver
  as binding annotations. Standalone conditionals have `void` statement type;
  value-producing chains require exhaustive, compatible branch results.
  Boolean/numeric conversions and arithmetic on booleans are rejected before emission; prefix `!` requires a boolean operand and returns `bool`. `&&` and `||` require boolean operands. Equality accepts two booleans, two characters, two strings, or two numbers, including mixed int/float values, and returns `bool`. Ordering comparisons require two numbers and return `bool`.
  Bindings become available after their initializer is checked;
  forward/self reads are currently rejected. This is implementation coverage,
  not a decision about declaration resolution for the broader language.
- `cloth/emitter/LLVM.kt` and `cloth/emitter/types/EmitExpression.kt` emit LLVM
  code for arithmetic, boolean negation (`LLVMBuildNot` on `i1`), short-circuit
  logical expressions (conditional branches and phi values), boolean equality and numeric equality/ordering
  comparisons (`icmp` on `i1`/`i8`/`i16`/`i32`/`i64` or `fcmp` on `float`/`double`), casts
  through `EmitCast`, binding reads, and conditional chains. Conditional value
  merges use phi nodes with each branch's actual ending block, including nested
  expressions and runtime guards. Statement conditionals have no value phi.
  Integer signedness comes from semantic types, since LLVM integer types do not
  encode it. `EmitBindings` allocates typed local
  storage and emits loads/stores (with a separate byte per boolean and conversion
  between `i1` values and `i8` storage); `EmitStatement` handles declarations and
  assignments. Branch emission restores lexical binding names without undoing
  stores to enclosing bindings. Native compilation prints the last statement's
  value when it produces one, and links a native executable. Boolean results
  print as `true` or `false`.
- `../../../../examples/Math.co` is the arithmetic example; `../../../../examples/Bindings.co` demonstrates explicit and
  inferred integer and float bindings and evaluates to `76.25`. It includes
  commented examples of invalid type-changing and immutable reassignments. `../../../../examples/Booleans.co` demonstrates boolean inference, explicit types, copies, negation, short-circuit logic, boolean/numeric equality and inequality, numeric ordering, and reassignment, and evaluates to `true`.
  `../../../../examples/Unsigned.co` demonstrates unsigned widths, wrapping, copies, division, float
  promotion, and comparisons, and evaluates to `true`.
  `../../../../examples/Casts.co` demonstrates numeric conversions and evaluates to `4294967294`.
  `../../../../examples/Test.co` describes the broader target
  language; it is not currently an end-to-end compiler test.
- Verified on 2026-10-30: the full compiler suite passed all 129 tests with no
  failures, errors, or skips using
  `gradlew.bat :Compiler:test --offline --console=plain --no-daemon`.
  All 15 new `ConditionalsTest.kt` cases also passed in a focused run. Coverage
  includes inline/multiline and nested chains, malformed syntax, exhaustive
  value branches, strict condition/result types, skipped-code checking, branch
  scopes and shadowing, mutability, all current primitive storage types,
  expression composition, actual LLVM predecessor blocks after nested guards,
  once-only condition evaluation, skipped alternatives/bodies, primitive `is`
  checks with operand side effects, absent output from standalone conditionals,
  the user's exact unsigned initializer, and the runnable conditional CLI example.
- Verified on 2026-10-30: 114 test cases checked for string equality and
  concatenation. The full run passed 113 cases and found a stale default-source
  assertion; after updating it to the current `examples/Strings.co` default,
  all three settings tests passed in a focused rerun. Operator tests cover strict
  types, precedence, immutable bindings, exact content equality, differing
  lengths, Unicode and normalization distinctions, empty strings, NULs, raw text,
  chained/nested concatenation, copied values, self-reassignment, short circuits,
  generated length guards and ABI declarations, and native concatenation growth
  across stack pages. The full run also passed all numeric-cast error tests
  after their failure emission was shared with concatenation guards.
- Verified on 2026-10-29: the full compiler suite passed all 106 tests across
  `BindingsTest.kt`, `UnsignedIntegersTest.kt`,
  `CastsTest.kt`, `NumericWidthsTest.kt`, `NativeCABITest.kt`,
  `CompilerSettingsTest.kt`, `ToolchainResolverTest.kt`, `CharactersTest.kt`, and
  `StringsTest.kt`. The full run used `--no-daemon` and a temporary init script
  under `build` to isolate test reports after Gradle reported corrupt/missing
  files in its default report directory. Standard focused string, character,
  and settings tests also passed. String tests cover literal decoding, raw
  text/newline preservation, Unicode validation, strict binding types and
  mutability, copies across reassignment, rejected operators/conversions, and
  byte-exact native UTF-8 output including empty strings, NULs, and percent signs.
  Character tests cover literal/escape decoding, string-quote separation, malformed
  and isolated-surrogate rejection, exact 32-bit storage, strict binding types,
  immutability, rejected operators/conversions, native copies and equality, and
  byte-exact UTF-8 output for literals and loaded bindings across every encoding
  boundary through U+10FFFF, including NUL, escapes, and emoji.
  Tests cover syntax, nested delimiter callbacks, semantic diagnostics, native
  arithmetic/assignment/copy behavior, inferred integer and float bindings,
  explicit floats with strict type and immutability checks, fixed inferred types
  on reassignment, mixed arithmetic, and CLI source/output paths. CLI tests
  compile and run all seven runnable examples from `examples` (`76.25`, `6689`,
  `true`, `true`, `4294967294`, `😀`, and `Unicode: é 中 😀`). New boolean
  checks cover syntax, strict types, arithmetic rejection, immutable bindings,
  separate byte allocations at 1/2/8/9/16/17 boolean bindings with interleaved
  integers and floats, native independent reassignment and copied values, and
  literal/assignment output. Negation checks cover prefix parsing, malformed
  operands, bool-only typing, nested negation, native literal and binding
  inversion, assignment, snapshots, and lexer separation of negation, equality, inequality, and assignment.
  Logical-operator tests cover malformed syntax, precedence and associativity,
  strict operand types (including skipped operands), generated branches that
  bypass the right operand's load, native truth tables using bindings, nested
  left/right logical expressions, negation, assignments, snapshots, and later
  numeric statements. Equality tests cover syntax, precedence, left associativity,
  boolean/numeric mixing rejection, fixed binding types, immutability, native truth
  tables for literals and bindings, negation, snapshots, assignments, and nested
  short-circuit expressions on either side of comparisons. Numeric equality
  tests cover exact integer values and boundaries, float values, signed zeros,
  infinities, NaN on either side, mixed promotion in both directions (including
  float32 rounding), arithmetic precedence, bool result storage and immutability,
  and short-circuit guards that skip integer division by zero. Ordering tests
  cover each operator for less/equal/greater cases, signed integer boundaries,
  float and mixed values, signed zeros, infinities, NaN on either side, float32
  rounding, precedence, malformed syntax, bool result storage and immutability,
  rejected bool operands and comparison chains, and guarded division by zero.
  Unsigned tests cover modifier and nested-width parsing, literal range checks,
  signedness and width preservation through inference/copy/reassignment, rejected
  implicit conversions, invalid modifiers, immutable bindings, unsigned LLVM
  instructions, native 32/64-bit boundary output, wrapping arithmetic, exact
  unsigned division/remainder/ordering/equality, positive unsigned float promotion
  and float32 rounding, short-circuit guards, independent storage alongside all
  existing primitive types, and signed 64-bit arithmetic/printing regressions.
  Cast tests cover syntax, agreed precedence and associativity, width/comparison
  disambiguation, exact binding rules, all 121 numeric source/target pairs, integer
  wrapping and source-signedness-aware widening, float32/64 precision and mixed
  promotion, float-to-integer boundary fractions and rounding, controlled errors
  for out-of-range values/NaN/infinities, bool separation and identity casts,
  runtime guards inside nested short circuits, copies, and generated conversion
  instructions and error paths. Float64 tests also cover arithmetic, negation,
  remainder, comparison, infinities, NaN, signed zero, storage, and printing.
  Numeric-width tests cover signed/unsigned 8/16-bit literal bounds, wrapping,
  storage, copies, printing, signed/unsigned arithmetic and comparisons, explicit
  conversions, strict binding types, keyword-derived spelling, width validation,
  half arithmetic and mixed promotion, and small float-to-integer range guards.
  Half conversion checks compare native results against JDK IEEE binary16
  conversions around normal/subnormal, tie, signed-zero, and overflow boundaries;
  double-to-half tests check direct rounding at ties and the overflow threshold.
  Default floating literal tests also cover exact decimal midpoint rounding,
  values immediately around overflow, and preservation of rounded values through
  inferred copies and native comparisons.
  ABI profile tests cover the architecture/OS/environment selection matrix,
  Windows versus Unix long widths, executable formats, LLVM triple normalization,
  versioned Darwin/macOS/MSVC spellings, unsupported targets and ABI variants, and
  the native emitted module's triple and C declarations. Native execution is
  verified on the current host; other platforms' profile selection is tested
  without claiming cross-platform executable verification.
  Toolchain tests use installed-directory fixtures and probe results for Windows,
  GNU/musl Linux mismatch detection, and macOS. They cover numeric version selection,
  architecture-specific runtime paths, static/dynamic choices, GUI CRT startup,
  SDK/sysroot selection, driver compatibility, explicit overrides, library ordering,
  paths containing spaces, CLI parsing, missing dependencies, and rejection before
  output directories are created. Native Windows tests additionally build and run
  programs with the dynamic CRT, GUI subsystem, and actual Microsoft LINK driver;
  the existing native suite exercises default static-CRT linking through LLD.
  CLI smoke checks also compiled `../../../../examples/Unsigned.co` with default and dynamic runtime
  options; both executables printed `true`.
  The test directory is now
  exempted from the broad test-directory ignore rule so tests can be tracked.
  `gradlew.bat :Compiler:run --args="Bindings.co" --offline --console=plain`
  also succeeded before examples were moved; that source now lives at
  `examples/Bindings.co`.

Repository-relative paths above identify current implementation locations; they
are not language specification boundaries.

## Open design questions

The following have not yet been settled. Ask about them when relevant, keeping
the user's preference for one question at a time:

- Type-narrowing behavior after reassignment and outside successful `if` branches.
- Default equality semantics for class instances: identity or field values.
- Whether there are restrictions on individual source-to-target transition paths.
  The confirmed phrase "permitted states" means class-supported states, not an
  approved transition-graph restriction mechanism.
- Whether state payloads can escape through stored or returned bindings, and
  their lifetime after the originating method finishes.
- The exact diagnostics/error model for invalid-state calls and deleted-object access.
- Deleting an already-invalid binding or intentionally null value.
- Character ordering, arithmetic, and conversions to/from numeric types.
- String indexing, content mutation, and dynamic/escaping string
  construction/lifetime rules beyond the current literal-backed bindings.
- Whether a value-producing `if` requires a final `else` in all cases.
- Branch result type compatibility/inference for value-producing `if` chains.
- How a value-producing branch selects its result when it contains multiple
  statements (for example, whether its last expression supplies the value).

This list is a set of unresolved areas, not an exhaustive specification checklist
or a set of required features.
