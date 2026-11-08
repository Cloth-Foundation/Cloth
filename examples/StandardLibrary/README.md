# Bundled standard-library example

This example supplies a small stand-in library because the shipped standard
library is still a scaffold. It demonstrates explicit imports, transitive source
resolution and an owned string returned after its private class instance is
deleted. The example's names are not promised standard-library APIs.

From the Cloth repository root, use an installed compiler:

```powershell
cloth run examples/StandardLibrary/app/Main.co build/standard-library-example --stdlib examples/StandardLibrary/library
shuttle run --project examples/StandardLibrary --stdlib examples/StandardLibrary/library
```

On Linux/macOS, the same commands use the Unix launchers. The override resolves
from invocation cwd; it selects `library/`, which contains `cloth/`, rather than
`library/cloth/`. The project manifest lists only App and needs no library entry.
With this fixture installed in a toolchain's `stdlib/`, both commands use it
without the override.

```text
Hello from bundled Cloth

PEC: 0
```

The application's [Main.co](app/Main.co) explicitly selects `greeting` from
[Greeting.co](library/cloth/example/Greeting.co), which imports `message` from
[Words.co](library/cloth/example/Words.co). A private OwnedGreeting instance owns
its string field; its copied result survives deletion. Other files do not enter
the graph merely because a library is available.

[Packaged-command tests](../../src/test/java/cloth/ToolchainDistributionTest.java)
copy these sources into a disposable installed bundle, compile with both commands
from outside the checkout, relocate the bundle, then make its original paths and
the application's source paths unavailable before running the native programs.
Both commands also emit this graph for all nine supported target profiles.
[Compiler publication tests](../../Compiler/src/test/kotlin/cloth/StandardLibraryPublicationTest.kt)
check the exact three-file graph, native execution and all-profile executable IR
and startup objects. Test outputs track changes to this example.

See the [toolchain guide](../../docs/standard-library.md) for source selection,
release layout and current limits, and the [Stage 5 record](../../.agents/skills/cloth-toolchains/references/standard-library-stage-5.md)
for observed verification and target scope.
