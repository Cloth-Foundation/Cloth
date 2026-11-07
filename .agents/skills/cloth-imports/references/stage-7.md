# Imports and Shuttle Stage 7: nested projects and publication

Integrated 2026-11-07. This completes the initial source-module milestone:
maintained project examples, Bootstrap integration, target verification and usage
documentation. The [import contract](semantics.md), [request API](compiler-api.md)
and [Shuttle contract](../../shuttle-build/references/semantics.md) own behavior.

## Nested folders and Bootstrap

Source folders remain hierarchical. Each dot-separated import path maps literally
under every declared module root: token.Token means token/Token.co. Resolution
uses the module roots even when the importing file is in cli/deep. Subfolders
need no module.toml unless independently declared as module roots. Matching an
entire relative path in multiple roots is ambiguous; basenames are not searched
recursively. Entry selection also supports nested files through the configured
module/file/function entryPoint.

Bootstrap's initial Shuttle build successfully reached lexer/Lexer.co and then
token/Token.co. Its first error was the class constructor default written with
`=`. It now uses the existing `?:` form. Token.co imports the small
[source/SourceFile.co](../../../../Bootstrap/Frontend/source/SourceFile.co) model
it referenced, holding a source name and text. Main snapshots the lexer result,
explicitly deletes the lexer and returns the result. The folder structure remains
Frontend/Main.co, lexer/Lexer.co, token/Token.co and source/SourceFile.co.

The next independent failure was `operator<string> ==()` in TokenType. The user
confirmed its intended operand-context contribution for enum/string comparison.
The declaration was correctly written; equality had been excluded from context
integration. The [equality context record](../../cloth-language/references/equality-operand-contexts.md)
owns that correction, signature validation, alias-flow effects and verification.
Bootstrap retains its operator declaration. Its current lexer scaffold now
builds and runs, splitting the sample input into six printed pieces and returning
PEC: 0. This verifies the current scaffold, not a complete self-hosted compiler.

The direct Compiler CLI deliberately supplies no project roots or TOML discovery.
C3097 now explains that a project should be built through Shuttle or passed with
explicit module roots through the public request API. The no-argument developer
example points to a runnable single-file example; Bootstrap uses Shuttle.

## Maintained example and checks

