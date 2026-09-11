"""Builds and tests Cloth packages through the compiler process protocol."""

load(":providers.bzl", "ClothPackageInfo")

_TOOLCHAIN_TYPE = "//tools/bazel/cloth:toolchain_type"

def _toolchain(ctx):
    return ctx.toolchains[_TOOLCHAIN_TYPE].cloth

def _valid_package_name(value):
    if not value or value[0] not in "abcdefghijklmnopqrstuvwxyz":
        return False
    for index in range(len(value)):
        character = value[index]
        if character not in "abcdefghijklmnopqrstuvwxyz0123456789-":
            return False
    return True

def _valid_alias(value):
    if not value or value[0] not in "abcdefghijklmnopqrstuvwxyz":
        return False
    for index in range(len(value)):
        character = value[index]
        if character not in "abcdefghijklmnopqrstuvwxyz0123456789_":
            return False
    return True

def _validate_package(package_name, package_version):
    if not _valid_package_name(package_name):
        fail("invalid Cloth package name: {}".format(package_name))
    if not package_version:
        fail("Cloth package version cannot be empty")

def _source_relative_path(ctx, source):
    if source.extension != "co":
        fail("Cloth source must end in .co: {}".format(source.short_path))
    prefix = ctx.label.package
    if prefix:
        prefix += "/"
    if not source.short_path.startswith(prefix):
        fail(
            "Cloth source must belong to Bazel package {}: {}".format(
                ctx.label.package,
                source.short_path,
            ),
        )
    relative = source.short_path[len(prefix):]
    if not relative or relative.startswith("../") or "/../" in relative:
        fail("invalid Cloth source path: {}".format(source.short_path))
    return relative

def _standard_library_relative_path(source):
    marker = "/stdlib/src/"
    index = source.short_path.find(marker)
    if index == -1:
        prefix = "stdlib/src/"
        if source.short_path.startswith(prefix):
            return source.short_path[len(prefix):]
        fail("standard-library source is outside stdlib/src: {}".format(
            source.short_path,
        ))
    return source.short_path[index + len(marker):]

def _source_mappings(ctx, sources):
    mappings = []
    logical_paths = {}
    case_folded_paths = {}
    for source in sources:
        relative = _source_relative_path(ctx, source)
        if relative in logical_paths:
            fail("duplicate logical Cloth source: {}".format(relative))
        folded = relative.lower()
        if folded in case_folded_paths:
            fail(
                "case-colliding Cloth sources: {} and {}".format(
                    case_folded_paths[folded],
                    relative,
                ),
            )
        logical_paths[relative] = True
        case_folded_paths[folded] = relative
        mappings.append((relative, source))
    return sorted(mappings)

def _record_name(record):
    return record.package_name

def _direct_alias(item):
    return item[0]

def _dependency_state(ctx, additional = []):
    toolchain = _toolchain(ctx)
    dependencies = []
    aliases = {}

    standard_library = ctx.attr._standard_library
    dependencies.append((standard_library, "cloth"))
    aliases["cloth"] = True

    for target, alias in additional:
        if alias in aliases:
            fail("duplicate Cloth dependency alias: {}".format(alias))
        aliases[alias] = True
        dependencies.append((target, alias))

    for target, alias in ctx.attr.deps.items():
        if alias in aliases:
            fail("duplicate Cloth dependency alias: {}".format(alias))
        aliases[alias] = True
        dependencies.append((target, alias))

    direct = []
    closure_by_name = {}
    for target, alias in dependencies:
        if not _valid_alias(alias):
            fail("invalid Cloth dependency alias: {}".format(alias))
        if ClothPackageInfo not in target:
            fail("dependency does not publish ClothPackageInfo: {}".format(
                target.label,
            ))
        package = target[ClothPackageInfo]
        if (
            package.target != toolchain.target or
            package.artifact_kind != toolchain.artifact_kind
        ):
            fail("Cloth dependency target or artifact kind is incompatible")
        direct.append((alias, package.package_name))
        for record in package.closure:
            existing = closure_by_name.get(record.package_name)
            if existing and (
                existing.package_version != record.package_version or
                existing.artifact != record.artifact or
                existing.receipt != record.receipt
            ):
                fail("conflicting Cloth package closure: {}".format(
                    record.package_name,
                ))
            closure_by_name[record.package_name] = record

    closure = sorted(closure_by_name.values(), key = _record_name)
    return sorted(direct, key = _direct_alias), closure

