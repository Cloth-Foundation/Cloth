# Shuttle and imports Stage 0 evidence

Stage: contract and ownership publication, 2026-11-07. Product implementation
starts in Stage 1. This record does not establish compiler import support,
manifest loading, Shuttle commands or native target execution.

## Published contracts

- [Compiler import semantics](../../cloth-imports/references/semantics.md) owns
  selected/wildcard syntax, roots, visibility, binding and circular source graphs.
- [Compiler request API](../../cloth-imports/references/compiler-api.md) owns the
  selected Stage 1 public types/facade and diagnostics/entry boundary.
- [Shuttle manifests and builds](semantics.md) owns TOML/schema, project discovery,
  configuration roots and build/run orchestration.
- [Implementation plan](../../../../todo/shuttle-and-imports-plan.md) owns stage
  progress and remaining implementation checks.

Source audit: CompilerDriver is still an internal CompilerSettings-based
single-file path. SourceUnit and DeclarationAnalyzer have no imports/source graph
and use one file scope. NamedTypes/FunctionAnalyzer still use one-file type maps.
ProgramAnalyzer scans the current unit's main functions. Shuttle has no source
implementation or TOML/Compiler dependencies. Bootstrap's module manifests still
use uppercase table names and need the Stage 2 schema migration. No runtime
compiler or Gradle dependency surface was changed by Stage 0.

## TOML choice and observed behavior

Select Jackson TOML 2.21.1, with STRICT_DUPLICATE_DETECTION enabled and TOML 1.0
as the syntax baseline. The JDK 25
[selection probe](../scripts/TomlParserProbe.java) executed against cached
jackson-dataformat-toml/core/databind 2.21.1 and annotations 2.21 and passed
**23 checks** on Windows x64.

Observed checks include all four existing Bootstrap TOML files, quoted/literal
and multiline strings, Unicode, comments, inline tables, arrays with standard
trailing commas, CRLF, duplicate keys/tables, table/value conflicts, first syntax
failure locations and rejection of the tested TOML 1.1-only forms.

A gated Reader returns one character at a time and throws AssertionError if the
parser reads beyond the first malformed declaration. Parsing aborts with the
first located StreamReadException before the reader gate is reached. This tests
immediate stop rather than taking the first item from an accumulated error list.
The probe also establishes the public success tree's unavailable field positions.
Shuttle must report schema key paths and honest file-level labels rather than
claiming precise field spans.

This is a focused dependency-selection probe, not a complete TOML conformance
suite. Stage 2 must test the actual loader, strict UTF-8 and schema behavior.
No compiler/native/linker work is exercised, and no Linux/macOS runtime claim is
made for this Java-only probe.

## Reproduce the dependency probe

Supply the four pinned jars on a JVM classpath. For the existing Gradle cache,
run from the repository root in PowerShell, setting the cache location for that
machine:

```powershell
$cacheRoot = 'C:/Users/wylan/.gradle/caches/modules-2/files-2.1'
$coordinates = @(
    'com.fasterxml.jackson.dataformat/jackson-dataformat-toml/2.21.1',
    'com.fasterxml.jackson.core/jackson-core/2.21.1',
    'com.fasterxml.jackson.core/jackson-databind/2.21.1',
    'com.fasterxml.jackson.core/jackson-annotations/2.21'
)
$probeJars = @(foreach ($coordinate in $coordinates) {
    rg --files "$cacheRoot/$coordinate" -g '*.jar' -g '!*-sources.jar' -g '!*-javadoc.jar'
})
$probeClasspath = $probeJars -join [System.IO.Path]::PathSeparator
java --class-path $probeClasspath .agents/skills/shuttle-build/scripts/TomlParserProbe.java .
```

On another OS, use that host's JVM classpath separator and the same pinned jars.
The checked Java source requires no product Gradle changes or generated files.

Primary library evidence:

- [Jackson TOML 2.21.1 API](https://javadoc.io/doc/com.fasterxml.jackson.dataformat/jackson-dataformat-toml/2.21.1/index.html).
- [Pinned parser source](https://raw.githubusercontent.com/FasterXML/jackson-dataformats-text/jackson-dataformats-text-2.21.1/toml/src/main/java/com/fasterxml/jackson/dataformat/toml/Parser.java):
  first-failure throws and duplicate handling.
- [Library usage](https://raw.githubusercontent.com/FasterXML/jackson-dataformats-text/2.21/toml/README.md).
- [TOML 1.0 specification](https://toml.io/en/v1.0.0).

## Publication checks

The bundled skill-creator quick validator passed for all six new/changed skills:
cloth-imports, shuttle-build, cloth-language, cloth-toolchains, cloth-program-entry
and cloth-semicolons. PyYAML 6.0.3 was installed into an isolated temporary
workspace directory to run the validator; no global Python or product dependency
was changed. That temporary dependency directory is removed after validation.

Publication checks passed for 14 documents: 366 local file links, 57 Markdown
anchors, two TOML schema examples parsed by Python's standard tomllib, balanced
code fences and clean whitespace. The development-history check covers the new
Stage 0 entry, not unrelated historical records. Git diff whitespace checks also
pass. References and scope statements distinguish this publication from later
runtime/compiler implementation. The plan records Stage 0 as complete and
Stages 1-7 as pending.
