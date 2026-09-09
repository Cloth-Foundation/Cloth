# Cloth compiler style

The self-hosted compiler uses a compact, Google-inspired source style adapted
to Cloth's capitalization-based visibility. Consistency is especially
important while navigation relies on repository search rather than a language
server.

## Layout

- Use two spaces per indentation level and no tabs.
- Keep lines at or below 100 columns unless a URL or diagnostic fixture cannot
  be split meaningfully.
- Put an opening brace on the declaration or control-flow line.
- Do not put a space between a callable name and its opening parenthesis.
- Use one statement per line and one primary implicit type per `.co` file.
- A braced `if` containing only a short `return` may remain on one line when the
  complete guard is easier to scan and stays below the line limit.
- Keep imports at the top, one per line. Group `cloth.*` imports before project
  imports, sort each group lexicographically, and separate groups with one blank
  line.
- Use blank lines to separate responsibilities, not every statement.

## Names

- Match every file stem and primary type exactly in `UpperCamelCase`.
- Use `UpperCamelCase` for public callables and `lowerCamelCase` for private
  callables. This is semantic in Cloth, not cosmetic.
- Use `lowerCamelCase` for parameters, locals, and private fields.
- Prefer complete domain names. Avoid abbreviations unless they are established
  language terms such as UTF-8, EOF, AST, HIR, or MIR.
- Do not introduce `Utils`, `Common`, `Misc`, `Manager`, or `Helpers` as a
  substitute for clear ownership.

Existing parity enums may retain names required to match the C++ bootstrap.
Enum cases are public regardless of capitalization.

## Functions and types

- Keep a function focused on one operation and return early when that makes the
  invariant clearer.
- Make state private unless another component must consume it through a small,
  named operation.
- Pass source ranges with data that can fail; do not reconstruct locations in
  a distant layer.
- Prefer structured kinds and values over matching or manufacturing diagnostic
  strings inside compiler logic.
- Comment invariants, ownership, and non-obvious recovery behavior. Do not
  narrate syntax already visible in the code.

## Changes

- Follow the active stage contract and keep later-stage scaffolding out.
- Add focused coverage beside every behavior change and differential coverage
  at compatibility boundaries.
- Do not combine broad reformatting with semantic changes.
- Keep generated output, build directories, and caches out of source control.

C++ changes in `cCloth` continue to follow that repository's `CODE_STYLE.md`
and Google C++ Style.