def _add_dependencies(arguments, direct, closure):
    for alias, package_name in direct:
        arguments.add("--direct")
        arguments.add(alias)
        arguments.add(package_name)
    for record in closure:
        arguments.add("--artifact")
        arguments.add(record.package_name)
        arguments.add(record.package_version)
        arguments.add(record.receipt)
        arguments.add(record.artifact)

def _action_inputs(toolchain, sources, closure):
    direct = [
        toolchain.compiler,
        toolchain.descriptor,
        toolchain.driver,
    ]
    direct.extend(toolchain.compiler_runtime.to_list())
    direct.extend(sources)
    direct.extend(toolchain.interpreter_runtime.to_list())
    for record in closure:
        direct.append(record.artifact)
        direct.append(record.receipt)
    return depset(direct = direct)

def _compile(
        ctx,
        package_name,
        package_version,
        source_mappings,
        direct,
        dependency_closure,
        entry = None):
    toolchain = _toolchain(ctx)
    artifact = ctx.actions.declare_file(ctx.label.name + ".cpa")
    receipt = ctx.actions.declare_file(ctx.label.name + ".receipt.json")

    arguments = ctx.actions.args()
    arguments.add("-S")
    arguments.add(toolchain.driver)
    arguments.add("compile")
    arguments.add("--compiler")
    arguments.add(toolchain.compiler)
    arguments.add("--protocol")
    arguments.add(toolchain.protocol)
    arguments.add("--target")
    arguments.add(toolchain.target)
    arguments.add("--artifact-kind")
    arguments.add(toolchain.artifact_kind)
    arguments.add("--receipt-schema")
    arguments.add(toolchain.receipt_schema)
    arguments.add("--artifact-format")
    arguments.add(toolchain.artifact_format)
    arguments.add("--output")
    arguments.add(artifact)
    arguments.add("--receipt")
    arguments.add(receipt)
    arguments.add("--package-name")
    arguments.add(package_name)
    arguments.add("--package-version")
    arguments.add(package_version)
    if entry:
        arguments.add("--entry")
        arguments.add(entry)
    for logical_path, source in source_mappings:
        arguments.add("--source")
        arguments.add(logical_path)
        arguments.add(source)
    _add_dependencies(arguments, direct, dependency_closure)

    sources = [source for _, source in source_mappings]
    ctx.actions.run(
        arguments = [arguments],
        env = {
            "PATH": (
                toolchain.interpreter.dirname +
                toolchain.path_separator +
                toolchain.compiler_runtime_directory
            ),
            "PYTHONHOME": toolchain.interpreter.dirname,
            "PYTHONDONTWRITEBYTECODE": "1",
        },
        executable = toolchain.interpreter,
        inputs = _action_inputs(toolchain, sources, dependency_closure),
        mnemonic = "ClothCompile",
        outputs = [artifact, receipt],
        progress_message = "Compiling Cloth package %{label}",
        toolchain = _TOOLCHAIN_TYPE,
        tools = [toolchain.compiler],
    )
    return artifact, receipt

def _package_record(package_name, package_version, artifact, receipt):
    return struct(
        artifact = artifact,
        package_name = package_name,
        package_version = package_version,
        receipt = receipt,
    )

def _package_info(
        package_name,
        package_version,
        artifact,
        receipt,
        toolchain,
        direct,
        dependency_closure):
    record = _package_record(
        package_name,
        package_version,
        artifact,
        receipt,
    )
    closure = list(dependency_closure)
    closure.append(record)
    closure = sorted(closure, key = _record_name)
    return ClothPackageInfo(
        artifact = artifact,
        artifact_kind = toolchain.artifact_kind,
        closure = closure,
        direct_dependencies = [
            struct(alias = alias, package_name = dependency)
            for alias, dependency in direct
        ],
        package_name = package_name,
        package_version = package_version,
        receipt = receipt,
        target = toolchain.target,
    )

