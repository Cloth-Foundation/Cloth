"""Runs the exhaustive C++/Cloth frontend record parity audit."""

from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from pathlib import Path
import shutil
import subprocess
import sys
from typing import Iterable, Sequence


_EXPECTED_COUNTS = {
    "bounded declaration": 32,
    "bounded definition": 47,
    "lexer": 697,
    "real declaration": 248,
    "real definition": 248,
    "semantic": 2,
}


class AuditError(Exception):
    """Reports a parity or declared-input contract failure."""


@dataclass(frozen=True)
class Tools:
    declaration_oracle: Path
    declaration_records: Path
    definition_oracle: Path
    definition_records: Path
    lexer_oracle: Path
    lexer_records: Path
    semantic_oracle: Path
    semantic_records: Path


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--main-root", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--temporary-root", type=Path, required=True)
    parser.add_argument("--jobs", type=int, default=8)
    for name in (
        "declaration-oracle",
        "declaration-records",
        "definition-oracle",
        "definition-records",
        "lexer-oracle",
        "lexer-records",
        "semantic-oracle",
        "semantic-records",
    ):
        parser.add_argument(f"--{name}", type=Path, required=True)
    return parser


def _run(
    command: Sequence[str],
    description: str,
    working_directory: Path | None = None,
    timeout: int = 60,
) -> bytes:
    try:
        result = subprocess.run(
            command,
            check=False,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout,
            cwd=working_directory,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        raise AuditError(f"{description} could not complete: {error}") from error
    if result.returncode != 0 or result.stderr:
        stderr = result.stderr.decode("utf-8", errors="replace")
        raise AuditError(
            f"{description} failed with status {result.returncode}:\n{stderr}"
        )
    return result.stdout.replace(b"\r\n", b"\n")


def _load_manifest(main_root: Path, manifest: Path) -> dict[str, list[Path]]:
    groups: dict[str, list[Path]] = {}
    for line in manifest.read_text(encoding="utf-8").splitlines():
        kind, relative = line.split("\t", maxsplit=1)
        path = (main_root / Path(relative)).resolve()
        if not path.is_file():
            raise AuditError(f"declared {kind} input is unavailable: {relative}")
        groups.setdefault(kind, []).append(path)
    return groups


def _first_mismatch(left: bytes, right: bytes) -> str:
    left_lines = left.splitlines()
    right_lines = right.splitlines()
    limit = max(len(left_lines), len(right_lines))
    for index in range(limit):
        left_line = left_lines[index] if index < len(left_lines) else b"<missing>"
        right_line = right_lines[index] if index < len(right_lines) else b"<missing>"
        if left_line != right_line:
            return (
                f"record {index + 1}:\n"
                f"  C++:   {left_line.decode('utf-8', errors='replace')}\n"
                f"  Cloth: {right_line.decode('utf-8', errors='replace')}"
            )
    return "byte output differs without a line mismatch"


def _compare(kind: str, path: Path, tools: Tools) -> None:
    name = path.stem
    if kind == "lexer":
        oracle_command = [str(tools.lexer_oracle), "record", str(path)]
        cloth_command = [str(tools.lexer_records), str(path)]
    elif kind == "declaration":
        oracle_command = [str(tools.declaration_oracle), str(path)]
        cloth_command = [str(tools.declaration_records), str(path), name]
    elif kind == "definition":
        oracle_command = [str(tools.definition_oracle), str(path)]
        cloth_command = [str(tools.definition_records), str(path), name]
    elif kind == "semantic":
        oracle_command = [str(tools.semantic_oracle), str(path)]
        cloth_command = [str(tools.semantic_records), str(path), name]
    else:
        raise AuditError(f"unknown parity kind: {kind}")

    oracle = _run(oracle_command, f"C++ {kind} oracle for {path}")
    cloth = _run(cloth_command, f"Cloth {kind} records for {path}")
    if oracle != cloth:
        raise AuditError(
            f"{kind} parity mismatch in {path}: {_first_mismatch(oracle, cloth)}"
        )


def _deduplicate(paths: Iterable[Path]) -> list[Path]:
    return sorted(set(paths), key=lambda path: str(path).casefold())


def _check_count(description: str, paths: Sequence[Path]) -> None:
    expected = _EXPECTED_COUNTS[description]
    if len(paths) != expected:
        raise AuditError(
            f"{description} corpus has {len(paths)} inputs; expected {expected}"
        )


def _run_phase(
    description: str,
    kind: str,
    paths: Sequence[Path],
    tools: Tools,
    jobs: int,
) -> None:
    _check_count(description, paths)
    print(f"{description} parity: {len(paths)} declared inputs", flush=True)
    with ThreadPoolExecutor(max_workers=jobs) as executor:
        list(executor.map(lambda path: _compare(kind, path, tools), paths))


def _generate(oracle: Path, directory: Path, description: str) -> list[Path]:
    directory.mkdir(parents=True, exist_ok=True)
    _run([str(oracle), "generate", str(directory)], description)
    return _deduplicate(path for path in directory.iterdir() if path.is_file())


def _package_check(
    tool: Path,
    paths: Sequence[Path],
    root: Path,
    description: str,
    timeout: int = 60,
) -> bytes:
    relative = [path.relative_to(root).as_posix() for path in paths]
    return _run(
        [str(tool), "package-check", *relative],
        description,
        working_directory=root,
        timeout=timeout,
    )


def _audit_production_semantics(
    compiler: Sequence[Path],
    main_root: Path,
    tools: Tools,
) -> None:
    summary = _package_check(
        tools.semantic_records,
        compiler,
        main_root,
        "production semantic package",
        timeout=300,
    )
    expected_prefix = f"P|{len(compiler)}|".encode()
    if not summary.startswith(expected_prefix):
        raise AuditError(
            "production semantic package published an unexpected summary: "
            f"{summary.decode('utf-8', errors='replace')}"
        )
    print(
        "production semantic audit: "
        f"{len(compiler)} files accepted as one package",
        flush=True,
    )


def _audit_semantic_determinism(
    paths: Sequence[Path],
    main_root: Path,
    temporary_root: Path,
    tools: Tools,
    jobs: int,
) -> None:
    parallel_runs = min(jobs, 4)
    for path in paths:
        command = [str(tools.semantic_records), str(path), path.stem]
        baseline = _run(command, f"semantic records for {path}")
        root = temporary_root / path.stem
        relocated = root / path.name
        relocated.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, relocated)
        variants = [
            _run(command, f"repeated semantic records for {path}"),
            _run(
                [str(tools.semantic_records), str(relocated), path.stem],
                f"relocated semantic records for {path}",
            ),
        ]
        with ThreadPoolExecutor(max_workers=parallel_runs) as executor:
            variants.extend(
                executor.map(
                    lambda index: _run(
                        command, f"parallel semantic records {index + 1}"
                    ),
                    range(parallel_runs),
                )
            )
        for index, candidate in enumerate(variants):
            if candidate != baseline:
                raise AuditError(
                    f"semantic records for {path} are nondeterministic in "
                    f"run {index + 2}: {_first_mismatch(baseline, candidate)}"
                )
    print(
        "semantic determinism audit: "
        f"{len(paths)} inputs, repeated, relocated, and parallel records",
        flush=True,
    )


