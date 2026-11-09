# Bundled standard-library contract

Status: Stages 0-5 integrated 2026-11-07. Default attachment, namespace enforcement,
override and disable command options are implemented.
The [Stage 5 record](standard-library-stage-5.md) completes publication with a
maintained example, real packaged runtime independence and explicit target scope.
The [toolchain guide](../../../../docs/standard-library.md) owns release/user instructions.
[Generic Stage 5](../../cloth-generics/references/stage-5.md) adds the actual public
List API and its imported/command tests. The new source is available through
development selection; release packaging retains the clean pinned-submodule
requirement. Its temporary snapshot export evidence does not publish a release.
The [Stage 4 record](standard-library-stage-4.md) specifies and verifies existing
class allocation as the first owning-collection runtime boundary. It adds no
public collection API or new production runtime code.
The [Stage 3 record](standard-library-stage-3.md) owns selection controls and
their verification; the [Stage 2 record](standard-library-stage-2.md) owns
explicit module roles, attachment and namespace diagnostics.
The [Stage 1 record](standard-library-stage-1.md) owns current packaging and
launcher handoff, including the user-approved Windows installation-path limitation.
The [implementation TODO](../../../../todo/standard-library-toolchain-plan.md)
stages that work. The [scaffold record](../../shuttle-build/references/standard-library.md)
owns the existing repository layout and its earlier object-build verification.

## Authority and selected policies

The user approved shipping matching Cloth library sources with each compiler
release, automatically making those sources available to both `cloth` and
Shuttle, and compiling explicitly imported files into the application. Source
imports remain explicit; availability adds no declarations to a file's scope.

The user resolved three Stage 0 questions through popup modals:

1. Reserve `cloth.*` for the selected standard library while it is enabled.
   With `--no-stdlib`, ordinary project roots may provide replacement `cloth/`
   sources.
2. Allow structurally valid development checkouts through `--stdlib PATH`.
   Do not require the exact bundled version or revision. Source incompatibility
   is reported by normal compiler diagnostics.
3. Fail a build early if its selected default bundle is missing or incomplete,
   even if the program imports no library files. `--no-stdlib` explicitly opts out.

The location model, option details, structural checks, typed module role and
failure order below are implementation choices selected during the authorized
Stage 0. They refine the approved direction without defining collection APIs,
native-call syntax or a stable binary ABI.

## Ownership and explicit inputs

Shared command support belongs in Compiler's `cloth.toolchain` package and is
available through the existing one-way `Shuttle -> Compiler` dependency. Both
command adapters use the same selection, location and validation logic. Keep it
separate from the emitter's existing native-linker `ToolchainResolver`.

The command layer resolves a toolchain context, selection mode and canonical
library root. It supplies ordinary immutable compiler inputs. It also retains
the selected distribution metadata paths for output-alias protection.

`ClothCompiler.compile(CompilationRequest)` performs no installation discovery,
environment lookup, project discovery, downloads, Git operations or TOML parsing.
Direct API callers continue to supply exactly the source roots they want.
Shuttle owns its TOML project/module parsing. Its command adapter combines the
resolved project model with the selected library.

### Explicit module-role extension

`SourceModule` has `role: SourceModuleRole = REGULAR`, with the two
values `REGULAR` and `STANDARD_LIBRARY`. At most one module in a request may have
the standard-library role. Canonicalization and immutable snapshots must retain
the role. Stage 2 implements this API extension.

The role carries the namespace boundary explicitly; neither module IDs nor a
directory named `cloth` silently assign it. A request containing only regular
modules preserves today's import behavior and has no implicit library.
Direct API callers can opt into the boundary by supplying a marked module.
Distribution-marker checks belong to command support, not the core API.

## Toolchain location and release layout

Use an explicit context with a layout kind and an absolute root:

| Context | Anchor | Default standard-library root |
| --- | --- | --- |
| Installed distribution | Installation root supplied by its launcher | `<installation>/stdlib` |
| Source-checkout development | Repository root supplied by Gradle/development launch configuration | `<repository>/Standard-Library` |

Generated installed launchers derive their root from their own location, including
relocated installations and paths with spaces or Unicode. The JVM executable's
directory is not the Cloth installation. Development tasks supply their anchor
explicitly; they do not infer it from the invocation directory.

Use launcher properties `cloth.toolchain.home` and `cloth.toolchain.layout`
(`installed` or `development`) as the handoff to shared command support. The root
must be absolute. A missing or invalid context is a selection diagnostic when
default library selection is needed. Do not fall back to cwd, PATH searches,
another installation, or ancestor-directory scans. Explicit override/disable
modes do not require the default toolchain context.

The installed layout is:

```text
<installation>/
    bin/cloth[.bat]
    bin/shuttle[.bat]
    lib/...
    toolchain.properties
    stdlib/module.toml
    stdlib/cloth/Library.co
    stdlib/cloth/collections/...
```

