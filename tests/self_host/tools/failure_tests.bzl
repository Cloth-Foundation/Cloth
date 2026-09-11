"""Macros for independently named Cloth failure-injection tests."""

load("//tools/bazel/cloth:defs.bzl", "expected_failure_test")

def failure_tests(binary, cases):
    """Declares one expected-failure target per mode and diagnostic."""
    tests = []
    for mode, expected in cases:
        name = mode.replace("-", "_") + "_test"
        expected_failure_test(
            name = name,
            binary = binary,
            expected_stderr = expected,
            failure_args = [mode],
            size = "small",
            tags = ["failure", "unit"],
        )
        tests.append(":" + name)
    native.test_suite(
        name = "failure_tests",
        tests = tests,
    )
