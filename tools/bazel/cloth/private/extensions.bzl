"""Imports local bootstrap and parity distributions for Bazel actions."""

_COMPILER_ENV = "CLOTH_BOOTSTRAP_COMPILER"
_COMPILER_RUNTIME_ENV = "CLOTH_BOOTSTRAP_RUNTIME"
_DECLARATION_ORACLE_ENV = "CLOTH_DECLARATION_ORACLE"
_DESCRIPTOR_ENV = "CLOTH_BOOTSTRAP_DESCRIPTOR"
_DEFINITION_ORACLE_ENV = "CLOTH_DEFINITION_ORACLE"
_LEXER_ORACLE_ENV = "CLOTH_LEXER_ORACLE"
_SEMANTIC_ORACLE_ENV = "CLOTH_SEMANTIC_ORACLE"
_ORACLE_CORPUS_ENV = "CLOTH_ORACLE_CORPUS"
_PYTHON_ENV = "CLOTH_BAZEL_PYTHON"
_SHUTTLE_ENV = "CLOTH_SHUTTLE"
_STDLIB_ENV = "CLOTH_BOOTSTRAP_STDLIB"

def _required_path(repository_ctx, name, directory = False):
    value = repository_ctx.os.environ.get(name)
    if not value:
        fail(
            (
                "missing {}. Set it with --repo_env={}=<absolute-path> or " +
                "in the ignored .bazelrc.user file."
            ).format(
                name,
                name,
            ),
        )

    path = repository_ctx.path(value)
    if not path.exists:
        fail("{} does not exist: {}".format(name, path))
    if directory and not path.is_dir:
        fail("{} is not a directory: {}".format(name, path))
    if not directory and path.is_dir:
        fail("{} is not a file: {}".format(name, path))
    return path

def _require_capability(capabilities, field, expected):
    values = capabilities.get(field, [])
    if expected not in values:
        fail(
            "bootstrap compiler does not advertise {} {}: {}".format(
                field,
                expected,
                values,
            ),
        )

def _bootstrap_repository_impl(repository_ctx):
    is_windows = repository_ctx.os.name.lower().find("windows") != -1
    if not is_windows:
        fail(
            "bootstrap toolchain supports Windows host execution; another " +
            "host requires an equivalent portability and hermeticity audit",
        )

    compiler = _required_path(repository_ctx, _COMPILER_ENV)
    descriptor = _required_path(repository_ctx, _DESCRIPTOR_ENV)
    python = _required_path(repository_ctx, _PYTHON_ENV)
    shuttle = _required_path(repository_ctx, _SHUTTLE_ENV)
    standard_library = _required_path(
        repository_ctx,
        _STDLIB_ENV,
        directory = True,
    )
    compiler_runtime = _required_path(
        repository_ctx,
        _COMPILER_RUNTIME_ENV,
        directory = True,
    )

    manifest = standard_library.get_child("Shuttle.toml")
    if not manifest.exists or manifest.is_dir:
        fail("standard-library root has no Shuttle.toml: {}".format(
            standard_library,
        ))

    descriptor_value = json.decode(repository_ctx.read(descriptor))
    if descriptor_value.get("schema") != 1:
        fail("bootstrap toolchain descriptor schema must be 1")
    library_value = descriptor_value.get("standard_library", {})
    if library_value.get("package") != "cloth":
        fail("bootstrap standard-library package must be 'cloth'")
    if library_value.get("version") != "0.6.0":
        fail("bootstrap standard-library version must be 0.6.0")

    capability_environment = {}
    current_path = repository_ctx.os.environ.get("PATH", "")
    capability_environment["PATH"] = str(compiler_runtime)
    if current_path:
        capability_environment["PATH"] += ";" + current_path

    capability_result = repository_ctx.execute(
        [compiler, "--shuttle-protocol-capabilities"],
        environment = capability_environment,
        quiet = True,
        timeout = 30,
    )
    if capability_result.return_code != 0:
        fail(
            "bootstrap compiler capability query failed ({}):\n{}".format(
                capability_result.return_code,
                capability_result.stderr,
            ),
        )
    if capability_result.stderr:
        fail("bootstrap compiler capability query wrote to stderr")

    capabilities = json.decode(capability_result.stdout)
    if capabilities.get("schema") != 1:
        fail("bootstrap compiler capability schema must be 1")
    _require_capability(capabilities, "protocols", 2)
    _require_capability(capabilities, "artifact_formats", 8)
    _require_capability(capabilities, "operations", "compile")
    _require_capability(capabilities, "operations", "link")
    reported_library = capabilities.get("standard_library", {})
    if reported_library.get("package") != "cloth":
        fail("bootstrap compiler does not require the cloth standard library")
    if reported_library.get("version") != "0.6.0":
        fail("bootstrap compiler requires an unexpected library version")

    python_result = repository_ctx.execute(
        [python, "--version"],
        quiet = True,
        timeout = 10,
    )
    if python_result.return_code != 0:
        fail("configured Python interpreter could not be executed")
    if not python_result.stdout.startswith("Python 3."):
        fail("Stage 48 requires a Python 3 action interpreter")

    executable_extension = ".exe"

    repository_ctx.symlink(
        compiler,
        "bin/clothc{}".format(executable_extension),
    )
    repository_ctx.symlink(
        compiler,
        "build/dev/clothc{}".format(executable_extension),
    )
    repository_ctx.symlink(
        descriptor,
        "build/dev/cloth-toolchain.json",
    )
    repository_ctx.symlink(shuttle, "tools/shuttle{}".format(
        executable_extension,
    ))
    for runtime_name in [
        "libgcc_s_seh-1.dll",
        "libstdc++-6.dll",
        "libwinpthread-1.dll",
    ]:
        runtime_file = compiler_runtime.get_child(runtime_name)
        if not runtime_file.exists or runtime_file.is_dir:
            fail("bootstrap compiler runtime is missing {}".format(
                runtime_name,
            ))
        repository_ctx.symlink(
            runtime_file,
            "compiler_runtime/{}".format(runtime_name),
        )
    repository_ctx.symlink(python.dirname, "python_runtime")
    repository_ctx.symlink(descriptor, "cloth-toolchain.json")
    repository_ctx.symlink(standard_library, "stdlib")
    repository_ctx.symlink(standard_library, "std")

    repository_ctx.file(
        "BUILD.bazel",
        """package(default_visibility = ["//visibility:public"])

exports_files([
    "bin/clothc{extension}",
    "cloth-toolchain.json",
    "python_runtime/python{extension}",
    "tools/shuttle{extension}",
])

alias(
    name = "compiler",
    actual = ":bin/clothc{extension}",
)

alias(
    name = "python",
    actual = ":python_runtime/python{extension}",
)

alias(
    name = "shuttle",
    actual = ":tools/shuttle{extension}",
)

alias(
    name = "shuttle_compiler",
    actual = ":build/dev/clothc{extension}",
)

filegroup(
    name = "descriptor",
    srcs = ["cloth-toolchain.json"],
)

filegroup(
    name = "compiler_runtime",
    srcs = glob(["compiler_runtime/**"]),
)

filegroup(
    name = "python_runtime",
    srcs = glob(
        [
            "python_runtime/*.dll",
            "python_runtime/*.zip",
            "python_runtime/DLLs/**",
            "python_runtime/Lib/**/*.py",
        ],
        exclude = [
            "python_runtime/Lib/**/__pycache__/**",
            "python_runtime/Lib/site-packages/**",
        ],
        allow_empty = True,
    ),
)

filegroup(
    name = "standard_library",
    srcs = glob(["stdlib/src/**/*.co"]),
)

filegroup(
    name = "shuttle_distribution",
    srcs = [
        "build/dev/cloth-toolchain.json",
        "std/Shuttle.toml",
    ] + glob(["std/src/**/*.co"]),
)
""".format(extension = executable_extension),
    )

