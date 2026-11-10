# State Runtime Stage 6 publication

The release slice covers nested and attached states, initial activation,
explicit transitions, active-state tests and shared state-method dispatch.
The Compiler and Shuttle entry points exercise the same checked state runtime.
[State plan](../../../../todo/open-questions-plan.md#state-0-implementation-stages) ·
[Activation and dispatch](state-runtime.md) ·
[Attachment rules](state-attachments.md)

## Maintained examples

```text
cloth run examples/States.co build/states
shuttle run --project examples/States
```

- `States.co` constructs a subclass with an inherited state default, uses a
  read-only host field as an omitted argument, and returns the original
  activation's array across a transition. The destructor checks the new
  state. The caller prints its retained values after deletion.
  [Standalone example](../../../../examples/States.co)
- The Shuttle project reuses the imported `Waiting` state on two unrelated
  classes. One selects it as the default with a class-local payload
  expression. The other enters it explicitly. Its entry hook calls a
  defaulted interface method, and its body transitions to each host's own
  `Ready` state. The returned receipt uses a private defining-file helper.
  [Project manifest](../../../../examples/States/build.toml) ·
  [App entry](../../../../examples/States/app/Main.co) ·
  [Reusable state](../../../../examples/States/library/work/Waiting.co)

## Verification routing

[StatePublicationTest](../../../../Compiler/src/test/kotlin/cloth/StatePublicationTest.kt)
loads both examples through the Compiler source graph and verifies executable
LLVM IR and startup objects. The test worker property
`cloth.test.statePublicationArtifacts` exports the IR and objects for
native linker probes.

[ToolchainDistributionTest](../../../../Tests/src/test/java/cloth/ToolchainDistributionTest.java)
runs the examples through the installed commands. It checks a single C3130
and no object output for a provable missing state body. A separate program
transitions during argument evaluation and checks the runtime diagnostic and
exit status 1 when dispatch reaches the missing body.

[StateRuntimeTest](../../../../Compiler/src/test/kotlin/cloth/StateRuntimeTest.kt) and
[StateDispatchTest](../../../../Compiler/src/test/kotlin/cloth/StateDispatchTest.kt)
cover activation retention and buffer release with poisoned frees and
live-byte checks. Their flow and semantic counterparts cover class and
destructor access, alias effects and deferred dispatch selection.

## Focused and installed checks

The 2026-11-09 offline focused run passed 170 Compiler tests across 12 suites.
It covered state syntax, flow, dispatch and imports alongside class inheritance
and lifetime, interface defaults, optional arguments and temporary array
cleanup. Both installed-command tests passed with the combined toolchain
exported from the clean Standard-Library pin
`7479f055f84d1cd4841cee0cf9d58b169143f3bf`.

Installed Cloth ran the standalone fixture and installed Shuttle ran the
imported project on Windows x64 and x86. Both commands returned status 1
with one located C3130 for a source with a provable missing body, before
publishing an object. In the runtime failure case both commands printed
`Changed to Closed` followed by `Invalid state for 'run(int)'` and returned
status 1.

## Native execution and cross-target emission

Each of the two maintained programs produced verified executable LLVM IR and
a startup object on all nine profiles, for 18 pairs. COFF, ELF and Mach-O
headers identified the expected architectures. The GNU x64 startup objects
linked with `cc -no-pie` and `-lm` under Ubuntu WSL using GCC 13.3.0. Both GNU
executables ran from `/` with the expected output and status zero.

| Target | Evidence |
| --- | --- |
| `x86_64-pc-windows-msvc`, `i686-pc-windows-msvc` | Installed commands and native execution, verified IR and startup objects |
| `x86_64-unknown-linux-gnu` | Native execution under Ubuntu WSL, verified IR and startup objects |
| `aarch64-pc-windows-msvc`, `aarch64-unknown-linux-gnu` | Verified IR and startup objects |
| `x86_64-unknown-linux-musl`, `aarch64-unknown-linux-musl` | Verified IR and startup objects |
| `x86_64-apple-darwin`, `aarch64-apple-darwin` | Verified IR and startup objects |

## Full regression

The offline release checks used isolated build outputs and Gradle caches.
The combined run passed the Compiler and distribution suites. Windows
held temporary Git fixture directories open during Shuttle's JUnit cleanup
in the combined run and the first retry. The isolated Shuttle rerun passed
with its standard JUnit temporary directory.

| Task | Tests | Suites |
| --- | ---: | ---: |
| Compiler test | 2,071 | 198 |
| Compiler generic syntax | 90 | 10 |
| Compiler import resolution | 54 | 4 |
| Compiler import semantics | 36 | 1 |
| Compiler import syntax | 23 | 2 |
| Shuttle test | 61 | 8 |
| Shuttle configuration | 25 | 2 |
| Installed distribution test | 12 | 1 |

All results in the table have zero failures, errors and skips.

```powershell
.\gradlew.bat :test :Shuttle:check :Compiler:check --continue --parallel --max-workers=3 --offline --console=plain --no-daemon
.\gradlew.bat :Shuttle:check --max-workers=1 --offline --console=plain --no-daemon
```
