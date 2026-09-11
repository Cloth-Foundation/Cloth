"""Bzlmod entry points for local Cloth bootstrap and parity inputs."""

load(
    "//tools/bazel/cloth/private:extensions.bzl",
    _cloth_bootstrap = "cloth_bootstrap",
    _cloth_oracles = "cloth_oracles",
)

cloth_bootstrap = _cloth_bootstrap
cloth_oracles = _cloth_oracles
