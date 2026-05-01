# Modules

A module declaration tells the compiler the location of a source file relative to the source directory. Every Cloth
source file must contain exactly one module declaration, and it must appear as the first statement in the file.

## Declaration

A module declaration begins with the `module` keyword followed by a dot-separated path and a terminating semicolon:

```cloth
module my.awesome.app;
```

Each segment of the path is a Cloth identifier and must correspond to the directory structure containing the source
file. For example, a file located at `src/my/awesome/app/` would use `module my.awesome.app`.

If a file is located in the root of the source directory, the module path must be the reserved identifier `_src`.

## Identifiers

Cloth identifiers are case-sensitive and must begin with a letter or an underscore. Digits may appear after the first
character.
