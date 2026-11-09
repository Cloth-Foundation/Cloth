# Generic types Stage 6: integration and publication

The generic-types plan is complete through its first delivered class/struct slice
and public source List. The [contract](semantics.md) owns confirmed semantics;
[Stage 5](stage-5.md) owns the actual library and import/ownership evidence.
The [user guide](../../../../docs/generics.md), runnable
[Generics.co](../../../../examples/Generics.co), public
[List API](../../../../Standard-Library/cloth/collections/README.md) and
[Collections project](../../../../examples/Collections/README.md) make this scope
discoverable outside implementation records.

Stage 6 changes documentation and adds the small generic example. Compiler,
Shuttle and public List implementation/test sources are unchanged from Stage 5.
It does not implement another generic feature, migrate consumers, commit/publish
Git changes or update the release library pin.

## Delivered scope

- Explicit invariant generic classes/structs, owner-wide lexical parameters and
  original declaration plus ordered exact closed argument identities.
- Declaration-time opaque checking of unused templates, then separate closed
  callable/flow checking and target-scoped native specialization. Ordinary value
  arguments, normalized nullable substitution and public-signature privacy apply.
- Existing value/callable storage for concrete layouts, constructors/destructors,
  methods/operators, generic bases, metadata and checked class identities.
  Independent recursive buffers, typed relocation and nullable witnesses retain
  their established contracts and resource bounds.
- Actual source List with private generic slots and nine documented methods.
  Independent array element dimensions, copy, checked rectangular export and
  caller-managed class element lifetime are implemented.

The new standalone example executes class and struct parameters, an independent
array result after deleting its Box, nullable struct storage, universal T metadata
and exact closed identity. It prints the output published in the user guide.

## Caller audit and follow-ups

The [caller audit](caller-audit.md) inventories actual source consumers and
traces Bootstrap's TokenList, same-file TokenCursor, token/span ownership,
successful scan transfer, partial-error cleanup and SourceFile lifetime. Default
List<Token> would not destroy Tokens. Copying an owning adapter also needs an
explicit policy to avoid duplicate destruction. No Bootstrap source is changed.
Concrete array-backed examples remain storage proofs, and Test.co retains intended
constrained/interface syntax. Existing math sources remain untouched.

The [separate follow-up record](../../../../todo/generic-follow-ups.md) keeps
constraints/capabilities, generic interfaces, callable-owned parameters,
omitted-argument inference/defaults, variance, collection protocols and additional
generic type forms unimplemented and undecided where appropriate. It preserves
the confirmed non-generic class-implementer interface order. Interface planning
now acknowledges the implemented class/struct parser foundation rather than
claiming angle arguments only mean numeric widths. No new semantics are selected.

## Verification (2026-11-08)

The normal project checks pass:

```powershell
.\gradlew.bat :Compiler:check :Shuttle:check --offline --console=plain --no-daemon '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process'
```

| Task | Suites | Tests | Stage 6 status |
| --- | ---: | ---: | --- |
| Compiler test | 182 | 1,950 | Up to date from the final Stage 5 full run. |
| Compiler genericSyntaxTest | 10 | 86 | Up to date. |
| Compiler importSyntaxTest | 2 | 23 | Fresh execution. |
| Compiler importResolutionTest | 4 | 54 | Fresh execution. |
| Compiler importSemanticsTest | 1 | 33 | Up to date. |
| Shuttle test | 8 | 61 | Up to date from the final Stage 5 full run. |
| Shuttle configurationTest | 2 | 25 | Up to date. |

All recorded suites have zero failures/errors/skips. The check run completes in
23s with two executed and thirteen up-to-date actionable tasks. Native-free tasks
repeat existing suites; their totals are not additional unique Compiler tests.
Stage 5's fresh 1,950 Compiler/61 Shuttle regression remains valid for the unchanged
implementation; this record does not describe cached suites as fresh execution.

Twenty-three fresh CLI probes pass:

- Ten Windows native runs: Generics, public Collections, concrete StringList,
  concrete RowList and Bootstrap through Shuttle, each on x64 and x86. Outputs
  match expectations; Bootstrap smoke output includes EOF and normal completion.
- Nine nonempty target objects for the new generic example: Windows MSVC x64,
  x86 and ARM64; Linux GNU/musl x64 and ARM64; macOS x64 and ARM64.
- Four first-error rejections for omitted arguments, constraints, callable-owned
  generic parameters and invalid unconstrained arithmetic. Each returns 1 with
  one expected diagnostic and no object output.

This stage's new example has fresh native/object evidence. Existing all-nine-profile
IR verification and full Bootstrap differential/ownership coverage remain in the
unchanged Stage 4/5 suites. Foreign Windows ARM64, Linux and macOS profiles are
not claimed as native executions. The Stage 6 Bootstrap runs are smoke checks,
not a replacement for BootstrapLexerTest's differential oracle.

Reports, task statuses, XML copies/hashes, source snapshots, probe outputs and
artifact hashes are archived under `build/generic-types-stage6/`. Preservation
checks compare existing caller sources and Stage 5 implementation hashes. Skill,
link, anchor, fence, whitespace and final plan checks complete publication.

All five updated skills pass the bundled validator with temporary pinned PyYAML
6.0.3. Final publication checks cover 27 documents, 909 local links, 94 anchors
and 209 archived XML hashes. Fifteen existing caller/source files and fifteen
Stage 5 implementation/build/test files match their recorded hashes. The new
example, caller inventory, probe logs/source snapshots and artifacts have their
own hashes. The generic plan has all 17 tasks checked; the array plan remains at
35 and the new follow-up tasks remain unchecked. Temporary validation scripts,
probe sources and dependency files are removed before completion.

## Completion and release boundary

All three Stage 6 tasks are completed after verification. The generic plan closes
Stages 0-6; the array plan's public List gate stays closed while its separate
Stage 8 remains untouched. The user-facing README no longer describes public List
as unavailable and links the implemented generic scope and future work.

The library pin remains `ea48ad55be9577f0b876f04c5aafe4420919597d`, with working
library edits and the existing math files preserved. Normal release packaging
still requires intended clean/pinned library history; Stage 5's temporary pinned
snapshot test is not a release of this checkout. Documentation publication here
does not authorize or perform commits, pushes, a pin update, interface/constraint
implementation or Bootstrap migration.
