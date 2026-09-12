# Cloth Bazel integration

These rules are private to the self-hosted compiler repository. They build
declared Cloth packages by calling compiler process protocol 2 directly. They
do not read `Shuttle.toml`, invoke Shuttle, or define a public `rules_cloth`
API.

## Local bootstrap configuration

Create an ignored `.bazelrc.user` at the repository root. Use absolute paths
with forward slashes:

```text
common --repo_env=CLOTH_BOOTSTRAP_COMPILER=D:/cCloth/build/dev/clothc.exe
common --repo_env=CLOTH_BOOTSTRAP_DESCRIPTOR=D:/cCloth/build/dev/cloth-toolchain.json
common --repo_env=CLOTH_BOOTSTRAP_STDLIB=D:/cCloth/std
common --repo_env=CLOTH_BOOTSTRAP_RUNTIME=C:/mingw64/bin
common --repo_env=CLOTH_LEXER_ORACLE=D:/cCloth/build/dev/cloth_lexer_parity_oracle.exe
common --repo_env=CLOTH_DECLARATION_ORACLE=D:/cCloth/build/dev/cloth_declaration_parity_oracle.exe
common --repo_env=CLOTH_DEFINITION_ORACLE=D:/cCloth/build/dev/cloth_definition_parity_oracle.exe
common --repo_env=CLOTH_SEMANTIC_ORACLE=D:/cCloth/build/dev/cloth_semantic_parity_oracle.exe
common --repo_env=CLOTH_ORACLE_CORPUS=D:/cCloth/tests/integration
common --repo_env=CLOTH_BAZEL_PYTHON=C:/Python313/python.exe
common --repo_env=CLOTH_SHUTTLE=D:/cCloth/shuttle/target/debug/shuttle.exe
```

`CLOTH_BOOTSTRAP_RUNTIME` must contain the MinGW runtime DLLs used by the
bootstrap compiler. Stage 48 audits Windows host execution; another host must
pass the equivalent portability and hermeticity audit before registration. The
configured compiler, descriptor, standard-library sources, action interpreter,
host runtime, four C++ frontend oracles, oracle corpora, and Shuttle executable
become declared Bazel inputs.
Configuration fails instead of searching `PATH` or guessing a neighboring
checkout. Shuttle is used only by the explicit compatibility test; Cloth
compile and link actions continue to use compiler protocol 2 directly.
The five oracle settings are test-only and required by the exhaustive parity
target in `//tests:full`; ordinary compiler builds do not load that repository.

An optional startup setting can keep Bazel state outside the checkout:

```text
startup --output_user_root=D:/build/cloth-bazel
```

Do not commit `.bazelrc.user`; machine paths do not belong in repository
configuration.

## Targets

BUILD files load `cloth_library`, `cloth_binary`, and `cloth_test` from
`//tools/bazel/cloth:defs.bzl`. Every source is declared through `srcs`.
Dependencies map Bazel labels to Cloth import aliases:

```starlark
cloth_library(
    name = "frontend",
    srcs = glob(["**/*.co"]),
    deps = {
        "//src/source": "source",
    },
    package_name = "clothc-frontend",
)
```

A `cloth_binary` currently requires `Main.co`. A `cloth_test` names one class
whose public static `Run()` function performs the test; the rule supplies its
process entry and the private `testing` dependency. `Run()` may declare any
typed error because the generated process boundary declares `throws Error`.

Declare fixture files with `data` and open them by repository-relative path.
Repository configuration enables Bazel's runfiles tree on Windows, so tests do
not depend on the checkout as their working directory.

The public suites are:

```sh
bazel test //tests:presubmit
bazel test //tests:full
```

`presubmit` runs deterministic unit and bounded integration tests. `full` adds
exhaustive C++ parity, focused parity records, GC, depth/resource checks, every
expected-failure diagnostic, and the isolated Shuttle compatibility audit for
x86-64 and wasm32. Useful focused commands include:

```sh
bazel test //tests/smoke:assert_smoke_test
bazel test //tests/self_host/tests:expression_parser_test
bazel test //tests/self_host:cross_compiler_parity_test
bazel test //tests/self_host:shuttle_compatibility_test
bazel test //tests/self_host/tools/syntax_tree_failure:validity_test
bazel build //src:clothc
bazel run //src:clothc -- --version
```

The manual negative smoke target is run explicitly when validating failed-test
diagnostics:

```sh
bazel test //tests/smoke:assert_failure_test
```

The migration inventory and manual timeout audit are documented in
[`tests/self_host/README.md`](../../../tests/self_host/README.md).
