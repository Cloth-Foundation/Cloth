# Shuttle-to-Compiler request contract

Status: public surface implemented in Stage 1 (2026-11-07). Stage 4 adds internal
source graph discovery; Stage 5 adds per-file graph semantics. Stage 6 adds
whole-program emission and primary-only entry selection. See the
[integration record](stage-1.md) for source ownership and observed verification.
Stage 3 parses file headers; [Stage 4 evidence](stage-4.md) records exact root
matching and circular discovery. [Stage 5 evidence](stage-5.md) records binding,
contextual types. [Stage 6 evidence](stage-6.md) covers emission and Shuttle commands.
[Stage 7](stage-7.md) verifies maintained nested projects through this API and
Shuttle. Source roots remain independent of the primary/importing file's directory.

## Ownership and public surface

Use the public `cloth.api.ClothCompiler.compile(request): CompilationResult`
facade in the Compiler Gradle module. Shuttle depends on Compiler. Compiler has
no dependency on Shuttle or TOML. Keep analyzers, emitter internals and mutable
semantic models out of this public surface.

Shuttle resolves its own project/manifest model, then constructs plain compiler
inputs. Compiler owns source reads and snapshots, import resolution, declaration
and body checking, ABI selection, LLVM verification, linking and output naming.
The facade performs compilation only: no console reporting, process exit,
program launch, TOML loading or project discovery.

## Request and result

The immutable public types live in `cloth.api`; CompilationRequest defensively
copies its collections into unmodifiable lists. Paths at this boundary are explicit absolute paths;
adapters resolve relative user inputs before constructing requests.

| Type/field | Contract |
| --- | --- |
| `SourceModule.id: String` | Nonblank unique logical identity for configuration and diagnostics; never a required source import prefix. |
| `SourceModule.sourceRoot: Path` | Existing source directory. Reject duplicate physical roots and IDs, including for direct API callers. |
| `CompilationRequest.sourceFile: Path` | Primary source file and beginning of source-graph discovery. Standalone requests can have no configured modules. |
| `CompilationRequest.outputFile: Path` | Requested artifact path; Compiler computes the actual target-specific final filename. |
| `CompilationRequest.modules: List<SourceModule>` | Available import roots, default empty. No import root priority. |
| `CompilationRequest.outputKind: cloth.settings.OutputKind` | Existing EXECUTABLE or OBJECT identity; default EXECUTABLE. Do not introduce another copy of this enum. |
| `CompilationRequest.entryFunction: String?` | EXECUTABLE requires `main` in the primary file; default `main`. OBJECT requires null; default null for that kind. |
| `CompilationRequest.targetTriple: String?` | Existing target selection contract; null selects the native ABI. Compiler chooses and verifies its TargetLayout. |
| `CompilationRequest.linkOptions: cloth.settings.LinkOptions` | Existing target/link options, default LinkOptions(). Snapshot its collections too. |
| `CompilationResult.artifact: Path` | Actual absolute emitted filename, including an executable suffix selected by the target. No cached-success artifact on failure. |

Do not add manifest filenames, TOML nodes, Shuttle classes, raw CLI arrays or
partially checked ASTs to these records. The source filename and entry selection
replace any need for Compiler to interpret a string such as
`Frontend.Main.main`: Shuttle resolves that to sourceFile and entryFunction.

Validate source/output paths and root identities for every caller. Preserve the
existing single-file parse/target/type diagnostic ordering rather than imposing
new global preflight ordering during the driver refactor. Check the final output
against every loaded source's physical identity before writing native output.
Shuttle additionally checks its own manifest paths against the destination.

Request shape validation still precedes source work and reports C0017 /
INVALID_COMPILATION_REQUEST. This includes nested LinkOptions paths, entry/output
agreement, module IDs, and physical source-root identities. It validates roots
without discovering manifests or compiling all files in them. Stage 4 shares
that validation with the internal graph loader and returns canonical module roots.
The driver discovers reachable imports after parsing each source. No-import
compilation still parses before target selection and checks the selected ABI
before bodies. Source/output I/O retain C0010/C0013 and their causes;
existing compiler diagnostics are propagated without reporting or wrapping.

## Entry selection

Executable project configuration selects the primary file, then its unique
`fn main`. Keep the existing accepted parameter/return types, optional-parameter
transport, Unicode startup, PEC output and exit-code normalization from the
[entry contract](../../cloth-program-entry/references/semantics.md).

Only the selected primary file contributes executable entry candidates. Multiple
main overloads there are still an error. A `main` declaration in another loaded
file is an ordinary callable and does not become another process entry. It can
be called/imported under ordinary rules. A selected main need not be public;
entry selection is not an import of that declaration. Alternate entry function
names are outside the initial project slice.

OBJECT requests select the primary source but require no main and emit no
startup or PEC. They check and emit the reachable source graph as one object;
this does not establish a public cross-module binary ABI.

## Failure and adapter boundary

An expected compilation failure throws one original structured ClothDiagnostic.
Do not convert failures to an error list, print them inside the facade or return
an artifact alongside a failure. Preserve source snapshots, codes and related
labels. Relevant I/O failures are translated into compiler diagnostics at their
owning source/output/toolchain boundary. Presentation code handles unexpected
internal failures under the existing diagnostic/exit contract.

The existing CLI keeps CompilerSettings as its argument adapter and preserves
`cloth [source.co] [output]` and `cloth run ...`. The one-file adapter supplies an
empty module list unless a caller explicitly provided roots. It must not discover
Shuttle manifests. No new standalone CLI root option is required in the first
milestone: root-aware clients use this API or Shuttle. A source import without
module context receives the import contract's missing-context diagnostic.

Shuttle reports its own configuration/launch failures under stable `S` diagnostic
codes, reports original compiler failures once with their original `C` codes,
and uses expected/internal failure statuses 1/2. Build success is 0; project run
returns the child process status. A failed build never launches a stale artifact.

## Initial implementation scope

Stage 1 implements this facade through the current one-file driver. Stage 2
promotes Shuttle's Compiler dependency to production and adds strict manifest
loading and resolved-project request conversion. Its
[evidence](../../shuttle-build/references/stage-2.md) includes a real loaded-TOML
object compilation and configuration checks without native backend jars.
Preparing a request never compiles source or resolves target/link options;
only calling this facade does so. Stage 4 extends the request path to complete
source discovery while keeping graph/AST types internal. Roots are never inferred
from cwd or a manifest. Discovery failures have C3097-C3100; successful graphs
with imports bind public exports in per-file scopes. C3102-C3104 report missing,
private and conflicting selections. Before binding, object
destinations and all supported executable filename variants are checked against
every loaded source, without selecting an ABI. This pre-ABI executable check is
conservative; the actual post-ABI filename check remains in the emission path.
The driver then selects the target and checks all graph declarations, signatures,
defaults and bodies in their original file contexts. Stage 6 selects an executable
entry only from the primary file's own functions, then emits the complete checked
model through the shared callable ABI and startup path. C3101 and C3105 are
retired. Direct one-unit semantic adapters retain C3096. Malformed import syntax
still fails first. A failed build returns no artifact and never launches stale output.

The CLI adapters share CompilerOptions, LinkOptions.resolvePaths and NativeProgram;
none of those introduce command arrays or launch work into CompilationRequest.
JavaCommandLine preserves direct Windows Java-main arguments through the original
wide OS command line. JVM argument-file expansion remains the JVM's responsibility.
The first milestone checks/emits all reachable sources together into one LLVM
program. Separate compiled module artifacts, ABI publication and package
resolution are later build-system work.