def _cloth_standard_library_impl(ctx):
    toolchain = _toolchain(ctx)
    mappings = []
    logical_paths = {}
    case_folded_paths = {}
    for source in toolchain.standard_library.to_list():
        if source.extension != "co":
            continue
        relative = _standard_library_relative_path(source)
        if relative in logical_paths:
            fail("duplicate standard-library source: {}".format(relative))
        folded = relative.lower()
        if folded in case_folded_paths:
            fail(
                "case-colliding standard-library sources: {} and {}".format(
                    case_folded_paths[folded],
                    relative,
                ),
            )
        logical_paths[relative] = True
        case_folded_paths[folded] = relative
        mappings.append((relative, source))
    mappings = sorted(mappings)
    artifact, receipt = _compile(
        ctx,
        toolchain.standard_library_package,
        toolchain.standard_library_version,
        mappings,
        [],
        [],
    )
    package = _package_info(
        toolchain.standard_library_package,
        toolchain.standard_library_version,
        artifact,
        receipt,
        toolchain,
        [],
        [],
    )
    return [
        DefaultInfo(files = depset([artifact, receipt])),
        package,
    ]

cloth_standard_library = rule(
    implementation = _cloth_standard_library_impl,
    doc = "Compiles the standard library paired with the selected toolchain.",
    toolchains = [_TOOLCHAIN_TYPE],
)

def _cloth_library_impl(ctx):
    _validate_package(ctx.attr.package_name, ctx.attr.package_version)
    mappings = _source_mappings(ctx, ctx.files.srcs)
    direct, dependency_closure = _dependency_state(ctx)
    artifact, receipt = _compile(
        ctx,
        ctx.attr.package_name,
        ctx.attr.package_version,
        mappings,
        direct,
        dependency_closure,
    )
    package = _package_info(
        ctx.attr.package_name,
        ctx.attr.package_version,
        artifact,
        receipt,
        _toolchain(ctx),
        direct,
        dependency_closure,
    )
    return [
        DefaultInfo(files = depset([artifact, receipt])),
        package,
    ]

_PACKAGE_ATTRS = {
    "_standard_library": attr.label(
        default = "//tools/bazel/cloth:standard_library",
        providers = [ClothPackageInfo],
    ),
    "deps": attr.label_keyed_string_dict(
        providers = [ClothPackageInfo],
    ),
    "package_name": attr.string(mandatory = True),
    "package_version": attr.string(default = "0.0.0"),
    "srcs": attr.label_list(
        allow_files = [".co"],
        mandatory = True,
    ),
}

cloth_library = rule(
    implementation = _cloth_library_impl,
    attrs = _PACKAGE_ATTRS,
    doc = "Compiles one explicitly declared Cloth package.",
    toolchains = [_TOOLCHAIN_TYPE],
)

