# Loom RFC Process

## Overview

Loom evolves through **RFCs** (Request for Comments). This process allows the community to propose, discuss, and refine language changes, compiler behavior, standard library additions, and tooling improvements before they are accepted.

The RFC process ensures Loom remains consistent, coherent, and community-driven as it grows.

---

## When to Submit an RFC

You should submit an RFC when you intend to:

- Add or change language syntax or semantics
- Introduce a new feature (e.g. generics, async/await)
- Modify or deprecate standard library behavior
- Change the compiler architecture or backend
- Standardize major developer tooling (formatter, LSP, etc.)
- Propose new governance or structural policies

**You do not need an RFC** for:
- Bug fixes
- Minor documentation or style corrections
- Internal refactors with no observable user impact

---

## RFC Lifecycle

1. **Draft**
   - Fork the repo, create a branch (e.g. `rfc/ternary-operator`)
   - Write your proposal in `rfcs/YYYYMMDD-feature-name.md`
   - Follow the [RFC Template](#template)

2. **Pull Request**
   - Submit a pull request to `main` with `[RFC]` in the title
   - Fill out the PR description with a summary of your motivation

3. **Discussion & Feedback**
   - The Loom Steering Council and community will provide feedback
   - Revisions may be requested
   - A final comment period will be announced before approval

4. **Final Decision**
   - The Steering Council votes on the RFC (majority rules)
   - If accepted:
     - The RFC is merged into the `rfcs/` directory
     - The feature is marked as "Approved" and may enter development
   - If rejected:
     - Reasoning is documented and the PR is closed

5. **Implementation**
   - Implementation may begin once the RFC is approved
   - Reference the RFC in related pull requests
   - Status updates should be posted in the PR or Discussions

---

## RFC Template

Copy this into your RFC file:

```markdown
# Feature Name

- Proposal: `YYYYMMDD-feature-name`
- Authors: [Your Name]
- Status: Draft / Under Review / Approved / Rejected / Implemented
- Discussion: (link to GitHub PR or thread)

## Summary

Concise description of the feature and motivation.

## Motivation

Why do we need this? What problems does it solve?

## Guide-Level Explanation

Explain the feature in a way that fits into Loom’s existing language model. Include usage examples, syntax, and edge cases.

## Reference-Level Explanation

Describe how the feature would be implemented. Include grammar changes, type system impacts, IR/codegen considerations, etc.

## Drawbacks

What are the downsides? Increased complexity, learning curve, breaking changes?

## Alternatives

What other options were considered? Why were they rejected?

## Prior Art

Mention any inspirations from other languages (Rust, C#, Java, etc.)

## Unresolved Questions

Anything still unclear? Areas needing exploration before implementation?

## Future Directions

How could this evolve? What related features could build on this?

````

---

## RFC File Naming

RFC filenames must be:

* Lowercase, hyphen-separated
* Prefixed with the date in `YYYYMMDD` format
* Example: `rfcs/20250804-ternary-operator.md`

---

## Decision Transparency

All RFC votes and rationale are recorded in GitHub via:

* PR comments
* A decision tag in the merged file
* Quarterly project updates

---

## Proposing Without Implementation

You are welcome to propose ideas even if you can't implement them. In that case:

* Mark the RFC as `Unassigned`
* A maintainer or contributor may adopt it

---

## Questions?

Open a discussion at [Discussions](https://github.com/SuperScary/Loom/discussions), or contact the Steering Council directly.

---

**Thank you for shaping the future of Loom.**
