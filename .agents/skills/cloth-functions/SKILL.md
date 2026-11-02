---
name: cloth-functions
description: Design, explain, or extend Cloth function declarations, strict parameters and returns, scopes, recursion, overload resolution, or deferred local function blocks.
---

# Cloth functions and function blocks

Read [confirmed function semantics](references/semantics.md). For ordered result
lists and positional reception, also use [multiple results](../cloth-multiple-results/SKILL.md).
For buffered parameters/results use [buffer lifetimes](../cloth-buffer-lifetimes/SKILL.md).

## Development rules

- An omitted return annotation means void. Brace bodies require explicit returns;
  `= expression` bodies return implicitly. Preserve void expression returns,
  bare void returns, and explicit `-> void` discards. Check every completing path.
- Keep names/signatures, typed call selection, return checking, and executable
  entry validation as distinct stages. Resolve forward/direct/mutually recursive
  calls by stable symbols. Return context does not choose an overload.
- Already-typed arguments/results match exactly. Contextual numeric literals are
  distinct from converting typed bindings. Prefer the ordinary default literal
  type when an exact overload exists; otherwise preserve unique-match/ambiguity
  rules, independent of declaration order. Array extents also affect matching.
- Arguments finish once, left to right, before the callee body; preserve snapshots
  before later captured effects. Mutable parameters rebind locally. `final`
  prevents rebinding while preserving the separate array element-write rule.
- A local `def<int> calculate = { return input + 1 }` declares a deferred,
  parameterless block. Its explicit annotation is mandatory. Every evaluated
  name occurrence reruns the block against current captures; declaration does
  not execute and ordinary copies store the evaluated result.
- A block return exits that block. Captures resolve only visible surrounding
  bindings; the declaration cannot capture itself or later locals. Current
  implementation expands each read with separate local/return control flow.

## Boundaries, source, and verification

Both `=` and `:=` block declarations work without memoization. Block binding
assignment/updates are currently rejected (C3042); future replacement and escaping
capture contracts remain unresolved. `opt<T>` / `final opt<T>`, named arguments and runtime defaults
are implemented for ordinary functions; read [optional arguments](../cloth-optional-arguments/SKILL.md)
for binding, declaration scope, evaluation, ranking and ABI invariants. Methods/classes
are separate future callable forms.

Start with [DeclarationAnalyzer](../../../Compiler/src/main/kotlin/cloth/semantic/DeclarationAnalyzer.kt),
[CallAnalyzer](../../../Compiler/src/main/kotlin/cloth/semantic/CallAnalyzer.kt),
[FunctionAnalyzer](../../../Compiler/src/main/kotlin/cloth/semantic/FunctionAnalyzer.kt),
and [EmitFunctions](../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitFunctions.kt).
Examples: [Functions.co](../../../examples/Functions.co) and
[FunctionBlocks.co](../../../examples/FunctionBlocks.co).

Relevant suites: `FunctionParsingTest`, `DeclarationScopesTest`,
`CallResolutionTest`, `FunctionReturnsTest`, `FunctionEmissionTest`, and
`FunctionBlocksTest`; add `StringFunctionTest` for buffered captures/returns.
Recorded stages and native verification are in the
[development history](../cloth-language/references/development.md#earlier-milestone-snapshots).
Use source units with function declarations for block checks; the legacy snippet
analyzer is a separate internal API. Historical test totals are not current runs.
