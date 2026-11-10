# Shuttle manifest and build contract

Status: Stage 2 manifest/model and Stage 6 build/run implementation, 2026-11-07.
See [configuration evidence](stage-2.md), [command evidence](stage-6.md) and
[Stage 7 publication](../../cloth-imports/references/stage-7.md).
The user named Shuttle, assigned TOML/build orchestration to its Gradle module,
and selected build.toml/module.toml. The initial schema/discovery defaults below
were selected during the authorized Stage 0 from the implementation plan.

## Ownership and first milestone

Shuttle owns TOML parsing, schema validation, project/module discovery,
configuration path resolution, project command options and build/run orchestration.
Compiler owns Cloth imports, source reads/graphs, typing, target/ABI selection,
LLVM emission and native linking. Use only the
[plain compiler request API](../../cloth-imports/references/compiler-api.md) across
the one-way `Shuttle -> Compiler` dependency.

The [bundled-library contract](../../cloth-toolchains/references/standard-library.md)
and [Stage 2 record](../../cloth-toolchains/references/standard-library-stage-2.md)
own implemented shared command selection and explicit module attachment. This
requires no mandatory library manifest entry. ProjectLoader and the project model
remain independent of installations; the command adapter augments the prepared
request and promotes an already configured matching root without duplication.
[Selection Stage 3](../../cloth-toolchains/references/standard-library-stage-3.md)
implements shared `--stdlib PATH`/`--stdlib=PATH` and `--no-stdlib` command options.
The override resolves from invocation cwd; both modes bypass default discovery.
Disabled selection retains all configured modules as regular roots, including
project replacements for `cloth.*`, without promotion or reservation.
[Bundling Stage 5](../../cloth-toolchains/references/standard-library-stage-5.md)
verifies the maintained App-only project through real packaged commands from an
external cwd, including native execution after its bundle/source copies are removed.

The first milestone compiles the source graph reachable from one selected primary
file together into one executable or object. All configured project module roots
are available to import resolution. Empty dependency tables do not restrict those
roots or request package downloads. Circular source imports are allowed across
them. Separately built modules, public binary ABIs, archives, package/registry
resolution, lockfiles, incremental caching and watch/build scheduling are later
work.

## TOML dependency and diagnostics

