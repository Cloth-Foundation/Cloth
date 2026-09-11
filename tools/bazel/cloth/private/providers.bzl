"""Providers published by the repository-private Cloth rules."""

ClothPackageInfo = provider(
    doc = "One compiled Cloth package and its deterministic artifact closure.",
    fields = {
        "artifact": "The compiler-owned package artifact.",
        "artifact_kind": "The compiler protocol artifact kind.",
        "closure": "Package records required to consume this package.",
        "direct_dependencies": "Sorted direct alias and package-name edges.",
        "package_name": "The logical package name.",
        "package_version": "The logical package version.",
        "receipt": "The validated compiler receipt.",
        "target": "The Cloth compilation target.",
    },
)

ClothToolchainInfo = provider(
    doc = "The declared bootstrap tools and compatibility requirements.",
    fields = {
        "artifact_format": "Required compiler artifact format.",
        "artifact_kind": "The compiler protocol artifact kind to emit.",
        "compiler": "The executable Cloth compiler.",
        "compiler_abi": "Required compiler ABI.",
        "compiler_runtime": "The compiler's declared host runtime closure.",
        "compiler_runtime_directory": "Execution path containing runtime DLLs.",
        "descriptor": "The compiler-paired toolchain descriptor.",
        "driver": "The repository-owned protocol action driver.",
        "executable_extension": "The host executable suffix.",
        "interpreter": "The declared Python interpreter for the action driver.",
        "interpreter_runtime": "The interpreter's declared runtime closure.",
        "path_separator": "The host separator used for executable search paths.",
        "protocol": "Required compiler process protocol.",
        "receipt_schema": "Required compiler receipt schema.",
        "runtime_abi": "Required runtime ABI.",
        "shuttle": "The executable Shuttle compatibility tool.",
        "shuttle_compiler": "The compiler in its Shuttle distribution layout.",
        "shuttle_distribution": "The compiler-paired Shuttle metadata and library.",
        "standard_library": "The exact standard-library source closure.",
        "standard_library_package": "The reserved library package name.",
        "standard_library_version": "The compiler-paired library version.",
        "target": "The native Cloth compilation target.",
    },
)
