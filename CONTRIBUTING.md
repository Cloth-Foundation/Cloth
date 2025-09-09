![Logo](https://github.com/Cloth-Foundation/.github/blob/main/Logos/PNG/File%20Logos/Contributing.png?raw=true)

Thank you for your interest in contributing to **Cloth**, a statically typed systems language with value semantics, forward declarations, and a modern standard library. Whether you're fixing a typo, proposing a new language feature, or optimizing the compiler, your contribution matters!

---

## Development Setup

### Prerequisites

- **Go (1.25)** — [Install with Go](https://go.dev)
- `git`, `make`, and a Unix-like environment

### Clone the Repository

```bash
git clone https://github.com/Cloth-Foundation/Cloth.git
cd cloth
````

### Build the Compiler

```bash
make
```

This will compile the Go source code in the `cloth/` directory.

### Run the Test Suite

```bash
make test
```

---

## Working on the Standard Library

The standard library is written in Cloth itself and located in the `std/` directory. After modifying any `.co` files:

```bash
make std
```

This will rebuild the library and run syntax validation. Contributions to the standard library **must** adhere to Cloth style conventions (see below).

---

## Testing Your Changes

You can test `.co` code manually:

```bash
./build/loomc examples/hello.lm
```

Or add automated tests in:

```
tests/
├── language/
├── typecheck/
├── stdlib/
```

Use `.lm` source files and include expected output/diagnostics.

---

## 📝 Contribution Guidelines

### 1. File Structure

* Place new compiler modules in `loom-compiler/`.
* Place new standard modules in `std/`.
* Add examples to `examples/` if demonstrating features.

### 2. Commit Messages

Use clear and descriptive commit messages:

```
parser: add support for ternary expressions
std: fix bug in strings.toLowerCase
```

### 3. Style Guide

#### OCaml (Compiler)

* 2-space indentation
* Descriptive `let` bindings
* Use modules to separate concerns (`Lexer`, `Parser`, `Typechecker`, etc.)

#### Cloth (Stdlib)

* Use `camelCase` for functions and variables
* Avoid unnecessary `null`
* Prefer expressions over imperative code where possible
* All functions should be documented with comments

---

## Feature Proposals (RFCs)

All language-level or compiler-impacting changes must go through the **RFC process**.

1. Fork the repo and create a branch like `rfc/feature-name`
2. Add your proposal to `rfcs/` using the template
3. Submit a pull request and tag it `[RFC]`

See the [RFC PROCESS](https://www.cloth.dev/docs/help/RFC-Process) for full details.

---

## 🛡️ Code of Conduct

By contributing, you agree to follow our [Code of Conduct](./CODE_OF_CONDUCT.md).

---

## Getting Help

* Open a GitHub Issue or Discussion
* Join the [Cloth Community](https://www.cloth.dev/community)
* Ping us on [GitHub Discussions](https://github.com/Cloth-Foundation/Cloth/discussions)

---
