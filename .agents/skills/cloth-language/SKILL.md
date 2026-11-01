---
name: cloth-language
description: Design, explain, document, or implement the Cloth programming language using its intended syntax, confirmed semantics, and implementation status. Use for Cloth source examples and compiler behavior.
---

# Cloth language

Cloth is a modern, strongly typed, object-oriented language for application and
library development. Java and Kotlin provide a familiar point of reference, with
a light C++ influence through primary constructors and named destructors. These
influences do not import those languages' semantics into Cloth.

The language's distinctive feature is first-class state machines embedded in
classes, with named states and explicit transitions.

Cloth favors concise syntax and removes unnecessary ceremony. Follow the
specific compact forms in the semantics reference rather than importing
boilerplate or silently inventing additional shorthand.

## Design authority

- [Test.co](../../../examples/Test.co) is intended language design, including features not
  implemented by the compiler yet. It is a substantial example, not an exhaustive
  specification or a claim that the whole file currently compiles.
- Read [confirmed semantics](references/semantics.md) before deciding language
  behavior. These user decisions refine the examples and include accepted syntax
  that has not yet been added to `../../../examples/Test.co`.
- Read [development status](references/development.md) when working on the
  compiler, assessing support, or deciding what work comes next. Recheck relevant
  source before relying on its implementation snapshot.
- For `usize`, target-sized counts, or migration of compiler data result types,
  read the [staged implementation plan](references/usize-implementation.md).
  The implemented contracts use exact target-sized usize results and native
  buffer counts; [Windows x86 validation](references/usize-x86-validation.md)
  records the supported 32-bit ABI, SSE2 baseline and assembly evidence.
  All seven migration steps are complete; preserve exact usize identity and
  the documented target coverage when extending this implementation.
  The earlier usize-main extension is superseded by the signed-isize entry
  policy implemented in isize step 2: unsigned main returns are rejected.
  Keep its completed steps and unresolved decisions current; planning is not
  evidence that a feature has been implemented or verified.
- For `isize`, read the [three-step implementation plan](references/isize-implementation.md).
  It reuses the shared integer and target machinery. Keep measurements as usize,
  preserve distinct isize identity and the implemented signed-only main policy.
  All three steps are complete, including real Windows x64/x86 integration and
  verified IR for the supported foreign targets. Preserve that coverage when
  extending the implementation; other 32-bit targets remain unsupported.
- Distinguish intended design, verified implementation, proposals, and unresolved
  questions. Missing implementation does not invalidate intended syntax.
- Ask focused questions when a necessary semantic choice is unresolved. In the
  ongoing design discussion, the user prefers one question at a time.

## Maintain project knowledge

Record explicit user decisions in the semantics reference. Keep open questions
separate and remove them when settled. Update development status when source or
verification establishes a change; do not invent a roadmap from parser gaps.

Use the examples and confirmed rules to guide implementation. Do not introduce
programmer-visible pointers, reference qualifiers, a `mut` qualifier, automatic
object destruction, GC, or C++ use-after-free behavior as an assumed convenience.
Internal compiler and LLVM pointer machinery is a separate implementation concern.
