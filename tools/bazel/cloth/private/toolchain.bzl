"""Cloth compiler toolchain declaration."""

load(":providers.bzl", "ClothToolchainInfo")

def _cloth_toolchain_impl(ctx):
    executable_extension = ""
    if ctx.executable.compiler.basename.endswith(".exe"):
        executable_extension = ".exe"

    info = ClothToolchainInfo(
        artifact_format = 8,
        artifact_kind = "object",
        compiler = ctx.executable.compiler,
        compiler_abi = 7,
        compiler_runtime = depset(ctx.files.compiler_runtime),
        compiler_runtime_directory = ctx.files.compiler_runtime[0].dirname,
        descriptor = ctx.file.descriptor,
        driver = ctx.file.driver,
        executable_extension = executable_extension,
        interpreter = ctx.executable.interpreter,
        interpreter_runtime = depset(ctx.files.interpreter_runtime),
        path_separator = ";" if executable_extension else ":",
        protocol = 2,
        receipt_schema = 1,
        runtime_abi = 11,
        shuttle = ctx.executable.shuttle,
        shuttle_compiler = ctx.executable.shuttle_compiler,
        shuttle_distribution = depset(ctx.files.shuttle_distribution),
        standard_library = depset(ctx.files.standard_library),
        standard_library_package = "cloth",
        standard_library_version = "0.5.0",
        target = "x86_64",
    )
    return [platform_common.ToolchainInfo(cloth = info)]

cloth_toolchain = rule(
    implementation = _cloth_toolchain_impl,
    attrs = {
        "compiler": attr.label(
            allow_single_file = True,
            cfg = "exec",
            executable = True,
            mandatory = True,
        ),
        "compiler_runtime": attr.label(
            allow_files = True,
            mandatory = True,
        ),
        "descriptor": attr.label(
            allow_single_file = True,
            mandatory = True,
        ),
        "driver": attr.label(
            allow_single_file = [".py"],
            mandatory = True,
        ),
        "interpreter": attr.label(
            allow_single_file = True,
            cfg = "exec",
            executable = True,
            mandatory = True,
        ),
        "interpreter_runtime": attr.label(
            allow_files = True,
            mandatory = True,
        ),
        "shuttle": attr.label(
            allow_single_file = True,
            cfg = "exec",
            executable = True,
            mandatory = True,
        ),
        "shuttle_compiler": attr.label(
            allow_single_file = True,
            cfg = "exec",
            executable = True,
            mandatory = True,
        ),
        "shuttle_distribution": attr.label(
            allow_files = True,
            mandatory = True,
        ),
        "standard_library": attr.label(
            allow_files = True,
            mandatory = True,
        ),
    },
    doc = "Declares the compiler, adapter, and paired standard library.",
)
