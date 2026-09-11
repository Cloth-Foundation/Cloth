"""Audits Shuttle against the declared self-hosted compiler distribution."""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import sys
from typing import Sequence


class AuditError(Exception):
    """Reports a Shuttle compatibility contract failure."""


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--compiler", type=Path, required=True)
    parser.add_argument("--main-root", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--shuttle", type=Path, required=True)
    parser.add_argument("--stage", type=Path, required=True)
    return parser


def _run(
    command: Sequence[str],
    description: str,
    *,
    cwd: Path,
    expect_success: bool = True,
) -> subprocess.CompletedProcess[bytes]:
    try:
        result = subprocess.run(
            command,
            check=False,
            cwd=cwd,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=120,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        raise AuditError(f"{description} could not complete: {error}") from error
    succeeded = result.returncode == 0
    if succeeded != expect_success:
        output = (result.stdout + result.stderr).decode("utf-8", errors="replace")
        expectation = "succeed" if expect_success else "fail"
        raise AuditError(
            f"{description} was expected to {expectation}; "
            f"status {result.returncode}:\n{output}"
        )
    return result


def _stage_files(main_root: Path, manifest: Path, stage: Path) -> None:
    if stage.exists():
        shutil.rmtree(stage)
    stage.mkdir(parents=True)
    for line in manifest.read_text(encoding="utf-8").splitlines():
        relative = PurePosixPath(line)
        if relative.is_absolute() or ".." in relative.parts:
            raise AuditError(f"invalid staged repository path: {line}")
        source = (main_root / Path(*relative.parts)).resolve()
        if not source.is_file():
            raise AuditError(f"declared repository input is unavailable: {line}")
        destination = stage.joinpath(*relative.parts)
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, destination)


def _digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def _completed_outputs(stage: Path) -> dict[Path, str]:
    target = stage / "tests" / "self_host" / "target" / "x86_64"
    outputs = [
        target / "clothc-tests.exe",
        target / "packages" / "cloth.cpa",
        target / "packages" / "clothc.cpa",
        target / "packages" / "clothc-tests.cpa",
    ]
    missing = [str(path) for path in outputs if not path.is_file()]
    if missing:
        raise AuditError("Shuttle omitted completed outputs:\n" + "\n".join(missing))
    return {path: _digest(path) for path in outputs}


def _shuttle_command(
    shuttle: Path,
    compiler: Path,
    manifest: Path,
    operation: str,
    target: str,
) -> list[str]:
    return [
        str(shuttle),
        operation,
        "--manifest-path",
        str(manifest),
        "--compiler",
        str(compiler),
        "--target",
        target,
        "--jobs",
        "4",
    ]


def main() -> int:
    args = _parser().parse_args()
    try:
        main_root = args.main_root.resolve()
        stage = args.stage.resolve()
        _stage_files(main_root, args.manifest, stage)
        manifest = stage / "tests" / "self_host" / "Shuttle.toml"
        if not manifest.is_file():
            raise AuditError("staged self-hosted Shuttle manifest is missing")

        print("Shuttle x86_64 cold build", flush=True)
        build = _shuttle_command(
            args.shuttle, args.compiler, manifest, "build", "x86_64"
        )
        _run(build, "Shuttle x86_64 cold build", cwd=stage)
        cold = _completed_outputs(stage)

        print("Shuttle x86_64 warm build", flush=True)
        _run(build, "Shuttle x86_64 warm build", cwd=stage)
        warm = _completed_outputs(stage)
        if cold != warm:
            raise AuditError("Shuttle warm build changed a completed x86_64 artifact")

        executable = next(path for path in cold if path.name == "clothc-tests.exe")
        result = _run([str(executable)], "Shuttle executable", cwd=stage)
        if result.stdout or result.stderr:
            raise AuditError("Shuttle compatibility executable was not silent")

        print("Shuttle wasm32 check", flush=True)
        wasm = _shuttle_command(
            args.shuttle, args.compiler, manifest, "check", "wasm32"
        )
        _run(wasm, "Shuttle wasm32 check", cwd=stage)

        invalid_source = stage / "src" / "Main.co"
        with invalid_source.open("ab") as source:
            source.write(b"\n@\n")
        failed = _run(
            build,
            "Shuttle invalid rebuild",
            cwd=stage,
            expect_success=False,
        )
        failed_output = failed.stdout + failed.stderr
        if b"unexpected character '@'" not in failed_output:
            raise AuditError("Shuttle invalid rebuild produced the wrong diagnostic")
        if _completed_outputs(stage) != warm:
            raise AuditError("Shuttle invalid rebuild changed a completed artifact")

        print("Shuttle compatibility audit passed", flush=True)
        return 0
    except (AuditError, OSError, StopIteration) as error:
        print(f"Shuttle compatibility: error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