Package sources and metadata from the pinned submodule, excluding Git metadata,
build outputs and scratch files. `stdlib/` is the source root; its `cloth/`
component remains in import paths. Initialization of the submodule belongs to
toolchain development/release preparation, not compilation of user programs.

Generate `toolchain.properties` at packaging time with format version `1`,
compiler version, standard-library version and standard-library Git revision.
Use keys `format`, `compiler.version`, `stdlib.version` and `stdlib.revision`.
Read it through the JDK properties reader with strict UTF-8 input validation;
validate required nonblank fields and the supported format. This metadata records
release provenance and is not a package-resolution or binary-compatibility scheme.
Development execution identifies its context as development and need not have
generated release metadata or a clean/published library working tree.

Stage 1 implements packaging and the launcher/development property handoff.
Stage 2 consumes those properties for default selection in shared command support.
The user deferred arbitrary Windows Unicode installation paths after reproducing
the installed JDK's code-page limitation; preserve the follow-up and actual tested
scope in the Stage 1 record rather than claiming full Windows launcher support.

The user subsequently requested a plan to fix both installed commands for Chinese
and emoji paths without changing locale or cwd, including actual installed-command
tests. The [launcher plan](../../../../todo/windows-unicode-launcher-plan.md) owns
that pending work. Its proposed native-host design is not implemented or verified;
the historical Stage 1 boundary remains the current implementation limit.

## Command selection

Both frontends implement the following selection controls before the
program-argument separator:

| Option | Selected behavior |
| --- | --- |
| Neither option | Select the default library from the explicit toolchain context. |
| `--stdlib PATH` or `--stdlib=PATH` | Select that source-module root instead of the bundled root. |
| `--no-stdlib` | Attach no implicit library and enable no standard-library namespace reservation. |

A relative override resolves from invocation cwd, following existing path-option
rules. An override denotes the root containing `cloth/`, not the `cloth/` folder
itself. Canonicalize its physical identity after resolution. Do not expand shell
variables or `~` inside the compiler.

Repeated `--stdlib` values use the last value, following existing scalar options;
only that selected root is opened. Each occurrence must still have a syntactically
valid nonblank path value. Repeated `--no-stdlib` is idempotent. Combining either
override form with `--no-stdlib` is a command error in either order.
`--no-stdlib=value` is invalid. Run arguments after the first `--` retain their
existing meaning and cannot change library selection. The standalone build
command retains its existing `--` handling for literal positional filenames.

Override and disable modes bypass default-bundle lookup and release metadata
validation. An invalid explicit override fails without fallback. Disabling the
library does not remove source modules explicitly configured by a user; they
remain regular modules and can provide `cloth/` files.
The existing `--runtime` option continues to select C runtime linkage.
Disabling library sources does not disable compiler builtins, primitive operations
or their existing runtime support.

### Structural validation

A command-selected root must be an accessible directory with these exact-case
entries: readable regular `module.toml`, directory `cloth`, and readable regular
`cloth/Library.co`. These are distribution/scaffold markers, not automatic imports.
Require valid installed release metadata when selecting an installed default.
Incomplete required structure fails before parsing the user's source, including
for object builds and programs with no library imports.

The shared resolver does not interpret `module.toml`: its schema remains owned
by Shuttle and is checked during release preparation or explicit project-model
loading. A source override needs no release descriptor, Git checkout, revision
match or declared compiler compatibility version. It may contain uncommitted
development changes.

Do not parse/type-check every library source or verify its entire API during
selection. Structural completeness does not establish source compatibility;
only reachable source files undergo normal compiler checks.

## Combining project and standard-library modules

The implicit module normally uses ID `StandardLibrary`. IDs remain configuration
identities, never additional source-import prefixes. Compare roots using physical
filesystem identity, not only normalized spellings.

- If the project already supplies the selected physical root once, promote that
  module to the standard-library role, retain its ID and manifest protections,
  and attach no duplicate. This also permits building the library's own project.
- If `StandardLibrary` is already assigned to a different project root and no
  existing module can be promoted, report an ID conflict. Do not rename a user
  module or select a different library implicitly.
- Other preexisting duplicate IDs or physical roots remain errors. Attachment
  does not repair malformed project configuration. Validate the final request
  using the compiler's ordinary identity rules and the new single-role rule.
- With `--no-stdlib`, do not promote any module or inject an extra one.

Library selection is explicit: a manifest entry pointing to a different library
does not override the default. Select that checkout through `--stdlib PATH`, or
disable implicit selection when supplying replacement sources as regular modules.

## Reserved source paths and collision handling

When a standard-library role is present, an import whose first path component is
exactly lowercase `cloth` belongs to that root. No case-folded alias is introduced.
For example, a future public declaration in `cloth/collections/List.co` is imported
with `using cloth.collections.List : List`.

