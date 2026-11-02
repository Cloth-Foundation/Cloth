---
name: cloth-feature-integration
description: Make each integrated Cloth compiler feature concrete in project skills. Use when implementing, extending, integrating, or completing a compiler feature or an implementation stage, and when updating the skill coverage for that work.
---

# Cloth feature integration

A feature integration is not complete until its behavior is captured in a
project skill. Apply this to each integrated stage as well as a completed feature;
record the supported slice without implying that later stages are implemented.

## Capture the feature

Read the [Cloth language skill](../cloth-language/SKILL.md) and the relevant
feature records before implementation. After integrating the change, create or
update a skill under `.agents/skills/` that makes the feature concrete for future
compiler development. Update an existing skill when it already owns the feature;
create a focused `cloth-<feature>` skill when the feature needs its own guidance.
Each feature must have explicit skill coverage, even when several related
features share a skill.

Capture the information needed to preserve and extend the integrated behavior:

- Confirmed syntax and semantics, with a minimal valid example or a link to a
  maintained example. Include meaningful rejection cases where they define the
  feature's boundary.
- Implementation scope and the compiler components that own it, including
  invariants that later changes must preserve.
- Verification evidence: relevant tests or commands, observed results, and target
  coverage. Distinguish native execution from verified IR and untested targets.
- Unsupported cases, incomplete stages, and unresolved decisions. Keep proposals
  distinct from confirmed rules and implemented behavior.

Use existing references as the authoritative location for substantial details;
link to them from the skill instead of copying competing versions. Recheck source
and test results before claiming support. Preserve Cloth's first-error behavior.

## Keep the coverage discoverable

Update the Cloth language skill's routing when adding a feature skill or reference,
so work on that feature loads its guidance. Keep confirmed feature decisions in the owning skill's references and shared
language decisions in the [semantics index](../cloth-language/references/semantics.md).
Keep current implementation rules and boundaries in the owning skill. Record dated
verification evidence in [development history](../cloth-language/references/development.md)
or a linked focused record. Avoid sending routine feature work through the entire
shared history or copying the same contract into competing authoritative files.

Before reporting the integration complete, check skill metadata, relative links,
examples, and consistency with the implemented scope and observed verification.
If the skill-creator validator is available, run it on new or changed skills.
Mention the skill coverage in the completion report. If implementation or
verification remains incomplete, record that state and report the remaining work.

Apply this workflow to the feature being integrated. Backfill older features when
requested or when their missing guidance is needed for the current change.
