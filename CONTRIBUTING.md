# Contributing to the Cloth compiler

This repository is the self-hosted Cloth compiler. The C++23 bootstrap,
language contracts, stage order, compatibility numbers, and cross-tool exit
audits live in [`cCloth`]. Changes here must implement an approved checkpoint
or repair behavior already covered by one.

## Before changing code

1. Read the active `cCloth` [`ROADMAP.md`] entry and its linked contract.
2. Confirm the work item exists in [`TODO.md`] and has implementation approval.
3. Read [`ARCHITECTURE.md`](ARCHITECTURE.md) and
   [`STYLE.md`](STYLE.md).
4. Keep unrelated cleanup and feature work in separate changes.

Language syntax, public standard-library APIs, runtime ABI, artifact format,
compiler ABI, and Shuttle protocol changes require their owning proposal before
implementation. A bootstrap convenience is not a reason to create a public
language or library feature.

## Source changes

- Put each primary implicit type in the matching `.co` file.
- Place code in the narrowest compiler-owned directory that describes it.
- Keep dependencies directed from orchestration into data and domain
  components; do not introduce cycles.
- Prefer explicit domain names over `Utils`, `Common`, `Misc`, or `Helpers`.
- Keep `Main.co` limited to driver composition and process behavior.
- Add or update focused tests with every behavioral change.
- Update the owning contract when an invariant changes.

The bootstrap currently has no language server. File paths, type names, imports,
and ownership boundaries therefore need to remain predictable enough to locate
with ordinary repository search.

## Verify the project

Use Shuttle with a current development build of `clothc`:

```sh
shuttle check --manifest-path Shuttle.toml --compiler <path-to-clothc>
shuttle build --manifest-path Shuttle.toml --compiler <path-to-clothc>
```

Run the focused and coordinated tests required by the active checkpoint in the
`cCloth` contract. Before review, also check formatting, documentation links,
repository whitespace, both supported LLVM targets, and the sanitizer build
when the changed boundary can execute natively.

## Proposals and pull requests

Open language and cross-repository proposals in `cCloth` and describe the
problem, exact behavior, ownership, compatibility impact, diagnostics, tests,
and explicit non-goals. Implementation pull requests should link that proposal
and state which checkpoint they close.

Work from an up-to-date branch, keep commits focused, and use imperative commit
subjects. Pull requests should include:

- the contract or defect being addressed;
- the affected compiler layers;
- commands and targets verified;
- compatibility changes, or an explicit statement that there are none; and
- any deliberate deferral recorded in the owning ledger.

Do not commit `target/`, generated executables, caches, or unrelated formatting
changes.

[`cCloth`]: https://github.com/Cloth-Foundation/cCloth
[`ROADMAP.md`]: https://github.com/Cloth-Foundation/cCloth/blob/master/ROADMAP.md
[`TODO.md`]: https://github.com/Cloth-Foundation/cCloth/blob/master/TODO.md
