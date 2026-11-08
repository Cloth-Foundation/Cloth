# Standard-library allocation fixture

This tree verifies the Stage 4 allocation boundary. It is not the shipped standard
library or a public collection API. The source root contains `module.toml` and
`cloth/Library.co`, so it can also be selected through `--stdlib`.

Run from the compiler repository root with an installed compiler on PATH:

```powershell
cloth run Compiler/src/test/resources/standard-library-runtime/Main.co build/standard-library-runtime --stdlib Compiler/src/test/resources/standard-library-runtime
```

Success prints `PEC: 0` and returns zero. The driver grows a collection, keeps
returned strings across clear/delete, checks class aliases and repeatedly creates
and deletes nodes. Private TextNode objects own their string buffers. The chain
destructor explicitly deletes its nodes.

[Contract, native probes and target evidence](../../../../../.agents/skills/cloth-toolchains/references/standard-library-stage-4.md)
are maintained with the toolchain skill. StandardLibraryRuntimeTest adds native
failure injection and poisoned-free observation to the same source graph.