def _link(ctx, package, data):
    toolchain = _toolchain(ctx)
    executable = ctx.actions.declare_file(
        ctx.label.name + toolchain.executable_extension,
    )
    arguments = ctx.actions.args()
    arguments.add("-S")
    arguments.add(toolchain.driver)
    arguments.add("link")
    arguments.add("--compiler")
    arguments.add(toolchain.compiler)
    arguments.add("--protocol")
    arguments.add(toolchain.protocol)
    arguments.add("--target")
    arguments.add(toolchain.target)
    arguments.add("--artifact-kind")
    arguments.add(toolchain.artifact_kind)
    arguments.add("--receipt-schema")
    arguments.add(toolchain.receipt_schema)
    arguments.add("--artifact-format")
    arguments.add(toolchain.artifact_format)
    arguments.add("--output")
    arguments.add(executable)
    arguments.add("--root-package")
    arguments.add(package.package_name)
    arguments.add("--entry")
    arguments.add("Main.co")
    _add_dependencies(arguments, [], package.closure)

    ctx.actions.run(
        arguments = [arguments],
        env = {
            "PATH": (
                toolchain.interpreter.dirname +
                toolchain.path_separator +
                toolchain.compiler_runtime_directory
            ),
            "PYTHONHOME": toolchain.interpreter.dirname,
            "PYTHONDONTWRITEBYTECODE": "1",
        },
        executable = toolchain.interpreter,
        inputs = _action_inputs(toolchain, [], package.closure),
        mnemonic = "ClothLink",
        outputs = [executable],
        progress_message = "Linking Cloth executable %{label}",
        toolchain = _TOOLCHAIN_TYPE,
        tools = [toolchain.compiler],
    )
    runfiles = ctx.runfiles(files = data)
    for target in ctx.attr.data:
        runfiles = runfiles.merge(target[DefaultInfo].default_runfiles)

    return [
        DefaultInfo(
            executable = executable,
            files = depset([executable]),
            runfiles = runfiles,
        ),
        OutputGroupInfo(
            cloth_package = depset([package.artifact, package.receipt]),
        ),
        package,
    ]

def _cloth_binary_impl(ctx):
    _validate_package(ctx.attr.package_name, ctx.attr.package_version)
    mappings = _source_mappings(ctx, ctx.files.srcs)
    if ctx.attr.entry != "Main.co":
        fail("Stage 48.2 supports only a root Main.co entry")
    direct, dependency_closure = _dependency_state(ctx)
    artifact, receipt = _compile(
        ctx,
        ctx.attr.package_name,
        ctx.attr.package_version,
        mappings,
        direct,
        dependency_closure,
        entry = ctx.attr.entry,
    )
    package = _package_info(
        ctx.attr.package_name,
        ctx.attr.package_version,
        artifact,
        receipt,
        _toolchain(ctx),
        direct,
        dependency_closure,
    )
    return _link(ctx, package, ctx.files.data)

_EXECUTABLE_ATTRS = dict(_PACKAGE_ATTRS)
_EXECUTABLE_ATTRS.update({
    "data": attr.label_list(allow_files = True),
    "entry": attr.string(default = "Main.co"),
})

cloth_binary = rule(
    implementation = _cloth_binary_impl,
    attrs = _EXECUTABLE_ATTRS,
    doc = "Compiles and links one Cloth executable.",
    executable = True,
    toolchains = [_TOOLCHAIN_TYPE],
)

def _valid_test_class(value):
    if not value or value[0] not in "ABCDEFGHIJKLMNOPQRSTUVWXYZ":
        return False
    for index in range(len(value)):
        character = value[index]
        if character not in (
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ" +
            "abcdefghijklmnopqrstuvwxyz" +
            "0123456789_"
        ):
            return False
    return True

def _cloth_test_impl(ctx):
    _validate_package(ctx.attr.package_name, ctx.attr.package_version)
    if not _valid_test_class(ctx.attr.test_class):
        fail("invalid Cloth test class: {}".format(ctx.attr.test_class))

    runner = ctx.actions.declare_file(ctx.label.name + ".generated/Main.co")
    ctx.actions.write(
        output = runner,
        content = """static func Main(): int32 throws Error {
  %s.Run();
  return 0;
}
""" % ctx.attr.test_class,
    )

    mappings = _source_mappings(ctx, ctx.files.srcs)
    for logical_path, _ in mappings:
        if logical_path.lower() == "main.co":
            fail("cloth_test reserves Main.co for its generated entry")
    mappings.append(("Main.co", runner))
    mappings = sorted(mappings)

    additional = [(ctx.attr._test_support, "testing")]
    direct, dependency_closure = _dependency_state(ctx, additional)
    artifact, receipt = _compile(
        ctx,
        ctx.attr.package_name,
        ctx.attr.package_version,
        mappings,
        direct,
        dependency_closure,
        entry = "Main.co",
    )
    package = _package_info(
        ctx.attr.package_name,
        ctx.attr.package_version,
        artifact,
        receipt,
        _toolchain(ctx),
        direct,
        dependency_closure,
    )
    return _link(ctx, package, ctx.files.data)

