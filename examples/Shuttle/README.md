# Shuttle project with nested source folders

This project demonstrates selective and wildcard imports, a nested entry file,
private helpers, enum/string operand contexts and a circular call pair across
two modules. Run it from the Cloth checkout root:

```powershell
.\gradlew.bat :Shuttle:run --args='run --project examples/Shuttle -- nested'
```

Use `./gradlew` on Linux or macOS. The output is:

```text
token Name: Cloth
nested

PEC: 0
```

The layout is intentional:

```text
build.toml
app/
    module.toml
    cli/Main.co
    token/Token.co
    math/Odd.co
support/
    module.toml
    report/Report.co
    math/Even.co
```

The selected build.toml declares App's source root as app and Support's root as
support. `entryPoint = "App.cli.Main.main"` selects app/cli/Main.co and its main.
Each declared root needs one module.toml. Ordinary subfolders need no manifests.

In any source file, `using token.Token: Token, TokenType` resolves the literal
token/Token.co path under the configured roots. Here that is app/token/Token.co.
The importing file's own directory does not change this base. Neither module
names nor a recursive search for matching basenames are part of import paths.
Two roots containing that same complete relative path would be ambiguous.

Main imports describe from support/report/Report.co. Its private prefix helper
stays in that defining file. The even and odd functions import one another from
different module roots and terminate at zero. TokenType's string equality context
contributes its lowercase case name when compared with a string; ordinary
TokenType-to-TokenType equality still compares enum cases.

After `:Shuttle:installDist`, use the packaged launcher directly:

```powershell
.\Shuttle\build\install\shuttle\bin\shuttle.exe build --project examples/Shuttle
.\Shuttle\build\install\shuttle\bin\shuttle.exe run --project examples/Shuttle -- nested
```

The default artifact is examples/Shuttle/build/Imports.exe on Windows or
examples/Shuttle/build/Imports on Linux/macOS. `-o PATH` overrides the artifact
relative to invocation cwd. An object build needs no linker or startup:

```powershell
.\gradlew.bat :Shuttle:run --args='build --project examples/Shuttle --emit object --target aarch64-unknown-linux-gnu -o build/imports.o'
```

The direct Compiler CLI accepts individual source files without project
discovery. Use Shuttle for this project, or supply explicit source roots through
the [compiler request API](../../Compiler/src/main/kotlin/cloth/api/CompilationRequest.kt).