def main() -> int:
    args = _parser().parse_args()
    try:
        if args.jobs < 1 or args.jobs > 32:
            raise AuditError("--jobs must be between 1 and 32")
        main_root = args.main_root.resolve()
        if (main_root / "README.md").exists():
            raise AuditError("undeclared workspace README leaked into test runfiles")

        groups = _load_manifest(main_root, args.manifest)
        tools = Tools(
            declaration_oracle=args.declaration_oracle,
            declaration_records=args.declaration_records,
            definition_oracle=args.definition_oracle,
            definition_records=args.definition_records,
            lexer_oracle=args.lexer_oracle,
            lexer_records=args.lexer_records,
            semantic_oracle=args.semantic_oracle,
            semantic_records=args.semantic_records,
        )
        temporary_root = args.temporary_root.resolve()
        generated_lexer = _generate(
            tools.lexer_oracle,
            temporary_root / "lexer",
            "bounded lexer corpus generation",
        )
        generated_declaration = _generate(
            tools.declaration_oracle,
            temporary_root / "declaration",
            "bounded declaration corpus generation",
        )

        compiler = _deduplicate(groups["compiler"])
        lexer = _deduplicate(
            path
            for path in groups["lexer"] + generated_lexer
            if path.suffix.lower() in (".co", ".bin")
        )
        declaration_bounded = _deduplicate(
            groups["declaration"] + generated_declaration
        )
        definition_bounded = _deduplicate(
            groups["declaration"] + groups["definition"] + groups["fixture"]
        )

        _run_phase("lexer", "lexer", lexer, tools, args.jobs)
        _run_phase(
            "bounded declaration",
            "declaration",
            declaration_bounded,
            tools,
            args.jobs,
        )
        _run_phase(
            "real declaration",
            "declaration",
            compiler,
            tools,
            args.jobs,
        )
        _run_phase(
            "bounded definition",
            "definition",
            definition_bounded,
            tools,
            args.jobs,
        )
        _run_phase(
            "real definition",
            "definition",
            compiler,
            tools,
            args.jobs,
        )
        semantic = _deduplicate(groups["semantic"])
        _run_phase(
            "semantic",
            "semantic",
            semantic,
            tools,
            args.jobs,
        )
        _audit_semantic_determinism(
            semantic, main_root, temporary_root / "semantic-determinism",
            tools, args.jobs
        )
        _audit_production_semantics(compiler, main_root, tools)
        print("cross-compiler parity audit passed", flush=True)
        return 0
    except (AuditError, KeyError, OSError, ValueError) as error:
        print(f"cross-compiler parity: error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
