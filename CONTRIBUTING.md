# Contributing to Cloth

Thank you for your interest in contributing to **Cloth**. Contributions of all kinds are welcome, including language
design, compiler development, tooling, documentation, and ecosystem improvements.

## Getting Started

> Cloth is still in its early stages of development. It is not yet ready for production use.
> Cloth is currently being bootstrapped in [C#](https://learn.microsoft.com/en-us/dotnet/csharp/) and [F#](https://learn.microsoft.com/en-us/dotnet/fsharp/) and compiles to Cloth IR (Intermediate Representation), which is then
> compiled to LLVM IR.

If you're new to the project, the best place to begin is by reviewing the Cloth specification and understanding the
language’s core principles.
Before making changes, ensure you are familiar with the current design direction and terminology defined in the
specification.

> It is recommended to use [JetBrains Rider](https://www.jetbrains.com/rider/) or [Visual Studio](https://visualstudio.microsoft.com/) as Cloth is a .NET project, currently written in C# and F#.

## Communication and Help

If you need help or want to discuss ideas:

* Open a discussion or issue in the repository
* Ask questions about design decisions or implementation details
* Propose changes before implementing large features

Cloth is still evolving, and discussion is encouraged before committing to major changes.

## Areas of Contribution

You can contribute to Cloth in several areas:

### Language Specification

* Syntax and grammar improvements
* Type system refinements
* Ownership and memory model design
* Error handling semantics (`maybe`, safe casts, fallback operators, etc.)

### Compiler Development

* Lexer, parser, and AST improvements
* Symbol table and scope resolution
* Type checking and diagnostics
* Intermediate Representation (IR) design
* LLVM backend integration and code generation

### Tooling

* Build system and package tooling
* Formatting and linting tools
* Language server (LSP) support for [VSCode](https://github.com/Cloth-Foundation/Cloth-VSCode-Language-Support) or [JetBrains](https://github.com/Cloth-Foundation/Cloth-Jetbrains-Language-Support).
* Debugging and profiling tools

### Standard Library

> Standard Library repository can be found [here](https://github.com/Cloth-Foundation/Standard-Library).

* Core utilities (`cloth.io`, `cloth.collections`, etc.)
* Platform abstractions
* Performance-critical primitives

### Documentation

* Specification clarity and completeness
* Examples and usage guides
* Tutorials and onboarding material

## Making Changes

* Keep changes focused and well-scoped
* Follow existing naming conventions and syntax style
* Maintain consistency with the specification
* Update documentation when behavior changes

For larger changes:

* Open an issue or proposal first
* Clearly explain the problem and the proposed solution
* Consider backward compatibility and long-term impact

## Compiler and Architecture Notes

Cloth uses a structured compilation pipeline:

1. **Pass 1 — Symbol Collection**

    * All modules are merged
    * Top-level declarations are registered

2. **Pass 2 — Semantic Analysis and Code Generation**

    * Type checking and validation
    * Ownership and lifetime enforcement
    * IR generation
    * Backend emission (LLVM or target-specific)

Contributions should respect this model and avoid introducing implicit or order-dependent behavior.

## Bug Reports

If you encounter a bug:

* Provide a minimal reproducible example
* Include the expected behavior vs. actual behavior
* Attach relevant compiler output or diagnostics

For compiler errors or crashes, include:

* Source code snippet
* Exact error message
* Environment details (OS, build setup, etc.)

## Design Philosophy

Cloth is built with the following goals:

* **Performant** — predictable, low-level control without unnecessary overhead
* **Maintainable** — clear, explicit syntax designed for long-term readability
* **Productive** — strong diagnostics and developer-friendly tooling
* **Memory Safe** — deterministic ownership model with explicit lifetimes

All contributions should align with these principles.