_TEST_ATTRS = dict(_PACKAGE_ATTRS)
_TEST_ATTRS.update({
    "_test_support": attr.label(
        default = "//tests/support/src:test_support",
        providers = [ClothPackageInfo],
    ),
    "data": attr.label_list(allow_files = True),
    "test_class": attr.string(mandatory = True),
})

cloth_test = rule(
    implementation = _cloth_test_impl,
    attrs = _TEST_ATTRS,
    doc = "Compiles and runs one isolated Cloth test class.",
    executable = True,
    test = True,
    toolchains = [_TOOLCHAIN_TYPE],
)

def _batch_safe(value, field):
    for character in ["\r", "\n", "\"", "%", "&", "|", "<", ">", "^", "!"]:
        if value.find(character) != -1:
            fail("{} contains an unsupported batch character".format(field))

def _expected_failure_test_impl(ctx):
    _batch_safe(ctx.attr.expected_stderr, "expected_stderr")
    for argument in ctx.attr.failure_args:
        _batch_safe(argument, "failure_args")

    binary = ctx.executable.binary
    if ctx.attr.expected_status == 0 or ctx.attr.expected_status < -1:
        fail("expected_status must be -1 or a positive process status")
    runner = ctx.actions.declare_file(ctx.label.name + ".bat")
    binary_runfile = binary.short_path.replace("/", "\\")
    quoted_arguments = " ".join([
        "\"{}\"".format(argument)
        for argument in ctx.attr.failure_args
    ])
    status_check = (
        "if \"%status%\"==\"0\" goto unexpected"
        if ctx.attr.expected_status == -1
        else "if not \"%status%\"==\"{}\" goto unexpected".format(
            ctx.attr.expected_status,
        )
    )
    ctx.actions.write(
        output = runner,
        content = """@echo off
setlocal EnableExtensions DisableDelayedExpansion
set "stdout=%%TEST_TMPDIR%%\\%s.stdout"
set "stderr=%%TEST_TMPDIR%%\\%s.stderr"
"%%RUNFILES_DIR%%\\_main\\%s" %s > "%%stdout%%" 2> "%%stderr%%"
set "status=%%ERRORLEVEL%%"
%s
for %%%%A in ("%%stdout%%") do if not "%%%%~zA"=="0" goto unexpected
%%SystemRoot%%\\System32\\findstr.exe /L /C:"%s" "%%stderr%%" >nul
if errorlevel 1 goto unexpected
exit /b 0

:unexpected
echo Expected %s to fail with stderr containing: %s 1>&2
echo Actual status: %%status%% 1>&2
if exist "%%stdout%%" type "%%stdout%%" 1>&2
if exist "%%stderr%%" type "%%stderr%%" 1>&2
exit /b 1
""" % (
            ctx.label.name,
            ctx.label.name,
            binary_runfile,
            quoted_arguments,
            status_check,
            ctx.attr.expected_stderr,
            ctx.label,
            ctx.attr.expected_stderr,
        ),
        is_executable = True,
    )

    files = [binary]
    for target in ctx.attr.test_data:
        files.extend(target[DefaultInfo].files.to_list())
    runfiles = ctx.runfiles(files = files)
    runfiles = runfiles.merge(ctx.attr.binary[DefaultInfo].default_runfiles)
    for target in ctx.attr.test_data:
        runfiles = runfiles.merge(target[DefaultInfo].default_runfiles)
    return [DefaultInfo(executable = runner, runfiles = runfiles)]

expected_failure_test = rule(
    implementation = _expected_failure_test_impl,
    attrs = {
        "failure_args": attr.string_list(),
        "binary": attr.label(
            cfg = "target",
            executable = True,
            mandatory = True,
        ),
        "expected_stderr": attr.string(mandatory = True),
        "expected_status": attr.int(default = -1),
        "test_data": attr.label_list(allow_files = True),
    },
    doc = "Passes only when an executable fails with the required diagnostic.",
    executable = True,
    test = True,
)

