# Imports Stage 6: whole-program emission and entry selection

Implemented 2026-11-07. The plain compiler facade now emits every source reachable
from its primary file together in one LLVM module, executable or object. Shuttle's
[command record](../../shuttle-build/references/stage-6.md) covers TOML-to-build/run
orchestration. [Confirmed imports](semantics.md) and the [request contract](compiler-api.md)
remain the authorities for source syntax, roots, visibility and API boundaries.

## Program and emission ownership

[CompilerDriver](../../../../Compiler/src/main/kotlin/cloth/CompilerDriver.kt)
discovers the graph, protects loaded sources, binds imports before ABI selection,
then checks the complete model with the actual target layout. The conservative
pre-ABI output checks from Stage 5 remain, including possible executable suffixes;
the actual final name is checked again after target selection. No-import target
and declaration/body failure ordering stays on the existing unit adapter.

[ProgramAnalyzer](../../../../Compiler/src/main/kotlin/cloth/semantic/ProgramAnalyzer.kt)
accepts the internal FileBindings model and checks every loaded declaration before
entry validation. Only `files.primary.functions`, the primary file's own symbols,
contribute executable main candidates. Imported main symbols cannot satisfy a
missing primary main; other files' own main overloads are ordinary callables.
Private primary main is valid. Existing parameter/result checks, optional transport,
Unicode startup, PEC timing and native status normalization remain shared. Object
mode selects no entry and requires no main, startup, PEC or linker.

The existing [LLVM emitter](../../../../Compiler/src/main/kotlin/cloth/emitter/LLVM.kt)
consumes the complete checked model through its existing executable/object paths.
An internal checked-program IR hook lets tests inspect the same startup path.
[EmitFunctions](../../../../Compiler/src/main/kotlin/cloth/emitter/callables/EmitFunctions.kt)
declares every callable before any body. Original callable symbols map to emitted
functions by identity; graph-wide ordered indices distinguish same-spelled private
functions and methods. Class definitions/tags, receiver owners and struct layout
caches already use nominal identity following Stage 5. Circular calls therefore
reuse one declaration/body rather than creating separate per-file copies.

**C3105 is retired**, alongside C3101; never reuse either code. Direct unit-only
semantic adapters still reject unresolved imports with C3096. Source, binding,
type, entry, ABI and toolchain failures retain their original diagnostic codes
and snapshots. The facade still does no reporting, launch or manifest discovery.

## Runtime type metadata

The emission audit found a spelling-based collision in
[EmitTypeInfo](../../../../Compiler/src/main/kotlin/cloth/emitter/text/EmitTypeInfo.kt).
It now interns descriptors by canonical primitive identity, recursive array
extent/element and nullable identity, and nominal declaration source/name position.
Within this single LLVM program, exact typeinfo equality compares those interned
descriptor addresses. Nominal globals retain significant addresses; do not mark
them unnamed-address constants or merge distinct declarations by their contents.

Two private structs named Hidden in different files therefore compare unequal,
including through nullable/array wrappers. Repeated metadata for the same original
declaration compares equal across functions/imports, and array `::element` retains
its nominal identity. Both structs still format as `Hidden`. Primitive spelling
aliases retain canonical equality, target integers retain distinct identity, and
static array extents remain exact. This is module-owned runtime representation,
not a published metadata ABI for separately linked binaries.

## Verification

[ImportIntegrationTest](../../../../Compiler/src/test/kotlin/cloth/ImportIntegrationTest.kt)
adds seven real-tree cases:

- Selective/wildcard imports within/across roots, repeated/self imports and a
  terminating mutually recursive pair; inspected IR has three Cloth callables
  and one startup adapter, and native execution reports one PEC and status 7.
- Primary-only main selection, ordinary imported-file main overloads, missing
  primary main despite an imported main, and duplicate labels confined to the
  primary source.
- Defining-file private defaults, struct methods, independent buffered copies
  and ordered multiple results crossing file boundaries.
- Same-spelled private structs, classes, enums and helpers with different layouts
  and original constructor/member/generated-reader ownership.
- Runtime nominal metadata equality across function returns and nullable/array
  wrappers, preserving unqualified display text and element identity.
- Multi-file objects for all nine supported ABI profiles with ordinary main
  overloads, a nonexistent linker override, and no startup/PEC strings.

[ImportSemanticBoundaryTest](../../../../Compiler/src/test/kotlin/cloth/ImportSemanticBoundaryTest.kt)
now checks successful graph object emission in place of the retired gate. Its
other cases retain imported unused-body checking and binding-before-target errors.
The existing parser/discovery/native-disabled semantic tasks remain unchanged.

The final isolated run succeeds in 7m 34s:

| Task | Suites | Tests | Failures/errors/skips |
| --- | ---: | ---: | --- |
| Compiler test, full suite | 142 | 1,600 | 0 / 0 / 0 |
| Compiler importSyntaxTest | 2 | 23 | 0 / 0 / 0 |
| Compiler importResolutionTest | 1 | 27 | 0 / 0 / 0 |
| Compiler importSemanticsTest | 1 | 33 | 0 / 0 / 0 |
| Shuttle test | 5 | 47 | 0 / 0 / 0 |
| Shuttle configurationTest | 2 | 24 | 0 / 0 / 0 |

```powershell
.\gradlew.bat :Compiler:check :Shuttle:check :Shuttle:installDist --offline --console=plain --no-daemon
```

The observed command also used `--init-script tmp/shuttle-stage6-20261107/isolate.gradle`
before its task arguments. The script redirected each Cloth project's build
directory to tmp/shuttle-stage6-20261107/build, leaving shared class/report
outputs from other work alone. Temporary validation helpers, dependencies,
packaging fixtures and isolated outputs are removed after verification.
All seven changed skills pass the bundled metadata validator; the Shuttle record
also records publication and packaged-launcher checks.

Native imported execution in this stage is Windows x64; foreign-object
emission does not establish execution on Windows ARM64, Linux or macOS. Broader
project examples, host portability and publication remain Stage 7. Separate module
artifacts, packages, incremental builds and public binary ABIs remain deferred.
