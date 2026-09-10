# Cloth self-hosted compiler

This repository contains the compiler being written in Cloth. It is compiled
with the production C++23 bootstrap compiler, [`cCloth`], and built as a
project by [Shuttle]. The goal is to replace the bootstrap only after each
frontend and backend boundary has exact behavioral coverage.

Cloth combines native compilation with garbage-collected memory safety,
familiar object-oriented types, declared error effects, concise imports, and
capitalization-based visibility. Each `.co` file defines one implicit type
named by its file stem, which removes repeated class envelopes without losing
nominal identity.

> The self-hosted compiler is under active bootstrap development. Use `cCloth`
> for supported compilation today.

## Build and run

You need a built `clothc` from `cCloth` and Shuttle. From this repository:

```sh
shuttle check --manifest-path Shuttle.toml --compiler <path-to-clothc>
shuttle build --manifest-path Shuttle.toml --compiler <path-to-clothc>
```

To run Shuttle from the `cCloth` checkout instead of an installed binary:

```sh
cargo run --manifest-path <path-to-cCloth>/shuttle/Cargo.toml --locked -- \
  check --manifest-path Shuttle.toml --compiler <path-to-clothc>
```

On Windows, the compiler path normally ends in `clothc.exe`. Shuttle selects
the compiler-paired standard library automatically; do not add the reserved
`cloth` dependency to `Shuttle.toml`.

The package and executable are both named `clothc`. After building, the current
frontend driver accepts:

```sh
target/x86_64/clothc check path/to/Source.co
target/x86_64/clothc --help
target/x86_64/clothc --version
```

The reserved package identity `cloth` remains exclusive to the standard
library.

## Source map

The production entry is [`src/Main.co`](src/Main.co). Compiler code is grouped
under `src/driver/` and `src/frontend/` by responsibility. Bootstrap checks,
fixtures, failure probes, and parity adapters form the separate
`tests/self_host` Shuttle package and are never compiled into `clothc`. Read
[`ARCHITECTURE.md`](ARCHITECTURE.md) before adding or moving a compiler component
and follow [`STYLE.md`](STYLE.md) for Cloth source.

The active implementation order and accepted contracts live in the `cCloth`
[`ROADMAP.md`] and [`TODO.md`]. This repository does not independently add
language features ahead of that schedule.

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md). Large language or compatibility
changes start as a proposal in `cCloth`; self-hosted implementation changes
must name the contract and checkpoint they satisfy.

## Repository boundaries

- [`cCloth`] — C++23 bootstrap compiler, runtime, language contracts, and the
  authoritative implementation schedule.
- [Shuttle] — project and build system.
- [Standard library] — compiler-paired `cloth.*` packages.
- [Documentation] — user-facing language documentation.

## License

Cloth is available under the repository's Apache License 2.0 with LLVM
Exceptions and MIT terms. See [`LICENSE.txt`](LICENSE.txt).

[`cCloth`]: https://github.com/Cloth-Foundation/cCloth
[`ROADMAP.md`]: https://github.com/Cloth-Foundation/cCloth/blob/master/ROADMAP.md
[`TODO.md`]: https://github.com/Cloth-Foundation/cCloth/blob/master/TODO.md
[Shuttle]: https://github.com/Cloth-Foundation/Shuttle
[Standard library]: https://github.com/Cloth-Foundation/Standard-Library
[Documentation]: https://cloth.dev/docs