def _output_test_impl(ctx):
    for argument in ctx.attr.process_args:
        _batch_safe(argument, "process_args")

    binary = ctx.executable.binary
    expected = ctx.file.expected_stdout
    runner = ctx.actions.declare_file(ctx.label.name + ".bat")
    binary_runfile = binary.short_path.replace("/", "\\")
    quoted_arguments = " ".join([
        "\"{}\"".format(argument)
        for argument in ctx.attr.process_args
    ])
    expected_runfile = expected.short_path.replace("/", "\\")
    ctx.actions.write(
        output = runner,
        content = """@echo off
setlocal EnableExtensions DisableDelayedExpansion
set "stdout=%%TEST_TMPDIR%%\\%s.stdout"
set "stderr=%%TEST_TMPDIR%%\\%s.stderr"
set "expected=%%RUNFILES_DIR%%\\_main\\%s"
"%%RUNFILES_DIR%%\\_main\\%s" %s > "%%stdout%%" 2> "%%stderr%%"
set "status=%%ERRORLEVEL%%"
if not "%%status%%"=="0" goto unexpected
for %%%%A in ("%%stderr%%") do if not "%%%%~zA"=="0" goto unexpected
%%SystemRoot%%\\System32\\fc.exe /A "%%stdout%%" "%%expected%%" >nul
if errorlevel 1 goto unexpected
exit /b 0

:unexpected
echo Expected %s to exit successfully and match %s 1>&2
echo Actual status: %%status%% 1>&2
if exist "%%stdout%%" type "%%stdout%%" 1>&2
if exist "%%stderr%%" type "%%stderr%%" 1>&2
exit /b 1
""" % (
            ctx.label.name,
            ctx.label.name,
            expected_runfile,
            binary_runfile,
            quoted_arguments,
            ctx.label,
            expected.short_path,
        ),
        is_executable = True,
    )

    files = [binary, expected]
    for target in ctx.attr.test_data:
        files.extend(target[DefaultInfo].files.to_list())
    runfiles = ctx.runfiles(files = files)
    runfiles = runfiles.merge(ctx.attr.binary[DefaultInfo].default_runfiles)
    for target in ctx.attr.test_data:
        runfiles = runfiles.merge(target[DefaultInfo].default_runfiles)
    return [DefaultInfo(executable = runner, runfiles = runfiles)]

output_test = rule(
    implementation = _output_test_impl,
    attrs = {
        "binary": attr.label(
            cfg = "target",
            executable = True,
            mandatory = True,
        ),
        "expected_stdout": attr.label(
            allow_single_file = True,
            mandatory = True,
        ),
        "process_args": attr.string_list(),
        "test_data": attr.label_list(allow_files = True),
    },
    doc = "Passes when a tool succeeds silently and emits the exact record file.",
    executable = True,
    test = True,
)

def _windows_path(value):
    return value.replace("/", "\\")

