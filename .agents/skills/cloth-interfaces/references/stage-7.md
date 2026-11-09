# Interface Stage 7 release verification

Stage 7 completed on 2026-11-09. The first class-backed interface release
slice covers non-generic interfaces
and explicit, unconstrained generic applications. Classes can use several
interface contracts while retaining their single class base. Interface views
carry the same checked object identity through fields, arrays, nullable values,
results and generic holders. [Language design](design.md) ·
[Value contexts](stage-6.md) · [Implementation plan](../../../../todo/interfaces-plan.md)

## Examples and entry points

- The README's `Read` example calls a private class implementation through an
  ordinary interface and deletes the instance through the view.
  [Ordinary interface example](../../../../README.md#interfaces)
- `Interfaces.co` exercises a non-generic interface extending `Value<string>`,
  a static omitted argument, an `Object` view, aggregate snapshots, formatting
  and exact closed metadata. [Standalone example](../../../../examples/Interfaces.co)
- The imported project reaches two same-spelled generic interfaces through
  separate source files. It checks exact nominal metadata, private method
  dispatch and a default that resolves a helper in its defining file.
  [Shuttle project](../../../../examples/Interfaces/build.toml)

```text
cloth run examples/Interfaces.co build/interfaces
shuttle run --project examples/Interfaces
```

## Regression and distribution checks

The focused offline run passed 110 tests across 16 suites in 1m 11s. It
included all 58 interface tests and affected class, generic, optional-argument
and import publication suites.

The full offline checks completed in 12m 27s. Every task below ran with zero
failures, errors and skips. The native-free tasks re-exercise selected suites
with reduced dependencies.

| Task | Tests | Suites |
| --- | ---: | ---: |
| Compiler test | 2,017 | 192 |
| Shuttle test | 61 | 8 |
| Installed distribution test | 10 | 1 |
| Compiler generic syntax | 86 | 10 |
| Compiler import syntax | 23 | 2 |
| Compiler import resolution | 54 | 4 |
| Compiler import semantics | 36 | 1 |
| Shuttle configuration | 25 | 2 |

```powershell
.\gradlew.bat :Compiler:test --tests 'cloth.Interface*Test' --tests cloth.ClassLifetimeTest --tests cloth.ClassEqualityTest --tests cloth.ClassFormattingTest --tests cloth.GenericEmissionTest --tests cloth.GenericClosedImportsTest --tests cloth.OptionalArgumentsEmissionTest --tests cloth.ImportPublicationTest --offline --console=plain --no-daemon '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'
.\gradlew.bat :test :Shuttle:check :Compiler:check --continue --offline --console=plain --no-daemon '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'
```

The verification used isolated Gradle outputs. The real bundle exporter
used a temporary clean checkout of the pinned Standard-Library revision
`8bfc57868589ce7bbc58f6af73228abc77da5c97` because the working library has
local edits. Both installed commands used that bundle for the CLI probes.

## CLI acceptance and diagnostics

Installed Cloth ran the README example and `Interfaces.co` on Windows x64
and x86. Installed Shuttle ran the imported project on both profiles. All six
commands produced the expected output and exit status zero.

The installed Cloth stale-view probe ran on both Windows profiles. After slot
reuse, the call reported `Class object is no longer live` and exited with status
1 before evaluating the supplied argument or entering the body. Six rejected
sources exited with status 1, one diagnostic each, before object emission.

| Rejection | Diagnostic |
| --- | --- |
| Two applications of one generic interface declaration | C3120 |
| Missing required method | C3122 |
| Matching class implementation missing `override:` | C3123 |
| Competing unrelated fallback bodies | C3124 |
| Conversion of an existing class array to an interface array | C3003 |
| Constrained interface parameter syntax | C2011 |

## Native execution and cross-target emission

`InterfaceIntegrationTest` verifies executable LLVM IR and emits startup
objects for both maintained fixtures on each profile, producing 18 verified
IR/object pairs. COFF, ELF and Mach-O headers identify the expected machine
architectures. The test worker property `cloth.test.interfacePublicationArtifacts`
exports these files for a native linker probe. The Ubuntu WSL runner uses
`cc -no-pie` with `-lm` on the GNU x64 startup objects and runs the two
executables from `/`. Both produce the expected output and exit zero.
[Release matrix test](../../../../Compiler/src/test/kotlin/cloth/InterfaceIntegrationTest.kt) ·
[Startup object helper](../../../../Compiler/src/test/kotlin/cloth/ImportPublicationTest.kt)

| Target | Evidence |
| --- | --- |
| `x86_64-pc-windows-msvc` | Native tests and installed CLI runs, verified IR and startup objects. |
| `i686-pc-windows-msvc` | Native tests and installed CLI runs, verified IR and startup objects. |
| `x86_64-unknown-linux-gnu` | Native runs under Ubuntu WSL with cc 13.3.0, verified IR and startup objects. |
| `aarch64-pc-windows-msvc` | Verified IR and startup objects only. |
| `aarch64-unknown-linux-gnu` | Verified IR and startup objects only. |
| `x86_64-unknown-linux-musl`, `aarch64-unknown-linux-musl` | Verified IR and startup objects only. |
| `x86_64-apple-darwin`, `aarch64-apple-darwin` | Verified IR and startup objects only. |

## Follow-on work

- Constraints, capability lookup, variance, and inferred type arguments belong
  to separate generic design work. Test.co shows the intended constrained
  `IHealth<T: Number>` form.
  [Generic follow-ups](../../../../todo/generic-follow-ups.md) · [Test.co](../../../../examples/Test.co)
- Struct and enum implementers need value-copy, mutation and lifetime rules.
  State contracts, ordinary class virtual dispatch and separate compilation
  have separate design boundaries. [Interface design](design.md#future-interface-work)
