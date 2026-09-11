"""Executes Cloth protocol actions from Bazel's relative execution paths."""

from __future__ import annotations

import argparse
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import tempfile
from typing import Any, Sequence


class DriverError(Exception):
    """Reports an invalid Bazel action or compiler protocol response."""


def _existing_file(value: str, description: str) -> Path:
    path = Path(value)
    if not path.is_absolute():
        path = Path.cwd() / path
    path = path.resolve()
    if not path.is_file():
        raise DriverError(f"{description} does not exist: {path}")
    return path


def _output_file(value: str) -> Path:
    path = Path(value)
    if not path.is_absolute():
        path = Path.cwd() / path
    return path.absolute()


def _logical_source(value: str) -> PurePosixPath:
    if "\\" in value:
        raise DriverError(f"logical source path uses a backslash: {value}")
    path = PurePosixPath(value)
    if path.is_absolute() or any(part in ("", ".", "..") for part in path.parts):
        raise DriverError(f"invalid logical source path: {value}")
    if path.suffix != ".co":
        raise DriverError(f"logical source path must end in .co: {value}")
    return path


def _validate_identity(value: str, description: str) -> None:
    if not re.fullmatch(r"[a-z][a-z0-9-]*", value):
        raise DriverError(f"invalid {description}: {value}")


def _validate_alias(value: str) -> None:
    if not re.fullmatch(r"[a-z][a-z0-9_]*", value):
        raise DriverError(f"invalid dependency alias: {value}")


