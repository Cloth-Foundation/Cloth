# Windows Unicode launcher reproduction

BUILD-1 Stage 0 reproduced the installed failures on 2026-11-10. The
combined release package contains `bin/cloth.bat`, the Kotlin/Native
`bin/shuttle.exe` and a shared `runtime/` image linked from Java 25.
Shuttle starts its private JVM worker for compilation. Keep those release
boundaries in the startup design. [Bundled Java](bundled-java-runtime.md) ·
[Native Shuttle](../../shuttle-build/references/native.md) ·
[BUILD-1 plan](../../../../todo/windows-unicode-launcher-plan.md)

## Installed reproduction

The checkout was `9f108c8` and the installed standard library pin was
`7479f055f84d1cd4841cee0cf9d58b169143f3bf`. The Windows x64 host reported
25H2 build `26200.9711`, system ACP 1252 and OEMCP 437. Bundled Java
reported Oracle OpenJDK `25+36-3489`, `amd64` and `native.encoding=Cp1252`.

Stage the complete combined `build/install/cloth` in four real directories
under `tmp/build1-unicode-repro`. The probe used hard links for files with
a copy fallback. Invoke each entry from the separate
`tmp/build1-unicode-repro/caller` directory. Give it a `Main.co` containing
`fn<int> main() = 0`, an `App` module rooted at `.`, a
`build.toml` selecting `App.Main.main`, and a `results` directory.

The probe set `JAVA_HOME` to a missing directory and limited `PATH` to
System32. It cleared inherited Java and command options and used an ASCII
scratch `TEMP` and `TMP`. `cloth.bat` ran through `cmd.exe /d /s /c` with
the full script path and individually quoted arguments. `shuttle.exe`
and bundled `java.exe` ran directly with separate stdout and stderr capture.
Use the following invocations for each staged bundle. `$bundle` names the
absolute directory and `$case` names a distinct output.

```powershell
& "$bundle/runtime/bin/java.exe" -version
& "$bundle/bin/cloth.bat" --help
& "$bundle/bin/cloth.bat" --emit=object Main.co "results/cloth-$case.o"
& "$bundle/bin/shuttle.exe" --help
& "$bundle/bin/shuttle.exe" build --project . --emit=object -o "results/shuttle-$case.o"
```

| Installation directory | Bundled Java | Cloth help and build | Shuttle help | Shuttle build |
| --- | --- | --- | --- | --- |
| `ASCII` | 0 | 0 | 0 | 0 |
| `中文` | 2 | 2 | 0 | 2 |
| `😀` | 2 | 2 | 0 | 2 |
| `Cloth 中文 😀` | 2 | 2 | 0 | 2 |

The two ASCII builds each produced a 343-byte object. Each Unicode build
exited before creating an object. Every status-2 case emitted the following
lines on stderr even though `runtime/bin/java.dll` was present.

```text
Error: could not find java.dll
Error: Could not find Java SE Runtime Environment.
```

Both commands also built successfully from `caller 中文 😀` with the
installation in `ASCII`. Native Shuttle help succeeded in every location.
The failure begins when the bundled Java launcher starts from the relocated
runtime path. The direct `java.exe -version` reproduction precedes JVM
application-class loading.

The ASCII runtime started with an absolute mixed-path `lib\*` classpath
but exited 1 with `ClassNotFoundException: cloth.EntryKt`. A direct
`-Dcloth.unicode.probe=中文😀` arrived as `????`, even with
`file.encoding`, `stdout.encoding` and `stderr.encoding` set to UTF-8.

## Detached manifest experiment

A detached copy of the bundled `java.exe` received an embedded
`activeCodePage=UTF-8` setting through SDK `mt.exe`. The original
installed executable retained its hash and valid signature. The modified
copy reported `NotSigned`. The Windows setting applies to the process
carrying the manifest. [Windows process code page](https://learn.microsoft.com/en-us/windows/apps/design/globalizing/use-utf8-code-page) ·
[Manifest Tool](https://learn.microsoft.com/en-us/windows/win32/sbscs/mt-exe)

- In `Cloth UTF8 中文`, both installed builds exited 0 and wrote
  343-byte objects from the mixed Unicode caller directory.
- In `Cloth UTF8 中文 😀`, direct Java startup exited 0 and preserved
  `java.home`, `user.dir` and the `中文😀` property. Cloth help and
  both builds exited 1 with missing main classes. Both wildcard and
  explicit classpaths reproduced the failure for Cloth.
- In the mixed path the exact Compiler JAR was present in
  `java.class.path`. The system class loader returned no resource for
  `cloth/EntryKt.class`, while `ZipFile` and a `URLClassLoader` built from
  `Path.toUri()` found that same entry. This is consistent with the
  OpenJDK 25 system classpath path-to-URL encoder escaping the UTF-16
  surrogate halves separately. [OpenJDK 25 classpath loader](https://github.com/openjdk/jdk/blob/jdk-25%2B36/src/java.base/share/classes/jdk/internal/loader/URLClassPath.java) ·
  [OpenJDK 25 URL encoding](https://github.com/openjdk/jdk/blob/jdk-25%2B36/src/java.base/share/classes/sun/net/www/ParseUtil.java)

The [Stage 1 proof](windows-unicode-launcher-proof.md) covers bundled JVM
discovery and application loading from supplementary-character paths. The
release acceptance gates for cwd, arguments, JVM options, streams and status
are in the [launcher plan](../../../../todo/windows-unicode-launcher-plan.md#5-implementation-stages).