_bootstrap_repository = repository_rule(
    implementation = _bootstrap_repository_impl,
    environ = [
        _COMPILER_ENV,
        _COMPILER_RUNTIME_ENV,
        _DESCRIPTOR_ENV,
        _PYTHON_ENV,
        _SHUTTLE_ENV,
        _STDLIB_ENV,
    ],
    local = True,
)

def _cloth_bootstrap_impl(module_ctx):
    _bootstrap_repository(name = "cloth_bootstrap")

cloth_bootstrap = module_extension(
    implementation = _cloth_bootstrap_impl,
)

def _oracle_repository_impl(repository_ctx):
    is_windows = repository_ctx.os.name.lower().find("windows") != -1
    if not is_windows:
        fail("Stage 48 C++ parity oracles currently require Windows execution")

    declaration = _required_path(repository_ctx, _DECLARATION_ORACLE_ENV)
    definition = _required_path(repository_ctx, _DEFINITION_ORACLE_ENV)
    lexer = _required_path(repository_ctx, _LEXER_ORACLE_ENV)
    semantic = _required_path(repository_ctx, _SEMANTIC_ORACLE_ENV)
    corpus = _required_path(
        repository_ctx,
        _ORACLE_CORPUS_ENV,
        directory = True,
    )
    for name in ["declaration_corpus", "definition_corpus"]:
        path = corpus.get_child(name)
        if not path.exists or not path.is_dir:
            fail("oracle corpus root is missing {}: {}".format(name, corpus))

    repository_ctx.symlink(declaration, "bin/declaration.exe")
    repository_ctx.symlink(definition, "bin/definition.exe")
    repository_ctx.symlink(lexer, "bin/lexer.exe")
    repository_ctx.symlink(semantic, "bin/semantic.exe")
    repository_ctx.symlink(corpus, "corpus")
    repository_ctx.file(
        "BUILD.bazel",
        """package(default_visibility = ["//visibility:public"])

exports_files([
    "bin/declaration.exe",
    "bin/definition.exe",
    "bin/lexer.exe",
    "bin/semantic.exe",
])

alias(
    name = "declaration",
    actual = ":bin/declaration.exe",
)

alias(
    name = "definition",
    actual = ":bin/definition.exe",
)

alias(
    name = "lexer",
    actual = ":bin/lexer.exe",
)

alias(
    name = "semantic",
    actual = ":bin/semantic.exe",
)

filegroup(
    name = "declaration_corpus",
    srcs = glob(["corpus/declaration_corpus/*.co"]),
)

filegroup(
    name = "definition_corpus",
    srcs = glob(["corpus/definition_corpus/*.co"]),
)
""",
    )

_oracle_repository = repository_rule(
    implementation = _oracle_repository_impl,
    environ = [
        _DECLARATION_ORACLE_ENV,
        _DEFINITION_ORACLE_ENV,
        _LEXER_ORACLE_ENV,
        _SEMANTIC_ORACLE_ENV,
        _ORACLE_CORPUS_ENV,
    ],
    local = True,
)

def _cloth_oracles_impl(module_ctx):
    _oracle_repository(name = "cloth_oracles")

cloth_oracles = module_extension(
    implementation = _cloth_oracles_impl,
)
