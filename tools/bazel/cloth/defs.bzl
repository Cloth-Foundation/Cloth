"""Public entry points for the repository-private Cloth rules."""

load(
    "//tools/bazel/cloth/private:rules.bzl",
    _cloth_binary = "cloth_binary",
    _cloth_library = "cloth_library",
    _cloth_test = "cloth_test",
    _cross_compiler_parity_test = "cross_compiler_parity_test",
    _expected_failure_test = "expected_failure_test",
    _output_test = "output_test",
    _shuttle_compatibility_test = "shuttle_compatibility_test",
    _timeout_probe_test = "timeout_probe_test",
)
load(
    "//tools/bazel/cloth/private:providers.bzl",
    _ClothPackageInfo = "ClothPackageInfo",
    _ClothToolchainInfo = "ClothToolchainInfo",
)

ClothPackageInfo = _ClothPackageInfo
ClothToolchainInfo = _ClothToolchainInfo
cloth_binary = _cloth_binary
cloth_library = _cloth_library
cloth_test = _cloth_test
cross_compiler_parity_test = _cross_compiler_parity_test
expected_failure_test = _expected_failure_test
output_test = _output_test
shuttle_compatibility_test = _shuttle_compatibility_test
timeout_probe_test = _timeout_probe_test