def _read_receipt(
    receipt_path: str,
    artifact_path: str,
    package_name: str,
    package_version: str,
    target: str,
    artifact_kind: str,
    receipt_schema: int,
    artifact_format: int,
) -> tuple[dict[str, Any], Path]:
    receipt_file = _existing_file(receipt_path, "dependency receipt")
    artifact_file = _existing_file(artifact_path, "dependency artifact")
    try:
        encoded = receipt_file.read_bytes()
        if len(encoded) > 16 * 1024 * 1024:
            raise DriverError(f"dependency receipt is too large: {receipt_file}")
        receipt = json.loads(encoded.decode("utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise DriverError(
            f"could not read dependency receipt {receipt_file}: {error}"
        ) from error

    if not isinstance(receipt, dict):
        raise DriverError(f"dependency receipt is not an object: {receipt_file}")
    if receipt.get("schema") != receipt_schema:
        raise DriverError(f"dependency receipt schema mismatch: {receipt_file}")
    if receipt.get("artifact_format") != artifact_format:
        raise DriverError(f"dependency artifact format mismatch: {receipt_file}")
    if receipt.get("kind") != artifact_kind:
        raise DriverError(f"dependency artifact kind mismatch: {receipt_file}")
    if receipt.get("target") != target:
        raise DriverError(f"dependency target mismatch: {receipt_file}")

    package = receipt.get("package")
    if not isinstance(package, dict):
        raise DriverError(f"dependency receipt has no package: {receipt_file}")
    if package.get("name") != package_name:
        raise DriverError(f"dependency package name mismatch: {receipt_file}")
    if package.get("version") != package_version:
        raise DriverError(f"dependency package version mismatch: {receipt_file}")

    artifact_id = receipt.get("artifact_id")
    if not isinstance(artifact_id, str) or not re.fullmatch(
        r"[0-9a-f]{64}", artifact_id
    ):
        raise DriverError(f"dependency artifact id is invalid: {receipt_file}")
    return receipt, artifact_file


def _compiler_result(command: Sequence[str]) -> subprocess.CompletedProcess[bytes]:
    try:
        result = subprocess.run(
            command,
            check=False,
            shell=False,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
    except OSError as error:
        raise DriverError(f"could not start Cloth compiler: {error}") from error
    if result.stderr:
        sys.stderr.buffer.write(result.stderr)
        sys.stderr.buffer.flush()
    return result


def _validated_output_receipt(
    encoded: bytes,
    package_name: str,
    package_version: str,
    target: str,
    artifact_kind: str,
    receipt_schema: int,
    artifact_format: int,
) -> None:
    if len(encoded) > 16 * 1024 * 1024:
        raise DriverError("compiler receipt is too large")
    if not encoded.endswith((b"\n", b"\r\n")):
        raise DriverError("compiler receipt is not line terminated")
    if len(encoded.splitlines()) != 1:
        raise DriverError("compiler wrote more than one receipt line")
    try:
        receipt = json.loads(encoded.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise DriverError(f"compiler wrote an invalid receipt: {error}") from error
    if not isinstance(receipt, dict):
        raise DriverError("compiler receipt is not an object")
    if receipt.get("schema") != receipt_schema:
        raise DriverError("compiler receipt schema mismatch")
    if receipt.get("artifact_format") != artifact_format:
        raise DriverError("compiler artifact format mismatch")
    if receipt.get("kind") != artifact_kind:
        raise DriverError("compiler artifact kind mismatch")
    if receipt.get("target") != target:
        raise DriverError("compiler receipt target mismatch")
    package = receipt.get("package")
    if not isinstance(package, dict):
        raise DriverError("compiler receipt has no package object")
    if package.get("name") != package_name:
        raise DriverError("compiler receipt package name mismatch")
    if package.get("version") != package_version:
        raise DriverError("compiler receipt package version mismatch")
    artifact_id = receipt.get("artifact_id")
    if not isinstance(artifact_id, str) or not re.fullmatch(
        r"[0-9a-f]{64}", artifact_id
    ):
        raise DriverError("compiler receipt artifact id is invalid")


def _dependency_arguments(args: argparse.Namespace) -> list[str]:
    artifacts: dict[str, tuple[str, str, str]] = {}
    for package_name, package_version, receipt, artifact in args.artifact:
        _validate_identity(package_name, "artifact package name")
        if package_name in artifacts:
            raise DriverError(f"duplicate artifact package: {package_name}")
        artifacts[package_name] = (
            package_version,
            receipt,
            artifact,
        )

    direct: dict[str, str] = {}
    for alias, package_name in args.direct:
        _validate_alias(alias)
        _validate_identity(package_name, "dependency package name")
        if alias in direct:
            raise DriverError(f"duplicate dependency alias: {alias}")
        if package_name not in artifacts:
            raise DriverError(
                f"direct dependency has no artifact input: {package_name}"
            )
        direct[alias] = package_name

    result: list[str] = []
    for alias in sorted(direct):
        result.extend(["--dependency", alias, direct[alias]])
    for package_name in sorted(artifacts):
        package_version, receipt_path, artifact_path = artifacts[package_name]
        receipt, artifact = _read_receipt(
            receipt_path,
            artifact_path,
            package_name,
            package_version,
            args.target,
            args.artifact_kind,
            args.receipt_schema,
            args.artifact_format,
        )
        result.extend(
            [
                "--artifact",
                package_name,
                package_version,
                receipt["artifact_id"],
                str(artifact),
            ]
        )
    return result


def _compile(args: argparse.Namespace) -> int:
    _validate_identity(args.package_name, "package name")
    compiler = _existing_file(args.compiler, "Cloth compiler")
    output = _output_file(args.output)
    receipt_output = _output_file(args.receipt)
    output.parent.mkdir(parents=True, exist_ok=True)
    receipt_output.parent.mkdir(parents=True, exist_ok=True)

    sources: dict[str, Path] = {}
    for logical_value, source_value in args.source:
        logical = _logical_source(logical_value)
        logical_text = logical.as_posix()
        if logical_text in sources:
            raise DriverError(f"duplicate logical source: {logical_text}")
        sources[logical_text] = _existing_file(source_value, "Cloth source")
    if not sources:
        raise DriverError("a Cloth package must contain at least one source")

    dependency_arguments = _dependency_arguments(args)
    with tempfile.TemporaryDirectory(
        dir=output.parent,
        prefix=f".{output.name}.sources-",
    ) as temporary_directory:
        source_root = Path(temporary_directory)
        for logical_text in sorted(sources):
            destination = source_root.joinpath(*PurePosixPath(logical_text).parts)
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(sources[logical_text], destination)

        command = [
            str(compiler),
            "--shuttle-protocol",
            str(args.protocol),
            "--operation",
            "compile",
            "--target",
            args.target,
            "--artifact-kind",
            args.artifact_kind,
            "--output",
            str(output),
            "--package",
            args.package_name,
            args.package_version,
            str(source_root),
        ]
        if args.entry:
            command.extend(["--entry", _logical_source(args.entry).as_posix()])
        command.extend(dependency_arguments)
        result = _compiler_result(command)

    if result.returncode != 0:
        return result.returncode
    _validated_output_receipt(
        result.stdout,
        args.package_name,
        args.package_version,
        args.target,
        args.artifact_kind,
        args.receipt_schema,
        args.artifact_format,
    )
    if not output.is_file():
        raise DriverError("compiler reported success without an artifact")
    receipt_output.write_bytes(result.stdout)
    return 0


def _link(args: argparse.Namespace) -> int:
    _validate_identity(args.root_package, "root package name")
    compiler = _existing_file(args.compiler, "Cloth compiler")
    output = _output_file(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)

    artifact_arguments = _dependency_arguments(args)
    command = [
        str(compiler),
        "--shuttle-protocol",
        str(args.protocol),
        "--operation",
        "link",
        "--target",
        args.target,
        "--output",
        str(output),
        "--root-package",
        args.root_package,
        "--entry",
        _logical_source(args.entry).as_posix(),
    ]
    command.extend(artifact_arguments)
    result = _compiler_result(command)
    if result.returncode != 0:
        return result.returncode
    if result.stdout:
        raise DriverError("compiler link operation wrote unexpected stdout")
    if not output.is_file():
        raise DriverError("compiler reported success without an executable")
    return 0


def _common_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--compiler", required=True)
    parser.add_argument("--protocol", required=True, type=int)
    parser.add_argument("--target", required=True)
    parser.add_argument("--artifact-kind", required=True)
    parser.add_argument("--receipt-schema", required=True, type=int)
    parser.add_argument("--artifact-format", required=True, type=int)
    parser.add_argument(
        "--artifact",
        action="append",
        default=[],
        nargs=4,
        metavar=("PACKAGE", "VERSION", "RECEIPT", "ARTIFACT"),
    )
    parser.add_argument(
        "--direct",
        action="append",
        default=[],
        nargs=2,
        metavar=("ALIAS", "PACKAGE"),
    )


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="operation", required=True)

    compile_parser = subparsers.add_parser("compile")
    _common_arguments(compile_parser)
    compile_parser.add_argument("--output", required=True)
    compile_parser.add_argument("--receipt", required=True)
    compile_parser.add_argument("--package-name", required=True)
    compile_parser.add_argument("--package-version", required=True)
    compile_parser.add_argument("--entry")
    compile_parser.add_argument(
        "--source",
        action="append",
        default=[],
        nargs=2,
        metavar=("LOGICAL", "FILE"),
    )
    compile_parser.set_defaults(handler=_compile)

    link_parser = subparsers.add_parser("link")
    _common_arguments(link_parser)
    link_parser.add_argument("--output", required=True)
    link_parser.add_argument("--root-package", required=True)
    link_parser.add_argument("--entry", required=True)
    link_parser.set_defaults(handler=_link)
    return parser


def main() -> int:
    try:
        args = _parser().parse_args()
        return args.handler(args)
    except DriverError as error:
        print(f"cloth-bazel: error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