[examples/Shuttle](../../../../examples/Shuttle/README.md) is a maintained project
with App and Support module roots, nested cli/Main.co entry, token model, report
function with a private helper and a terminating even/odd cycle across roots.
It demonstrates selective/wildcard imports and enum/string equality contexts
without module prefixes in source paths. README commands cover project selection,
build/run, arguments, packaged launchers and object output. The root
[README](../../../../README.md#projects-and-imports) distinguishes the direct
source CLI from project builds and documents the plain compiler API boundary.

[ImportResolutionTest](../../../../Compiler/src/test/kotlin/cloth/ImportResolutionTest.kt)
adds an isolated graph case with a deep importing file, transitive nested import
and a same-named decoy beside the importer. It proves root-relative resolution
and rejects a flattened basename. [ProjectModelTest](../../../../Shuttle/src/test/kotlin/shuttle/ProjectModelTest.kt)
adds a backend-free nested entry selection case without subfolder manifests.

[ImportPublicationTest](../../../../Compiler/src/test/kotlin/cloth/ImportPublicationTest.kt)
adds three maintained-source checks: exact nested graph membership, public-request
native execution, and executable IR plus startup objects for every existing ABI
profile. Compiler tests supply explicit roots and do not read TOML. The test-only
IR parser emits startup objects without a foreign linker; an optional artifact
directory retains Linux GNU x64 outputs for manual native execution.
[ShuttlePublicationTest](../../../../Shuttle/src/test/kotlin/shuttle/ShuttlePublicationTest.kt)
copies the maintained TOML/source projects into temporary trees and tests real
Java-main execution from nested module folders. It also emits a Linux ARM64
object through Shuttle with an unavailable linker override. The sources remain
maintained in the repository rather than duplicated test strings.

## Target evidence

Both maintained projects execute with status zero on Windows x64/MSVC and
x86/MSVC. The maintained example has exact-output assertions; Bootstrap smoke
checks completion and one PEC without pinning its evolving diagnostic output.
Their executable LLVM modules verify and emit startup
objects for all nine existing profiles: Windows x64/x86/ARM64, Linux GNU and musl
x64/ARM64, and macOS x64/ARM64. This is existing target coverage, not an expansion
of the supported target list.

The emitted GNU Linux x64 startup objects were linked by Ubuntu WSL's installed
cc with -lm, then executed. The example printed token Name: Cloth and its supplied
argument, followed by one PEC: 0. Bootstrap printed the six source pieces and
one PEC: 0. Both returned zero. Windows ARM64, other Linux profiles and macOS
have IR/object evidence here; no native execution is claimed for unavailable hosts.

The equality context fixtures independently execute on Windows x64/x86 and verify
IR/object emission for the same nine profiles. They cover evaluation order,
buffered snapshots, ==/!= priority/fallback, width capture, nullable safety and
read-only default receivers.

## Final validation

The focused publication run passed in 54s, including compiler,
Shuttle, native-disabled path and backend-free configuration checks. A later
incremental full-build attempt lost compiled semantic classes and was interrupted;
the final run uses a fresh task-local build directory and project cache with Kotlin
incremental compilation disabled and in-process compilation.

The final full check/installDist passes in **8m 17s**:

| Task | Suites | Tests | Failures/errors/skips |
| --- | ---: | ---: | --- |
| Compiler test | 144 | 1,610 | 0 / 0 / 0 |
| Compiler importSyntaxTest | 2 | 23 | 0 / 0 / 0 |
| Compiler importResolutionTest | 1 | 28 | 0 / 0 / 0 |
| Compiler importSemanticsTest | 1 | 33 | 0 / 0 / 0 |
| Shuttle test | 6 | 51 | 0 / 0 / 0 |
| Shuttle configurationTest | 2 | 25 | 0 / 0 / 0 |

From the checkout root, the reproducible check is:

```powershell
.\gradlew.bat :Compiler:check :Shuttle:check :Shuttle:installDist '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process' --no-build-cache --no-configuration-cache --offline --console=plain --no-daemon
```

The observed command additionally selected
`--project-cache-dir tmp/shuttle-stage7-20261107/project-cache` and
`--init-script tmp/shuttle-stage7-20261107/isolate.gradle`. The init script set
each Cloth project's build directory to tmp/shuttle-stage7-20261107/verified-build
and supplied the test-only Linux artifact directory. This isolates class files,
reports and caches from other workspace work. CompilerSettings' developer default
change occurred before this full run. A concurrent Bootstrap dogfooding banner
was preserved afterward; its smoke assertions now avoid exact debug-output
coupling. The publication checks were rerun for that current source state.
Those six publication cases pass again in 24s, and the newly emitted Bootstrap
Linux startup object was linked/run again with its dogfooding banner and PEC: 0.

The packaged shuttle.bat independently ran Bootstrap and the example (including
a spaced program argument), both with status 0 and exact output, and emitted
Linux ARM64 object output with a nonexistent linker. The README's Gradle example
command also passed with the simple nested argument and status/PEC zero. The
checked-in example is listed explicitly in the root ignore rules so its README
is discoverable alongside its source/manifests.

Five changed skills pass the bundled metadata validator using task-local PyYAML
6.0.3. Documentation links/anchors, code fences, TOML examples, manifests and
source whitespace checks pass: 19 documents, 786 local links, 98 anchors, three
TOML examples, three maintained manifests and 26 source/configuration files.
Temporary build/cache directories, exported Linux outputs, scripts and dependencies
are removed after verification. Root/submodule whitespace checks also pass.

Separate binary modules, public binary ABIs, packages/downloads and incremental
project scheduling remain deferred. Shuttle remains its own submodule; this stage
publishes source guidance and verification without changing the pinned Git commit.