The [TOML 1.0 specification](https://toml.io/en/v1.0.0) defines the syntax
baseline. The [native command](native.md) contains the pinned tomlkt parser and
strict syntax fixes. It preserves the type distinction between dates and
strings, rejects duplicate definitions and stops at the first detected failure.
The worker applies the existing schema to the resulting typed tree.

Keep the original text and path for one configuration diagnostic. Syntax
diagnostics retain the line supplied by tomlkt and use file context when a line
is unavailable. Schema diagnostics identify the exact logical key path and
label the manifest at file level. The parser supplies the grammar and source
positions.

## Project selection and roots

The CLI uses `shuttle build [--project PATH]` or
`shuttle run [--project PATH] [-- programArguments]`, with existing compiler
target/link options forwarded through the request. Stage 6 implements these
commands, `--help`, and `-o PATH` / `--output PATH` (also `--output=PATH`).
An output override and all compiler path overrides resolve from invocation cwd.
Configured output.directory still resolves from the selected project root.
The first -- is allowed only with run and terminates build-option parsing;
everything following it is a program argument, including --debug and -- itself.
Project/output flags accept separate or equals-attached values. Repeated scalar
options use their last value; library paths/libraries preserve written order.

An explicit --project path selects either a directory containing build.toml or
the build.toml file itself. Resolve a relative --project path from invocation cwd.
Explicit selection wins and must not fall back to another project after failure.
Without it, search cwd and successive parent directories for the nearest
build.toml; stop at the filesystem root. An invalid nearest manifest is an error,
not a reason to skip upward to a different project.

Configured module directories are source roots. Ordinary subfolders remain
hierarchical and need no module.toml of their own. A nested entryPoint such as
App.cli.Main.main selects cli/Main.co under App's root. Source imports continue
to resolve from the declared roots even when that primary file is nested.
The [maintained example](../../../../examples/Shuttle/README.md) demonstrates this.

The selected build.toml's parent is the project root. Values in its modules table
and output.directory are relative to that root. Absolute directory values remain
explicit absolute paths; resolve/canonicalize them, without interpreting shell
expansions, environment substitutions or executable configuration expressions.
Never change process cwd to make paths resolve.

Each configured module directory contains module.toml and is its own source root.
Module names remain configuration/diagnostic identities and are absent from
source import paths. Reject repeated logical IDs or physically identical roots;
overlapping distinct roots are allowed, with source ambiguity resolved by the
compiler's rules. Relative import file components are matched with exact case
on every OS. Preserve filesystem aliases for actual identity checks, not for
silently choosing among multiple candidate roots.

## Project schema

```toml
[project]
name = "Cloth"
version = "0.0.1"
entryPoint = "Frontend.Main.main"

[modules]
Frontend = "Frontend"
Compiler = "Compiler"
Backend = "Backend"

[output]
directory = "build"
```

| Key | Initial rule |
| --- | --- |
| project | Required table. |
| project.name | Required nonblank string, used as the default artifact filename stem. Validate one portable filename: reject path separators, control/Windows-forbidden characters, reserved device names, dot/dot-dot and trailing dots/spaces. |
| project.version | Optional nonblank metadata string; omission has no inferred package version/resolution behavior. |
| project.entryPoint | Required dotted string: module identity, one or more source-file path identifiers, then main. All components are identifiers and match case exactly. |
| modules | Required nonempty table mapping module identities to nonblank directory strings. Each identity is one Cloth identifier. |
| output | Optional table. |
| output.directory | Optional nonblank directory string; default build relative to the project root. |
| dependencies | Optional empty table reserved for later dependency semantics. Nonempty values report an unsupported-dependency diagnostic. |

Reject unknown tables/keys and wrong types instead of silently ignoring typos.
The parser handles duplicate TOML definitions. Do not normalize table/key case
or accept alternative spellings such as Project or entry_point.

`Frontend.Main.main` selects sourceFile `<Frontend-root>/Main.co` and entryFunction
main. It does not inject Frontend into source import paths. Require the selected
module/root/file to exist. Let Compiler validate the executable declaration and
its current main signature; Shuttle must not parse Cloth to validate it.

For OBJECT output the same configured source file starts discovery, but the
compiler request's entryFunction is null and no main declaration is required.
An executable artifact defaults to project.name in output.directory; an object
defaults to project.name plus .o. Compiler returns the actual target-specific
final filename. Explicit
command output options override that requested artifact path under the existing
compiler output semantics. During request preparation Shuttle checks lexical and
physical destination aliases against all of its manifest inputs, including all
supported executable suffix possibilities without selecting a target. This
conservatively protects both the requested filename and a possible .exe name.
Compiler checks loaded source inputs. Loading or preparing a request creates no
output directories or artifacts.

## Module schema

```toml
[module]
name = "Frontend"
version = "0.0.1"

[dependencies]
```

The module table and its name are required. Name must exactly equal the key that
selected this module in the project modules table. Version is optional nonblank
metadata with no implicit dependency-resolution behavior. Dependencies may be
omitted or empty; nonempty tables are explicitly unsupported in the first slice.
Reject other tables/keys and wrong types.

Canonical table names are lowercase module and dependencies. Stage 2 migrates
Bootstrap's three module manifests. Uppercase `[Module]`/`[Dependencies]` tables
have no case-insensitive compatibility alias.

## Failure ordering and run behavior

Select/read/parse the project manifest once, then validate its schema in the
documented field order above. Load/validate module manifests in ordinal module-ID
order for repeatable failures. Missing required fields are checked where they
appear in that schema order. Reject unexpected keys in deterministic ordinal
order within each table after its known fields. After all module manifests,
validate the output directory and then the entry file. Request preparation checks
artifact selection/manifest aliases. Never collect several failures or use
ordering to choose a source-import candidate. Read every manifest as valid UTF-8
without replacement decoding.

Use stable S-prefixed Shuttle diagnostics; Stage 2 allocates S0001-S0013 for
configuration, listed in the [stage record](stage-2.md#diagnostics). Compiler
C-prefixed diagnostics retain their code, text, snapshots and
related labels and are reported exactly once. Expected failures return 1,
unexpected internal failures return 2, and successful builds return 0.
Stage 6 adds S0014 invalid commands/paths, S0015 object runs, S0016 launch/wait
failure, S0017 command I/O and S9000 unexpected Shuttle failure. Shared compiler
option diagnostics keep their C codes and display Shuttle usage where applicable.

Build finishes before launch. Run accepts only executable output, forwards
program arguments after -- in order, preserves empty/Unicode/quoted arguments
and streams, and inherits invocation cwd. A successful child supplies the run
status. A failed rebuild never launches an artifact left by an earlier build.
Compiler retains startup, PEC and process exit normalization; Shuttle does not
print a second PEC report.

## Current scope and verification

Stage 2 implements the schema, discovery and request conversion above through
internal Shuttle model APIs. Its 28-test normal suite includes a real TOML-to-
compiler object fixture; 24 configuration tests also pass with native backend
jars removed from their runtime classpath. See [Stage 2 evidence](stage-2.md).
Stage 6 first implemented the build, run and process-status contract.
[Stage 6 evidence](stage-6.md) records the original JVM launchers.
The [native command](native.md) now launches the worker and emitted programs.
The worker shares CompilerOptions and calls the plain compilation facade.
Configuration-only tests call the native parser without LLVM jars.
[Stage 3](../../cloth-imports/references/stage-3.md)
parses compiler import headers; [Stage 4](../../cloth-imports/references/stage-4.md)
discovers their complete source graph independently of TOML. [Stage 5](../../cloth-imports/references/stage-5.md)
binds/checks it; [compiler Stage 6](../../cloth-imports/references/stage-6.md)
emits the complete graph and selects the primary file's own main.
Stage 1's [record](../../cloth-imports/references/stage-1.md) retains the original
compiler API evidence. The
[Stage 0 record](stage-0.md) gives the dependency probe and documentation checks.
The [plan](../../../../todo/shuttle-and-imports-plan.md) stages implementation,
native execution and foreign-target verification independently.