Resolve each reached import deterministically. If a regular root supplies a
matching exact-case `cloth` source path, report one reserved-namespace diagnostic
with the import, conflicting path(s) and selected library root. Do this even if
the selected library does not contain that file. A project cannot extend or
shadow the selected library through a second root. Do not silently prefer a
candidate, combine files or fall back to a project copy after a missing file.
If no regular-root candidate exists, select the library's matching file or report
the ordinary missing-source error.

Paths outside this prefix retain the existing all-root resolution and ambiguity
rules. With no standard-library role, every path, including `cloth.*`, follows
those ordinary rules. Apply the same policy to transitive imports regardless of
the importing file's root.

Reservation is checked for reached import paths, not by recursively scanning
unused directories. This adds no blanket filesystem ban on an application's
primary file. Keep exact-case matching, public selection rules, circular graphs,
single physical source identities and original diagnostic snapshots.

## Failure order and output protection

The command sequence is:

1. Parse all command options and reject invalid option combinations or unsupported
   command/output combinations, including object runs. Help/usage rendering does
   not require library discovery.
2. Resolve the selected library and validate its required structure/provenance,
   unless disabled. A bad default fails here even without library imports.
3. For Shuttle, load and validate its project model using the existing manifest
   order. Combine its roots with the selected library; the standalone adapter
   combines its ordinary source/output inputs with that library.
4. Protect selected metadata paths against the actual requested output variants,
   validate the compiler request, then use existing parse/discovery/binding/target/
   typing/emission order. Namespace conflicts occur during source discovery.
5. Launch only after a successful executable build.

Protect the selected `module.toml` and any consumed `toolchain.properties`
alongside Shuttle's existing manifest protections. Compiler retains protection
for every loaded source, including library sources. No output is created on a
selection, configuration, namespace or source failure; never launch a stale
artifact after failure.

Shared selection failures and namespace failures use original structured
compiler diagnostics, rendered once by either command. Shuttle-owned project
failures retain their S codes. Expected failures return 1, unexpected/internal
failures return 2, successful builds return 0, and successful runs return the
child status. C0018 covers selection structure/context/metadata; C0019 covers an
occupied implicit module ID; C0020 protects selected toolchain inputs; C3106 covers
reserved paths. Option conflicts and attached disable values use C0003; missing
or blank override values use C0002 and invalid path syntax uses C0012.

## Implementation ownership and verification boundary

Current sources were inspected at Stage 0: shared
[CompilerOptions](../../../../Compiler/src/main/kotlin/cloth/settings/CompilerOptions.kt),
[CLI adaptation](../../../../Compiler/src/main/kotlin/cloth/CompilerDriver.kt),
[request types](../../../../Compiler/src/main/kotlin/cloth/api/CompilationRequest.kt),
[request validation](../../../../Compiler/src/main/kotlin/cloth/api/CompilationRequestValidation.kt),
[graph discovery](../../../../Compiler/src/main/kotlin/cloth/source/SourceGraphLoader.kt),
[Shuttle settings](../../../../Shuttle/src/main/kotlin/shuttle/command/ShuttleSettings.kt),
[project model](../../../../Shuttle/src/main/kotlin/shuttle/project/ResolvedProject.kt),
and [build adaptation](../../../../Shuttle/src/main/kotlin/shuttle/command/ProjectBuild.kt).
They establish the handoff points. Stage 2 now attaches the selected root in both
adapters, preserves its role in request validation and checks reservation during
discovery. The Stage 2 record owns those sources and observed verification.
Stage 3 adds shared typed selection through both adapters, bypasses default lookup
for override/disabled modes and documents snapshot identification in its linked record.

Implementation verification must cover shared option semantics, relocation and
development contexts, early missing-bundle failure, override/disable bypass,
module promotion/ID conflicts, reserved and ordinary import paths, transitive
imports, output aliases and unchanged explicit-API behavior. Use the existing
settings/request/import/project-model suites where applicable, then packaged
command fixtures and native execution as described by the TODO. Do not infer
new runtime support from this Stage 0 source audit or documentation checks.

Stage 4 now verifies existing typed class allocation, owned field buffers and
explicit destruction for an owning collection fixture. Later buffer/I/O/clock/
thread services retain their own prerequisites in that record. This contract
introduces no public `List` API, generic implementation, new runtime-service call
syntax, archive/export ABI, shared library or package download behavior.

### Stage 0 publication checks (2026-11-07)

Documentation checks passed for 12 files: four single-line skill metadata blocks,
623 local links, 76 heading anchors, balanced fences, final newlines and clean
whitespace. The TODO has all five Stage 0 items checked and no later-stage items
checked. `git diff --check` passed for the affected skill directories; explicit
content checks also covered the new/untracked contract and local TODO.

The bundled skill-creator validator was attempted with Python UTF-8 mode but
could not start because PyYAML is absent. The metadata checks above validate
these skills' existing simple format and do not claim a full YAML-parser run.
No compiler tests or new target executions were needed for this contract-only
change. The earlier scaffold object build remains separate historical evidence.