def _shuttle_compatibility_test_impl(ctx):
    toolchain = _toolchain(ctx)
    manifest = ctx.actions.declare_file(ctx.label.name + ".inputs")
    repository_files = sorted(
        depset(ctx.files.repository_files).to_list(),
        key = lambda file: file.short_path,
    )
    ctx.actions.write(
        manifest,
        "\n".join([file.short_path for file in repository_files]) + "\n",
    )

    runner = ctx.actions.declare_file(ctx.label.name + ".bat")
    runtime_file = toolchain.compiler_runtime.to_list()[0]
    runtime_directory = runtime_file.short_path[
        :-(len(runtime_file.basename) + 1)
    ]
    interpreter_directory = toolchain.interpreter.short_path[
        :-(len(toolchain.interpreter.basename) + 1)
    ]
    ctx.actions.write(
        output = runner,
        content = """@echo off
setlocal EnableExtensions DisableDelayedExpansion
set "main=%%RUNFILES_DIR%%\\_main"
set "PYTHONHOME=%%main%%\\%s"
set "PYTHONDONTWRITEBYTECODE=1"
set "PATH=%%main%%\\%s;%%PATH%%"
"%%main%%\\%s" -S "%%main%%\\%s" ^
  --compiler "%%main%%\\%s" ^
  --main-root "%%main%%" ^
  --manifest "%%main%%\\%s" ^
  --shuttle "%%main%%\\%s" ^
  --stage "%%TEST_TMPDIR%%\\Shuttle Stage 49"
exit /b %%ERRORLEVEL%%
""" % (
            _windows_path(interpreter_directory),
            _windows_path(runtime_directory),
            _windows_path(toolchain.interpreter.short_path),
            _windows_path(ctx.file._audit_script.short_path),
            _windows_path(toolchain.shuttle_compiler.short_path),
            _windows_path(manifest.short_path),
            _windows_path(toolchain.shuttle.short_path),
        ),
        is_executable = True,
    )

    files = [
        ctx.file._audit_script,
        manifest,
        toolchain.interpreter,
        toolchain.shuttle,
        toolchain.shuttle_compiler,
    ]
    files.extend(toolchain.compiler_runtime.to_list())
    files.extend(toolchain.interpreter_runtime.to_list())
    files.extend(toolchain.shuttle_distribution.to_list())
    files.extend(repository_files)
    return [
        DefaultInfo(
            executable = runner,
            runfiles = ctx.runfiles(files = files),
        ),
    ]

shuttle_compatibility_test = rule(
    implementation = _shuttle_compatibility_test_impl,
    attrs = {
        "_audit_script": attr.label(
            allow_single_file = [".py"],
            default = "//tests/self_host/tools:shuttle_compatibility.py",
        ),
        "repository_files": attr.label_list(
            allow_files = True,
            mandatory = True,
        ),
    },
    doc = "Checks the self-hosted package graph through Shuttle in test temp.",
    executable = True,
    test = True,
    toolchains = [_TOOLCHAIN_TYPE],
)

def _manifest_entries(kind, files):
    return [
        "{}\t{}".format(kind, file.short_path)
        for file in sorted(files, key = lambda file: file.short_path)
    ]

def _cross_compiler_parity_test_impl(ctx):
    toolchain = _toolchain(ctx)
    manifest = ctx.actions.declare_file(ctx.label.name + ".inputs")
    entries = []
    entries.extend(_manifest_entries("lexer", ctx.files.lexer_inputs))
    entries.extend(_manifest_entries("compiler", ctx.files.compiler_inputs))
    entries.extend(_manifest_entries("declaration", ctx.files.declaration_corpus))
    entries.extend(_manifest_entries("definition", ctx.files.definition_corpus))
    entries.extend(_manifest_entries("fixture", ctx.files.definition_fixtures))
    ctx.actions.write(manifest, "\n".join(entries) + "\n")

    interpreter_directory = toolchain.interpreter.short_path[
        :-(len(toolchain.interpreter.basename) + 1)
    ]
    runtime_file = toolchain.compiler_runtime.to_list()[0]
    runtime_directory = runtime_file.short_path[
        :-(len(runtime_file.basename) + 1)
    ]
    runner = ctx.actions.declare_file(ctx.label.name + ".bat")
    ctx.actions.write(
        output = runner,
        content = """@echo off
setlocal EnableExtensions DisableDelayedExpansion
set "main=%%RUNFILES_DIR%%\\_main"
set "PYTHONHOME=%%main%%\\%s"
set "PYTHONDONTWRITEBYTECODE=1"
set "PATH=%%main%%\\%s;%%PATH%%"
"%%main%%\\%s" -S "%%main%%\\%s" ^
  --main-root "%%main%%" ^
  --manifest "%%main%%\\%s" ^
  --temporary-root "%%TEST_TMPDIR%%\\parity corpus" ^
  --jobs %s ^
  --lexer-oracle "%%main%%\\%s" ^
  --lexer-records "%%main%%\\%s" ^
  --declaration-oracle "%%main%%\\%s" ^
  --declaration-records "%%main%%\\%s" ^
  --definition-oracle "%%main%%\\%s" ^
  --definition-records "%%main%%\\%s"
exit /b %%ERRORLEVEL%%
""" % (
            _windows_path(interpreter_directory),
            _windows_path(runtime_directory),
            _windows_path(toolchain.interpreter.short_path),
            _windows_path(ctx.file._parity_script.short_path),
            _windows_path(manifest.short_path),
            ctx.attr.jobs,
            _windows_path(ctx.executable.lexer_oracle.short_path),
            _windows_path(ctx.executable.lexer_records.short_path),
            _windows_path(ctx.executable.declaration_oracle.short_path),
            _windows_path(ctx.executable.declaration_records.short_path),
            _windows_path(ctx.executable.definition_oracle.short_path),
            _windows_path(ctx.executable.definition_records.short_path),
        ),
        is_executable = True,
    )

    files = [
        ctx.executable.declaration_records,
        ctx.executable.definition_records,
        ctx.executable.lexer_records,
        ctx.file._parity_script,
        manifest,
        ctx.executable.declaration_oracle,
        ctx.executable.definition_oracle,
        toolchain.interpreter,
        ctx.executable.lexer_oracle,
    ]
    files.extend(ctx.files.compiler_inputs)
    files.extend(ctx.files.declaration_corpus)
    files.extend(ctx.files.definition_corpus)
    files.extend(ctx.files.definition_fixtures)
    files.extend(ctx.files.lexer_inputs)
    files.extend(toolchain.compiler_runtime.to_list())
    files.extend(toolchain.interpreter_runtime.to_list())
    runfiles = ctx.runfiles(files = files)
    for target in [
        ctx.attr.declaration_records,
        ctx.attr.definition_records,
        ctx.attr.lexer_records,
    ]:
        runfiles = runfiles.merge(target[DefaultInfo].default_runfiles)
    return [DefaultInfo(executable = runner, runfiles = runfiles)]

