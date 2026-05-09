<div align="center">
    <picture>
        <img alt="The Cloth Programming Language"
             src="https://github.com/Cloth-Foundation/.github/blob/main/Logos/PNG/Header%20-%20NO%20BG.png?raw=true"
             width="50%" />
    </picture>

[Website][Cloth] | [Learn] | [Documentation] | [Contributing]

<p align="center">
  <img src="https://img.shields.io/badge/Status-Early%20Alpha-FF9800?style=for-the-badge"  alt="Early Access"/>
  <img src="https://img.shields.io/badge/License-Apache%202.0-4CAF50?style=for-the-badge&logo=apache&logoColor=white"  alt="Apache License"/>
  <img src="https://img.shields.io/badge/License-MIT%202.0-4CAF50?style=for-the-badge&logo=apache&logoColor=white"  alt="MIT License"/>
  <a href="https://github.com/Cloth-Foundation/rCloth">
    <img src="https://img.shields.io/github/stars/Cloth-Foundation/Cloth?style=for-the-badge"  alt="GitHub Stars"/>
  </a>
</p>
</div>

[Cloth]: https://cloth.dev

[Learn]: https://cloth.dev/learning-center

[Documentation]: https://docs.cloth.dev

[Contributing]: CONTRIBUTING.md

This is the main source code repository for [Cloth](https://cloth.dev), including the compiler, standard library,
documentation, and tooling. Shuttle can be found [here.](https://github.com/Cloth-Foundation/Shuttle)

# What is Cloth?

Cloth is a high-performance, object-oriented, low-level language designed for predictable execution and maintainable
systems programming. It combines familiar C-style control with a structured, Java-like class model.

- *Performance* – Cloth avoids garbage collection and uses deterministic destruction, resulting in predictable runtime
  behavior and minimal overhead.
- *Maintainability* – A structured, class-oriented design and explicit syntax make large codebases easier to reason
  about and evolve over time.
- *Productivity* – Strong compile-time guarantees and explicit error handling reduce runtime surprises and debugging
  complexity.
- *Memory Safety* – Cloth uses a hierarchical ownership model with deterministic destruction. Objects form an ownership
  tree rooted at program entry, while static data exists in a separate root-lifetime domain, allowing for safe and
  predictable memory management without a garbage collector.

## Quick Start Guide

Download the latest compiler installer for your operating system and follow the on-screen instructions. You may need
administrator permissions.

## Build From Source

While not recommended, you can follow [the Installation Guide](INSTALL.md).

## Required 3rd Party Libraries

> There is a plan when an installer is made that it will automatically install these libraries.

- [Git](https://github.com/git/git)
- [Clang](https://clang.llvm.org/)

## Help

See the [Help Center](https://cloth.dev/resources) or the [Documentation](https://docs.cloth.dev/) for help resources.

## Contributing

See [Contributing.md](CONTRIBUTING.md).

## License

Cloth is distributed under the terms of the MIT license and Apache 2.0 license. 