cross_compiler_parity_test = rule(
    implementation = _cross_compiler_parity_test_impl,
    attrs = {
        "_parity_script": attr.label(
            allow_single_file = [".py"],
            default = "//tests/self_host/tools:cross_compiler_parity.py",
        ),
        "compiler_inputs": attr.label_list(
            allow_files = [".co"],
            mandatory = True,
        ),
        "declaration_corpus": attr.label_list(
            allow_files = [".co"],
            mandatory = True,
        ),
        "declaration_oracle": attr.label(
            allow_single_file = True,
            cfg = "exec",
            executable = True,
            mandatory = True,
        ),
        "declaration_records": attr.label(
            cfg = "target",
            executable = True,
            mandatory = True,
        ),
        "definition_corpus": attr.label_list(
            allow_files = [".co"],
            mandatory = True,
        ),
        "definition_fixtures": attr.label_list(
            allow_files = True,
            mandatory = True,
        ),
        "definition_oracle": attr.label(
            allow_single_file = True,
            cfg = "exec",
            executable = True,
            mandatory = True,
        ),
        "definition_records": attr.label(
            cfg = "target",
            executable = True,
            mandatory = True,
        ),
        "jobs": attr.int(default = 8),
        "lexer_inputs": attr.label_list(
            allow_files = True,
            mandatory = True,
        ),
        "lexer_oracle": attr.label(
            allow_single_file = True,
            cfg = "exec",
            executable = True,
            mandatory = True,
        ),
        "lexer_records": attr.label(
            cfg = "target",
            executable = True,
            mandatory = True,
        ),
    },
    doc = "Compares exhaustive declared frontend records with C++ oracles.",
    executable = True,
    test = True,
    toolchains = [_TOOLCHAIN_TYPE],
)

def _timeout_probe_test_impl(ctx):
    runner = ctx.actions.declare_file(ctx.label.name + ".bat")
    ctx.actions.write(
        output = runner,
        content = """@echo off
:wait
goto wait
""",
        is_executable = True,
    )
    return [DefaultInfo(executable = runner)]

timeout_probe_test = rule(
    implementation = _timeout_probe_test_impl,
    doc = "Manual probe used to verify that Bazel classifies timeouts as failures.",
    executable = True,
    test = True,
)
