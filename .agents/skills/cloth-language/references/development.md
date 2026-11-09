# Cloth development status

For current feature guidance and focused source/test routing, use the
[feature skill index](../SKILL.md#feature-skills). This file retains dated
development and verification history; read the relevant milestone rather
than treating every earlier snapshot as current behavior.

## Current work

### Interface Stage 1 declarations and names (2026-11-08)

The Kotlin and Bootstrap lexers reserve `interface` and `override`. The
parser retains required signatures, explicit defaults, generic parent
references and method prefixes. File scopes register original interface
identities across imports. C3118 reports invalid header kinds and argument
lists. C4035 guards interface compilation before conformance and code
generation.

The focused run passed 133 tests across 14 suites in 1m 1s. The native-free
import task repeated its 35 tests successfully. Existing class and generic
regressions ran on Windows x64 and x86 and covered IR and objects on all
nine profiles. Bootstrap matched 124 inputs on both native profiles.
[Interface Stage 1](../../cloth-interfaces/references/stage-1.md)

### Generic interfaces added to the interface release plan (2026-11-08)

After class and struct generics landed, the user asked to include generics in
interface planning. The release now includes explicit, unconstrained interfaces
and generic class conformance. The non-generic fixture remains the first
dispatch milestone. The plan maps generic scope, substitution and specialization
onto the interface stages. Constraints and additional implementer kinds have
separate follow-ups. [Interface scope](../../cloth-interfaces/references/design.md#generic-interface-release-scope) ·
[Interface plan](../../../../todo/interfaces-plan.md)

The user confirmed one application of each interface declaration per class,
struct, interface, or enum. The rule includes inherited interfaces. A diamond
that reaches the same application contributes one entry. The conformance stage
now covers conflicting applications from direct clauses and inherited paths.
[One-application rule](../../cloth-interfaces/references/design.md#one-application-per-interface-declaration)

### Growable arrays Stage 8 final integration (2026-11-08)

The [final array record](../../cloth-arrays/references/growable-arrays-stage-8.md)
reconciles the already-complete rectangular Stage 6 and fulfilled public List
gate with the remaining Stage 8 audit. Obsolete outer-count tests were migrated
in Stage 1; fixed literals, explicit extents and rectangular inner-shape checks
remain covered. No compiler, SDK, Bootstrap or maintained example changes are
required. Bootstrap's owning list and borrowed cursor retain their separate
migration boundary.

The fresh focused run passes 463 tests across 38 suites. Twenty-four fresh
example executions pass across Windows x64, Windows x86 and GNU Linux x64 under
Ubuntu WSL. Row-array and actual imported Collections examples verify startup
IR/objects on all nine profiles; six other fixtures add Linux x64 objects.
Fresh full regression passes 1,950 Compiler and 61 Shuttle tests; both project
checks pass in 10m 2s with zero failures/errors/skips. Unchanged native-free
generic/import/configuration tasks remain up to date. Publication checks pass
for 27 documents and seven skills, preserving 524 implementation/test/example
inputs. All 39 array-plan tasks are complete; the generic plan retains its 17
completed tasks and separate future protocols/owning-consumer follow-ups.

### Generic types Stage 6 integration and publication (2026-11-08)

[Stage 6](../../cloth-generics/references/stage-6.md) closes the delivered generic
class/struct and public List plan. The user-facing generic guide and standalone
Generics example demonstrate exact arguments, value copies, nullable storage and
metadata. The README now links the implemented List rather than treating it as
a future collection. Separate follow-ups retain constraints, generic interfaces/
functions, callable-owned parameters, inference, variance and collection protocols.

The caller audit preserves Bootstrap's owning TokenList, same-file borrowed
TokenCursor, token/span ownership, partial-error cleanup and source lifetime.
Existing callers, intended Test.co and math sources are hash-checked unchanged.
Compiler, Shuttle and public List implementation/test sources match the Stage 5
hashes. No consumer migration, release pin update or Git publication is performed.

Both project checks pass in 23s: import syntax/resolution execute freshly;
the 1,950 Compiler and 61 Shuttle tests and other native-free tasks remain up to
date from the passing Stage 5 source state. Twenty-three fresh CLI probes cover
ten native example runs on Windows x64/x86, nine generic-example objects and four
one-error rejections. The linked record distinguishes cached regression, fresh
native checks and foreign object-only evidence and retains publication validation.

### Generic types Stage 5 public List and imported-library proof (2026-11-08)

[Stage 5](../../cloth-generics/references/stage-5.md) supplies the actual
standard-library List class with private generic slots and the documented nine
methods. The maintained Collections project imports the real file. Copies retain
independent array elements and complete absent nullable-field witnesses; array
export keeps rectangular checks. Class-valued elements remain caller-managed.

Actual-source tests cover defining-file helper privacy, repeated/circular routes,
root aliases, invariant arguments, metadata identity and default/override/disabled
selection. Native Windows x64/x86, all-nine-profile IR/objects and poisoned helper
cycles pass. Both real commands consume the source through the existing public
compiler boundary. A temporary pinned snapshot verifies byte-identical release
export without changing the working library's Git pin or existing math files.
The new [collections skill](../../cloth-collections/SKILL.md) owns future API work.
The focused record retains final counts and publication evidence. Stage 6,
collection protocols, release preparation and Bootstrap migration remain separate.

The final focused checks pass 191 Compiler tests in 25 suites and nine Shuttle
tests in two suites, plus 86 generic, 33 import and 25 configuration native-free
tests (1m 37s). The full regression freshly executes 1,950 Compiler tests in 182
suites, 61 Shuttle tests in eight suites and the 86 generic tests; the other
native-free tasks remain up to date (10m 3s), all with zero failures/errors/skips.
Eighteen CLI probes and eight skill validations pass. The linked record retains
27-document publication checks and 243 archived XML hashes. The public List
delivery gate is closed; generic Stage 6 is not marked complete.

### Generic types Stage 4 native values, storage and metadata (2026-11-08)

[Stage 4](../../cloth-generics/references/stage-4.md) emits closed classes/structs,
constructors/destructors, methods/operators, generic bases and exact typeinfo
through the existing ABI and value storage. The maintained imported generic List
and Maybe fixtures execute on Windows x64/x86. Private struct slots preserve
independent row lengths, returned copies, absent nullable witnesses and class
identity under poisoned frees; rectangular exports retain their shape checks.

Module-local nominal metadata keys use canonical closed descriptors, fixing
same-spelled private actuals and avoiding flattened argument keys. Canonical text
premeasures shared argument DAGs and stops above 1,048,576 UTF-8 bytes as C4034;
human display stays separately bounded. Struct buffered classification is memoized
after fields are finalized; exact inline-size checks precede LLVM layout. C4033
retains the symbolic/unresolved native guard. The focused record owns final counts,
native versus all-nine-profile IR/object evidence and publication validation.

Only Stages 0-4 are completed. The actual public standard-library List module,
its delivery gate and Bootstrap migration remain separate work.

The final focused run passes 155 tests in 20 suites (58s), plus 77 generic and 33
import native-free tests. The full regression freshly executes 1,929 Compiler
tests in 179 suites and 57 Shuttle tests in seven suites, with zero failures,
errors or skips (9m 34s); native-free tasks remain up to date from that focused
run. Nineteen CLI probes pass. Eleven skills validate, and the linked native
record retains publication checks, archived XML hashes and exact target scope.

### Generic types Stage 3 callable and flow integration (2026-11-08)

[Stage 3](../../cloth-generics/references/stage-3.md) checks unused templates with
unknown exact CTypeParameters, then checks required closed callables. Independent
file bindings/models and source-linked body views preserve original ASTs while
isolating defaults, captures, ordered results, operators, overload trials,
presence, metadata and class lifetime facts. Signature keys retain exact types;
private access follows original class declarations across their instances.

Public generic signatures, inherited public struct methods and inferred operator
results reject private helper exposure as C3117, while caller-private actual
arguments remain allowed. C3115/C3116 bound symbolic expansion independently of
closed quotas. Numeric width templates check opaque owner capabilities with the
widest supported format, followed by actual width checks when requested. Bounded
human display avoids flattening shared argument trees or splitting surrogate pairs;
canonical metadata/identity remain separate. FunctionAnalyzer now succeeds for
valid generics; C4033 moves to native emission validation.

The four new suites cover the maintained imported List fixture and two simultaneous
owners, exact overloads, nullable substitution/proofs, invalidated captures,
explicit class lifetime, operators/widths, private helpers and source error order.
The native-free tasks exercise generic checking without LLVM binaries. Ten CLI
probes each return 1 with one expected error and no artifact. The focused record
retains exact final test totals, target scope, report hashes and publication checks.
Only Stages 0-3 are marked complete; native generic programs, public List and
Bootstrap migration remain future work.

The final focused run passes 94 tests in nine suites; native-free tasks pass
77 generic and 33 import tests. The final full regression freshly executes
1,906 Compiler tests in 176 suites and 57 Shuttle tests in seven suites, all with
zero failures/errors/skips (8m 54s); the native-free tasks are up to date from that
focused run. The final run includes the corrected Unicode display case. Eleven
skills validate; publication covers 20 documents, 954 local links, 117 anchors
and preserved plan scope. Archived final XML retains per-suite SHA-256 hashes.

### Generic types Stage 2 closed types and bounded instantiation (2026-11-08)

[Stage 2](../../cloth-generics/references/stage-2.md) adds target-scoped interning
by original template and ordered exact normalized arguments, substituted
fields/base links/declared callable types, and per-owner annotation/receiver facts.
Closed CClass/CStruct descriptors share original ASTs while retaining independent
fields and signature facts. Nullable substitution reuses one carrier; primitive,
target-sized, array and nominal identities remain exact. Defining-file private
helpers and caller-private actual types retain their original identities through
repeated/circular imports.

An explicit worklist registers provisional identities before requirements and
validates inheritance/struct cycles after field discovery. C3112/C3113/C3114
control 64 new dependency steps, 4,096 distinct instances and 256 argument layers
after substitution. Same-key class recursion reuses before charging budgets.
Generic compilation now reaches C4033 after closed validation; symbolic body
checking, callable/flow specialization and generic native programs remain later.

The focused run passes 42 tests in six suites; the expanded native-free generic
task passes 45 tests in five suites. Exact limit boundaries, nullable/array
substitution, preserved ASTs, target isolation, private imported helpers and the
maintained List fixture all pass. Native LLVM storage shapes check all nine ABI
profiles, without emitting generic programs/objects. Eight CLI probes each return
1 with one expected diagnostic and no artifact. Stages 0-2 are marked complete;
public List, later generic stages and Bootstrap migration remain open. The focused
record owns final regression, archived hashes and publication evidence. The final
run passes 1,874 Compiler tests in 172 suites, 45 native-free generic tests,
33 native-free import tests and 57 Shuttle tests in seven suites (9m 29s), all
freshly executed with zero failures/errors/skips. Seven skills validate;
publication checks cover 15 documents, 817 local links, 99 anchors and the
preserved plan scope. Final XML copies retain per-suite SHA-256 hashes.

### Generic types Stage 1 syntax and source identity (2026-11-08)

[Stage 1](../../cloth-generics/references/stage-1.md) adds class/struct parameter
lists, nested named applications and lexical type-parameter symbols with original
source spans. Named receivers and cast/check targets use token lookahead to
preserve comparisons; primitive widths, return lists, opt wrappers, nullable and
array suffixes keep their existing roles. The 256-level argument guard rejects
deeper syntax with C2012.

Name resolution uses original file bindings, keeping imported/private template
identities and owner parameter scope without requiring closed layouts. C3108-C3111
cover invalid parameters, arity, raw templates and invalid arguments. Deferred
parameter/function/enum grammar uses C2011; C4033 stops closed compilation after
name checks, including unused templates. Legacy type/signature APIs also prevent
application erasure. Symbolic body checks, substitution, closed types/layouts,
public signature visibility and native generic execution remain later stages.

The focused run passes 131 tests in ten suites; separate native-free tasks pass
25 generic parsing/name tests and 33 import semantic tests. The final regression
passes 1,852 Compiler tests in 168 suites and 57 Shuttle tests in seven suites,
all freshly executed with zero failures/errors/skips (9m 27s). Six CLI probes each
return 1 with one expected diagnostic and no executable. The boundary tests reject
generic object emission on all nine targets and execute ordinary compatibility
programs on Windows x64/x86. Public List and Bootstrap remain untouched. The
generic plan marks only Stages 0-1 complete; the focused record retains exact
regression and publication evidence. All seven changed skills validate; publication
checks pass for 14 documents, 797 local links, 99 anchors and the preserved plan
scope. XML copies retain per-suite hashes.

### Generic types Stage 0 contract and baseline (2026-11-08)

The user confirmed the [generic first-slice contract](../../cloth-generics/references/semantics.md):
classes/structs with explicit invariant arguments, owner-wide lexical parameter
scope, declaration and closed-instance checking, ordinary value arguments,
normalized nullable substitution and private helper visibility. Constraints,
generic interfaces/functions/method parameters, variance and inferred type
arguments remain later. The [acceptance fixtures](../../cloth-generics/references/acceptance-fixtures.md)
specify imported List/private slots, independent row lengths, nullable values,
metadata, first-error rejections and controlled resource boundaries. They are
future language inputs, not runnable or shipped generic source.

The [Stage 0 record](../../cloth-generics/references/stage-0.md) captures a fresh
1,821-test/165-suite Compiler baseline, with zero failures/errors/skips (8m 55s).
Shuttle's existing 57-test/seven-suite result remains up to date; it is not a fresh
execution. Forty-three named Compiler preservation suites account for 571 tests.
Archived XML/hash reports, exact task statuses and 396 unchanged Kotlin source
hashes separate that evidence from generic implementation. Resource policies are
selected for future controlled guards: 64 dependency steps, 4,096 closed instances
and 256 levels of argument structure. No production/test implementation,
Bootstrap, standard-library or intended Test.co changes are added. Generic
implementation stages and public List delivery remain open.
All five changed skills validate. Publication checks cover 12 documents,
719 relative links and 96 anchors, and recheck source/archived XML hashes.
Both generic Stage 0 tasks are complete; later stages remain unchecked.
### Growable arrays Stage 7 collection proof and generic List gate (2026-11-08)

The [Stage 7 record](../../cloth-arrays/references/growable-arrays-stage-7.md)
proves the complete ordinary StringList method surface and a private struct-slot
RowList with independently sized array elements. The user confirmed different
List array-element lengths, independent copy and checked rectangular toArray.
The [generic-types plan](../../../../todo/generic-types-plan.md) and
[generic skill](../../cloth-generics/SKILL.md) capture the class/struct prerequisite,
closed identities, substitution, specialization limits, imports, metadata and
collection protocol boundaries. Their proposed generic semantics are not yet
implemented or all confirmed.

All 87 focused tests across nine suites pass, including 19 new tests. Real
allocation/poison probes cover repeated collection methods, independent exports,
removed rows and retained witnesses. Both 1,000-cycle helper-frame probes return
to reserved live bytes; 100 reserved string appends allocate exactly 200 received
buffers and move zero old bytes. Both Windows widths execute the fixtures; the
two new suites verify IR/objects across all nine profiles. Bootstrap differential,
import and standard-library runtime tests pass unchanged. No production compiler,
Bootstrap or standard-library source changes are needed. Public generic List is
still absent, so four Stage 7 tasks are checked and its delivery milestone remains
open. The last full Compiler/Shuttle regression is the Stage 6 snapshot below.
Both maintained examples also execute through the CLI with --no-stdlib on Windows
x64/x86. All seven changed skills validate; publication checks pass for 16
documents, 842 relative links, 99 anchors and four source/test/example files.
The Stage 7 record owns archived XML/hashes, CLI outputs and validation evidence.

### Growable arrays Stage 6 rectangular mutation and integrations (2026-11-08)

The [Stage 6 record](../../cloth-arrays/references/growable-arrays-stage-6.md)
owns whole-row append/insert/removeAt, clear and reserve for rectangular arrays.
Incoming rows establish/check inner shape before growth allocation. Owned shared
vectors retain witnesses after removal, all-null rows, clear and null. Whole
replacement checks the live binding descriptor; selected older descriptors keep
their own outer count. Removed rows copy parent constraints before slot cleanup.
Embedded-row shape changes still fail with C3107; the native rank gate is removed.

All 248 focused tests across 15 suites pass, including 26 new source/storage tests.
Native Windows x64/x86 probes cover ranks two through four, nullable witnesses,
struct/class/self fields, removed results, poisoned frees and invalidated reads.
Two 1,000-cycle probes retain only reserved live bytes after each cycle; 200 null
row inserts/removals allocate no extra storage. Providers, growable type identity,
recursive text, producers/defaults/captures, ordered results and foreach snapshots
retain their existing contracts. Both new suites verify IR/objects on all nine
profiles. [GrowableArrayRows.co](../../../../examples/GrowableArrayRows.co) also
executes through the CLI at both Windows widths, as does the record's minimal
code sample. Full offline regression passes 1,802 Compiler tests across 163 suites
and 57 Shuttle tests across seven suites, with zero failures/errors/skips (9m 10s).
All 13 changed skills pass validation; publication checks cover 28 documents,
1,140 relative links, 139 anchors and 11 source/test/example files. The Stage 6
record owns archived XML, copy-verified hashes and commands. Stages 7-8 remain
open, including generic-type support before public `List<T>` delivery.

### Growable arrays Stage 5 ordered edits and removed values (2026-11-08)

The [Stage 5 record](../../cloth-arrays/references/growable-arrays-stage-5.md)
owns executable one-dimensional insert/removeAt/clear across stored and temporary
receivers. Ordered shifts move live inline bytes and reparent buffered slot heads.
Wanted removed values receive independent storage before slot release; direct
discarded builtins omit unused snapshots. Clear releases live payloads and keeps
capacity. Destroyed child views of every rank/fixed extent are invalidated before
later current-bound checks. Wide result/index scratch restores locally without
admitting mutators into enclosing arena-suffix cleanup proofs.

All 183 focused tests across nine suites pass, including 32 new tests. A 1,000-cycle
append/insert/discarded-remove/clear probe returns to reserved live bytes after
every iteration and uses 6,002 total allocations. Reserved front insertion/removal
shifts exactly 999,000 string descriptors without copying old payloads. Poisoned
probes cover removed structs/witnesses, nested owners, nullable/wide values,
class identity, invalidated children and allocation failures. Both Windows widths
execute the fixtures; both new suites verify IR and objects on all nine profiles.
The maintained [GrowableArrayEdits.co](../../../../examples/GrowableArrayEdits.co)
uses the complete one-dimensional surface through ordinary private-array methods.
Full offline regression passes 1,776 Compiler tests across 161 suites and 57
Shuttle tests across seven suites, with zero failures/errors/skips (8m 46s).
The example also executes through the CLI at both Windows widths. All 12 changed
skills pass the full validator; 25 documents and 12 source/test/example files pass
publication checks. The Stage 5 record owns archived XML, hashes, commands and
the retained conservative snapshot/cleanup boundaries. Stages 6-8 remain open.

### Growable arrays Stage 4 field growth and scoped selections (2026-11-08)

The [Stage 4 record](../../cloth-arrays/references/growable-arrays-stage-4.md)
owns executable one-dimensional append/reserve on class/aggregate fields, nested
array-element fields and `self`. Direct publication uses the array's existing
owner, avoiding per-append root repacking. Scoped owner observations preserve
selected reads/writes/ordinary struct receivers across growth in separately
emitted methods. Owner cleanup invalidates back-links before capacity release;
normal scope and frame cleanup unregister observations. The private callable ABI
and independent copy/export rules remain unchanged.

All 86 focused tests across seven suites pass, including 25 new Stage 4 tests.
Scalar class wrappers reach 4,096 elements with 13 capacity growths and 16,380
moved bytes. Reserved string wrappers retain only live payloads after each call
and make two received-payload copies per appended string. Native Windows x64/x86
probes cover poisoned growth, owner replacement/deletion and abrupt cleanup;
both new suites verify IR/objects on all nine profiles. The maintained
[GrowableArrayFields.co](../../../../examples/GrowableArrayFields.co) proves shared
class aliases, private backing storage and independent exports surviving deletion.
Full offline regression passes 1,744 Compiler tests across 159 suites and 57
Shuttle tests across seven suites, with zero failures/errors/skips (8m 36s).
The corrected cleanup/layout selection passes 103 tests across four suites;
exact byte/peak expectations include the fourth owner-control word. The example
also executes through the CLI on Windows x64/x86. All 12 changed skills pass the
full validator; 24 documents and 14 source/test/example files pass publication
checks. The Stage 4 record owns archived XML, hashes, commands and remaining
conservative lifetime boundaries. Stages 5-8 remained open at that milestone;
the Stage 5 entry above owns current one-dimensional edits.

### Growable arrays Stage 3 source append and reserve (2026-11-08)

The [Stage 3 record](../../cloth-arrays/references/growable-arrays-stage-3.md)
owns one-dimensional local/parameter/temporary append/reserve, exact received
elements, protected indirect operands and scoped cleanup. Selected indexed places
and ordinary struct receivers follow capacity movement while retaining original
bounds and stopping at whole-binding replacement. Other operations, direct field
receivers and rectangular mutation retain C4032; callable ABI and class deletion
rules are unchanged.

All 25 new source fixtures pass focused verification, including native Windows
x64/x86 allocation, movement, poisoned-free and failure probes, plus all-nine-profile
IR/objects. Scalar growth reaches 4,096 values with one initial owner plus 13 growth
allocations and 16,380 moved bytes. String producers, self-element copies, full-cap
integer arguments and repeated temporary calls have bounded measured ownership.
Full offline regression passes 1,719 Compiler tests across 157 suites and 57
Shuttle tests across seven suites, with zero failures/errors/skips (8m 31s).
The documented example executes at both Windows widths. All 12 changed skills
pass the full validator; 22 documents and 14 source/test/example files pass
publication checks. The Stage 3 record owns archived XML, hashes, commands and
the retained conservative cleanup boundaries. Stages 4-8 remained open at that
milestone; the Stage 4 entry above owns current field support.

### Initial interface scope confirmed (2026-11-08; design only)

The user confirmed non-generic interfaces first, with `IHealth<T>` following
general generic types and constraints. The
[interface scope contract](../../cloth-interfaces/references/design.md#initial-implementation-scope-confirmed-2026-11-08)
and [plan](../../../../todo/interfaces-plan.md) retain concrete first-release
fixtures and the intended generic Test.co design. Class implementers remain
first; struct/enum implementers remain later work. No compiler implementation
or new execution evidence was added by this decision.

The user also confirmed that interface calls use labels and omitted-argument
defaults from the static interface declaration; runtime dispatch selects only the
body. The [call-contract record](../../cloth-interfaces/references/design.md#static-interface-call-contracts-confirmed-2026-11-08)
keeps ordinary evaluation/snapshot rules and prevents a second default evaluation
by the implementation. Optional-argument guidance now links to that accepted
design. Signature/optionality compatibility and class-view contracts remain open.

The user also confirmed that a newly declared matching method without
`override:` in an implementing class is an error even when the interface
provides a default; the compiler must not silently use that fallback. The
previously accepted inherited-public-method reuse remains valid without a
forwarding declaration. These are design records, with no new compiler tests.

### Interface implementation planning (2026-11-07; no compiler change)

The user selected class implementers first, with struct/enum implementers later.
The user subsequently confirmed signature-only required methods, explicit
`= default (...)` expression fallbacks and `= default { ... }` block fallbacks. The
[confirmed method contract](../../cloth-interfaces/references/design.md#required-methods-and-explicit-defaults-confirmed-2026-11-07)
records those choices; the other Stage 0 decisions remain open. This changes
design records, with no compiler implementation. The user also confirmed one
class base plus multiple implemented interfaces in a comma-separated `:` header,
and multiple parent interfaces in the same colon/comma syntax.
The user confirmed `override: pub mt<...> ...` or `override: mt<...> ...`, required
on every newly declared class implementation of an interface method. Test.co now
uses that prefix in place of `@Override`. The user also confirmed reuse of a
matching public inherited method, even when the base does not name the interface,
without a forwarding declaration. The
[prefix record](../../cloth-interfaces/references/design.md#override-declaration-prefix-confirmed-2026-11-07)
retains private inheritance, subclass replacement and detailed matching rules as
remaining decisions.
The user confirmed default precedence: eligible class implementations win, then
the most-specific interface default; unrelated competing defaults require an
explicit class `override:` implementation. Shared-ancestor defaults retain their
declaration identity through diamonds. Re-abstraction and the other remaining
Stage 0 details remain open.
The [interface skill](../../cloth-interfaces/SKILL.md) and
[design record](../../cloth-interfaces/references/design.md) retain established
Test.co syntax and visibility separately from recommended/open contracts.
The [plan](../../../../todo/interfaces-plan.md) stages the contract, syntax and
nominal identities, conformance, typed calls, checked dispatch, default bodies,
cross-feature integration and publication. No interface stage is complete.

The focused foundation run passed **106 tests across six suites**, zero
failures/errors/skips, in 40 seconds. Existing class fixtures executed on Windows
x64/x86 and verified IR/objects for all nine profiles; this is not interface
execution evidence or new foreign-native coverage. The plan retains the exact
command; XML is preserved under `build/interfaces-planning-baseline/test-results/`.

### Growable arrays Stage 2 owned capacity and typed relocation (2026-11-07)

The [Stage 2 record](../../cloth-arrays/references/growable-arrays-stage-2.md)
owns array-only capacity/owner layout, registered resource cleanup, typed live-slot
copy/relocation/release and nullable struct-field witnesses. Fixed/growable arrays
share the private layout; strings retain their two-field descriptor. Ordinary
copies remain independent, moved slot arenas reparent their resource owners,
and superseded capacity frees immediately. Existing unproven indexed writes
retain superseded payloads in their enclosing owner. Source mutators retain C4032.

Verification passes, including Windows x64/x86 native private probes and
all-nine-profile IR/object checks. The scalar probe grows to
4,096 live values with 13 growth allocations and moves 4,095 prior elements.
Buffered reuse, poisoned frees, allocation failure, child-owner movement and
independent unsized struct-field witnesses have direct fixtures. The 15 new tests
pass in the full offline Compiler/Shuttle run: **1,694 Compiler tests across 155
suites and 57 Shuttle tests across seven suites**, zero failures/errors/skips,
in 8m 24s. Final XML is preserved under `build/growable-arrays-stage2/`. All nine
changed skills pass the full validator; relative links, fences and source/test
whitespace checks pass. Temporary validation dependencies are removed. Only
Stages 0-2 are complete; source mutators and generic List remain later work.

### Growable arrays Stage 1 typing, receiver plans and effects (2026-11-07)

The [Stage 1 record](../../cloth-arrays/references/growable-arrays-stage-1.md)
owns growable/fixed type inference, separate current-count/retained-witness
facts, the five typed mutators, stored/temporary receiver plans, readonly and
nullable checks, class alias/lifetime effects and metadata identity. Native
whole-array replacement can change a growable outer count while retaining
explicit extents and rectangular inner shape. Mutators and unsized struct array
field storage have an explicit C4032 boundary; capacity and in-place growth are
not implemented. Source-method name collisions retain ordinary presence behavior.

The final full offline Compiler/Shuttle run passes in 7m 44s: 1,679 Compiler tests
across 153 suites and 57 Shuttle tests across seven suites, zero failures/errors/
skips. The 27 new tests include Windows x64/x86 native reception and all-nine-
profile IR/objects. No new native Linux/macOS/AArch64/musl evidence is claimed.
XML is preserved under `build/growable-arrays-stage1/`. The
[plan](../../../../todo/growable-arrays-and-lists-plan.md) marks only Stages 0-1
complete; generic List remains a separate dependent feature.

### Growable arrays Stage 0 contract and baseline (2026-11-07)

The user confirmed explicit growable `T[]`, fixed `T[N]`, sized literal inference,
binding-only immutability for length changes, retained rectangular inner shape,
append/insert/removeAt/clear/reserve and ordinary stored/temporary receiver rules.
The [array-owned contract](../../cloth-arrays/references/growable-arrays.md)
identifies the historical fixed-initial-outer-shape rule it supersedes as intended
design. No compiler behavior changes in Stage 0; growth and generic List remain
future implementation work in the
[plan](../../../../todo/growable-arrays-and-lists-plan.md).

The [Stage 0 record](../../cloth-arrays/references/growable-arrays-stage-0.md)
publishes the exact offline command and 322 passing tests across 24 suites, zero
failures/errors/skips, in 1m 46s. Existing fixtures execute on Windows x64/x86 and
verify IR/objects across all nine profiles. WSL Ubuntu is installed; this baseline
does not claim native Linux/macOS/AArch64/musl execution. Baseline XML is preserved
under `build/growable-arrays-stage0/test-results/`. Only Stage 0 is complete.

### Bundled standard library Stage 5 publication and runtime independence (2026-11-07)

The [maintained example](../../../../examples/StandardLibrary/README.md) supplies
a stand-in public greeting function with a transitive import and private owned
string storage. Both packaged commands compile it from external cwd without a
library manifest entry. Four Windows x64/x86 executables then run after their
disposable compiler and source trees are deleted, with Java/compiler environment
removed. An invalid unreachable source remains unloaded. Both commands emit
objects for all nine target profiles; the compiler publication suite verifies
the exact three-file graph and all-profile executable IR/startup objects.
GNU Linux x64 links with Ubuntu WSL cc and runs from `/` with exact output and PEC 0.

The [Stage 5 record](../../cloth-toolchains/references/standard-library-stage-5.md)
owns source/test routing, observed verification and target limits. The
[toolchain guide](../../../../docs/standard-library.md) publishes release layout,
selection, provenance and preparation. The installed-Shuttle library self-build
with an explicit override also succeeds, emitting the 306-byte scaffold object.
Example files are tracked as root/Compiler test inputs, and Git ignore exceptions
retain both maintained READMEs. The full offline regression/distribution command
passes in 9m 30s: 1,652 Compiler tests across 151 suites, 57 Shuttle tests, nine
packaged tests, 23 syntax, 54 resolution, 33 native-disabled semantic and 25
configuration checks, with zero failures/errors/skips. Rebuilt ZIP/TAR archives
and both per-command installations match the tested JARs and all library/provenance
inputs by SHA-256. Publication checks cover 24 documents, 876 links, 92 anchors and
38 source/configuration files; all five changed skills pass the full validator.

All six source-bundling stages are complete. The shipped library remains the
published/pinned 0.0.1 scaffold at ea48ad5; no public collection API or new runtime
implementation is introduced. Full Unicode Windows installation paths, wider
native services, generic collections and binary/package infrastructure remain
separate future work. Unrelated Bootstrap/emitter/string changes are preserved.

### Bundled standard library Stage 4 allocation service boundary (2026-11-07)

The user selected an owning collection as the first runtime-service consumer.
Its [contract and evidence](../../cloth-toolchains/references/standard-library-stage-4.md)
specify existing class `::new`/`::delete`, private target-sized C allocation calls,
recursive field ownership, explicit child destruction and fail-fast behavior.
The maintained OwnedTextChain source fixture keeps collection logic in Cloth,
without a new public collection/raw-memory API or production runtime change.
The contract identifies later buffer, filesystem, clock and threading prerequisites
and keeps source-library selection separate from C runtime linkage.

Six StandardLibraryRuntimeTest cases pass offline in 37s with no failures/errors/
skips. Windows x64/x86 run the source graph with selected and regular roots,
poison freed node/field/frame payloads, balance malloc/free, observe four table
growth allocations followed by reuse, inject fatal allocation/reallocation failure
and verify static/runtime alias errors. All nine profiles' executable IR and
startup objects pass. GNU Linux x64 links with cc under Ubuntu WSL and executes
with exit 0 and PEC 0. Native ARM64/macOS/musl execution remains untested.
The maintained fixture also passes through the existing installed compiler with
an explicit library override. Publication checks pass for 21 documents, 834 links,
92 anchors and 29 source/configuration files; all three changed skills pass the
full validator. Unrelated workspace work is preserved.
The standard-library scaffold/pin and existing release archives are unchanged.
Stage 5 remains pending.

### Bundled standard library Stage 3 selection controls (2026-11-07)

Both commands now share `--stdlib PATH`/`--stdlib=PATH` and `--no-stdlib`.
Overrides resolve from invocation cwd and accept structurally valid development
trees without release/Git/version checks. Both explicit modes bypass default
context and descriptor lookup, and invalid overrides never fall back. Disabled
selection keeps configured roots regular, permits cloth replacements with ordinary
ambiguity, and preserves builtins. Conflicts use C0003 before source/project work;
missing/invalid paths retain C0002/C0012, and selection/input protection retain
C0018/C0020. Help lists the flags without requiring a valid bundle.

The [Stage 3 record](../../cloth-toolchains/references/standard-library-stage-3.md)
owns source/test routing, snapshot identification, verification and limitations.
Full offline checks pass: 1,644 Compiler tests across 149 suites, 57 Shuttle tests,
seven packaged-command tests, 23 syntax, 54 resolution, 33 native-disabled semantic
and 25 configuration checks, with zero failures/errors/skips. Both Windows x64
launchers execute programs with relative/spaced/Chinese/supplementary-Unicode
override paths, bypass broken defaults, preserve replacements/builtins and forward
literal library flags after `run --`. Distribution/publication evidence is
recorded there. The command passes in 8m 55s and rebuilds combined ZIP/TAR archives
and both per-command installations; their application JARs match the tested bundle
by SHA-256. Publication checks cover 17 documents, 750 links, 83 anchors and 24
source/configuration files; all five owning skills pass the full validator.
Build outputs are isolated from concurrent workspace work.
The standard-library source/pin is unchanged; runtime-service and final publication
work remain in Stages 4-5.

### Bundled standard library Stage 2 automatic source availability (2026-11-07)

Both command adapters now resolve the explicit installed/development context,
validate the default structure/provenance, and attach its source root before
compilation. SourceModule gains a regular/default versus standard-library role;
canonicalization retains it, matching configured roots are promoted once, and
regular candidates for reached cloth paths produce C3106. Plain compiler API
requests still discover no installation and retain ordinary behavior without a
marked root. C0018/C0019/C0020 cover selection, ID conflicts and selected-input
output aliases. Override/disable options remain the next stage.

The [Stage 2 record](../../cloth-toolchains/references/standard-library-stage-2.md)
owns implementation and evidence. Full offline checks and rebuilt distributions
pass in 9m 2s: 1,629 Compiler tests in 147 suites, 56 Shuttle tests, four distribution
tests, 23 syntax, 46 resolution, 33 native-disabled semantic and 25 backend-free
configuration checks; no failures/errors/skips. Native Windows x64 programs call
transitive/circular library fixtures through both real packaged commands without
manifest entries. Unreachable malformed files are ignored and declarations remain
explicit imports. Public API requests, physical aliases, role uniqueness, early
missing-default failures, metadata protection and namespace snapshots are verified.
The standard-library repository's source/pin is unchanged by this stage. Full
Windows Unicode installation paths and native-service/library API work remain pending.

Concurrent emitter/string-storage edits were preserved. The full-suite counts
describe the earlier compiled snapshot; after those unrelated changes were
rebuilt, focused resolution/Shuttle/packaged verification passed again in 1m 26s.
Archives are repacked from the tested JARs without recompiling changing sources.
Publication checks pass for 16 documents, 721 local links, 83 anchors, 17 source/
configuration files and five full skill validations. Only Stages 0-2 are complete.

### Bundled standard library Stage 1 packaging (2026-11-07)

Root Gradle now builds a combined compiler/Shuttle distribution and includes the
pinned standard-library sources/provenance in existing per-command distributions.
The build-only Shuttle exporter reuses ManifestLoader, validates clean Git state
and the compiler index pin, and archives that revision with canonical line endings.
The scaffold is committed/published as `ea48ad5` and the index pin matches it.
Launchers pass relocated installation context; development JavaExec tasks pass
the source-checkout context. Automatic source attachment and options remain pending.

The [Stage 1 record](../../cloth-toolchains/references/standard-library-stage-1.md)
owns commands, source routing and scope. Offline verification passes in 1m 35s:
two root distribution tests, all 56 Shuttle tests and 25 backend-free configuration
tests, with no failures/errors/skips. Both archives have the 50 expected entries;
both per-command installs and development object builds pass. Windows x64 tests
execute relocated launchers from a path with spaces and `é`. Unix syntax and
argument handoff pass in Ubuntu WSL with a stub, without a Linux JVM execution claim.

An initial Git archive regression converted source line endings to CRLF; disabling
core.autocrlf for export fixes the pinned-byte assertion. The Windows JDK also
reproduces character loss for Chinese JVM property/classpath arguments. The user
chose Gradle launchers with that documented installation-path limit and deferred
full Unicode launcher support. No language syntax, native ABI or library API changed.

Publication checks pass for 13 documents, 638 local links, 77 anchors, fences,
whitespace and Stage 0-1 checklist scope. Four skills pass the full bundled
validator with temporary validation dependencies. The final two distribution tests
pass again in 7s after making their Unicode fixture respect native encoding.
Generated toolchain artifacts and reports remain; temporary probes are removed.

### Bundled standard library Stage 0 contract (2026-11-07)

The user authorized the first stage of the bundled-library TODO and resolved
three policy questions through popup modals: reserve `cloth.*` while enabled and
allow replacement roots with `--no-stdlib`; accept development overrides without
an exact version match; fail early for a missing/incomplete selected default even
when no library file is imported.

The [toolchain contract](../../cloth-toolchains/references/standard-library.md)
records shared installed/development contexts, command options, structural
validation, an explicit future source-module role, identity/promotion rules,
namespace collisions, metadata protection and deterministic failure order.
The [TODO](../../../../todo/standard-library-toolchain-plan.md) marks only Stage 0
complete. Compiler/Shuttle production sources and the standard-library scaffold
are unchanged by this stage; bundling and runtime enforcement remain future work.
Source/API ownership was checked against the actual command, request, project
model and graph-loading implementations. Verification covers documentation and
skill publication, with no new runtime or target-support claim.

Publication checks pass for 12 documents, four simple skill metadata blocks,
623 local links, 76 heading anchors, code fences, whitespace and Stage 0-only
checklist completion. The official skill validator could not start because
PyYAML is missing; the focused contract records that limitation and the fallback
checks. No production-code tests or target executions were added or rerun.

### Bootstrap string-input lexer port (2026-11-07)

The user set Bootstrap's immediate boundary at a faithful Kotlin lexer port
using a string, before file I/O or parsing. The scanner now produces original
lexemes and UTF-16-compatible spans for keywords/operators, comments, numeric
forms, quoted/raw strings, characters and recursive interpolation. It returns
an owning concrete TokenList or one retained failure, without partial output.
The list owns tokens/spans; cursors and optional source identities are borrowed.
Main preserves the dogfooding banner and accepts text as its first argument.

No compiler language feature or platform adapter was added. Dogfooding exposed
growable/generic collection, file/diagnostic I/O, string builder and ownership API
boundaries. Naive growing JSON concatenation reproduced a native stack overflow;
bounded recursive formatting fixes the same 3,072-scalar regression using current
function lifetimes. Binary expression line continuation remains a parser limit.

The [Bootstrap lexer skill](../../cloth-bootstrap-lexer/SKILL.md) owns current
guidance and the [port record](../../cloth-bootstrap-lexer/references/port.md)
owns the supported slice, exact ownership, Unicode compatibility boundaries and
verification. Differential coverage passes 124 inputs on Windows x64/x86,
including every Bootstrap frontend source file, first errors, 2,048 list nodes,
long lexemes and 24 nested interpolations. Four native Windows/Linux comparison
cases also agree, including failure status and long-lexeme output. The complete
graph still verifies executable IR/startup objects for all nine existing targets.
Compiler test inputs now track Bootstrap source changes for correct reruns.
Final focused verification passes in 49 seconds: four Compiler integration
tests, 51 Shuttle tests and 25 backend-free configuration tests, all without
failures, errors or skips. The preceding full Stage 7 Compiler check remains
the latest full compiler-suite run; production compiler code is unchanged here.

### Shuttle and imports Stage 7 nested projects and Bootstrap (2026-11-07)

The maintained [Shuttle example](../../../../examples/Shuttle/README.md) uses a
nested entry file, selective/wildcard imports, private defining-file helpers and
a circular even/odd pair across module roots. New backend-free regressions verify
deep imports and nested configured entries without flattening folders or adding
subfolder manifests. C3097 now guides project users to Shuttle or explicit API
roots. The standalone developer default source is a runnable single-file example.

Bootstrap's nested lexer/token paths already resolved. Source fixes change its
class default to `?:`, supply source/SourceFile.co and explicitly delete the lexer
before returning its result. The user's clarification confirmed the intended
enum/string operand context. Its existing syntax was correct; equality was
excluded from integration and its full-comparison signature check assumed an
explicit parameter. The shared path now supports mixed present-value ==/!=
contributions while preserving ordinary nominal/class comparisons, null handling,
readonly defaults and alias invalidation. No new native platform adapter is added.
[Equality context semantics and evidence](equality-operand-contexts.md)

The focused integration run passes in 54s. Both maintained projects execute on
Windows x64/x86 and as GNU Linux x64 executables linked under Ubuntu WSL, with
exact output and one PEC: 0 each. Verified executable IR/startup objects cover all
nine existing profiles; unavailable native hosts remain distinct from that
emission evidence. The packaged Shuttle launcher also runs Bootstrap/the example
and emits Linux ARM64 object output with a nonexistent linker override.
[Stage 7 record](../../cloth-imports/references/stage-7.md) owns source routing,
reproduction and the final full-suite/publication results.

An incremental full run lost compiled semantic classes and was interrupted.
Final validation uses a fresh isolated build directory/project cache with Kotlin
incremental compilation disabled and in-process compilation. The full check and
installDist pass in 8m 17s: 1,610 Compiler tests across 144 suites, 51 Shuttle
tests across six suites, 23 syntax, 28 resolution, 33 native-disabled semantic
and 25 backend-free configuration tests. All have zero failures, errors or skips.
The README's Gradle example and Bootstrap commands also run successfully.
A concurrent Bootstrap dogfooding banner is preserved; six publication cases
pass again in 24s with smoke checks that permit its evolving diagnostic output,
and the current Linux startup object runs successfully after relinking under WSL.
All five changed skills pass metadata validation. Publication checks pass for
19 documents, 786 local links, 98 anchors, three TOML examples, three manifests
and 26 source/configuration files. Root/submodule whitespace checks pass.
Temporary helpers, exported artifacts, dependencies and isolated outputs
are removed after verification. The plan marks Stages 0-7 complete.

### Shuttle and imports Stage 6 whole-program build/run (2026-11-07)

The compiler now emits the complete checked source graph through one LLVM module,
selecting only the primary file's own main for startup. Other files' mains remain
ordinary callables; object mode needs no main or linker. Original symbols, private
struct/class/enum identities, defaults and buffered results cross files through
the shared callable ABI. Runtime typeinfo interning now distinguishes nominal
declarations and recursive wrappers while preserving unqualified display text.
C3105 is retired. [Compiler Stage 6 evidence](../../cloth-imports/references/stage-6.md)
owns implementation and target scope.

Shuttle now packages its real application entry point and executes project build/
run through the plain compiler request. Shared compiler-option parsing, absolute
path adaptation and launch transport preserve ordered flags/arguments, inherited
I/O/cwd, actual artifact paths, child status and one PEC. Failed builds stop before
launch, and manifest/source output aliases stay protected. Direct Windows Java
main arguments recover original UTF-16 from the OS command line because this
JDK's native code page replaces some Unicode before main. In-process arrays remain
authoritative and JVM argument-file expansion stays with Java.
[Shuttle Stage 6 evidence](../../shuttle-build/references/stage-6.md) records commands,
diagnostic codes and the native argument adapter.

The full isolated check/installDist run passes in 7m 34s: 1,600 Compiler tests
across 142 suites; 47 Shuttle tests across five suites; 23 syntax, 27 resolution,
33 native-disabled semantic and 24 backend-free configuration tests. Zero failures,
errors or skips. Seven new compiler integration tests cover circular native
execution, primary entry isolation, private emission/metadata, buffered/default
contexts and objects across all nine profiles. Nineteen Shuttle tests add actual
build/run, failure, process transport and shared standalone compiler-main evidence.
Native imported execution here is Windows x64; broader imported-program portability,
maintained examples and publication remain Stage 7. The packaged shuttle.bat also
passes Unicode-path circular build/run (statuses 0/7, one PEC) and Linux ARM64 object
emission with a nonexistent linker (status 0). Other workspace work is preserved.

All seven changed skills pass validation. Publication checks pass for 16 documents,
710 local links, 94 anchors, two TOML examples, fences and whitespace. All 24 touched
source/build files and root/submodule diff whitespace checks pass. Temporary
helpers, dependencies, fixtures and isolated outputs are removed after verification.
The plan marks Stages 0-6 complete.

### Shuttle and imports Stage 5 per-file semantics (2026-11-07)

The compiler now predeclares nominal/function identities across source cycles,
binds each file's own public exports, and checks signatures, defaults and bodies
through original defining-file contexts. Same-spelled private nominal types stay
distinct; method signature keys retain nominal identity. Struct identities precede
field resolution. Global class/struct/enum catalogs use identity and share one
Object root, with primary-file name views retained for single-file compatibility.

[Stage 5 evidence](../../cloth-imports/references/stage-5.md) owns sources, behavior
and verification. C3102-C3104 locate missing/private/conflicting imports; C3101 is
retired. The driver binds before target selection, checks the whole graph with
the actual layout, then reports C3105 until Stage 6 emission. Existing type/body
diagnostics propagate unchanged. Direct single-unit adapters retain C3096.

Thirty-three source-tree cases cover privacy, exports, overloads, original default
contexts, nominal isolation, class/struct/enum integration, providers, cross-module
cycles, invalid layout/inheritance cycles and shared Object identity. The isolated
semantic task retains the existing builtin JVM API dependencies but excludes
platform binaries and disables native loading. Parser/discovery tasks still
exclude every LLVM/JavaCPP jar. Three public-facade cases check the semantic and
emission boundaries.

The final full isolated check passes in 6m 59s: 1,593 Compiler tests across 141
suites, 23 isolated syntax tests, 27 isolated graph tests and 33 isolated semantic
tests. Shuttle's 28 normal and 24 isolated configuration tests also pass. Every
task has zero failures, errors or skips. Existing native/foreign-object regressions
remain covered; imported-program emission/execution and graph entry integration
remain Stage 6. Other concurrent workspace changes are preserved.
All eight changed skills pass validation. Publication checks pass for 15
documents, 710 local links, 97 anchors, two TOML examples, fences and whitespace,
plus 26 touched Compiler source/build files. The plan marks Stages 0-5 complete.

### Full Unicode string casing (2026-11-07)

String `toUpperCase`/`toLowerCase` now return independent strings using Unicode
17 default full mappings and original-text final-sigma context. Existing char
casing and all seven original Unicode artifacts remain unchanged. The existing
`UnicodeGenerator.d` pipeline adds authenticated SpecialCasing input and three
new artifacts; all ten reproduce byte for byte with DMD 2.113.0.

The [string casing record](../../cloth-strings/references/string-case.md) owns
semantics, source routing and evidence. The focused run passes 218 tests across
13 suites in 58 seconds, with zero failures, errors or skips. Exhaustive mapping
and context predicates execute on Windows x64/x86; source conversion, poisoned
lifetimes and all-nine-profile IR/object checks pass. The final full run passes
1,557 tests across 139 suites in 6m 55s, with zero failures, errors or skips.
It isolates both compiler outputs and Gradle project-cache/execution history from
concurrent builds; the focused record explains the initial missing-class run.

### Shuttle and imports Stage 4 source graphs (2026-11-07)

The compiler now discovers exact-case source paths across supplied module roots,
checks ambiguity before selectors/cache lookup, and retains separate source units
and located import edges. Canonical paths plus filesystem/file-key identity
deduplicate physical aliases. Explicit DFS frames and loading/loaded states
preserve self/mutual/longer cycles without recursion or topological ordering.
Reads use strict UTF-8 and keep original snapshots. Request validation remains
shared and precedes all source work; no TOML discovery enters Compiler.

[Stage 4 evidence](../../cloth-imports/references/stage-4.md) owns sources,
diagnostics and scope. C3097-C3100 locate discovery failures; C0010 retains
source read causes. Completed graphs protect every loaded source from output
aliases and then stop with C3101 while binding is pending. Direct single-unit
analysis retains C3096. No-import ABI/body/output behavior remains covered.

The final 44-second run passes 117 Compiler tests across nine suites, 27 isolated
graph tests and 23 isolated syntax/boundary tests. Shuttle's 28 normal and 24
isolated configuration tests also pass. All have zero failures, errors or skips.
Coverage includes a 768-file cycle, cross-module/diamond/repeated imports, exact
case, ambiguity, original snapshots, hard links, NTFS directory junctions and a
null-file-key provider. Host policy restricts symbolic links; the junction
fallback keeps that directory-alias fixture active. Existing no-import native
Windows x64 and all-nine-target object regressions pass; imported-program
binding/emission and Linux/macOS host filesystem checks remain future work.
All five changed skills pass the bundled validator. Publication checks pass for
12 documents, 605 local links, 82 anchors, two TOML examples, fences and whitespace,
plus 10 Compiler source/build files. The plan marks Stages 0-4 complete.

### Shuttle and imports Stage 3 syntax (2026-11-07)

Keywords.USING and located import/path/selection nodes now support the confirmed
selective/wildcard file header. SourceUnit preserves ordered imports, declarations,
EOF and original source identity; optional semicolons remain separate from node
spans. Continuations occur only after colon/comma, with physical line checks
preserving boundaries through multiline block comments. Malformed, misplaced,
legacy and repeated selectors fail first with original spans; C2010 labels the
first/repeated selector as one error.

The [Stage 3 record](../../cloth-imports/references/stage-3.md) owns source routing,
codes, commands and observed scope. Imports are parsed but not resolved/bound.
C3096 prevents the driver and single-unit semantic adapters from silently
ignoring imports. The driver checks before ABI/body/output work; CLI build/run
report one failure, preserve stale output and never launch after rejection.

The final 15-second run passes 90 tests across eight Compiler suites, plus 23
import tests with native backend jars removed and class-resource absence checked.
Shuttle's 28 normal tests and 24 isolated configuration tests also pass through
the Git submodule layout. All tasks have zero failures, errors or skips. Existing
request regressions retain Windows x64 native execution and all-nine-profile
object coverage for no-import sources. This does not establish imported-program
execution, foreign host execution or circular binding. Stage 4 source discovery
is next; the full regression remains a later integration check.
All six changed skills pass the bundled validator. Publication checks pass for
13 documents, 610 local links, 88 anchors, two TOML examples, fences and whitespace,
along with 13 Compiler source/build files. The plan marks Stages 0-3 complete.

### Shuttle and imports Stage 2 manifests (2026-11-07)

Shuttle now owns production Compiler/Jackson TOML dependencies, strict UTF-8
manifest parsing, schema validation, project discovery, canonical module roots,
exact-case entry files and an immutable resolved project model. It prepares
plain compiler requests without parsing Cloth, selecting an ABI, discovering a
linker or writing output. Stable S0001-S0013 failures retain original manifest
snapshots, parser locations where available and honest schema key/file context.
Bootstrap's three module manifests use canonical lowercase tables.

The [Stage 2 record](../../shuttle-build/references/stage-2.md) routes sources and
tests. All 28 Shuttle tests pass across three suites; the separate configuration
task passes 24 manifest/model tests with LLVM/JavaCPP jars removed from the test
runtime. Both runs have zero failures, errors or skips. A real loaded-TOML fixture
emits an AArch64 Linux ELF object without main or a linker and preserves the
original compiler diagnostic on a later source error. This verifies foreign
object emission on Windows x64, not AArch64 execution or Linux/macOS hosts.

The [Shuttle skill](../../shuttle-build/SKILL.md), shared routing and
[plan](../../../../todo/shuttle-and-imports-plan.md) reflect Stages 0-2 complete.
Compiler production code is unchanged in this stage. Source imports follow in
Stages 3-6. Application packaging stays with the actual Stage 6 build/run CLI;
there is no Shuttle launcher yet. Loading Bootstrap is verified, compiling its
entire source scaffold is not.
All four changed skills pass the bundled validator. Publication checks pass for
10 documents, 538 local links, 76 anchors, two TOML examples, fences and whitespace.
The final Shuttle build, including both test tasks, passes in 10 seconds; compile
dependency reports confirm TOML stays inside Shuttle and native backend libraries
remain absent from its compile classpath.

### Shuttle and imports Stage 1 compiler API (2026-11-07)

The [public compiler facade](../../../../Compiler/src/main/kotlin/cloth/api/ClothCompiler.kt)
now accepts immutable CompilationRequest/SourceModule inputs and returns the
actual CompilationResult artifact for one source file. The existing CLI/test
driver adapts its settings and paths to this same boundary. Request shape/root
validation uses C0017, while source, syntax, target, body, output-alias and I/O
diagnostics retain their original identities and fail-first ordering.

[Stage 1 evidence](../../cloth-imports/references/stage-1.md) records 71 passing
tests across seven suites in 17 seconds on the final run, with zero failures/errors/skips.
The 13 new request tests cover snapshots, validation, silent diagnostics,
failure priority, I/O and overwrite protection, Windows x64 native execution and
architecture-checked objects for all nine targets. Three Shuttle consumer tests
prove public compilation and diagnostic rendering across the Gradle boundary.
The consumer compile classpath contains diagnostic API libraries and no
LLVM/JavaCPP or TOML. Native API execution on other targets was not newly tested.

The [imports skill](../../cloth-imports/SKILL.md),
[Shuttle skill](../../shuttle-build/SKILL.md) and
[plan](../../../../todo/shuttle-and-imports-plan.md) describe the supported slice.
Shuttle's Compiler dependency remains test-scoped; manifest/project loading and
source import parsing/graphs are later stages.
All five changed skills pass the bundled validator. Publication checks pass
for 12 documents, 380 file links, 53 anchors, two TOML examples, fences and
whitespace. Stages 0-1 are complete; Stage 2 is next.

### Shuttle and imports Stage 0 publication (2026-11-07)

Stage 0 publishes focused [cloth-imports](../../cloth-imports/SKILL.md) and
[shuttle-build](../../shuttle-build/SKILL.md) guidance, moving import authority
out of this shared index. The contracts select the initial import header,
collision/overload, file identity, manifest discovery/schema and primary-file
entry rules, preserving the user's circular-import decision. The
[public API contract](../../cloth-imports/references/compiler-api.md) keeps
Shuttle's TOML/model ownership separate from compiler source graphs and emission.

The selected Jackson TOML 2.21.1 dependency passes 23 focused checks, including
strict duplicates, original syntax locations and a gated Reader proving first
failure abort without later-statement reads. Successful-tree field locations
are unavailable; schema diagnostics will carry key paths and honest file-level
labels. The [evidence](../../shuttle-build/references/stage-0.md) and
[plan](../../../../todo/shuttle-and-imports-plan.md) record verification and scope.
No compiler facade/import implementation, manifest loader, Shuttle command or
product Gradle dependency is added by this stage.
All six new/changed skills pass the bundled validator. Publication checks pass
for 14 documents, 366 file links, 57 anchors, two TOML examples, code fences and
whitespace. Stage 0 is complete; Stages 1-7 remain pending.

### Shuttle and compiler import planning (2026-11-07)

The user selected TOML for project/module manifests and identified Shuttle as
Cloth's build system, with an existing `Shuttle` Gradle module. The
[implementation plan](../../../../todo/shuttle-and-imports-plan.md) assigns
manifest loading and build orchestration to Shuttle, and source import parsing,
resolution and binding to Compiler through a manifest-independent request API.
Circular source imports were confirmed allowed, including cycles across
configured modules. The plan requires single-load graph discovery, declaration
preregistration and semantic/runtime cycle coverage; ordinary invalid type-layout
and inheritance cycles remain errors.
It stages configuration, compiler API, located imports, source graphs, file
scopes, whole-program execution and publication. Only planning and documentation
are integrated; no Shuttle command, manifest loader or compiler imports are
implemented. Verification for this task covers documentation and source links.

### String delimiter split overload (2026-11-06)

The user requested `split(string) -> string[]` for multi-character delimiters.
The [split record](../../cloth-strings/references/split-method.md) owns exact
char/string overload registration, binding/presence integration, literal
non-overlapping matching, scalar-boundary empty delimiters and independent
results. The focused run passes 151 tests across seven suites in 36 seconds,
with zero failures, errors or skips. Native Windows x64/x86 example/stress runs
and all-nine-profile IR/object checks pass. The final full regression passes
1,470 tests across 132 suites in 6m 44s, with zero failures, errors or skips.
It isolated compiler classes/reports from concurrent workspace builds; an earlier
shared-class run encountered enum-diagnostics assertions during unrelated edits.

### Character-delimited string splitting (2026-11-06)

`StringAttachments.SPLIT` implements the user-requested `split(char) -> string[]`.
The [split integration record](../../cloth-strings/references/split-method.md)
owns matching, preserved empty pieces, independent nested storage, emitter
invariants and verification. The focused run passes 107 tests; the full compiler
suite passes 1,459 tests across 131 suites in 6m 31s, with zero failures, errors
or skips. The maintained example and discarded-buffer stress program execute on
Windows x64/x86; parameterized split results verify IR and objects for all nine
profiles. Native Linux/macOS/Windows ARM64 split execution remains untested.

### Single-statement void callable bodies (2026-11-06)

[Function return contracts](../../cloth-functions/references/semantics.md#function-return-contracts)
records the void `= statement` form for ordinary functions and methods.
[FunctionParser](../../../../Compiler/src/main/kotlin/cloth/parser/subs/FunctionParser.kt)
parses the ordinary statement grammar and retains the `=` token on the body
for optional declaration termination. Statement bodies reuse the brace-body
checks and LLVM emission. Void expression bodies retain explicit discard
rules, and operator declarations retain their own result contract.

[FunctionParsingTest](../../../../Compiler/src/test/kotlin/cloth/FunctionParsingTest.kt)
covers spans, declaration boundaries and rejection in a value-returning body.
[FunctionReturnsTest](../../../../Compiler/src/test/kotlin/cloth/FunctionReturnsTest.kt)
covers value rejection in void bodies.
[AssignmentOperatorsTest](../../../../Compiler/src/test/kotlin/cloth/AssignmentOperatorsTest.kt)
executes all five float compound assignments, a struct field update and a
single-statement loop on Windows x64 and x86. The full
`./gradlew.bat :Compiler:test --offline --console=plain --no-daemon` run passed
1,428 tests across 126 suites with zero failures, errors or skips.

### Struct methods publication (2026-11-05)

[StructMethods.co](../../../../examples/StructMethods.co) demonstrates the
shared callable pipeline and ordinary struct methods. The
[method publication record](../../cloth-functions/references/method-publication.md)
owns the Windows x64/x86 and GNU Linux x64 execution evidence, LLVM IR and
objects across all nine profiles, full regression results and class handoff.

### Basic structs publication (2026-11-05)

[Structs.co](../../../../examples/Structs.co) demonstrates the completed basic
struct slice. The [publication record](../../cloth-structs/references/publication.md)
owns native Windows x64/x86 and GNU Linux x64 results, all-profile LLVM IR and
object checks, full regression counts and the class handoff.

### Simple character casing (2026-11-04)

The user confirmed zero-argument `toUpperCase()` and `toLowerCase()` in the existing
`CharAttachments`: exact char results, pinned Unicode 17.0.0 simple mappings,
locale/context independence and original-scalar fallback for missing mappings.
The [casing integration record](../../cloth-strings/references/character-case.md)
owns source routing, authenticated data generation and verification. Named
`valueMethod` registrations match the other primitive providers; existing exact
typing, nullable proofs, receiver evaluation and first-error diagnostics apply.
Full string casing, expansions, folding and locale overrides remain outside scope.

Immutable versioned data holds 1,505 uppercase and 1,488 lowercase mappings.
Module-local binary-search helpers reuse private constant source/target tables,
returning the input on a miss. No host Unicode/locale or runtime UCD dependency
is introduced. The offline generator adds pinned UnicodeData fields 12/13 and
retains the Unicode License V3 and source SHA256 metadata.

The focused command
`.\gradlew.bat :Compiler:test --tests cloth.CharCaseTest --tests cloth.CharAttachmentsTest --tests cloth.CharactersTest --tests cloth.BoolAttachmentsTest --tests cloth.StringAttachmentsTest --tests cloth.ArrayTemporaryCleanupTest --tests 'cloth.Nullability*' --offline --console=plain --no-daemon`
passes 140 tests across 10 suites, zero failures/errors/skips. Independent raw-UCD
oracles execute both mappings over every one of the 1,112,064 valid scalars on
Windows x64/MSVC and x86/MSVC. Source tests preserve char identity, snapshots,
effects, nullable/ordered/deferred/recursive results, early exits, strict located
errors and CLI first-error behavior. A poisoned-free allocation probe preserves
saved scalars and arrays while reclaiming producers in 1,000 iterations and
while/do-while conditions.

Maintained [CharCase.co](../../../../examples/CharCase.co) executes on both Windows
targets; it and the parameterized two-result fixture verify IR and nonempty
objects across all nine supported profiles. Foreign native execution remains
untested.

The full command `.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon`
passes 1,292 tests across 101 suites in 5m 37s, zero failures/errors/skips. All
seven generated Unicode artifacts reproduce byte for byte; the original five
classification artifacts retain their previous hashes. Downloaded scratch inputs
were removed. Direct changed-skill metadata, relative links and whitespace checks
pass. The optional Python skill validator remains unavailable without PyYAML;
Gradle uses the existing external cache.

### Character attachments (2026-11-04)

The user confirmed seven methods in a separate `CharAttachments`, pinned Unicode
17.0.0 and exact Letter/decimal-digit/binary-case properties. The
[character integration record](../../cloth-strings/references/character-attachments.md)
owns the contract and source routing. Six predicates return bool; `toInt` returns
the Unicode code point as signed int32. Character arithmetic/casts remain deferred;
case conversion was subsequently integrated in the casing milestone above.
The provider matches existing named `valueMethod`
registration, read-only storage and exact receiver/result rules.

Immutable versioned range data and module-local binary-search helpers provide
portable classification without host locale/JVM predicates or native library
dependencies. Letter is category L, digits Nd, case uses Uppercase/Lowercase;
case need not imply Letter. Whitespace shares the existing 25-scalar table.
The offline generator authenticates pinned inputs by SHA256 and reproduces all
five table/license/compressed-oracle artifacts byte for byte. Unicode License V3
and source provenance are retained in resources.

The focused command
`.\gradlew.bat :Compiler:test --tests cloth.CharAttachmentsTest --tests cloth.BoolAttachmentsTest --tests cloth.CharactersTest --tests cloth.StringAttachmentsTest --tests cloth.StringWhitespaceTest --tests cloth.StringMemberStorageTest --tests cloth.ArrayTemporaryCleanupTest --tests 'cloth.Nullability*' --offline --console=plain --no-daemon`
passes 147 tests across 11 suites in 36 seconds, zero failures/errors/skips.
Independent raw-UCD oracles execute every one of the 1,112,064 Unicode scalars
on Windows x64/MSVC and x86/MSVC. Source tests cover Unicode 17 additions, signed
int32 through U+10FFFF, case/Letter distinctions, nullable proofs/fallbacks,
snapshots, ordered/deferred/recursive results, early exits and first-error CLI
rejection. The poisoned-free character probe bounds array producers over 1,000
iterations and while/do-while conditions while preserving preexisting arrays.

Maintained [CharAttachments.co](../../../../examples/CharAttachments.co) executes
on both Windows targets; it and the parameterized all-method fixture verify IR
and nonempty objects across all nine profiles. Native foreign execution remains
untested.

The full command `.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon`
passes 1,283 tests across 100 suites in 5m 30s, zero failures/errors/skips.
Gradle uses the existing external cache. Direct changed-skill metadata and 504
relative file links pass, together with whitespace checks. The optional Python
skill validator remains unavailable without PyYAML. Downloaded scratch inputs
were removed after verifying reproducibility; pinned resources remain in source.

### Boolean attachment (2026-11-04)

The user approved only `toInt()` in a separate `BoolAttachments`, matching the
other providers' named `valueMethod` declarations. The
[boolean integration record](../../cloth-numerics/references/bool-attachments.md)
owns the exact contract: no explicit arguments, false to 0, true to 1, always
signed int32. The target-independent boolean provider enters the ordinary exact
receiver lookup and read-only catalog. `BuiltinEmissionContext.boolToInt` emits
zero extension from i1 to i32; no new runtime checks, diagnostic codes or lifetime
machinery are needed. Existing boolean/numeric implicit/cast rejection is preserved.

The focused command
`.\gradlew.bat :Compiler:test --tests cloth.BoolAttachmentsTest --tests cloth.FloatAttachmentsTest --tests cloth.IntegerAttachmentsTest --tests cloth.StringAttachmentsTest --tests cloth.StringMemberStorageTest --tests cloth.ArrayTemporaryCleanupTest --tests 'cloth.Nullability*' --offline --console=plain --no-daemon`
passes 140 tests across 10 suites in 52 seconds, zero failures/errors/skips.
The six new tests cover registration, value snapshots, once-only receiver effects,
scores, nullable proofs/fallbacks, deferred reads, ordered results, arrays/foreach,
short circuits, loop conditions, early returns, strict result identities and CLI
first-error rejection before object/linker work. Maintained
[BoolAttachments.co](../../../../examples/BoolAttachments.co) and source fixtures
execute on Windows x64/MSVC and x86/MSVC. Parameterized conversion and example
IR/objects pass all nine profiles; foreign native execution remains untested.
The earlier 1,269-test full run predates this addition and was not repeated.
Direct changed-skill metadata and 477 relative file links pass, along with
`git diff --check`. The optional Python validator remains unavailable without
PyYAML; no environment dependencies were installed.

### Float attachments (2026-11-04)

The user approved a separate float provider matching the string/integer
registration style and settled every special-value rule. The
[float integration record](../../cloth-numerics/references/float-attachments.md)
owns abs/clamp/signum for all three float widths. Abs/clamp preserve exact float
identity; signum returns signed int32 and rejects NaN. Abs clears the sign bit;
clamp validates non-NaN ordered bounds, then selects original operands with strict
comparisons, preserving in-range signed zeros and NaN receivers. Half comparisons
use existing software widening without native half comparison/runtime helpers.

Shared-name member lookup now handles non-continuing receivers using argument
family hints and stable representatives, preserving ordinary static checks and
early exits. Closed floating constants publish C3075/C3076; dynamic guards retain
once-only ordered operands, source locations and first-error termination without
PEC. Elvis now keeps nullable float fallback literal widths even without outer
numeric context; typed fallbacks retain exact-type checks. Existing storage
contracts and closed reclamation proofs apply without new ownership mechanisms.

The focused command
`.\gradlew.bat :Compiler:test --tests cloth.FloatAttachmentsTest --tests cloth.IntegerAttachmentsTest --tests cloth.StringAttachmentsTest --tests cloth.StringMemberStorageTest --tests cloth.ArrayTemporaryCleanupTest --tests 'cloth.Nullability*' --tests cloth.NumericLiteralsTest --tests cloth.NumericLiteralsIntegrationTest --offline --console=plain --no-daemon`
passes 151 tests across 11 suites in 48 seconds, zero failures/errors/skips.
Parameterized native implementations run against independent IEEE/JVM half
oracles for raw boundary/random patterns, signed zeros, subnormals, NaNs,
infinities and nine clamp-bound pairs. Source tests cover exact widths, precision,
presence/fallbacks, captured snapshots, ordered results, early exits, skipped
operations and first-error diagnostics. The poisoned-free float probe verifies
bounded producer storage in 1,000 iterations and while/do-while conditions.

Oracle programs, source fixtures and maintained
[FloatAttachments.co](../../../../examples/FloatAttachments.co) execute on Windows
x64/MSVC and x86/MSVC. Parameterized fixtures and the maintained example verify IR
and nonempty objects across all nine profiles. Foreign native execution remains
untested for this feature.

The full command `.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon`
passes 1,269 tests across 98 suites in 5m 42s, zero failures/errors/skips. Gradle
uses the existing external cache. Changed skill metadata and 462 relative file
links pass direct checks, as does `git diff --check`. The optional Python skill
validator remains unavailable because PyYAML is missing.

### Shared attachment registration style (2026-11-04)

The user requested string and integer registrations look as similar as possible.
Both providers now declare named entries with inherited
`PrimitiveAttachment.valueMethod(name, parameters, returns) { context, values -> ... }`.
The base helper explicitly opts into read-only, independent-result storage;
`register` retains its conservative unknown default. Integer names/signatures/bodies
live together in declarations, matching strings. `IntegerMethod` remains only a
backend operation enum without spellings. Static integer validation compares cached
attachment identities instead of consulting a second name table. Receiver/target
specialization remains lazy and all language contracts are preserved.

The focused command
`.\gradlew.bat :Compiler:test --tests cloth.IntegerAttachmentsTest --tests cloth.StringAttachmentsTest --tests cloth.StringMemberStorageTest --tests cloth.ArrayTemporaryCleanupTest --offline --console=plain --no-daemon`
passes 85 tests across four suites in 42 seconds, zero failures/errors/skips.
This reruns full-cap oracles/stress, Windows x64/x86 execution, all-nine-profile
objects, first-error diagnostics, snapshot behavior and poisoned-memory lifetimes.
Changed skill metadata/relative links and `git diff --check` pass. The earlier
1,258-test full run predates this behavior-preserving refactor; it was not rerun.

### Integer attachments (2026-11-04)

The user confirmed all six contracts and full integer-family coverage.
[The integration record](../../cloth-numerics/references/integer-attachments.md)
owns `abs`, `clamp`, `signum`, `leadingZeros`, `trailingZeros` and `toStringRadix`.
Type-preserving abs errors on signed minimum; unsigned abs is identity. Inclusive
clamp preserves exact types and rejects reversed bounds. Signum always returns
signed int32; bit counts return target usize and inspect declared bits, with zero
returning the width. Radix bases 2..36 produce independent lowercase signed-magnitude
strings without prefixes/padding, including successful signed-minimum formatting.

Integer receiver/target definitions specialize lazily through the typed attachment
registry. A representative catalog supports pre-typing presence/non-continuing
receivers without enumerating every width. Closed integer constant checks reuse
RangeConstants and publish C3072/C3073/C3074; dynamic guards preserve operand order,
original source locations and first-error exit without PEC. Scalar operations use
ordinary LLVM comparisons/count intrinsics; wide operations reuse canonical limbs.
New bounded chunked radix/trailing helpers use i32 words/i64 arithmetic. Existing
operand snapshots, outgoing copies and closed arena/stack proofs remain in force.

The focused command
`.\gradlew.bat :Compiler:test --tests cloth.IntegerAttachmentsTest --tests cloth.StringAttachmentsTest --tests cloth.ArrayTemporaryCleanupTest --offline --console=plain --no-daemon`
passes 81 tests across three suites in 35 seconds, zero failures/errors/skips.
Independent full-value oracles cover all scalar widths and representative wide
widths through 65536, every base 2..36 at selected widths and representative bases
through the cap. Source tests exercise strict identities, nullable proofs,
captured writes, once-only operands, early exits, skipped calls and intrinsic
diagnostics inside overload probes. A poisoned-free probe checks escaping text,
ordered wide/text results and captured publication. Maintained
[IntegerAttachments.co](../../../../examples/IntegerAttachments.co), oracle programs
and a 2,000-iteration full-cap storage stress execute on Windows x64/MSVC and
x86/MSVC. IR and nonempty objects pass all nine profiles; foreign native execution
is untested for this feature.

The full command `.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon`
passes 1,258 tests across 97 suites in 5m 33s, zero failures/errors/skips, including
the expanded one-bit/default/full-cap runtime abs guards. Gradle uses the existing
external cache. Changed skill metadata and 380 relative file links pass direct
checks. The optional Python validator cannot start because PyYAML is unavailable.

### String member storage cleanup (2026-11-04)

The user authorized cleanup of redundant string member copies and temporary
retention. [The storage record](../../cloth-buffer-lifetimes/references/string-member-storage.md)
owns the proof and verification. `BuiltinStorageContract` defaults to unknown;
the 17 read-only string bodies explicitly promise independent results and no
operand mutation/publication/retention. `EmitExpression` captures immutable string
descriptors once without copying their bytes, protecting operand storage through
consumption. Unknown contracts/types retain existing copies. `ArrayTemporaryAnalysis`
admits known bodies only with proven receiver/argument effects; discarded/direct
results release arena suffixes and restore dynamic stacks. Existing loop proofs
recognize those effects while buffered publication remains conservative.
The public string API and all independent result/return-copy rules are unchanged.

The existing string/lifetime regression command passed after the core change.
The new focused command
`.\gradlew.bat :Compiler:test --tests cloth.ArrayTemporaryCleanupTest --tests cloth.StringMemberStorageTest --offline --console=plain --no-daemon`
passes 60 tests in 23 seconds, zero failures/errors/skips: 56 lifetime and four
storage/portability tests. Native allocation/poison counters verify zero extra
operand copies across all 17 methods, prompt producer reclamation, preexisting
storage preservation, bounded while/do-while conditions and captured publication.
A 5,000-iteration discarded 4 KB result probe restores stacks even inside an outer
loop blocked by buffered publication. The stress program executes on Windows
x64/MSVC and x86/MSVC; shared cleanup/escaping-result IR and nonempty objects pass
all nine target profiles. Unknown/default contracts and captured publication
remain rejected by the reclamation proof. Foreign native execution is untested.
The full regression command
`.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon`
passes 1,247 tests across 96 suites in 5m 10s, zero failures/errors/skips. Gradle
used the existing cache outside the sandbox. Skill metadata and relative file
links pass direct checks; the optional Python validator cannot start because its
environment lacks PyYAML.

### Unicode whitespace string methods (2026-11-04)

The user approved `trim`, `trimStart`, `trimEnd` and `isBlank`, and selected the
pinned Unicode White_Space property. [The whitespace record](../../cloth-strings/references/whitespace-methods.md)
owns Unicode 17.0.0 classification, empty-string blankness, boundary-only removal
and independent string results. `UnicodeWhiteSpace` holds ten immutable ranges
covering 25 scalars, independent of JVM/C predicates or locale. The new
`EmitStringWhitespace` reuses full UTF-8 decoding, checks live scalar bounds,
stops leading scanning at content and tracks the last content end using forward
traversal. Trims copy one bounded interval into independent checked storage;
ordinary snapshots, recursive return copies and conservative cleanup remain.

The focused command
`.\gradlew.bat :Compiler:test --tests cloth.StringWhitespaceTest --tests cloth.StringAttachmentsTest --tests cloth.StringScalarMethodsTest --tests cloth.StringEditingTest --tests cloth.ArrayTemporaryCleanupTest --offline --console=plain --no-daemon`
passes 102 tests across five suites in 27 seconds, zero failures/errors/skips.
This includes 12 new whitespace tests and the 51-test lifetime suite. Evidence
covers every White_Space scalar and adjacent/excluded points using an independent
oracle, complete UTF-8 contents/NULs, nullable proofs/results, zero-argument
contracts, first-error CLI rejection, once-only receivers, skipped operations,
early returns/jumps, recursion, ordered results and 10,000-scalar whitespace runs.
A poisoned-free probe verifies saved and captured/ordered results after source
frames release, with zero outstanding allocations after the receiving frame exits.

[StringWhitespace.co](../../../../examples/StringWhitespace.co) executes with exact
output and zero status on Windows x64/MSVC and x86/MSVC. The shared all-four-method
fixture verifies IR and nonempty objects across all nine target profiles; native
Linux/macOS/Windows ARM64 execution remains untested. The full regression command
`.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon`
passes 1,238 tests across 95 suites in 5m 8s, zero failures/errors/skips. Gradle
commands used the existing cache outside the sandbox. Changed skill metadata and
relative file links pass direct checks; the optional Python validator cannot
start because its environment lacks PyYAML.

### Literal string editing (2026-11-04)

The user approved `replace`, `replaceFirst`, `removePrefix` and `removeSuffix`,
then explicitly chose Unicode scalar-boundary matches for empty searches.
[The editing record](../../cloth-strings/references/editing-methods.md) owns exact
case-sensitive literal matching, non-overlapping replacement without rescanning
new text, once-only affix removal and independent string results. Typed registry
entries reuse receiver/argument snapshots and strict positional signatures.
`EmitStringEdit` measures with checked target-byte additions, allocates one result,
then fills from the same immutable snapshots; empty-match progress uses the
shared scalar-boundary helper. No cleanup proof, borrowing API or GC is added.

The focused command
`.\gradlew.bat :Compiler:test --tests cloth.StringEditingTest --tests cloth.StringAttachmentsTest --tests cloth.StringScalarMethodsTest --tests cloth.ArrayTemporaryCleanupTest --tests cloth.BufferCountsTest --offline --console=plain --no-daemon`
passes 97 tests across five suites in 26 seconds, zero failures/errors/skips.
This includes 12 editing tests with 240 independent replacement cases and 90 affix
cases, Unicode/NUL/literal contents, side effects, strict diagnostics, nullable
proofs, early exits, recursive/ordered results and 16,384-scalar expansion/deletion.
The poisoned-free edit-result probe checks saved values, released operand frames,
captured publication and final allocation balance. Three new native count probes
on Windows x64/x86 reject all/first/empty replacement output overflow before any
inaccessible replacement payload is read. The user's expanded attachment example
is preserved, with its output expectation updated for the scalar/array demo.

[StringEditing.co](../../../../examples/StringEditing.co) executes with exact
output and zero status on Windows x64/MSVC and x86/MSVC. The all-four-method
parameterized fixture verifies IR and nonempty objects across all nine target
profiles; native Linux/macOS/Windows ARM64 execution remains untested.
The full regression command
`.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon`
passes 1,225 tests across 94 suites in 5m 17s, zero failures/errors/skips.
Gradle commands used the existing cache outside the sandbox.
Skill metadata and relative file links pass direct checks; the optional Python
validator cannot start because its environment lacks PyYAML.

### Scalar-indexed string methods (2026-11-04)

The user accepted zero-based Unicode scalar positions and proceeding with the
five-method batch. [The scalar-method record](../../cloth-strings/references/scalar-methods.md)
owns `charAt`, exclusive-end `substring`, nullable-position `indexOf`/`lastIndexOf`
and independent `toChars`. Exact `usize` parameters and results specialize by
compilation target; shared x86/x64 ASTs retain target literal bounds. Searches
preserve overlaps and deterministic absence, with empty needles at zero/the
scalar count. Extraction guards source bounds before byte access, decodes full
UTF-8 scalars including NULs, and allocates independent string/char-array values.
Member array results enter ordinary freshness/shape/return-copy machinery;
nullable search results retain explicit-binding and presence-proof rules.
Cleanup stays conservative and direct bracket indexing remains deferred.

The focused command
`.\gradlew.bat :Compiler:test --tests cloth.StringScalarMethodsTest --tests cloth.StringAttachmentsTest --tests cloth.ArrayTemporaryCleanupTest --offline --console=plain --no-daemon`
passes 76 tests across three suites in 22 seconds, zero failures/errors/skips:
13 new scalar-method tests, 14 original attachment tests and 49 lifetime tests.
Evidence includes independent code-point/subslice expectations, UTF-8 width
boundaries/scalar maximum, 144 first/last search cases, nullable result/fallback
behavior, exact type and literal errors, ordered effects/early exits, original
runtime locations and first-error CLI failure before output/linker work.
The new poisoned-free lifetime probe verifies returned substrings/char arrays,
independent copies, captured receiver updates and deferred blocks, with no
outstanding allocations after frame release.

[StringScalars.co](../../../../examples/StringScalars.co) executes on Windows
x64/MSVC and x86/MSVC with exact output and zero exit status. The shared
all-five-method fixture verifies IR and nonempty objects across all nine target
profiles; native Linux/macOS/Windows ARM64 execution remains untested.
The full regression command
`.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon`
passes 1,212 tests across 93 suites in 4m 59s with zero failures/errors/skips.
Gradle commands used the existing cache outside the sandbox.
Changed skill metadata and relative links pass direct checks. The optional
Python validator cannot start because its environment lacks PyYAML.

### String attachments and member-call groundwork (2026-11-03)

The user approved the first four string methods and typed primitive registry.
[The member-call record](../../cloth-strings/references/member-calls.md) owns
`isEmpty()`, `contains(string)`, `startsWith(string)` and `endsWith(string)`:
boolean results, exact case-sensitive UTF-8 contents including NULs, empty-search
matches, strict positional arguments, nullable proofs and receiver-before-argument
snapshots. Named registrations publish themselves and reuse bound built-in
contracts/emission without retaining LLVM handles. Dot-call AST, declarations,
completion, nullable flow, metadata effects, committed trial facts, snippets and
native emission are integrated. Member cleanup remains conservative; class/static
calls, overloads and other primitive methods remain deferred. The earlier mockup's
unimplemented names are retained in the record's deferred API inventory.

`StringAttachmentsTest` initially passed 12 tests. The full compiler command
`.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon` then passed
1,197 tests across 92 suites in 5m 9s with zero failures/errors/skips, including
the expanded 13-test attachment suite. After a canonical operator-spelling cleanup
and an additional metadata-effect test, the final command
`.\gradlew.bat :Compiler:test --tests cloth.StringAttachmentsTest --tests cloth.StringsTest --offline --console=plain --no-daemon`
passed 30 tests (14 attachment and 16 string) in 18 seconds with zero failures,
errors or skips. Gradle runs used the existing cache outside the sandbox.

The native matrix checks 430 predicates, covering empty, overlapping, long,
case-sensitive, supplementary/decomposed Unicode and embedded-NUL inputs.
Additional cases verify registry identity/read-only lists, parser spans/chaining,
strict rejection, intrinsic diagnostics inside outer overload probes, captured
array element writes, nullable presence invalidation/results, early returns,
metadata writes and first-error CLI exit 1 before output/linker work.
[StringAttachments.co](../../../../examples/StringAttachments.co) executes with
exact output and zero status on Windows x64/MSVC and x86/MSVC. The shared
parameterized fixture verifies IR and nonempty objects across all nine profiles;
Linux, macOS and Windows ARM64 native execution remains untested for this feature.
Changed skill metadata and relative file links pass direct checks; the optional
skill validator could not start because its Python lacks PyYAML.

### Do-while Stage 5: integration, portability and publication (2026-11-03)

[Stage 5](../../cloth-control-flow/references/do-while-loops.md#stage-5-integration-portability-and-publication-2026-11-03)
publishes maintained [DoWhile.co](../../../../examples/DoWhile.co). It demonstrates
the false first test, repeated condition effects, an unbraced body with optional
body/trailer semicolons, continue-to-test and break-without-test. Main checks the
results and returns zero. DoWhileTest reads the actual example, verifies exact
output and zero status on Windows x64/MSVC and x86/MSVC, and verifies its LLVM IR
and nonempty objects across all nine profiles alongside the nested do/foreach
fixture. The suite now has 13 tests; no compiler behavior changes were required
for final integration.

The focused affected command:

```powershell
.\gradlew.bat :Compiler:test --tests cloth.DoWhileTest --tests cloth.DoWhileFlowTest --tests cloth.DoWhileSemanticsTest --tests cloth.DoWhileParsingTest --tests cloth.ArrayTemporaryCleanupTest --tests cloth.WhileTest --tests cloth.LoopControlTest --tests cloth.ForEachTest --tests cloth.ForRangeTest --tests cloth.FunctionBlocksTest --tests cloth.FunctionReturnsTest --tests 'cloth.Nullable*' --offline --console=plain --no-daemon
```

Passes 285 tests across 14 suites in 55 seconds, zero failures/errors/skips.
The full compiler command follows the focused success:

```powershell
.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon
```

Passes 1,184 tests across 91 suites in 5m 15s, zero failures/errors/skips,
including all 79 do-while tests and all 48 lifetime tests. Both commands require
Gradle cache access outside the sandbox. Target profiles are
i686-pc-windows-msvc, x86_64-pc-windows-msvc,
aarch64-pc-windows-msvc, x86_64-unknown-linux-gnu,
aarch64-unknown-linux-gnu, x86_64-unknown-linux-musl,
aarch64-unknown-linux-musl, x86_64-apple-darwin and aarch64-apple-darwin.
The native evidence covers Windows x64/x86; Windows ARM64, Linux and macOS
do-while native execution remains untested. Object emission does not imply
native execution, linking or a public library ABI.

Plan, feature/language/semantics, jump, semicolon and toolchain routing now link
the maintained example and final verification record. The closed cleanup proof
remains unchanged: captured publication/unknown effects retain frame lifetime,
and arbitrary loops have no bounded-storage guarantee. Skill-creator quick
validation was attempted but could not start because Python lacks PyYAML;
direct checks validate nine skill metadata blocks, relative links/fragments,
fences and whitespace, and git diff --check passes. Stages 0-5 are complete;
the agreed slice has no outstanding implementation work. Stages 0-4 retain
their historical evidence below.

### Do-while Stage 4: execution and proven cleanup (2026-11-03)

[Stage 4](../../cloth-control-flow/references/do-while-loops.md#stage-4-execution-and-proven-cleanup-2026-11-03)
adds EmitDoWhileLoop and statement dispatch through EmitExpression. The CFG
enters body before test without duplicating it; continue reaches test and break
reaches exit. Tests emit only after body fallthrough/continue. Unreached test
blocks and non-fallthrough exits receive unreachable terminators, including
when a reached test returns or jumps outward. The body target is removed before
test emission, preserving surrounding loop destinations. Existing return,
deferred-block, nullable/metadata and located runtime-failure handling is reused.

ArrayTemporaryAnalysis recognizes do-while in iteration and whole-loop proofs.
Body, test and whole-loop regions use independent closed proofs. Cleanup
reclaims arena suffixes before restoring stack checkpoints, including continue,
final false tests, break and crossed regions on outward test jumps. Outgoing
buffers copy before frame cleanup. Captured publication and unknown effects
retain frame lifetime; no general bounded-storage promise is introduced.

DoWhileTest adds 12 execution/IR tests. Six new ArrayTemporaryCleanupTest probes
cover repeated body/test regions, publication from each region, buffered
string-array returns, outward condition jumps and full-cap wide-integer stack
restoration. Poisoned frees and allocator counters verify surviving values and
zero allocations/live bytes after function completion. A 20,000-iteration
body/test fixture performs 30,000 allocations with a 264-byte live peak; nested
outward continue/break fixtures perform 4,002 allocations with a 264-byte peak.
The 2,000-iteration int<65536> fixture retains only its pre-loop array (one
allocation, 24-byte peak). Arena counters do not measure exact stack peaks.
The wide probe includes that array because the allocator instrumentation
requires a module allocation hook; its original stack-only fixture could not
be instrumented. The corrected fixture passes.

The final affected command:

```powershell
.\gradlew.bat :Compiler:test --tests cloth.DoWhileTest --tests cloth.DoWhileFlowTest --tests cloth.DoWhileSemanticsTest --tests cloth.DoWhileParsingTest --tests cloth.ArrayTemporaryCleanupTest --tests cloth.WhileTest --tests cloth.LoopControlTest --tests cloth.ForEachTest --tests cloth.ForRangeTest --tests cloth.FunctionBlocksTest --tests cloth.FunctionReturnsTest --tests 'cloth.Nullable*' --offline --console=plain --no-daemon
```

Passes 284 tests across 14 suites in 50 seconds, zero failures/errors/skips.
Gradle required cache access outside the sandbox. The shared execution fixture
runs on Windows x64 and x86. A nested do/foreach fixture verifies LLVM IR and
emits nonempty objects for all nine supported profiles: i686-pc-windows-msvc,
x86_64-pc-windows-msvc, aarch64-pc-windows-msvc, x86_64-unknown-linux-gnu,
aarch64-unknown-linux-gnu, x86_64-unknown-linux-musl,
aarch64-unknown-linux-musl, x86_64-apple-darwin and aarch64-apple-darwin.
Windows ARM64, Linux and macOS native execution was not tested.

Feature, language, semicolon, jump, lifetime, function, nullable, compiler-data
and diagnostic skills route to the implemented behavior and observed evidence.
Skill metadata, relative links/fragments, fences and whitespace pass direct
publication checks, as does git diff --check. The skill-creator quick validator
was attempted but could not start because Python lacks PyYAML. Stages 0-4 are
complete; Stage 5 retains the maintained runnable example, full compiler suite
and final integration/publication. No full compiler-suite pass is claimed here.

### Do-while Stage 3: completion, nullable and metadata flow (2026-11-03)

[Stage 3](../../cloth-control-flow/references/do-while-loops.md#stage-3-completion-nullable-and-metadata-flow-2026-11-03)
replaces the Stage 2 bridge with unconditional first body entry, precise test
reachability, real return/outer-jump propagation and ordinary false/break exits.
An always-returning body or intentional true-loop nontermination can complete a
non-void function. Only fallthrough/continue reaches the test; skipped tests
contribute no runtime returns or jumps, while all source remains checked.

NullFlowAnalysis intersects body-fallthrough/continue facts for the test and
false-test/break facts at exit without zero trips. MetadataAnalysis uses the same
path schedule and actual jump snapshots, retaining only agreed represented types.
Backedge-capable bodies invalidate repeated nullable writes and mutable metadata
before publishing first-entry occurrences. Proven nonrepeating bodies preserve
incoming facts; no trailing true-test proof is assumed before first entry.
Nested/captured writes remain included. Analysis stays conservative without a
precision-maximizing fixed point, preserving immutable/body-local scope rules.

DoWhileFlowTest adds 27 semantic regressions for first entry and nontermination,
condition reachability, outward jumps, deferred/function returns, non-continuing
value branches, nullable/captured-write joins, nonrepeating bodies, metadata
capabilities and continue/break snapshots. The final command:

```powershell
.\gradlew.bat :Compiler:test --tests cloth.DoWhileFlowTest --tests cloth.DoWhileSemanticsTest --tests cloth.DoWhileParsingTest --tests cloth.WhileTest --tests cloth.LoopControlTest --tests cloth.DeclarationScopesTest --tests cloth.CallResolutionTest --tests cloth.FunctionReturnsTest --tests cloth.FunctionBlocksTest --tests cloth.OptionalArgumentsTest --tests cloth.ForSemanticsTest --tests cloth.ForFlowTest --tests cloth.ConditionalsTest --tests cloth.UnbracedConditionalsTest --tests 'cloth.*ResultsTest' --tests 'cloth.Null*' --tests 'cloth.*Provider*' --offline --console=plain --no-daemon
```

Passes 387 tests across 26 suites with zero failures/errors/skips. Gradle required
cache access outside the sandbox. Skill metadata, relative links/fragments and
`git diff --check` pass manual publication checks; the skill-creator quick
validator could not start because Python lacks PyYAML. No full compiler suite or
do-while native execution, cleanup probes, IR verification or objects were run.
Existing emission regressions do not establish do-while runtime support. The plan
and skills mark Stages 0-3 complete and Stages 4-5 pending.

### Do-while Stage 2: scopes, types and jump resolution (2026-11-03)

[Stage 2](../../cloth-control-flow/references/do-while-loops.md#stage-2-scopes-types-and-jump-resolution-2026-11-03)
resolves do bodies in a child scope with their target active, then resolves
trailing tests in the surrounding scope/jump context. Shared typing checks body
before condition, requires scalar bool or bottom, and records an empty statement
result. Functions, deferred blocks, default arguments, overload trials and legacy
snippets share the implementation. Intrinsic loop errors survive probes; body
locals remain isolated, deferred jumps remain bounded, and condition jumps target
only an enclosing body. C3014/C3069 and existing void/result/return diagnostics
retain their codes and original spans.

Minimal conservative flow traversal lets the semantic pipeline process the new
node safely before Stage 3: ControlFlowModel always permits ordinary fallthrough,
NullFlowAnalysis removes repeated body/test writes and uses stable entry facts,
and MetadataAnalysis visits body then test while forgetting mutable represented
types. Recursive write scans cover nested do-while and captured deferred effects.
These are temporary implementation boundaries, not new language rules. Exact
first-entry completion, continue/false/break joins and body-established condition
proofs remain Stage 3 work; execution remains Stage 4 work.

DoWhileSemanticsTest adds 19 regressions for entry points, scopes/shadowing,
strict/nullable/bottom conditions, source traversal/error order, unreachable
checking, function/deferred returns, nearest/header jump targets, defaults,
overload diagnostics/commits, snippets, immutable metadata capability checks and
stale nullable facts. The final command:

```powershell
.\gradlew.bat :Compiler:test --tests cloth.DoWhileSemanticsTest --tests cloth.DoWhileParsingTest --tests cloth.WhileTest --tests cloth.LoopControlTest --tests cloth.DeclarationScopesTest --tests cloth.CallResolutionTest --tests cloth.FunctionReturnsTest --tests cloth.FunctionBlocksTest --tests cloth.OptionalArgumentsTest --tests cloth.ForSemanticsTest --tests cloth.ForFlowTest --tests 'cloth.Null*' --tests 'cloth.*Provider*' --offline --console=plain --no-daemon
```

Passes 336 tests across 23 suites in 35 seconds, zero failures/errors/skips.
Gradle required cache access outside the sandbox. Skill metadata, relative
links/fragments and `git diff --check` pass manual publication checks; the
skill-creator quick validator could not start because Python lacks PyYAML.
No full compiler suite, do-while native execution, IR verification or objects
were run for this stage. Existing emission regressions do not establish do-while
runtime support. The plan and skills mark Stages 0-2 complete and Stages 3-5 pending.

### Do-while Stage 1: located syntax and parser boundaries (2026-11-03)

[Stage 1](../../cloth-control-flow/references/do-while-loops.md#stage-1-located-syntax-and-parser-boundaries-2026-11-03)
adds DoWhileStatement with a shared LoopBody, independent LoopTarget and exact
do/while/parenthesis/condition spans, plus parser dispatch and a dedicated loop
parser. Shared unbraced bodies track simple-statement terminator ownership
through for/while/if nesting. An explicit trailer parameter admits same-line
bare returns in body/expression tails while blocks, headers and delimiters keep
their ordinary context. Braced-body separators, semicolons before else and
unrelated return/while adjacency remain errors. Parenthesized conditionals keep
their expression-statement terminators despite sharing the standalone if AST.
Spans exclude consumed ordinary
terminators, and the body is retained without lowering or duplication.

DoWhileParsingTest adds 20 parser tests covering the accepted grammar, nested
ownership, broadcast definitions, contextual boundaries, spans/CRLF, snippets,
deferred blocks, malformed source and first-error locations. The focused runs:

```powershell
.\gradlew.bat :Compiler:test --tests cloth.DoWhileParsingTest --tests cloth.ForParsingTest --tests cloth.UnbracedConditionalsTest --tests cloth.SemicolonsTest --tests cloth.FunctionParsingTest --tests cloth.WhileTest --tests cloth.LoopControlTest --tests cloth.FunctionReturnsTest --tests cloth.FunctionBlocksTest --tests cloth.ConditionalsTest --offline --console=plain --no-daemon
```

The final run passes 172 tests across ten suites in 21 seconds, with zero
failures/errors/skips. Gradle required cache access outside the sandbox.
These are focused results; the full compiler suite was not run.

Skill metadata, relative links/fragments and `git diff --check` pass manual
publication checks. The skill-creator quick validator was attempted but could
not start because Python lacks PyYAML; no successful validator run is claimed.
The plan and routing now mark Stages 0-1 complete and Stages 2-5 pending.
Declaration/jump resolution, bool validation, completion and nullable/metadata
flow, emission and a runnable example remain unimplemented for do-while. Parsing
is target-independent; no do-while native execution, IR verification or objects
have been tested. Existing loop/function regressions do not establish do-while
execution support.

### Do-while Stage 0: accepted contract publication (2026-11-03)

Stage 0 publishes the [accepted do-while contract](../../cloth-control-flow/references/do-while-loops.md)
and marks Stage 0 complete in the [implementation plan](../../../../todo/do-while-loops-plan.md).
The focused reference owns syntax, body/trailer and semicolon boundaries,
strict bool conditions, scopes, jumps, and required first-entry flow/lifetimes.
The plan now links to that authority instead of keeping a competing contract.
Control-flow/language skills and semantics indexes, semicolon guidance, while
and shared loop-control references all route to the accepted design and retain
the explicit pending status for compiler support.

Verification is documentation-only: manual metadata checks for the three changed
skills, local Markdown link/fragment checks, contract/status review and
`git diff --check` pass. The available skill-creator `scripts/quick_validate.py`
was attempted with `python` but could not start because PyYAML is not installed
(`ModuleNotFoundError: No module named 'yaml'`). Manual metadata checks are
recorded separately and do not claim that validator passed.

`Keywords.DO` remains reserved and `Parser.parseStatement` still has no do
dispatch. No compiler source, tests or executable examples changed. Compiler
tests, native execution and cross-target emission were not run for this stage;
there is no do-while runtime or portability evidence yet. Stages 1-5 remain
pending, beginning with located syntax and parser boundaries.

### Numeric range membership: review fixes (2026-11-03)

The [follow-up review](../../cloth-numerics/references/range-membership.md#review-fixes-2026-11-03)
fixes contextual float widths lost in nullable Elvis operand hints, rejection
or misleading context from operands that always exit, and original membership
errors hidden by source-call overload probes. Intrinsic checking is scoped to
membership; ordinary contextual argument matching resumes afterward. All
operand syntax remains checked, even when execution exits before reaching it.

Five new regressions cover float16/64 fallbacks and arithmetic, early returns,
break/continue, unreachable alternative types, wide/indexed snapshots through
65536 bits, precise argument diagnostics and unchanged overload selection.
Focused runs pass 104 nullable/array/loop/literal/membership tests and 75
membership/call/default/diagnostic tests. The final full compiler run passes
1099 tests across 87 suites, zero failures/errors/skips, in 4m35s. Windows
x64/x86 execution and all nine ABI profiles' IR/object checks remain passing;
the new fallback/control-flow/snapshot cases execute on native Windows x64.
Metadata, relative links and `git diff --check` pass manual publication checks.

### Numeric range membership (2026-11-03)

`==` / `!=` now test integer/float range membership in either operand order,
with contextual literal widths and exact typed contracts. The
[membership record](../../cloth-numerics/references/range-membership.md) owns
the confirmed syntax, interval/type/evaluation policies, source invariants and
verification. [RangeMembership.co](../../../../examples/RangeMembership.co)
is the maintained runnable example. Standalone range values remain unsupported;
C3070 locates invalid membership contracts without changing first-error behavior.

`RangeMembershipTest` adds 13 regressions including full-cap integer membership,
all float formats, precision, endpoint guards, source-order snapshots, early
returns, nullable proofs, snippets, CLI rejection and example output. A portable
fixture executes on Windows x64/x86; all nine ABI profiles verify IR and emit
objects. Native Linux/AArch64/macOS/musl execution is untested for this feature.

Final `.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon`
passes 1094 tests across 87 suites, zero failures/errors/skips, in 4m37s.
Skill metadata and local links pass manual checks; the quick validator could
not start because the installed Python runtimes lack PyYAML.

### Arbitrary integer widths: final integration and publication (2026-11-03)

Stage 6 publishes [IntegerWidths.co](../../../../examples/IntegerWidths.co), README
syntax/entry updates and the [integrated implementation record](../../cloth-numerics/references/arbitrary-width-integers.md).
Three new publication regressions cover exact native example output, objects/CLI
publication across nine target profiles and isolated dynamic full-cap programs.
The focused arbitrary-width/lifetime run passes 88 tests across nine suites.

The production probe completes 18 cases at widths 65535/65536 across all nine
profiles, including dense nonzero arithmetic, calls, internal 2N checks and exact
decimal output. Four Windows x64/x86 runs pass; all objects and assembly pass.
Supplementary GNU Linux x64 execution under Ubuntu WSL passes both widths, each
matching 98674 normalized output bytes and the independent oracle hash. No
AArch64 cross compiler/emulator is installed; AArch64/macOS/musl execution remains
untested. These limits are retained explicitly rather than inferred from object
success.

Observed object emission is 53..69 ms at 108161..114240 bytes. The JVM per-pool
heap-peak sum is 62874424..73480264 bytes; it excludes native LLVM/process memory.
The final record compares this integrated pipeline with Stage 1's raw scalar
resource evidence without treating differing workloads as a speedup benchmark.
Durable production/Linux tables retain all statuses, timings and checksums.

Observed final full regression: `.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon`
passes 1081 tests across 86 suites, zero failures/errors/skips, in 4m32s.
All planned integer-width stages are complete. Skill metadata/local links and
`git diff --check` pass; temporary measurement scratch is removed after preserving
durable tables. The bundled skill validator cannot import its unavailable PyYAML
dependency, so metadata/link checks used local manual validation.

NumberTypes.co's existing native fixture now independently checks its
`unsigned int<0x200>` initialized from `unsigned int<512>::max`, preserving the
user's source example. The five numeric integration tests plus three publication
tests pass in a follow-up targeted run.

### Arbitrary integer widths: Stage 5 decimal output (2026-11-03)

Exact decimal text now works through 65536 bits for print/println, interpolation,
scalar `::str`, nullable values and recursive array text. One bounded base-1e9
helper converts private unsigned magnitudes with i32/i64 arithmetic, preserving
signed minimum and high unsigned bits. Exact width-derived capacity reserves a
sign/NUL; the helper checks every write. Small native formatting and floats are
unchanged. The temporary integer/text preflight is removed and C4031 retired.

The [Stage 5 record](../../cloth-numerics/references/arbitrary-width-output.md)
owns capacities, lowering, scratch and current verification. Native Windows
x64/x86 oracle tests compare exact decimal strings through the cap and all output
routes; foreign IR/objects verify across all nine supported target profiles.
Array measure/fill checkpoints now precede wide element decoding and reclaim
snapshots with formatter scratch. A 512-element cap-width array and native x64
poisoned-memory probes cover scratch reuse and outgoing strings. A safe 20000-trip
loop uses exactly 20000 result allocations with a 47-byte peak, ending at zero.

The focused output/lifetime run passes 96 tests across six suites. Observed full
regression: `.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon`
passes 1078 tests across 85 suites, zero failures/errors/skips, in 4m34s.
Skill metadata/local links and `git diff --check` pass; scratch is clean. The
bundled skill validator still lacks PyYAML, so metadata/link checks used the local
manual checks. Stage 6 publication/examples/portability review remains pending;
native AArch64/Linux/macOS execution is not claimed.

### Arbitrary integer widths: Stage 4 storage and composition (2026-11-03)

Source signed/unsigned values now execute through 65536 bits using exact scalars
through 64 and inline native-limb storage above it. Independent read snapshots,
private pointer parameters, caller-owned outgoing copies, nullable/array/row
composition, multiple/conditional results, defaults, metadata, full-bit indices
and wide signed main are integrated. `ValueType.indirect` keeps private integer
transport separate from string/array buffering. Decimal text above 64 bits
remains located C4031 pending Stage 5, before LLVM/output/linker work.

The [Stage 4 record](../../cloth-numerics/references/arbitrary-width-storage.md)
owns source invariants, observed layouts and verification boundaries. Eight new
storage integration tests execute on Windows x64/x86; full-cap composition IR
verifies and objects emit across all nine supported profiles. Three new native
x64 poisoned-memory/cleanup probes cover outgoing lifetimes, 20000 safe iterations
with a 32-byte peak and bounded full-cap counter snapshots. A focused run passed
61 tests. Native AArch64/Linux/macOS execution is not claimed.

Observed serial regression: `.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon`
passes 1069 tests across 84 suites, zero failures/errors/skips, in 4m39s.
Skill metadata/local links and `git diff --check` pass; workspace scratch is clean.
Metadata/link checks used the local manual validator because the bundled Python
skill validator cannot import its unavailable PyYAML dependency.

### Arbitrary integer widths: Stage 3 arithmetic and conversions (2026-11-03)

Production bounded integer kernels now implement wrapping arithmetic, signed/
unsigned comparison, guarded division/remainder and power, cross-width casts,
IEEE conversion and checked 2N header updates through 65536 source bits and
131072 internal bits. Scalar source widths 1..64 now emit and execute, including
one-bit power without shift poison and mathematical one-bit range-header steps.
Float formats/defaults remain unchanged. The
[Stage 3 record](../../cloth-numerics/references/arbitrary-width-arithmetic.md)
owns implementation invariants, API/test routing and remaining scope.

Wide kernels execute against BigInteger/BigDecimal and IEEE-bit oracles on native
Windows x64/x86. Full-cap kernel IR verifies and objects emit across all nine
supported target profiles. Integration tests cover every scalar width, ordinary
composition and controlled located failures; direct production API tests cover
wide operation routing, full-cap casts and runtime guards. Foreign native
execution remains untested. Wide source emission is still C4031-gated pending
Stage 4 storage/calls/snapshots and Stage 5 decimal output; helper success does
not establish those source features.

Observed full regression: `.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon`
passes 1058 tests across 83 suites, zero failures/errors/skips, in 4m24s.
The three new Stage 3 suites contribute 11 tests. Skill metadata/local links
and `git diff --check` pass; workspace scratch is clean.

### Arbitrary integer widths: Stage 2 types and literals (2026-11-03)

Semantic types now admit every signed/unsigned fixed width 1..65536, with exact
BigInteger width decoding and contextual literals through the cap. Machine/ABI
widths and target counts remain independent; floats/defaults and exact typed
compatibility are unchanged. The [Stage 2 record](../../cloth-numerics/references/arbitrary-width-types.md)
owns source invariants, contextual/rejection coverage and the remaining work.

Native emission still admits 8/16/32/64. Located C4031 preflight rejects valid
new widths before LLVM module/output/linker work, avoiding the raw backend gaps
found in Stage 1. Type/provider registration no longer eagerly applies byte-size
arithmetic to odd/wide integers. Production arithmetic, carriers/calls and output
remain pending Stages 3-5.

All 13 new width tests pass. The full compiler regression passes 1047 tests
across 80 suites, with zero failures/errors/skips. Existing native/foreign object
regressions pass; no new-width source execution support is claimed.

### Arbitrary integer widths: backend foundation (2026-11-03)

The accepted source design admits signed/unsigned widths 1..65536, preserving
32-bit defaults and existing float formats. Completed Stage 1 adds isolated raw LLVM
experiments and a bounded limb-loop prototype; it does not enable these source
types. The [backend record](../../cloth-numerics/references/arbitrary-width-backend.md)
owns measurements, native/foreign target scope, the selected representation and
the explicit production helper/integration work remaining for Stages 2-5.

Across nine ABI profiles, 1251 isolated matrix cases expose raw backend gaps and
verify the selected approach. All 153 bounded limb cases and 27 strengthened
core/control-flow cases pass, with full-cap/internal-width native checks on
Windows x64/x86 and foreign object checks. The four focused regression suites
pass 22 tests with zero failures/errors/skips. The backend record links durable
TSV measurements and distinguishes expected experiment failures from compiler
support. Source-width acceptance and production wide operations remain pending.

### Numeric literal context review fixes (2026-11-03)

Review found silent float32 rounding when an integer peer replaced a float64
literal context, and loss of numeric payload context in nullable arithmetic
destinations. `ExpressionTyping` now carries floating literal format separately
from integer context through nested arithmetic, unary expressions and conditional
results, and unwraps nullable receiving payloads before selecting numeric context.
Typed values still retain exact types; nullable sources still require presence
proofs, and mixed integer/float operations retain their minimum float32 result.

The [numeric implementation record](../../cloth-numerics/references/numeric-literals.md#arithmetic-context-review-fixes)
owns the source invariants and regression scope. Three new tests failed before
the fix and passed afterward. They check actual `16777217` precision on Windows
x64/x86, contextual float16/32/64 and integer range errors, nullable assignments,
returns/calls/arrays, and CLI failure before object creation. The new arithmetic
AST also verifies IR and emits objects across all nine supported ABI profiles;
native Linux/macOS execution is not claimed.

The focused numeric/call/return/reception/range run passed all 131 tests across six
suites, with zero failures/errors/skips. The final
`.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon` run passed
all 1,030 tests across 78 suites, with zero failures, errors or skips. Skill
metadata, 126 local link targets across six changed Markdown files and
`git diff --check` passed publication checks. The optional skill-creator validator
remains unavailable because its Python runtimes lack PyYAML; lightweight metadata
validation was performed directly.

### Numeric literals (2026-11-03)

Hexadecimal `0x`, binary `0b`, octal `0o`, decimal `i` / `I` and `f` / `F`
are integrated. `123.f`, `123f` and `123.0f` are floating literals; `123.i` is
rejected. Suffixes select category without fixing width. Fixed/target-sized
integer context and floating context in bindings, assignments and returns keep
declared widths/ranges authoritative. Already-typed values retain exact-type
rules; integer literal forms also work in numeric widths and fixed extents.

Original token spelling/spans are retained. Shared `NumericLiterals` helpers
decode radix-aware BigInteger magnitudes and exact floating text for parser,
type resolver and structural signature keys. Float range checks occur after
context is available; bool/string/etc. destinations supply no floating width.
C1013 reports malformed numeric literals, C1002 retains separator failures, and
existing contextual range codes remain unchanged. The first failure still
terminates compilation before object/linker work.

The [numeric contract](../../cloth-numerics/references/semantics.md),
[implementation record](../../cloth-numerics/references/numeric-literals.md) and
[plan](../../../../todo/numeric-literals-plan.md) own detailed behavior, stages,
source routing and limits. `NumericLiteralsTest` and
`NumericLiteralsIntegrationTest` cover malformed forms/spans, all integer
domains, target-dependent bounds, shared ASTs, type syntax/signatures, float
contexts, strict values, overloads and CLI failures. The mixed numeric fixture
and updated NumberTypes.co execute on Windows x64 and Windows x86 PE32; LLVM IR
and objects verify across all nine supported ABI profiles. Native Linux/macOS
execution is not claimed.

The focused numeric/width/cast/return/binding/call/usize/isize/semicolon/diagnostic
run passed 175 tests across 11 suites, with zero failures/errors/skips. The final
`.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon` run passed
all 1,027 tests across 78 suites, with zero failures, errors or skips. Legacy
float32-only expectations in binding/return/reception/loop tests now accept
contextual literals while still rejecting implicit conversions of typed values.
Skill metadata, 246 local link targets across nine Markdown files and
`git diff --check` passed publication checks. The optional skill-creator validator
remains unavailable because the Python runtimes lack PyYAML; lightweight metadata
validation was performed directly.

### Nullable type providers (2026-11-03)

The user requested missing nullable type providers such as `int?::max`.
`CNullable.providers` now delegates payload providers supporting TYPE receivers
through a validated, type-only `ProviderResult.NullableType` recipe. Numeric
min/max return the exact plain payload type, including fixed widths, unsignedness,
usize/isize and finite float bounds. Nullable array types with known outer extents
expose size as usize. Nullable-owned type metadata and full scalar storage sizes
take precedence. No instance or nullable payload is evaluated for these queries.

Unproven nullable value payload providers still require presence. Unsupported
receiver forms now use the normal invalid-receiver diagnostic, unknown names
remain unknown providers, and the blanket nullable-type-provider C3060 is removed.
Numeric constant facts are forwarded by recipes so compile-time integer divisor
checks and range checks retain their ordinary behavior through the adapter.

The [provider contract](../../cloth-compiler-data/references/nullable-providers.md),
compiler-data and nullability skills own current behavior and source routing.
Runnable example: [NullableTypeProviders.co](../../../../examples/NullableTypeProviders.co).
`NullableTypeProvidersTest` checks exact constants/types, defaults and overloads,
array counts, nullable metadata/storage, valid range constants, strict receiver
errors and the single-diagnostic executable/object CLI matrix on Windows x64/x86.
Its mixed fixture verifies LLVM IR and emits objects across all nine profiles;
native Linux/macOS execution is not claimed. The focused provider/nullable/metadata/
range suites passed. The final
`.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon` run passed
all 1,013 tests across 76 suites, with zero failures, errors or skips. Skill
frontmatter, 342 local links across 21 changed Markdown files and
`git diff --check` passed separate checks. The optional skill validator remains
unavailable because its Python runtime lacks PyYAML.

### Nullable row implementation (2026-11-03)

Nullable rows now execute under the confirmed Cloth rectangular contract, with
C# marker placement at every nullable array level and the existing C extent order.
`int[]?[]` permits null rows and `int[]?[]?` also permits a null outer array.
All-null rows provide no inner shape witness; first presence fixes unknown axes,
present empty rows witness zero, and null does not reset retained dimensions.
Known literal mismatches are compile-time errors; dynamic mismatches take the
existing first-error runtime path. Jagged rows remain unsupported.

Only array trees containing nullable rows gain a shared shape-vector pointer in
their internal descriptors. Owned descendants use depth-offset slices of the
same constraints; independent array copies and parameters have independent vectors.
Binding replacement retains constraints even if every row is absent. Return
copying moves both metadata and nested string/array data into the destination
arena before callee cleanup. Read/validation paths do not publish shape changes
into borrowed storage. Nullable row text writes directly into the existing
two-pass recursive output buffer.

The [row record](../../cloth-arrays/references/nullable-rows.md) owns source
routing, invariants and verification; the array/nullability/lifetime/optional/
control-flow/output skills link it. The maintained example is
[NullableRows.co](../../../../examples/NullableRows.co).
`NullableRowsTest` executes all-null initialization, first-presence and retained
constraints, nested sibling writes, optional defaults, source-order snapshots,
independent copies, returned arrays, explicit/zero extents, metadata, foreach
and Unicode output on Windows x64/x86. Its mixed fixture verifies LLVM IR and
emits objects across all nine supported target profiles. Native Linux/macOS
execution is not claimed. The poisoned-memory cleanup probe also passes for
returned shape metadata and nested Unicode buffers, with balanced allocations.
The affected nullable/optional/array/lifetime suites passed. The full
`.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon` run passed
all 1,007 tests across 75 suites with zero failures, errors or skips. Skill
frontmatter, 317 local links across 18 changed Markdown files and
`git diff --check` passed separate checks. The optional Python skill validator
remains unavailable because its runtime lacks PyYAML.

### Nullable rectangular row decision (2026-11-03)

After reviewing the difference between C# jagged rows and Cloth rectangular
arrays, the user selected Cloth rectangular rows. Null rows supply no shape
witness; the first present row fixes unknown inner dimensions, all present rows
must match recursively, and null never resets established constraints. Explicit
extents apply before presence; present empty rows witness length zero.
The [array contract](../../cloth-arrays/references/semantics.md#nullable-rectangular-rows)
owns the decision. C# marker placement remains accepted. This is a design record;
nullable-row implementation is still pending behind C3060.

### Nullable strings and arrays (2026-11-03)

The user requested nullable buffer execution for `opt<string[]?> args ?: null`,
selected C# nullability marker placement, and kept nullable rows gated. Strings,
whole arrays and nullable scalar/string elements now execute through ordinary
bindings/calls/defaults, multiple returns, deferred blocks, branches, Elvis,
exact nullable casts, metadata, recursive output and explicitly typed foreach.
`int[][]?` wraps the whole array; `int[]?[]` retains C3060. Cloth's existing
rectangular dimensions, explicit nullable destinations and independent values
remain in force. Main accepts a nullable `string[]?`; startup supplies a present
validated array even when empty and does not select an omission default.

Presence branches guard recursive copies, transfers and discarded-result shape
checks. Nullable array shape storage survives null and captures unspecified
dimensions on first presence. Array elements encode/decode byte presence flags;
nullable strings use content equality and recursive array formatting renders
absence as `null`. Nullable wrappers conservatively copy without acquiring
fresh-array ownership. The first-error diagnostic policy is unchanged.

The [nullability skill](../../cloth-nullability/SKILL.md) and
[buffer implementation record](../../cloth-nullability/references/buffers.md)
own the contract, source routing and boundaries. The array, lifetime, optional,
output, control-flow and entry skills link that record. Maintained runnable
example: [NullableBuffers.co](../../../../examples/NullableBuffers.co).

Verification: `NullableBuffersTest` runs mixed buffer carriers and the example
on Windows x64/x86 and verifies LLVM IR across all nine supported target profiles.
Its cases cover nullable element storage, recursive independent copies and
returns, source-order snapshots, defaults/Elvis, shape retention, discards,
foreach, startup Unicode/empty arrays, strict diagnostics and nullable-row gates.
`ArrayTemporaryCleanupTest` adds actual allocation/live-byte observation and
poisoned freed buffers for nullable string/array returns and absent defaults.
The focused nullable/optional/cleanup run passed, as did the final focused
entry/example run (41 tests). The final
`.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon` run passed
all 1,000 tests across 74 suites, with zero failures, errors or skips.
Native execution covers Windows x64/x86; the nine-profile buffer verification
is LLVM IR evidence, not native Linux/macOS execution. The Fib fixture's default
now uses null, while its existing added output loop is preserved and reflected
in the expected output; the test drains that larger output concurrently.
The standard skill validator could not run because Python lacks PyYAML;
frontmatter and 288 local links across 16 changed Markdown files passed separate
checks. `git diff --check` passed.

### Unbraced conditional bodies (2026-11-03)

`IfParser` now accepts one source expression/statement instead of a braced
block for every `if`, `else if` and `else` alternative. It reuses the existing
branch statement lists, scopes, completion analysis and emission. Immediate
`else` is also a bare-return boundary in `Parser`. Nested chains preserve
nearest-`if` ownership, including through loop bodies, and semicolons still
cannot intervene before `else`. The [control-flow skill](../../cloth-control-flow/SKILL.md)
and [confirmed syntax](../../cloth-control-flow/references/semantics.md#conditional-expressions-and-statements)
own the contract. [Conditionals.co](../../../../examples/Conditionals.co) now
demonstrates expressions, assignments and returns without braces.

Verification on Windows:

- `:Compiler:test --tests cloth.UnbracedConditionalsTest --tests
  cloth.ConditionalsTest --tests cloth.ForParsingTest --tests cloth.WhileTest
  --tests cloth.SemicolonsTest --tests cloth.FunctionReturnsTest --offline
  --console=plain --no-daemon` passed all 101 tests with no failures/errors/skips.
- Nine new tests cover the user's exact syntax while preserving return-type
  rejection, mixed bodies, comment/newline attachment, dangling `else`,
  definitions and broadcast expansion, assignments/updates/indexed writes,
  bare and multiple returns, scopes, skipped-code checks, missing bodies,
  first-error locations, CRLF spans and following-statement boundaries.
  A native program combines value/return chains, side effects and loop jumps
  and exits with the expected status 42. Parser behavior is target-independent;
  this change adds no claim of native Linux/macOS execution.
- The complete `:Compiler:test --offline --console=plain --no-daemon` regression
  run passed all 990 tests with no failures/errors/skips in 3m 46s, including
  CLI compilation and native execution of the updated conditional example.
- The skill-creator validator was attempted with the default and bundled
  Python runtimes; both lack PyYAML. Frontmatter, example syntax and relative
  source/reference links were checked manually. `git diff --check` passed.

### One-click example execution (2026-11-02)

Added `:Compiler:runExample` under Gradle's `application` group for IntelliJ and
command-line development testing. No source or target argument is required:
`CompilerSettings.getSourceFile()` selects the default example and `runCompiler`
retains native ABI selection. Optional `--args` supports ordinary compiler
arguments. The development-only Kotlin launcher is compiled in a separate source
set associated with main, uses normal source/output naming and ABI executable
extensions, and starts the native program only after successful compilation.
It forwards console streams and the native exit code; nonzero program completion
fails JavaExec. Object output is rejected without execution. The production
compiler entry point and its compile-only behavior are unchanged. Current usage,
ownership and invariants live in [cloth-toolchains](../../cloth-toolchains/SKILL.md#one-click-example-execution).

Verification on Windows x64:

- `:Compiler:compileDevelopmentKotlin` and `:Compiler:help --task runExample`
  succeeded using the existing offline Gradle cache.
- Temporary isolated fixtures verified no-argument default-source compilation and
  native execution (`PEC: 0`), compilation failure with an already-built binary
  left unexecuted (one C3020), source/output paths containing spaces, native exit
  7 reaching JavaExec (`PEC: 7`), regular `:Compiler:run` remaining compile-only,
  and object-output rejection. The default-source fixture mirrored the current
  settings path in an isolated working directory; no example source was changed
  and no default filename was pinned in committed tests.
- `:Compiler:test --tests cloth.CompilerSettingsTest --tests cloth.DiagnosticsTest
  --tests cloth.ProgramEntryTest` passed all 40 tests with zero failures/errors/
  skips. `:Compiler:jar` succeeded and its entries exclude the development launcher.
- The smoke executable's PE machine is `0x8664` (x64). Linux/macOS native execution
  and cross-target execution were not tested. The launcher uses portable JVM
  process APIs and existing ABI executable naming.
- The updated toolchain skill passed `quick_validate.py` with cached PyYAML.
  Temporary verification scripts, fixtures, binaries and validation dependencies
  were removed afterward.

### Shared compiler driver and run command (2026-11-06)

The CLI's build and run modes share `compileArtifact(CompilerSettings)`. The
driver returns the actual emitted path. `cloth run` separates program
arguments at `--`, validates executable mode before compilation and starts
the returned path with inherited console streams. The Gradle `runExample`
task now invokes the same command. [Build and run contract](../../cloth-toolchains/SKILL.md#build-and-run-commands)

On Windows, `ProgramEntryTest`, `DiagnosticsTest`, `CompilerSettingsTest`,
`NativeCABITest` and `ToolchainResolverTest` passed 57 tests in the focused
run. The new regressions check a spaced output path, Unicode and literal
arguments, exit status 7, stale-binary prevention, and object rejection before
output creation. `:Compiler:run` built and ran `MultiReturns.co` on x64 with
`Cloth: 8` and `PEC: 0`. `:Compiler:runExample` built and ran `Functions.co`
on x86 with `PEC: 0`. The smoke artifacts' PE machine fields were `0x8664`
and `0x014C`. Native launcher verification covers Windows x64 and x86.
The full `:Compiler:test --offline --console=plain --no-daemon` run passed
1,434 tests in 127 suites with zero failures, errors or skips. Skill metadata
and changed links were checked directly because the validator requires PyYAML.

### Production path test pilot (2026-11-06)

`BindingsTest`, `AssignmentOperatorsTest` and `ConditionalsTest` wrap body
fixtures in complete Cloth functions. Syntax tests inspect parsed statements,
semantic tests use `FunctionAnalyzer` and IR tests use declaration module
emission. Native fixtures use `compileArtifact`, observe values with explicit
output calls and assert the real completion report. The same process runner
serves the existing struct fixtures. [Program fixtures](../../../../Compiler/src/test/kotlin/cloth/ProgramFixtures.kt)

The migrated expectations use the production diagnostic `C3020` for unknown
names. The type-check fixtures exercise `1 is void` producing `false`
and reject the invalid `void?` target. The negative integer power case checks
the fatal exit without a completion report. Compiler semantics were preserved.

On Windows x64, all 74 tests in the three pilot suites passed, together with
the four `StructEmissionTest` cases. The struct native fixtures also ran on
Windows x86.
The focused Gradle run used `:Compiler:test` with the four named `--tests`
filters and `--offline --console=plain --no-daemon`.
The full `:Compiler:test --offline --console=plain --no-daemon` run passed
1,434 tests in 127 suites with zero failures, errors or skips.

### Production path reception tests (2026-11-06)

`MultiAssignmentTest` folds the old snippet-only mutability check into the
production target checks. Each immutable-target diagnostic locates both the
declaration and the assignment. `DestructuringTest` checks positional plans
for declarations, assignments and discards inside one function. Its metadata
swap case verifies that a binding holding string metadata after the swap
cannot expose an array element type. The native fixture observes the
received values and `PEC`, then compiles the same body as an object.
[Multi-assignment tests](../../../../Compiler/src/test/kotlin/cloth/MultiAssignmentTest.kt) ·
[Destructuring tests](../../../../Compiler/src/test/kotlin/cloth/DestructuringTest.kt)

The focused `:Compiler:test` run passed 97 tests in eight suites with zero
failures, errors or skips. Filters covered `MultiAssignmentTest`,
`DestructuringTest`, `MultiBindingsTest` and `cloth.MultiReturn*Test` with
`--offline --console=plain --no-daemon`. Coverage included Windows x64/x86
execution and verified multi-result IR for all nine profiles.

### Production path fixture migration (2026-11-06)

The compiler API cleanup's second stage moved the remaining snippet and
call-only fixtures to complete source units. Parser checks inspect real
function bodies. Semantic checks use `FunctionAnalyzer`, and
`DeclarationAnalyzer` serves tests of scope and name resolution.
Native body fixtures use the compile driver, explicit `print` or `println`,
and exact UTF-8 output with the normal process completion report. Runtime
failure cases check the actual status and diagnostic. ABI and fault injection
tests can still compose LLVM modules directly.
[Compiler fixture guidance](../SKILL.md#compiler-fixtures)

Removed `ASTStructure`, `parseSnippet`, `SemanticAnalyzer` and the snippet
emitter and printer. `FunctionAnalyzer` now owns full call and body checking.
Operators without declared results infer a return type before the ordinary
body and return checks.
Binding storage and lowering take `BindingSymbol` throughout. The migrated
fixtures use C3020 for unresolved names and check production diagnostics with
their source labels. C3000, C3060, C3077 and C3086 remain reserved.
[Function analyzer](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/FunctionAnalyzer.kt) ·
[Binding storage](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitBindings.kt)

The full `.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon`
run passed 1,431 tests in 127 suites with zero failures, errors or skips.
The three removed cases covered retired snippet or call-only behavior.
The suite ran native Windows x64 and x86 fixtures. Portable fixtures such as
`WhileTest` also verified IR and object emission for all nine supported targets.
A final focused run passed all 18 tests in `StringAttachmentsTest` and
`NativeCABITest` after renaming a fixture and retaining the cast guard's
`puts` ABI assertion. Skill metadata and changed links were checked directly.

### Semantic facts and overload trials (2026-11-06)

`FunctionAnalyzer` validates declared contracts and runs the semantic passes.
`BodyTyping` checks bodies, and the call and operator resolvers own their
separate ranking rules. `ResultShape` owns result facts. Scalar queries derive
from the stored shape. Trial snapshots use identity-keyed tables registered
at their declarations. A trial publishes to its parent, including any nested
call choices, inferred operator signatures and completed width specializations.
Each width gets its own expression facts even when the syntax is shared.
[Body facts and overload trials](../../cloth-functions/SKILL.md#body-facts-and-overload-trials)

The specialization regression reproduced an abandoned width appearing in the
emitter's callable list. The fixed case checks nested commits, distinct widths,
and a recursive annotated context. The bare value-target `is` fixture checks
operand identities before typing and the selected receiver after typing.
The flow passes share eager operand order and keep their own continuation and
effect rules. Metadata slots use resolved binding identities for declarations,
grouped targets, assignments and loop bindings.
[Operand contexts](operand-contexts.md) ·
[Eager operands](../../../../Compiler/src/main/kotlin/cloth/semantic/flow/ExpressionOperands.kt)

The focused overload and ordered-result run passed 85 tests in six suites.
The full `.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon`
run passed 1,433 tests in 127 suites with zero failures, errors or skips.
`OperandContextTest` ran native Windows x64 and x86 fixtures and verified IR
and object emission for all nine supported targets. The full run also covered
nullable proofs, metadata, class lifetimes, temporary cleanup and loop completion.
Skill metadata and changed links were checked directly.

The overload benchmark reused one parsed unit with 100 calls to four overloads.
Each call contains an `if` argument with a nested call that requires a wider
literal context. On OpenJDK 25 with a 512 MiB heap, 12 warmups and 15 samples,
median thread allocation fell from 122,635,104 to 78,945,408 bytes per analysis.
Median elapsed analysis time fell from 36.15 to 21.82 ms in those local runs.
The samples measure the complete declaration and body analysis of that unit.
Trial storage uses eager copies.

### Shared value transfer and text recipes (2026-11-06)

Bindings, rows and class fields use `EmitArray.constrain` to apply declared
extents or merge retained dimensions. Bindings retain live shape descriptors.
Rows in nullable-row arrays share their parent's shape vector. Nullable fields
preserve a witness across null and arena replacement. `EmitValueTransfer` owns
frame snapshots and one-use freshness for arrays and result carriers. Class field storage
owns allocation replacement and releases superseded buffers after copying.
[Shape operation](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitArray.kt) ·
[Value transfer](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitValueTransfer.kt)

`EmitPlaces` saves indices before the source runs and replays destinations
whose storage can move. Grouped declarations and assignments evaluate a
producer once and prepare positional results before publishing stores.
Broadcast stores retain their source order. The field shape fixture checks
a known extent error before emission, a valid replacement after null, and
a runtime mismatch that stops after the preceding output.
[Place lowering](../../../../Compiler/src/main/kotlin/cloth/emitter/storage/EmitPlaces.kt) ·
[Grouped reception](../../../../Compiler/src/main/kotlin/cloth/emitter/types/EmitStatement.kt)

`implicitText` binds print arguments, interpolation parts and array elements.
Interpolation keeps its recipe in the semantic model. All three paths
use `NullableText` for optional values, while explicit `::str` uses source
receiver checks. Nested array rows write directly into the shared buffer,
and effectful leaf formatters run once before the writing pass.
[Output contract](../../cloth-output/SKILL.md) ·
[Recipe regression](../../../../Compiler/src/test/kotlin/cloth/BuiltinFunctionsTest.kt)

The transfer and replay run passed 178 tests in 10 suites. The formatting
run passed 75 tests in nine suites. The focused runs exercised Windows x64
and x86 binaries and verified IR and object emission for all nine supported
targets. The full `.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon`
run passed 1,434 tests in 127 suites with zero failures, errors or skips.
Skill metadata and changed links were checked directly.

### Compiler package ownership and target handoff (2026-11-06)

Declaration resolution and named types live in `semantic.declarations` beside
the existing `semantic.symbols` identities. Body and call typing, candidate
trials and operator specialization live in `semantic.typing`. Completion,
presence, metadata and class lifetimes live in `semantic.flow`. Constructor
initializer operands come from the declaration model and feed both presence
and metadata analysis. Shared plans and program entry validation live in
`semantic`.

The backend groups frames, arguments, results and entry adapters in
`emitter.callables`. Places, bindings, array shapes, copies and arenas live in
`emitter.storage`. Provider emission, metadata materialization, formatting
and string operations live in `emitter.text`. These packages organize
imports within the existing Compiler module. The owning feature skills
link to the moved sources.
[Package ownership](../SKILL.md#compiler-package-ownership)

The shared driver resolves the ABI, then passes its layout to the program
analyzer and hands the checked model to the emitter. Semantic analysis,
provider lookup, built-in binding and LLVM type lowering take an explicit
layout. Tests can inject synthetic layouts or use the selected native ABI's
layout. The emitter checks agreement between the analyzed layout and its ABI.
[Driver](../../../../Compiler/src/main/kotlin/cloth/CompilerDriver.kt) ·
[Target layout](../../../../Compiler/src/main/kotlin/cloth/target/TargetLayout.kt)

The focused run passed 45 tests in `TargetLayoutTest`, `CompilerSettingsTest`,
`ProgramEntryTest` and `WindowsX86Test`. The driver regression rejects a
`usize` literal of 4294967296 on x86 before creating an output directory,
then emits an x64 object from the same source.
[Target regression](../../../../Compiler/src/test/kotlin/cloth/TargetLayoutTest.kt)

The full `.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon`
run passed 1,435 tests in 127 suites with zero failures, errors or skips.
The publication and x86 suites verified IR and objects for all nine supported
profiles and ran native binaries on Windows x64 and x86. The updated skill
metadata and links passed direct checks. The bundled validator requires
PyYAML, which was unavailable in the test environment.
[Operator publication suite](../../../../Compiler/src/test/kotlin/cloth/OperatorPublicationTest.kt)

### Shared private call ABI (2026-11-06)

`EmittedCallable` owns the declared LLVM signature and private call slots for
each Cloth symbol. Its body reads receiver or allocation fields, parameter
values, the caller's result arena and omission flags from that layout. Calls
use the same layout to pack evaluated operands and delegate ABI encoding and
decoding to `EmitResults`. `EmitFunctions` keeps receiver checks, written-order
argument evaluation, snapshots and the declaration-order default prelude.
[Callable layout](../../../../Compiler/src/main/kotlin/cloth/emitter/callables/EmittedCallable.kt) ·
[Function emitter](../../../../Compiler/src/main/kotlin/cloth/emitter/callables/EmitFunctions.kt)

Startup uses the shared packer for the call to Cloth `main`. Enum payload and
text readers, generated destructor and class formatter dispatch, and comparison
overrides use it for their calls to Cloth symbols. The startup adapter converts
arguments, owns its result arena and normalizes the exit status after the call.
[Entry adapter](../../../../Compiler/src/main/kotlin/cloth/emitter/callables/EmitEntryPoint.kt) ·
[Optional arguments example](../../../../examples/OptionalArguments.co)

The focused run passed 125 tests in 14 suites. The wide-main fixture covers a
present optional argument, caller-owned wide result and native status
normalization together. Struct, class and operator publication suites verify
IR and emit objects for all nine supported profiles, with native execution on
Windows x64 and x86.
[Wide-main test](../../../../Compiler/src/test/kotlin/cloth/ArbitraryIntegerStorageTest.kt) ·
[Operator publication](../../../../Compiler/src/test/kotlin/cloth/OperatorPublicationTest.kt)

The full `.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon`
run passed 1,435 tests in 127 suites with zero failures, errors or skips.
Skill metadata and links passed direct checks. The bundled validator requires
PyYAML, which was unavailable in the test environment.

### Compiler API audit closeout (2026-11-06)

Executable emission validates the semantic target and callable ABI before
discovering the linker or creating output. Executable IR and native binaries
share startup emission and final module verification. Both IR text paths use
one helper that releases the LLVM message. The target regression supplies a
mismatched model and a missing linker and checks the target diagnostic and
the absence of an output directory.
[LLVM emitter](../../../../Compiler/src/main/kotlin/cloth/emitter/LLVM.kt) ·
[Target regression](../../../../Compiler/src/test/kotlin/cloth/TargetLayoutTest.kt)

The test-only `ArrayExtent` recipe and its raw 64-bit sentinel are retired.
The source-size tests exercise `ArraySize` for runtime and fixed outer
counts and check fixed receiver effects. Descriptor tests check the inner
known flag across native calls and distinguish unknown, zero and maximum
extents. A projected row exposes the preserved maximum through `ArraySize`
at both 32 and 64 bits.
[Size contract](../../cloth-compiler-data/SKILL.md) ·
[Buffer counts](../../../../Compiler/src/test/kotlin/cloth/BufferCountsTest.kt)

C4030 identifies the rejected Cloth callable type at its annotation.
Declaration resolution owns the C3053 check for grouped deferred blocks.
The focused run passed 99 tests in 10 suites, including the diagnostic,
target, array descriptor and provider checks.
[Diagnostics](../../cloth-diagnostics/SKILL.md) ·
[Result boundaries](../../cloth-multiple-results/SKILL.md)

The full `.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon`
run passed 1,436 tests in 127 suites with zero failures, errors or skips.
The publication suites verified IR and emitted objects for all nine supported
targets and exercised native Windows x64 and x86 binaries. Skill headers and
links passed direct checks because the bundled validator requires PyYAML.
[Operator publication](../../../../Compiler/src/test/kotlin/cloth/OperatorPublicationTest.kt)

### Enum case name provider (2026-11-06)

Enum value `::name` returns the selected case's exact declared spelling as
non-nullable `string`. A nullable receiver requires a presence proof.
The provider evaluates its receiver once and uses the generated case-name
reader, which also supplies default enum text. A custom `operator ::str()`
owns presentation text, and payload `.name` access uses the declared field.
[Enum contract](value-operators.md#enum-values-and-formatting)

`CEnum` registers an `EnumName` recipe with value-receiver access.
`DeclarationAnalyzer` creates the name reader for every enum.
`DeclarationModel` selects the reader for name access and default text,
while formatter effects follow the selected user override. Reader calls use
the shared Cloth callable ABI and caller-owned text storage.
[Enum model](../../../../Compiler/src/main/kotlin/cloth/semantic/declarations/EnumModel.kt) ·
[Provider recipe](../../../../Compiler/src/main/kotlin/cloth/data/ProviderResult.kt)

The edited main and test sources compiled with cached Kotlin 2.4.20 and JDK 25.
The 64-test focused run passed through the JUnit launcher, including
`EnumNameTest`, value operators, operator publication and contexts, class
formatting, optional arguments, provider syntax and provider results.
The new fixture executed on Windows x64 and x86 and verified IR and objects
for all nine supported targets. Gradle's daemon connection was unavailable
in the execution sandbox.
[Case-name tests](../../../../Compiler/src/test/kotlin/cloth/EnumNameTest.kt)

The full `.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon`
run passed 1,440 tests in 128 suites with zero failures, errors or skips after
granting the local cache, daemon and native filesystem access required by the
test environment. CLI `run` checks on Windows x64 and x86 printed `READY`
for `State.READY::name` and `ready to work` for its custom `::str`,
then completed with status zero. Updated skill headers and links passed direct
checks because the bundled validator requires unavailable PyYAML.

### Nullable array conditional contexts (2026-11-06)

The user confirmed keeping both enum `::name` and `::str` and requested fixing
the audit's nullable array payload defect. Explicit `int?[2]` payloads now work
with multiple cases and header defaults, as do ordinary function conditionals
whose declared destinations contain nullable elements or rows.
[Enum payload contract](value-operators.md#enum-values-and-formatting) ·
[Nullable buffers](../../cloth-nullability/references/buffers.md)

`ExpressionTyping.conditionalResults` uses `ValueType.containsNullability` for
the explicit destination as well as each branch value. An array's nullable
element or row annotation supplies the context without an outer `CNullable`
wrapper. The shared rule serves generated enum readers, returns, arguments,
bindings, assignments, broadcast and positional results. Missing nullable
annotations, incompatible element layouts/types and invalid dimensions still
fail through the existing first-error checks.
[Conditional typing](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/ExpressionTyping.kt)

Before the fix, the new ordinary-return regression and both enum payload tests
failed with the audit's C3061. After the fix, the focused Gradle run passed
80 tests in 10 suites with no failures, errors or skips: `EnumPayloadTest`,
`EnumNameTest`, `ValueOperatorsTest`, all `Nullability*` suites,
`NullableBuffersTest`, `NullableRowsTest` and `ConditionalsTest`.
The payload fixture executed on Windows x64 and x86 and verified IR and objects
for all nine supported targets. It checks single and multiple cases, defaults,
nullable whole arrays/elements/rows, Unicode, varying unsized dimensions and
independent payload reads. The typing regressions cover explicit contexts and
preserve rejection of inferred nullability and invalid element types/shapes.
[Payload tests](../../../../Compiler/src/test/kotlin/cloth/EnumPayloadTest.kt) ·
[Typing tests](../../../../Compiler/src/test/kotlin/cloth/NullabilityTypingTest.kt)

The full `.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon`
run passed 1,444 tests in 129 suites with zero failures, errors or skips.
Two skill headers and 32 added relative links and anchors passed direct checks;
the bundled skill validator could not run because PyYAML is unavailable.

### Enum case enumeration provider (2026-11-06)

The user requested enum-specific `EnumType::values` after confirming type-only
access, a fixed-size array of all cases in declaration order, and ordinary
independent array semantics. The result contains nominal enum values, with the
exact enum element type and declared case count. Custom equality does not merge
cases, and enumeration does not execute custom formatting.
[Enum contract](value-operators.md#enum-values-and-formatting)

`CEnum` registers `ProviderResult.EnumValues` under the contextual `values`
spelling. The typed recipe permits type receivers and materializes the declared
case ordinals through `ProviderEmissionContext.arrayLiteral` and the existing
`EmitArray.literal`. Shared provider validation handles receiver forms; ordinary
array copying, argument/result transport and foreach snapshots handle the result.
Enum instances and non-enum types reject the provider. The provider is read-only
and cannot be overridden; ordinary payload `.values` access remains distinct.
[Provider recipe](../../../../Compiler/src/main/kotlin/cloth/data/ProviderResult.kt) ·
[Array service](../../../../Compiler/src/main/kotlin/cloth/emitter/text/ProviderEmissionContext.kt)

The focused Gradle run passed 49 tests across seven suites: `EnumValuesTest`,
`EnumNameTest`, `EnumPayloadTest`, `ValueOperatorsTest`, `ProviderResultsTest`,
`ProviderSyntaxTest` and `BufferProvidersTest`. The four new tests check exact
typing, declaration order, independent reads/copies, foreach snapshots under
source mutation, returned/defaulted/forwarded arrays, multiple results, metadata,
single-case enums, custom equality/formatting, payload naming and 18 rejection
cases with located first-error diagnostics. The fixture executed on Windows
x64 and x86 and verified IR and objects for all nine supported profiles.
[Enumeration tests](../../../../Compiler/src/test/kotlin/cloth/EnumValuesTest.kt)

The follow-up focused run including the current `StringSplitTest` passed
59 tests across eight suites. The full
`.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon` run passed
1,459 tests in 131 suites with zero failures, errors or skips. Three updated
skill headers, 49 added relative links and anchors, and the provider-plan link
passed direct checks. The bundled skill validator requires unavailable PyYAML.

### Enum payload diagnostic preflight (2026-11-06)

The user requested consistent payload diagnostics regardless of enum case count.
Payload field-type mismatches now report C3095 with the original value, field
annotation and applicable case locations. Header-default mismatches identify
the default and its field without attributing it to a synthetic case. Specific
literal, cast, array and struct errors retain their shared checker diagnostics.
[Enum contract](value-operators.md#enum-values-and-formatting)

`FunctionAnalyzer` invokes `checkEnumPayloads` after binding/presence setup and
before checking callable defaults and bodies. The focused typing helper checks
header defaults in field order, then supplied values in case/argument order,
using `BodyTyping` and each field's declared type. It validates defaults even
when every case overrides them. Generated readers continue through the ordinary
body checker. Constant-form restrictions and numeric cast evaluation policy
remain under the separate open enum audit question.
[Payload typing](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/EnumPayloadTyping.kt) ·
[Structured diagnostic](../../../../Compiler/src/main/kotlin/cloth/diagnostics/SemanticErrors.kt)

Before the fix, payload mismatches could report C3032 for a single-value reader
or C3016 for a conditional reader. Five of the seven new tests reproduced the
reader-dependent failures; valid contextual payloads and intrinsic failures
already passed. After the fix, the focused Gradle run passed
49 tests across eight suites: `EnumPayloadDiagnosticsTest`, `EnumPayloadTest`,
`EnumNameTest`, `EnumValuesTest`, `ValueOperatorsTest`, `DiagnosticsTest`,
`StructSemanticsTest` and `NullabilityTypingTest`. The new regressions cover
single/multiple cases, defaults, exact types, original Unicode/CRLF labels,
first-error ordering, and intrinsic literal/cast/shape/struct errors. Valid
numeric, nullable array and aggregate payloads type-check for Windows x64/x86.
The 18 CLI rejection checks select all nine supported targets and stop before
output creation or linker discovery. Existing enum fixtures executed on native
Windows x64/x86 and verified IR and objects for all nine target profiles.
[Diagnostic tests](../../../../Compiler/src/test/kotlin/cloth/EnumPayloadDiagnosticsTest.kt)

The full `.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon`
run passed 1,470 tests in 132 suites with zero failures, errors or skips. Two
skill headers and 55 added relative links and anchors passed direct checks;
the bundled validator requires unavailable PyYAML.

### Enum constant cast validation (2026-11-07)

The user requested compile-time rejection of invalid enum payload casts.
The existing literal, literal-unary, cast, array and struct forms now validate
their closed numeric conversions before emission, including unread cases and
defaults overridden by every case. C3005 labels the original failing cast,
field annotation and applicable case. Broader arithmetic/reference/call payload
forms remain outside this integration.
[Enum contract](value-operators.md#enum-values-and-formatting)

`EnumPayloadTyping` checks typed constant values before final field compatibility.
`EnumConstantExpressions` visits admitted forms in shared `eagerOperands` order;
`EnumModel` reuses that traversal for its form check. Numeric facts live in the
reusable `NumericConstants` evaluator, extracted from `RangeConstants`. The
range/attachment facade retains ordinary non-nullable result boundaries.
Constant evaluation never executes calls or infers bindings. Generated readers
continue through ordinary emission, copying and runtime guard lowering.
[Enum validation](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/EnumConstantExpressions.kt) ·
[Numeric evaluator](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/NumericConstants.kt)

Float-to-integer checks require a finite value whose exact truncated result fits
the selected integer type, including widths through 65536 and target-sized
identities. Evaluation rounds each intermediate float conversion, preserves
integer wrapping/source-signedness widening and uses exact binary-to-decimal
conversion for wide integer results. Nullable absence skips conversion; known
present nullable values receive the same checks. Floating overflow to infinity
remains a valid floating result; a later integer conversion rejects it.
[Numeric contract](../../cloth-numerics/references/semantics.md#explicit-numeric-casts)

Focused runs passed 70 tests in the enum constant, payload diagnostic, cast,
range and float-attachment suites, then 14 tests in the new numeric evaluator
and integer-attachment suites. After sharing operand traversal, the final
20-test enum/evaluator/payload-diagnostic run also passed. Nine enum integration
tests cover invalid casts in unread cases, nested/default payloads, original
spans, nullable conversion, 32/64-bit target bounds, valid native results and
ordinary dynamic failure guards. Four evaluator tests cover exact wide results,
NaN/both infinities, signed zero, half decimal ties versus cast-source rounding,
and unknown/nullable range boundaries. Native fixtures execute on Windows x64/x86;
valid payload IR/objects verify across all nine supported profiles. The nine CLI
rejection checks stop before output creation or linker discovery.
[Enum constant tests](../../../../Compiler/src/test/kotlin/cloth/EnumConstantExpressionsTest.kt) ·
[Evaluator tests](../../../../Compiler/src/test/kotlin/cloth/NumericConstantsTest.kt)

The full `.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon`
run passed 1,483 tests in 134 suites with zero failures, errors or skips. Three
updated skill headers and 74 added relative links and anchors passed direct
checks. The bundled skill validator requires unavailable PyYAML.

### While loops (2026-11-02)

Implemented strict-bool while conditions with pre-body/repeated testing,
braced/single-statement bodies, independent body scopes and shared break/continue
targets. Continue returns to the test; break skips it. The LoopBody parser/AST
representation is shared with for loops (ForBody remains a compatibility alias).
The condition keeps its enclosing scope/jump context. Non-bool conditions use
located C3014 without adding recovery or diagnostic accumulation.

Completion distinguishes possible zero trips from simple syntax-proven true
loops; a true loop without reachable breaks cannot fall through. Nullable
analysis invalidates writes in both condition and body before checking repeated
occurrences, supplies true facts to bodies and merges false/break exits.
Metadata forgets mutable represented types before repeated tests and after loops.
EmitWhileLoop uses the shared jump/cleanup API, with independent proven condition,
body and whole-loop regions. Condition cleanup also runs on the final false test;
captured publication and outgoing buffered returns retain their existing lifetime
rules. The authoritative [while record](../../cloth-control-flow/references/while-loops.md)
captures behavior, source ownership and remaining do/while scope.

WhileTest adds 20 tests; ArrayTemporaryCleanupTest adds three poisoned-free and
live/peak-byte tests, including 20,001 condition tests and 20,000 iterations with
continued bodies and dynamic literal storage. While.co executes on native
Windows x64 and PE32/x86; one canonical AST emits verified IR and nonempty objects
for all nine supported ABI profiles. No new native Linux/macOS/Windows ARM64
execution is claimed.

```powershell
.\gradlew.bat :Compiler:test --tests cloth.WhileTest --tests cloth.LoopControlTest --tests cloth.ForParsingTest --offline --console=plain --no-daemon
.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon
```

The focused while/jump/parser run passed all 52 tests with no failures/errors/skips.
The final full compiler run passed **981 tests**, zero failures/errors/skips,
including all 20 while tests and 35 cleanup tests. Skill validation passed for
cloth-control-flow, cloth-language, cloth-buffer-lifetimes, cloth-diagnostics,
cloth-semicolons and cloth-nullability using cached PyYAML under tmp, removed
afterward. All 235 relative file links across 11 feature records resolve, including
the while milestone anchor; whitespace checks pass. README has matching local
guidance under its existing skip-worktree setting.

### Reusable loop jumps (2026-11-02)

Integrated bare break/continue statements for numeric ranges and array foreach,
including unbraced bodies, conditional expressions and snippets. Shared LoopTarget
identities, jump resolutions and completion outcomes separate fallthrough, return,
break and continue. Function/deferred-block boundaries reject escaping jumps with
located C3069. Range continues execute existing updates/guards; foreach continues
advance the snapshot index. Break skips the remaining body and update.

Explicit update blocks receive normal completion and continues. Shared emission
tracks cleanup depth and releases crossed proven arena/stack regions before each
jump, preserving foreach snapshots and conservative buffered-publication lifetime.
Nullable and metadata analyses merge all reachable continue-path facts into the
stepper. Future while/do-while can reuse this infrastructure while supplying their
own schedule and destinations; their syntax/execution and labeled jumps remain
deferred. Header expressions retain the enclosing body context, as documented in
the authoritative [loop-control record](../../cloth-control-flow/references/loop-control.md).

LoopControlTest adds 17 tests; ArrayTemporaryCleanupTest adds five native poisoned-
free/live-byte/peak regressions, including 20,000 continues and nested header
cleanup. LoopControl.co executes on Windows x64 and PE32/x86. One AST emits
verified IR and nonempty objects across all nine supported ABI profiles. No new
native Linux/macOS/Windows ARM64 execution is claimed. The earlier focused run
passed all 45 then-current jump/cleanup tests. The final full compiler run passed
**958 tests**, zero failures/errors/skips, including all 17 jump tests and all 32
cleanup tests. This includes the final expression-region cleanup regression.

```powershell
.\gradlew.bat :Compiler:test --tests cloth.LoopControlTest --tests cloth.ArrayTemporaryCleanupTest --offline --console=plain --no-daemon
.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon
```

The first full run passed 956 of 957 tests; its sole failure was an existing
ISizeIntegrationTest assertion expecting an obsolete Fibonacci banner. The
maintained Fib.co already prints the first ten values using for loops. Updated
the assertion to check those exact values through the CLI on both target widths,
without changing the example. The final full-suite command above then passed.

Skill validation passed for cloth-control-flow, cloth-language,
cloth-buffer-lifetimes, cloth-diagnostics and cloth-semicolons using cached PyYAML
extracted under tmp and removed afterward. All 233 relative file links in the ten
feature records resolve, including the new milestone anchor; whitespace checks
pass. README remains under its
existing skip-worktree setting and has matching local guidance.

### Canonical operator and keyword spellings (2026-11-02)

Applied the shared spelling rule across lexer delimiters and character checks,
parser checks and synthesized tokens, null-flow checks, numeric range constant
evaluation/emission, type and signature formatting, diagnostics, and emitted
boolean/null/array text. Text uses the canonical enum `.toString()` methods
(including Kotlin enum interpolation); identity-only range dispatch uses enums.
Boolean text lengths derive from the canonical spellings' UTF-8 bytes.

`Operators.compoundAssignmentBase()` maps the five supported compound assignments
to their arithmetic operators. Ordinary assignment lowering, semantic range
stepper expressions and range emission share this mapping instead of slicing
spellings. Original token lexemes/spans remain available for source diagnostics.
Literal source/spelling test fixtures and target/CLI syntax remain independent
of this migration. No Cloth syntax or first-error behavior changed.

Verification: the final
`.\gradlew.bat :Compiler:test --offline --console=plain --no-daemon`
run passed **936 tests**, zero failures/errors/skips, after the compound mapping
and final diagnostic replacements. This includes existing native Windows and
cross-target IR/object regressions; it establishes no additional native target
coverage. The dedicated validator and relative-link checks passed for
cloth-language and cloth-diagnostics using cached PyYAML extracted only under
`tmp/`. Whitespace checks passed; temporary review/validation files were removed.

### Loop publication and portability: Stage 5 (2026-11-02)

Published executable ForRanges.co, FloatRanges.co and ForEach.co examples with
self-checking ordinary main functions, plus README syntax/behavior guidance.
Test.co retains its intended class/state design, and Arr.co's local commented
demonstration remains intact. Existing feature skills own the integrated loop
contract, implementation and support limits; no competing loop specification
was added.

ForIntegrationTest adds six tests: examples run on Windows x64 and real PE32 x86;
x86 covers all integer counter domains, dynamic wide multiplication overflow and
finite/progress failures with preserved effect order. One AST emits verified IR
and objects for all nine supported Windows/Linux GNU/Linux musl/macOS ABI profiles.
CLI object mode accepts explicit example paths without linker discovery. No native
Linux/macOS/Windows ARM64 loop execution is claimed.

All **305 focused tests passed**, zero failures/errors/skips, including all 112
loop tests, existing x86/target/numeric/diagnostic regressions and 27 cleanup tests.
The dedicated validator passed for ten changed skills using cached PyYAML 6.0.3
in a temporary workspace target, resolving the prior validation dependency gap
without changing global Python. All **936 compiler tests passed**, zero failures,
errors or skips. All 325 relative links across 17 changed records resolve;
whitespace checks pass, and temporary validator dependencies were removed.
Stages 0-5 are complete for the agreed slice. Exact commands and the bounded
completion scope are in the
[Stage 5 record](../../cloth-control-flow/references/for-implementation.md#stage-5-regression-portability-and-feature-publication-2026-11-02).

### Array foreach and lifetime integration: Stage 4 (2026-11-02)

Implemented source capture once per entry as an independent contents/shape
snapshot, transferring fresh owned arrays or recursively copying stored values.
Foreach traverses with target usize counts and receives a fresh mutable value
per element, including independent nested rows and string bytes. Original writes,
receiver rebinding and row mutation cannot alter the snapshot or saved copies.
Existing scopes, shape constraints, source failures, empty axes and buffered
function/deferred-block returns remain intact. The C3067 gate is retired; existing
nullable-array gates remain. No general iterator or break/continue support was added.

Closed proofs now permit normal-path arena suffix release plus stack restoration
for safe iteration bodies, range step expressions and complete loops. Received
buffers are allocated after iteration checkpoints; saved sources remain outside.
Buffered/indexed writes and unrecognized/captured publication retain frame
lifetime. Returns bypass normal releases and keep existing outgoing-copy cleanup.
This establishes bounded verified workloads, not universally bounded loop memory.

All **248 focused tests passed**, including 19 new foreach tests and 11 new native
allocation/poisoning/stack probes. Probes verify one-allocation fresh source
transfer, an extra borrowed snapshot, safe row/string reclamation, saved/captured
values, early buffered returns, and 20,000-iteration literal body/step workloads
with 264-byte peak arena usage. Native execution ran on Windows x64; foreach IR
verified for i686 Windows, x86_64 Windows/Linux GNU and aarch64 macOS. No foreign
native foreach execution is claimed. The final **930-test compiler suite passed**
with no failures, errors or skips;
see [source ownership and evidence](../../cloth-control-flow/references/for-implementation.md#stage-4-array-foreach-and-lifetime-integration-2026-11-02).

### Numeric range execution: Stage 3 (2026-11-02)

Implemented saved start/end evaluation and validation, scoped counter storage,
fixed-bound membership, default `+1` and all eight explicit header updates.
Descending ranges, freely changing direction, body writes, singleton/equal bounds,
fresh runtime step effects and actual function/deferred-block returns execute.
Integer header arithmetic overflow exits before storing a wrapped value; body/RHS
arithmetic and invalid division/remainder keep their ordinary rules. Half/single/
double counters round per update with finite and unchanged-progress guards.
Closed nonfinite endpoint/step constants receive C3068, including direct decimal
half rounding; dynamic values receive located runtime failures. C3067 now gates
array foreach only, including skipped/deferred source, before backend work.

Binding allocation stays in function/snippet entry blocks. Existing buffered
copies and return cleanup remain safe; unproven allocations keep frame lifetime,
with no new general iteration-reclamation proof. Foreach snapshots, independent
elements and iteration cleanup remain Stage 4. No while/do/break/continue support
was added. Stale spelling-API tests and the commented Arr.co demonstration's parser
test were adapted without changing the example or public API.

All **203 focused tests passed**, and the additional integer-to-float rounding
regression passed separately. Loop suites now contain 87 tests, including 32 new
execution tests. The final **900-test compiler suite passed** with zero failures,
errors or skips. Native range execution ran on Windows x64; IR verified for i686 Windows,
x86_64 Windows and x86_64 Linux GNU. No new native Linux/macOS or x86 loop
execution is claimed. Full-suite results and exact source/test ownership are in
the [stage record](../../cloth-control-flow/references/for-implementation.md#stage-3-numeric-range-execution-2026-11-02).

### For-loop semantics: Stage 2 (2026-11-02)

Implemented distinct loop binding symbols and header/body scopes, surrounding-
scope source lookup, strict range/foreach inference, exact numeric identities,
received row shapes and ordinary assignment/update validation. Target-aware
ForLoopPlan facts survive overload forks/commits without changing canonical ASTs.
Completion preserves zero-trip paths and real return ownership; nullable facts
are invalidated before repeated/captured writes can create stale proofs. Mutable
represented-type metadata is conservatively forgotten across loops, while
immutable/body-local facts remain usable. Existing nullable-array gates remain.

Declaration/call/function/snippet/completion APIs now admit valid loops. C3068
labels invalid loop contracts. C3067 moved to the backend validation boundary,
after semantic checks and before LLVM/linker/output work. Numeric/foreach runtime
progression, guards, snapshots, value transfers and cleanup remain stages 3-4.
The current keyword migration was preserved, and parser/prefix regressions were
aligned with `for` as a keyword without adding while/do/break/continue execution.

All **105 focused tests** and the final **868-test compiler suite** passed with
no failures, errors or skips. Loop suites contain 55 tests, 35 added since Stage 1.
New tests perform semantic checks and backend-gate checks on Windows, including
one AST alternating 32/64/32-bit target layouts; this is not loop IR or native
iteration verification. No new native Linux/macOS loop execution is claimed.
Source ownership, commands, conservative analysis boundaries and verification
are in the [stage record](../../cloth-control-flow/references/for-implementation.md).

### For-loop parsing: Stage 1 (2026-11-02)

Implemented the confirmed range/foreach header syntax, located loop AST,
original stepper operators and braced/single-statement bodies. Range operators
remain loop-source delimiters rather than ordinary expression values. Stage 1
rejects malformed/legacy headers at the first error and deliberately gates
well-formed loops with C3067 before downstream semantic/execution/output work.

`ForParsingTest` (15 tests) and `ForStageGateTest` (5 tests), together with
`SemicolonsTest`, `FunctionParsingTest`, `DiagnosticsTest` and `ConditionalsTest`,
passed all 71 focused tests. The subsequent full compiler suite passed **833
tests**, with no failures, errors or skips. Commands, source ownership, span and
CLI/output-creation evidence are in the
[stage record](../../cloth-control-flow/references/for-implementation.md).

Parsing is target-independent and verification ran on Windows. No loop IR,
native loop execution, or new native Linux/macOS coverage is claimed. Stages 2-5
remain pending; parsed loop syntax does not establish scopes, inference, numeric
guards, foreach snapshot storage or repeated-flow correctness. Skill metadata and
links are checked directly because both inspected Python runtimes lack PyYAML
and cannot run the dedicated validator.

### Semicolon placement correction (2026-11-02)

The user clarified that optional semicolons follow valid terminator positions,
with `fn main() { ... };` explicitly rejected. The earlier broad boundary
acceptance below is superseded by this correction and the current
[semicolon skill](../../cloth-semicolons/SKILL.md).

Parser now accepts one optional terminator only for expression-bodied functions,
rejects semicolons after brace-bodied functions and standalone file-level empty
statements, and preserves body-level optional terminators. Conditional lookahead
crosses newlines only: a semicolon before `else` now fails instead of silently
joining the branches. Bindings initialized by deferred blocks or conditional
expressions still accept terminators. Existing empty statements inside function
bodies remain tolerated, without changing final branch values or source spans.

The user's array-printing source now fails at its final semicolon; removing that
character produces verified LLVM IR. `SemicolonsTest` adds exact-location
regressions for the example, function boundaries across comments/newlines,
file-level empty statements, expression-bodied conditional functions, and
semicolons splitting `else` chains. The native program with terminated returns,
bindings, calls and updates still executes with exit code 11 on Windows x64.

The focused command recorded below passed all 81 tests across the same five
suites, including twelve semicolon tests, with no failures, errors or skips.
No native Linux/macOS or other architecture execution was run. Skill validation
uses direct metadata/local-link checks because bundled Python lacks PyYAML.

### Optional semicolons (2026-11-02)

The user confirmed optional semicolons, including `return 10;`, unless a syntax
explicitly requires them. The [semicolon skill](../../cloth-semicolons/SKILL.md)
owns the contract, parser invariants and verification routing.

Parser accepts semicolons alongside newline separators in source units and body
fragments, including empty/repeated terminators, same-line declarations and
statements, bare returns, and conditional-chain separators before `else`.
ExpressionParser leaves the semicolon for its enclosing grammar rather than
rejecting it as an unsupported operator. Lists, operands and headers retain
their grammar checks; the lexer does not discard semicolons. Source spans and
final branch values remain intact, without empty AST statements. Missing-separator
help now mentions `;`, retaining the existing diagnostic code and first-error flow.

Verification: `./gradlew.bat :Compiler:test --tests cloth.SemicolonsTest --tests
cloth.FunctionParsingTest --tests cloth.FunctionReturnsTest --tests
cloth.DiagnosticsTest --tests cloth.ConditionalsTest --offline --console=plain
--no-daemon` passed all 79 tests across five suites (no failures, errors or skips).
Ten new cases cover acceptance, malformed syntax, spans, results, branch values,
snippets, first-error behavior, literals/comments, verified IR and a native
Windows x64 executable that returns 11. Linux/macOS native execution and other
architectures were not run for this parser change.
The skill validator could not run because bundled Python lacks PyYAML; direct
metadata and local-link checks passed, as did git diff --check.


### Recursive array text (2026-11-02)

The user selected recursive contents for print, interpolation and `::str`, leaving
`::addr` deferred. The [output skill](../../cloth-output/SKILL.md#recursive-array-text)
and [confirmed contract](../../cloth-output/references/semantics.md#recursive-array-text)
own the feature; array, string and compiler-data skills route to them.

`CArray.providers` registers a value-only `ProviderResult.ArrayText` when the
element supports text conversion, retaining the recursive element recipe.
`ProviderEmissionContext` delegates to `EmitArrayText` through `EmitString`;
print built-ins need no array-specific branch. Interpolation now uses the same
provider capability and emission path instead of a scalar whitelist.

Formatting uses brackets and comma-space separators, including `[]` for empty
arrays and `[[], []]` for empty rows. Scalar/metadata leaf providers retain their
existing text, with raw string/character contents including Unicode and NULs.
The implementation measures checked target-sized byte counts, then fills a single
buffer directly across nested rows. Per-leaf stack checkpoints bound temporary
numeric/character scratch. Both passes consume the already evaluated receiver;
normal buffered return handling preserves results before frame cleanup.

`ArrayTextTest` adds eight tests covering dynamic/empty/nested arrays, all supported
leaf types, shared print/interpolation/provider output, once-only evaluation,
captured writes, early returns, recursive returned strings, a 16,384-element
matrix, native Windows x86 execution and verified IR/object output on all nine
supported ABI profiles. `StringInterpolationTest` replaces the old array rejection
expectations with acceptance and native snippet coverage, retaining first-error
checks for malformed arrays. Provider/output suites reflect the new capability;
`BufferCountsTest` adds 32/64-bit helper verification and controlled measurement
overflow probes that fail before reading synthetic string payloads.

Verification:

- Final `./gradlew.bat :Compiler:test --offline --console=plain --no-daemon`
  ran 801 tests across 62 suites: 800 passed, one failed, with no errors or skips.
  The sole failure was the pre-existing `CompilerSettingsTest` default-source
  mismatch: the working-tree setting uses `examples/Arr.co`, while the test
  expects `examples/MultiReturns.co`. This unrelated setting was preserved.
  All array, provider, interpolation, output, nullable and buffer-lifetime suites
  passed, including the updated provider-capability expectation and native
  snippet cases.
- The user then clarified that the no-argument example source is only a local
  development convenience and will not be part of the production compiler.
  Removed the filename assertion from `CompilerSettingsTest`, retaining explicit
  source/output and link-option coverage. A focused rerun with `--tests
  cloth.CompilerSettingsTest` passed all three tests. The
  [toolchain skill](../../cloth-toolchains/SKILL.md) records that this filename
  must not be treated as a tested compiler contract.
- All eight `ArrayTextTest` cases and all eight `BufferCountsTest` cases passed.
  Native formatting executed on Windows x64/x86; foreign target checks verify
  IR and object emission, without claiming native Linux/macOS or AArch64 execution.
- `./gradlew.bat :Compiler:run --args="examples/ArrayPrinting.co tmp/array-printing-review"
  --offline --console=plain --no-daemon` compiled the maintained example. Running
  it printed the expected five lines and exited with PEC 0; the temporary
  executable was removed.
- The bundled skill validator cannot run because its Python lacks PyYAML.
  Direct metadata and local-link validation supplies the fallback; whitespace
  checks use `git diff --check`.

### Optional syntax final review (2026-11-02)

The final review found one parameter source-span defect: a parenthesized default
such as `opt<int> value ?: (1)` omitted its closing parenthesis from the parameter
span because the expression AST discards grouping delimiters. A new regression
reproduced this failure before the fix. Parser now captures the parameter span
through the last consumed token; the underlying type/default expression and all
omission, evaluation, overload and runtime behavior are unchanged. The
[optional skill](../../cloth-optional-arguments/SKILL.md) records this invariant.

Additional tests cover comments between modifier/wrapper/type tokens, comparisons
inside defaults, names beginning with reserved-word prefixes, EOF inside wrappers
or defaults, direct metadata/static type identity, and writes to elements of a
final optional array. Parenthesized integer, nested floating and helper-call
defaults retain their full parameter spans. Earlier malformed wrapper, modifier,
nullable/array, signature and runtime regressions remain in place.

The scan covered all 66 `.co` examples and `.agents` Markdown files: no obsolete
unbracketed optional declarations remain. One obsolete declaration sample was
removed from an older history entry while retaining its historical verification
results. Legacy forms in compiler tests are deliberate rejection cases; malformed
wrapper examples in semantic guidance are explicitly documented as rejected.

Verification:

- `./gradlew.bat :Compiler:test --tests 'cloth.OptionalArguments*' --tests
  cloth.FunctionParsingTest --tests cloth.DiagnosticsTest --offline
  --console=plain --no-daemon` passed all 58 focused tests after the span fix.
- `./gradlew.bat :Compiler:test --offline --console=plain --no-daemon` passed
  all 793 tests, including 34 optional-argument tests, with no failures,
  errors or skips. Native optional cases execute on Windows x64/x86; shared
  fixtures verify LLVM IR across the nine supported profiles. This does not
  establish native Linux/macOS execution.
- Skill metadata/scaffold checks, local documentation file links, old-syntax scans
  and `git diff --check` passed. The bundled validator was attempted but lacks
  PyYAML; direct checks supplied the fallback. Temporary review scripts were
  removed before delivery.

### Optional parameter syntax: final opt<T> (2026-11-02)

The user selected `opt<T>` / `final opt<T>` declarations while preserving the
existing omission and default semantics. Parser order is now optional `final`,
optional `opt<...>` around the complete ordinary type, parameter name, then the
required `?:` fallback for optional parameters. Numeric widths, unsignedness,
arrays and nullability stay inside the brackets. The parameter AST retains the
underlying T as its TypeReference, with separate wrapper tokens/span and a whole
span beginning at final when present. Binder, overload ranking, default evaluation
and native calling conventions are unchanged. This introduces no runtime wrapper
or new general type; old unbracketed optional declarations are deliberately rejected.

All optional test fixtures, runnable examples and current skill/plan examples
use the new syntax. The [owning skill](../../cloth-optional-arguments/SKILL.md) and
[confirmed semantics](../../cloth-optional-arguments/references/semantics.md)
document modifier order, enclosed types and rejection boundaries. Earlier dated
entries retain their historical verification results; this migration supersedes
the prior declaration spelling.

Verification:

- `./gradlew.bat :Compiler:test --tests 'cloth.OptionalArguments*' --tests
  cloth.FunctionParsingTest --offline --console=plain --no-daemon` passed all
  45 tests: 30 optional-argument and 15 function-parsing tests.
- `./gradlew.bat :Compiler:test --offline --console=plain --no-daemon` passed
  all 789 tests with zero failures, errors or skips.
- New grammar cases cover nested widths (`opt<float<64>>`), unsigned/nullable and
  rectangular array types, multiline wrappers, CRLF and exact source spans,
  malformed/empty/multiple/nested wrappers, missing defaults, misplaced final,
  rejection of old syntax and rejection outside parameters.
- Migrated emission fixtures execute on Windows x64/x86 and verify IR across
  nine target profiles. They retain named evaluation order, nullable null versus
  omission, recursive/per-call/skipped defaults, array independence, shape checks,
  returned buffer lifetime, early returns and runtime failure ordering. Foreign
  IR verification does not establish native Linux/macOS execution.
- Skill metadata/scaffold checks, local Markdown links and diff whitespace checks
  passed. The bundled validator was attempted but still lacks PyYAML; direct
  metadata/scaffold and link checks supplied the fallback.

### Optional arguments and runtime defaults (2026-11-01)

The user requested implementation of the optional/default plan and explicitly
accepted its recommended ordinary-call contract. The new
[cloth-optional-arguments skill](../../cloth-optional-arguments/SKILL.md) owns
confirmed mapping, scope, evaluation and ranking semantics, implementation
invariants and test routing. Language/function/nullable/result routing now links
there. The [plan](../../../../todo/optional-arguments-plan.md) records ordinary
function stages 0-4 and final ordinary-function verification as complete while
retaining future method/state/constructor and foreign ABI decisions separately.

Implementation includes reserved `opt`, optional parameter declarations,
structured named arguments and a shared deterministic candidate binder. The
declaration spelling was subsequently migrated as recorded in the 2026-11-02
syntax entry above. Defaults resolve/type-check in declaration scope
with read-only earlier parameter views; self/later reads, parameter writes,
caller/body-local capture and escaping returns fail before emission. Existing
strict typing and ordinary numeric/payload overload preference precede omission
counts; incomparable contextual matches remain ambiguous. C3065/C3066 identify
argument-binding/default-scope failures, retaining first-error behavior.

The selected call retains source-index placement and omission plans. Supplied
values snapshot in written order before callee entry. Internal omission flags
drive a definition-owned prelude that initializes complete parameters in order,
evaluates only omitted defaults, and never decodes omitted placeholders. This
supports direct/recursive defaults without AST expansion and keeps null distinct
from omission. Existing array transfers, return arenas, default-frame cleanup,
bool/half ABI and target-sized values are preserved. The nullable-half decoder
branches internally; default phis use its actual exit block. Nullable buffers
remain gated, built-in labels unpublished, and class/state runtimes deferred.

Verification:

- `./gradlew.bat :Compiler:test --offline --console=plain --no-daemon` passed
  784 tests with zero failures, errors or skips. This includes the initial 25
  optional-argument tests and resolves the previously preserved `opt` declaration
  failure in `MultiReturns.co`.
- After adding explicit maintained-example and dynamic-default-shape coverage,
  `./gradlew.bat :Compiler:test --tests 'cloth.OptionalArguments*' --offline
  --console=plain --no-daemon` passed all 27 tests with zero failures/errors/skips.
  Production code did not change after the successful full-suite run.
- Execution covers Windows x64 and x86: optional-before-required/mixed placement,
  source-order named effects, scalar/array snapshots, skipped/per-call defaults,
  declaration-order dependencies, recursive defaults, early supplied returns,
  failure before later defaults/body, nested deferred return boundaries, recursive
  independent default arrays, returned Unicode buffers, fixed-shape checks,
  supported scalar/nullable carriers and optional startup parameters.
- One common fixture verifies LLVM IR across Windows x64/x86/ARM64, Linux
  x64/ARM64 GNU and musl, and macOS x64/ARM64. This is not native Linux/macOS
  execution evidence.
- [OptionalArguments.co](../../../../examples/OptionalArguments.co) executes on
  Windows x64/x86. Invalid call binding returns CLI status 1 before output creation.
- Skill metadata/scaffold checks and 339 local Markdown links passed. The bundled
  skill validator was attempted but lacks PyYAML; direct metadata/scaffold and
  link validation supplied the fallback. `git diff --check` passed.


### Dedicated nullability skill (2026-11-01)

At the user's request, [cloth-nullability](../../cloth-nullability/SKILL.md) now
owns the nullable system's feature guidance. Confirmed nullable semantics and
scalar implementation evidence moved into its references; the shared semantics
index and old nullable reference paths forward there. The language, numeric,
compiler-data and control-flow skills route nullable work to the dedicated skill.
The skill covers explicit typing, proofs/captures, equality, lazy Elvis, casts,
formatting, metadata/storage, carrier invariants, tests and pending buffer stages.
This documentation reorganization changes no compiler behavior or language rule;
verification counts in the implementation record remain dated evidence.
Required skill metadata, scaffold checks, all 324 local links across project
skills/the nullable plan, and diff whitespace checks passed. The bundled skill
validator still lacks PyYAML, so those checks used a direct fallback. Compiler
tests were not rerun for this documentation-only change.

### Nullable scalar rule completion (2026-11-01)

The user requested the remaining scalar language rules and confirmed
presence-preserving nullable-target casts, static expression-type `is`, payload
metadata inspection with retained nullable identity, full nullable scalar storage
sizes, and fallback-supplied context for bare-left-null Elvis. These are now
implemented. Nullable strings/arrays/elements remain Stage 4 work. See
[the rules record](../../cloth-nullability/references/implementation.md) for confirmed contracts,
source ownership, rejection boundaries and verification commands; skill coverage
is linked from the language, numeric, compiler-data and control-flow skills.

`EmitCast` shares ordinary conversions with nullable mapping, so absent paths
skip conversions and present paths retain range/NaN guards. Metadata inspection
uses payload facts with ordinary capability guards across opaque calls.
`ProviderResult.StorageSize` queries the module's target data layout for the
complete nullable storage representation. Flow analysis preserves a binding's
declared nullable receiver for scalar `::size`, independently of payload narrowing.
Metadata analysis now visits Elvis fallbacks and merges captured-write effects.
The runnable example is [NullableRules.co](../../../../examples/NullableRules.co).

All ten follow-up tests pass, alongside the original 28 nullable tests. Native
checks cover Windows x64/x86; verified IR covers all nine supported profiles,
without claiming native Linux/macOS execution. The CLI rejection matrix now has
40 invocations, including invalid numeric casts and forbidden nullable inference.
The full compiler run completed 759 tests across 59 suites: 758 passed, with only
the same preserved unsupported `opt` sketch in MultiReturns.co failing. No user
example was moved. This milestone supersedes the scalar capability gates in the
original implementation snapshot below.

The final focused check, after enforcing the typed-source rule for bare-null
`is`, passed all 85 tests across the
four nullable suites and conditionals/metadata/casts/scalar-provider suites.
Skill metadata, 175 local links and whitespace checks passed. The optional skill
validator was unavailable because its PyYAML dependency is missing; metadata and
relative links were checked separately.

The user subsequently confirmed option 1 for the bare-null `is` question:
`null is int?` is rejected, while `(null -> int?) is int?` is true. The existing
implementation and tests already cover both forms, so this confirmation required
only semantic/skill record updates, with no compiler changes or new test run.

### Nullable scalar implementation (2026-11-01)

Original milestone; the rule-completion entry above extends this initial slice.

The bounded scalar slice in [the nullability plan](../../../../todo/nullability-plan.md)
Stages 0-3 is implemented. Nullable fixed-width/target-sized integers, floats,
bool, char and typeinfo support explicit bindings, calls/returns, multiple results,
presence checks, exact lifting, equality, guarded payload use and lazy Elvis.
Every new nullable binding needs an explicit type, including copies, result
reception and nullable conditional results. Printing/interpolation accept nullable
values and bare null, rendering absence as `null`; explicit `::str` still needs
proof or a non-null fallback.

The parser retains nullable marker placement/spans and gives Elvis its own AST
node, right associativity and precedence below logic/comparisons. `CNullable`
preserves canonical identity and native presence/payload carriers; an internal
context-free absence marker is never an inferred binding type. `NullFlowAnalysis`
computes symbol/occurrence proofs independently of overload probes, with capture
entry isolation and transitive deferred-write invalidation. `EmitNullable` guards
payload conversion, comparison and formatting. Bool payload storage stays byte
sized and nullable half ABI payloads use raw i16 fields. Calls/results/reception
preserve source snapshots and existing ordinary buffer lifetimes.

Nullable strings/arrays/elements, nullable-target casts, nullable `is` checks and
wrapper metadata inspection remain gated. Unknown wrapper metadata arriving
through opaque calls has a controlled runtime capability guard. Bare-left-null
Elvis currently requires explicit destination context. Classes, unsafe handles
and optional parameters remain separate work. The planning history below
describes the earlier state rather than current executable coverage.

All 28 new nullability tests pass across three suites (9 parsing/identity, 10
typing/flow and 9 emission/integration). Native execution covers Windows x64/x86;
the same parsed carrier fixture produces verified IR for all nine supported
profiles. Foreign IR is not native Linux/macOS execution evidence. Coverage
includes all numeric extrema/identities, nullable half/bool/char/typeinfo transport,
mixed results, snapshots, deferred effects, NaN, lazy/terminating fallbacks and
absence-aware formatting with returned string lifetime. The runnable scalar
example builds through the CLI on both widths; 28 invalid CLI invocations retain
first-error diagnostics before output or linker work.

The final full run completed 749 tests across 58 suites: 748 passed, one failed,
zero errors/skips. The remaining failure is the existing `opt` sketch appended
to `examples/MultiReturns.co`; its runnable-example integration check reports
C2000 because optional parameters are still unimplemented. The user's location
preference is pending, and that edit remains unchanged. The metadata layout
assertion was updated for the internal nullable flag; a CLI-default assertion
already stale in HEAD was aligned with the existing MultiReturns.co default.
No runtime CLI default changed. Seventy-four local documentation links and
whitespace checks passed.

### Deferred result-selection RFC (2026-11-01)

The user requested a formal [RFC](../../../../todo/result-selection-rfc.md) for
`myFunc().1` to select the first returned value for immediate use, such as
`println(myFunc().1)`. This is an unscheduled draft for future reference, not an
accepted language contract or implementation request. It proposes one-based
literal positions, exact selected types and complete call evaluation with normal
discard effects/failures. Broader producers, scalar-call selection, lexical
details and future method/class interactions remain open. Compiler code and
current supported syntax are unchanged.

### Optional-argument planning (2026-11-01)

The user requested the standalone
[optional-argument plan](../../../../todo/optional-arguments-plan.md) after the
nullability plan. It preserves `opt` with required omission-only `?:` defaults,
the intended optional-before-required `nameAndAge(25)` call, named arguments and
reuse for future methods/constructors. Detailed mapping, runtime fallback scope
and order, overload ranking and lowering remain recommendations, not confirmed
rules. All implementation stages are pending; no compiler code changed.

The source audit finds positional expression lists in call ASTs, type/name/final
metadata without defaults in ParameterDeclaration, exact argument-count filtering
in CallAnalyzer, and same-index argument/parameter emission in EmitFunctions.
The plan proposes shared binding plans that separate source evaluation order
from parameter placement, with isolated overload facts and presence-independent
omission tracking. These observations are not evidence of implemented support.

### Nullability planning (2026-11-01)

Historical design/approval record; current scalar implementation is documented
above.

The user requested [a standalone nullability plan](../../../../todo/nullability-plan.md)
before a separate plan for `opt` parameters and required `?:` fallbacks. The
accepted distinction is value absence (`T?`, including `def<string?> x = null`)
versus argument omission (`opt`); an explicitly supplied null does not trigger
an optional-parameter fallback. The plan records proposed typing, inference,
flow facts, expression Elvis, overload interaction, storage, staged work and
verification separately from confirmed decisions. All stages were pending when
the plan was written; this documentation adds no compiler support. The review
follow-up below records the subsequent Stage 0 progress.

The user subsequently confirmed that `void?` is invalid in every type position.
The plan now records this as a settled rule, including the invalid nullable-void
function example and required rejection coverage. Compiler implementation remains
pending.

The subsequent storage discussion is recorded in the plan: ordinary types are
non-nullable by default; the shared nullable model uses conceptual presence and
payload storage without separate nullable heap boxing or manual scalar cleanup.
The plan explains the 32-bit int example, possible alignment overhead, stack and
register storage, existing string/array buffer cleanup, and future class identity
and explicit destruction. Exact native layouts remain implementation choices.
This clarification adds no compiler support.

A source audit confirms that NULL, QUESTION and ELVIS token entries already
exist, but TypeReference/TypeParser do not model nullable qualifiers,
ExpressionParser does not parse null/Elvis expressions, and ValueType/TypeResolver
do not define ordinary nullable values. Existing Kotlin nullable fields and
internal allocation-null checks are not Cloth nullability. The companion
optional-argument plan now records the separately scoped call/default work.

After implementation review, the user approved tightening the nullability plan
and proceeding toward scalar implementation after Stage 0. Stage 0 contract
review began with compiler stages 1-5 still pending. The follow-up
records first-present array shape capture without reset on null, excludes mutable
capture proofs inherited from a deferred block's declaration site, and identifies
concrete array/string checks that must handle wrappers recursively before nullable
buffers are enabled. Nullable-row rectangularity remains deferred to the array
stage. The user explicitly selected `f(int)` over `f(int?)` for a proven-present
`int?` argument, without changing the binding's declared nullable type. Other
scalar contracts remain under review; no compiler code or executable coverage
changed in this follow-up.

The user then confirmed that ordinary non-nullable values cannot be compared
with null. The plan and confirmed semantics record symmetric `==`/`!=` rejection,
while retaining presence tests on declared nullable bindings after narrowing.

The user also confirmed payload inference under a presence proof: inside the
guard for an `int? x`, `def copy = x` infers `int` and stores an independent scalar
snapshot. The plan includes a safe deferred-block example using that snapshot,
alongside the rejected mutable nullable capture. Unproven copies remain nullable;
an explicit nullable annotation is retained. This remains a recorded contract,
not compiler implementation or test evidence.

Nullable-to-payload equality is also confirmed: `int? n` supports `n == 5` and
`n != 5` without a presence proof, including reversed operands. Null compares
unequal to a present operand; present values use underlying equality. Both
operands retain their normal evaluation effects. Compiler implementation remains
pending.

The literal/lifting priority is confirmed: `f(25)` selects `f(int?)` over
`f(int<64>)`, preserving default `int` literal preference before nullable lifting.
Existing typed numeric identities and whole-argument overload rules remain in
force; no overload implementation changed.

Lazy expression-level `left ?: fallback` is now confirmed: evaluate the left
once and evaluate the fallback only when the left is null. Optional-parameter
fallbacks remain omission-only. The user also confirmed right associativity and
precedence below comparisons and logical operators: `a ?: b ?: c` groups to the
right, and comparing a selected integer requires `(count ?: 0) == 1`. The user
also confirmed that Elvis requires a nullable source type: `5 ?: 0` is rejected,
while a declared nullable binding remains eligible after a presence proof and
skips its fallback on that path. Elvis result nullability is also confirmed:
`T? ?: T` produces `T`, while a `T?` or null fallback produces `T?`, retaining
strict payload typing. Remaining Elvis boundaries stay under Stage 0 review;
parsing, typing and execution are still pending.

Ordinary casts are confirmed not to unwrap unproven nullable values. `n -> int`
for `int? n` requires a presence proof or prior non-null fallback; payload casts
then follow ordinary rules. Nullable-target conversions remain unspecified and
may be gated in the initial slice. Existing void discards remain applicable.
This is a contract update; no compiler implementation or verification changed.

The user confirmed declared-type metadata for nullable bindings: `int? x` retains
nullable `x::type` inside a presence guard, while a payload snapshot inferred
there has non-null `int` metadata. Value receivers keep once-only evaluation
without reading an absent payload. Remaining metadata/provider details stay
under Stage 0 review; no compiler support is claimed by this decision.

The user confirmed static proof or fallback for payload operations, explicitly
excepting printing. `print`/`println` may render an absent nullable value as `null`
without proof and use normal formatting when present; `::str` still requires
payload proof. The source audit identifies `BuiltinParameter.Text`'s current
reliance on `str`: nullable printing needs its own presence-aware adaptation
without exposing an unchecked nullable provider. The plan's flow example now
prints null after reassignment and rejects unproven `::str` instead. The user then
confirmed the same absence-aware behavior for nullable interpolation: absent
holes contribute `null` without proof, while explicit nullable `::str` still
requires proof. Hole evaluation order, early returns and string lifetimes remain
unchanged. The user also confirmed `print(null)`, `println(null)` and `#"{null}"`
without payload context, while retaining the explicit nullable annotation needed
by `def x = null`. The formatting adapter must not invent an arbitrary payload
type or ordinary null-only binding type. No compiler code changed.

The user rejected the proposed automatic nullable inference for a conditional
with a null branch. Such conditionals require an explicit nullable type in either
branch order, including all-null conditionals. The plan now records the rejected
unannotated form and explicit `def<int?>` form. The user then confirmed that
declared function return and parameter types also provide explicit nullable
context, with no intermediate typed local; competing nullable overload contexts
remain ambiguous. Array inference must not bypass this context requirement.
Conditional nullable-context validation replaces the earlier automatic-inference
recommendation; no compiler implementation changed.

The user extended the explicit nullable-destination requirement to conditionals
whose branch already has a known nullable type. The plan rejects inferred
`def n = if (flag) { existing } else { 5 }` for `int? existing`; declared nullable
bindings, returns and parameters can provide the required context. Proven
payload inference retains its confirmed rule. This
supersedes the proposed nullable branch-join inference and adds no compiler code.

The user then stated the governing rule, "Cloth will never infer/assume null."
The plan removes the conflicting proposal to infer nullable array elements from
null/present literals; explicit nullable element context is required. The user
confirmed that every new nullable binding needs its own explicit type, including
known nullable copies, nullable function results and named destructuring positions.
Proven payload inference remains non-null. The user also confirmed
nullable-to-nullable equality for comparable payloads: both null equal, one null
unequal, and both present use ordinary equality. No compiler code changed.

### Multi-return final review: discarded array constraints (2026-11-01)

The final source audit found one runtime validation gap: an explicitly typed
discard such as `def<int[2], bool> _, ready = pair()` could accept a dynamically
sized one-element array. Named positions enforced their receiving extents, but
discarded positions skipped those guards; an all-discard declaration bypassed
receiving constraints altogether. This also affected scalar broadcast discards
such as `def<int[2]> _ = array()`.

`EmitStatement` now validates discarded positional array extents alongside named
positions before creating bindings. `EmitExpression` validates all-discard
receiving plans before releasing producer temporaries, without allocating discard
storage or copying discarded arrays. Producer evaluation remains once-total,
and the existing conservative lifetime for captured publishing writes remains.

`MultiReturnReviewTest` adds five regressions: mismatching discarded first/last/
all positions, scalar broadcasts and nested dimensions; valid constraints with
captured writes; all arithmetic compound assignments and updates on received
values; rejection of result groups in scalar compound operations; and runtime
division/remainder guards. Native checks reuse each AST on Windows x64/x86.
Compound tests include reading the old target before an RHS destructuring write,
once-only deferred effects, Unicode string snapshots, half arithmetic, unsigned
wrapping, isize arithmetic, scopes and broadcast independence. The initial
four-test run reproduced the discarded-shape defect; after the fix, the focused
seven-suite assignment/lifetime run passed all 88 tests, zero failures/errors/skips.

The full `gradlew.bat :Compiler:test --offline --console=plain` run then passed
all 721 tests across 55 suites, zero failures/errors/skips. Additional explicit
discard constraints were added to the existing poisoned-buffer cleanup probes:
pure scalar/mixed producers still release all temporary bytes before the next
statement, while captured publishing writes preserve live buffers. The final
`MultiReturnReviewTest`/`ArrayTemporaryCleanupTest` run passed all 21 tests across
two suites, zero failures/errors/skips. Whitespace checks and 50 local Markdown
links passed. No compound-assignment regression or further actionable defect was
found within this review's covered behavior. Foreign coverage remains verified
IR for the supported profiles; native execution coverage is Windows x64/x86.

### Multi-return stage 5: final integration and documentation (2026-11-01)

`examples/MultiReturns.co` is the runnable name/age example, covering explicit
and inferred receiving types, `_`, conditional results, mutable/immutable
bindings, positional reassignment/swaps, compound updates, once-total scalar
broadcasts and independent array values. The user's provisional spellings in
`examples/Test.co` now use `_` and implicit `=` returns, preserving the rest of
the broader intended-design example. `Test.co` still includes unrelated language
features outside the compiler's current execution coverage. README documents the
implemented contract, runnable commands, value lifetimes and remaining boundaries.

`MultiReturnIntegrationTest` adds five integration cases. One parsed program runs
on Windows x64, x86 and x64 again, checking PE machine headers, runtime target
width, Unicode arguments, mixed half/bool/usize/isize/metadata/string-array
results, recursive buffer copies, captured mutations, conditional literal
contexts, broadcast/destructuring effects, short-circuit skipping, scopes and
metadata swaps. Another reuses parsed source across all nine canonical existing
triples: Windows x86/x64/ARM64 MSVC, Linux x64/ARM64 GNU and musl, and macOS
x64/ARM64. Each module is LLVM-verified with its target triple, carrier widths,
half decoding, boolean storage, aggregate phis, recursive copies and shape guards.
These are IR checks for foreign targets, not native Linux/macOS execution or
additional target/ABI support.

CLI fixtures check 13 failure categories through executable/object modes on both
native widths (50 failing invocations; the entry-only main restriction does not
apply in object mode). Each reports one expected located diagnostic before any
output directory or missing-linker work. The runnable example compiles/executes
through the CLI and also emits objects at both widths, printing `Cloth: 8` and
PEC: 0. Four runtime failure fixtures run at both widths, checking once-only
earlier effects, skipped later effects, array shape/index guards and invalid
target-sized casts, without normal PEC output.

The five focused multi-return suites passed all 70 tests, zero failures/errors/
skips. The full `gradlew.bat :Compiler:test --offline --console=plain` run passed
all 716 tests across 54 suites, zero failures/errors/skips. Changed/new-file
whitespace checks and 50 local Markdown links passed. All five multi-return
stages are complete within the plan's documented boundaries. Stage 5 adds
integration tests, examples and documentation; no additional compiler runtime
changes were needed.

### Multi-return stage 4: native transport, snapshots and lifetime preservation (2026-11-01)

`EmitResults` represents multiple results as an internal LLVM aggregate of ordered
per-position ABI types. Single-result and void layouts remain unchanged. Half
results use raw i16 fields at the aggregate/function boundary and decode to
ordinary half values on reception; booleans, numeric signedness/width identities,
characters, metadata and target-sized integers retain existing value rules. This
does not introduce a source tuple type, tuple storage or a foreign C aggregate ABI.

`EmitFunctions` declares ordered result carriers and supplies a caller arena when
any position is buffered. `EmitFunctionFrame` joins encoded result carriers,
copies each outgoing string/array into that caller's arena, then releases the
callee frame. Copies recurse through nested arrays and string elements, keeping
result positions independent even when their sources alias. Whole-result
forwarding, direct/mutual recursion, early returns and mixed buffered/scalar
results use the same boundary.

Result lists evaluate and snapshot each position before evaluating the next.
Strings copy conservatively; arrays retain the existing one-use transfer proof
or recursively copy. Carrier ownership is tracked separately from scalar array
freshness, consumed on extraction and reset with temporary checkpoints. Value
conditionals merge carriers using actual continuing predecessor blocks; branches
that return contribute no incoming value. Native reception prepares every
positional RHS snapshot before any binding/write and then stores named targets
in source order. Discards execute and validate their producers but own no binding
storage. Scalar broadcasts retain their existing semantics and copy behavior.

Temporary analysis now understands result lists and multiple-result calls/ifs.
Safe all-discard calls can release their buffers before the next statement;
captured writes keep the conservative frame lifetime so published arrays stay
alive. Non-continuing lists fall back to frame cleanup after outgoing values
have been copied. The C4030 stage gate for valid result-list/multiple-result
emission is removed across source, object and snippet paths. Existing semantic
and unsupported-type checks still run before emission. C3053 remains for the
separately unresolved multiple-result/discard function-block declarations.

`MultiReturnEmissionTest` adds eight native cases covering the user's examples,
swaps, repeated destinations, snapshots before captured mutations, nested buffer
recursion/forwarding, runtime conditional merges, half/bool/numeric/char/metadata
ABI fields, early/partial-list returns, mutual recursion and controlled runtime
failures. Mixed carriers and nested buffered recursion execute on Windows x64
and x86. Two new `StringFunctionTest` probes count allocations and poison freed
buffers to check recursive multi-result lifetime/balance and an allocation failure
that must skip later result effects. Two `ArrayTemporaryCleanupTest` probes check
immediate all-discard cleanup and captured writes that must retain buffers.
Prior stage-boundary tests now check successful source/object/snippet emission.
The focused six-suite run passed all 108 tests, zero failures/errors/skips.

The full `gradlew.bat :Compiler:test --offline --console=plain` run passed all
711 tests across 53 suites, zero failures/errors/skips. Changed/new-file whitespace
and 44 local Markdown links passed. Native execution coverage is Windows x64/x86;
the final integration/foreign-target matrix and runnable example remain stage 5.

### Multi-return stage 3: reception plans, discards and broadcast compatibility (2026-11-01)

Grouped declarations and assignment lists now record a `ReceptionPlan` from the
typed RHS: scalar broadcast, positional results, or non-continuation. Each slot
retains its original target, checked/inferred type and source result index.
Positional plans require snapshots of all RHS results before any destination
write; actual positional native emission remains stage 4. Metadata assignment
already snapshots all positional facts before source-ordered target updates.

`DeclarationAnalyzer` resolves the complete producer in the enclosing scope
before declaring any receiving name. Named positions have stable `GroupLocalSymbol`
identities. Discards have no symbol or local storage and may repeat. Duplicate
names, parameter collisions, ordinary shadowing, mutability, fixed types, inferred
array extents and subsequent assignment-shape rules remain checked. Receiving
lists never copy through the first binding or depend on a discarded first slot.
The scalar compatibility expansion and its synthetic binding ASTs were removed.

`ExpressionTyping` shares reception checking between source functions and legacy
snippets. Separate scalar and positional contexts preserve one exact scalar
type for broadcast assignments and independent literal contexts for positional
assignments, without fake wildcard value types. Calls still select overloads
from arguments, independently of the receiver. Per-position metadata facts flow
into receiving bindings and through swaps; repeated destinations receive writes
in source order, so the last slot supplies their final metadata fact. Reception
plans join the semantic facts copied through overload probes/commits.

Stage 3 implements the plan's defaults: a single annotation is shared only by
scalar broadcast; explicitly typed multiple results require a complete positional
type list. Scalar broadcasts may include discards, with the first named assignment
target supplying their shared context. Repeated assignment destinations are
allowed and retain source order after all positional snapshots. These choices
describe implementation policy, not additional separately confirmed user syntax.
C3057 reports receiving arity mismatch; C3058 reports invalid annotation lists.
Discarded annotated positions retain type/range/shape checks. Void cannot supply
a receiving value. C3053 remains only for unresolved multiple-result/discard
function-block declaration combinations; existing independent scalar blocks stay
deferred and preserve separate syntax identities.

The scalar emitter now consumes broadcast plans directly: evaluate once, skip
discard storage, and copy arrays independently into named bindings. Native
multiple-result reception remains guarded by C4030 before output creation,
including source/object and snippet emission paths. `DestructuringTest` adds 23
cases covering positional functions/conditionals, typed/inferred/discard lists,
arity/type/range errors, scopes, mutability, swaps/repeated targets, array shapes,
metadata snapshots, non-continuation, overload transactions, target-width reuse,
and backend boundaries. A native scalar broadcast test executes on Windows x64
and x86, proving once-total effects, independent copies, discarded first/all
positions, no `_` local alloca, and non-continuing producers. The seven-suite
focused run passed 128 tests, zero failures/errors/skips.

The full `gradlew.bat :Compiler:test --offline --console=plain` run passed all
699 tests across 52 suites, zero failures/errors/skips. Changed/new-file whitespace
and 44 local Markdown links passed. Native scalar broadcast execution is verified
on Windows x64/x86; positional reception is verified semantically and through
metadata plans, with explicit rejection before native emission until stage 4.

### Multi-return stage 2: result contracts, conditional typing and flow (2026-11-01)

`ResultShape` distinguishes no results, one ordinary value, multiple ordered
values, and non-continuation without adding a tuple `ValueType` or tuple storage.
`FunctionType.results`, `SemanticModel.resultOf`, and function-model result APIs
retain full ordered contracts and expression/statement result facts. Scalar
helpers reject multiple results rather than selecting the first slot. Overload
forks/commits retain result and per-position metadata facts.

Function analysis resolves all return annotations, contextually checks integer
literals per position, validates exact result count/type and completion, and
supports complete-result forwarding through explicit or implicit returns. Void
still means no results; void positions in a multiple-result annotation are
rejected. Unannotated functions remain void. Return type/count does not change
overload identity or parameter-based selection. Floating return literals retain
their existing rules; no implicit width changes were introduced.

Value-producing conditionals merge continuing results by count and position,
using ordinary scalar and array compatibility rules. Terminating branches do
not provide merge values. All source is still checked, including skipped branch
bodies and later result positions. Producer inference works independently of
a receiving declaration or function return annotation. Standalone calls and
`-> void` can semantically discard all results. Metadata traversal follows
each produced position, retaining represented-type facts separately from the
carrier and conservatively merging facts from executable continuing paths.

C3054 reports return-count mismatch, C3055 multiple results in a scalar context,
and C3056 incompatible conditional result counts. Existing scalar return and
branch diagnostics retain their codes; positional return mismatches label the
offending result and its annotation. Executable main still rejects multiple
results with C3037; library main remains an ordinary function.

Reception remains stage 3: explicit positional/discard forms receive C3053;
inferred ordinary groups still use the scalar-broadcast bridge and reject a
multiple-result RHS with C3055. Native multi-result emission remains stage 4:
valid producers receive C4030 before LLVM emission/output creation, including
discarded or non-continuing result-list syntax. Scalar native layouts remain.

`MultiReturnTypingTest` adds 21 cases covering ordered contracts, contextual
integer widths and strict floating/typed results, arity/type/void diagnostics,
independent conditional inference, recursive forwarding, overload selection,
termination, scalar misuse, arrays, positional metadata and invalid metadata
in ignored results, overload fact transactions, target-width reuse, main policy,
and rejection before native output. Stage 1 parser assertions now distinguish
the remaining reception boundary from supported producer typing. The focused
seven-suite run passed all 137 tests, zero failures/errors/skips.

The full `gradlew.bat :Compiler:test --offline --console=plain` run passed all
676 tests across 51 suites, zero failures/errors/skips. Changed-file/new-file
whitespace and 44 local Markdown links passed. Existing native Windows x64/x86
regressions remain green; new multiple-result coverage is semantic and native
preflight rejection, with actual multi-result execution still pending stage 4.

### Multi-return stage 1: syntax and retained receiving groups (2026-11-01)

The user confirmed `_` receiving-position discards and implicit return lists
after `=`, and extended the intended design to multi-result conditionals such
as `def x, _ = if (true) { true, false } else { false, true }`. Existing scalar
broadcast definitions/assignments and arithmetic compound assignments must
remain available. The [five-stage plan](multi-return-implementation.md) records
the audited parser/semantic/emission changes, recommended additional contracts,
verification gates, and unresolved choices. Stage 1 is implemented; later stages
remain pending.

Function annotations retain ordered return-type references. A result-list AST
node handles implicit/explicit returns, receiving RHSs and conditional tails;
named/discard targets and stored receiving groups keep their original source
tokens. Assignment-list lookahead distinguishes named branch results from actual
assignment syntax. Calls, arrays, indexing, interpolation and nested type widths
retain their own comma boundaries. Exact `_` remains a discard/operator token;
underscore-prefixed identifiers now lex without splitting their prefix.

A guarded scalar-broadcast compatibility path preserves existing resolution,
typing, metadata and native behavior with stable binding identities. It does not
choose positional reception by target count or drop extra annotations/results.
Deferred multiple-name blocks retain separate bodies and execute on every use.
Multiple-result/discard syntax parses but produces one located C3053 diagnostic
during semantic analysis; result typing/reception/emission remain later stages.

The focused new parser suite plus existing broadcast, function parsing, function
block and assignment operator suites passed 71 tests across five suites, zero
failures/errors/skips. The 13 new parser tests cover source spans including CRLF,
result/target lists, nested conditionals, delimiter isolation, block identities,
first syntax errors and unsupported semantic boundaries.

The full `gradlew.bat :Compiler:test --offline --console=plain` rerun passed all
655 tests across 50 suites, zero failures/errors/skips. An initial full run found
one obsolete syntax-error expectation in `MultiAssignmentTest`; named result
tails and positional RHS lists now intentionally parse, so those expectations
were removed and their accepted ASTs are checked by the new parser tests.
Changed-file whitespace and 44 local Markdown links pass. The existing native
regressions include Windows x64/x86; multi-result execution remains unimplemented.

### isize step 3: cross-width integration and final documentation (2026-11-01)

The final stage of the [isize plan](isize-implementation.md) adds four
ISizeIntegrationTest cases. One AST runs on Windows x64, x86 and x64 again,
checking executable machine headers and runtime width with exact identities,
deferred provider/block effects, assignment lists, short-circuit skipping,
scopes, nested array copies, returned-text lifetime, maximum empty shapes and
negative wide conversions. CLI fixtures retain first-error semantics across
passes; runtime failures evaluate operands once in order and stop before later
effects or PEC output. Existing backend/entry tests retain foreign IR coverage
and real x64/x86 signed numeric and exit-boundary checks.

The new examples/ISize.co and the two existing Builtins.co/Fib.co examples compile
and execute through the CLI on both Windows widths. The existing examples only
change their main return annotations from usize to isize; their bodies, output
and return behavior are preserved. CompilerSettingsTest's stale Builtins.co
expectation now matches the existing Fib.co default; CLI behavior is unchanged.
README and compiler-data-access notes now describe both primitives and the
unsigned measurement contract.

The focused integration, primitive, backend, entry and settings suites passed
all 47 tests across 5 suites, zero failures/errors/skips. The full
`gradlew.bat :Compiler:test --offline --console=plain` run passed all 642 tests
across 49 suites, zero failures/errors/skips. All three isize stages are complete.
Whitespace and manual skill frontmatter/placeholder checks passed; 54 local
Markdown links resolve across the seven updated documentation files. The
standard skill validator remains unavailable without PyYAML. Native execution
coverage is Windows x64 and x86; supported foreign target coverage remains
verified IR, not native Linux/macOS execution or additional 32-bit support.

### isize step 2: signed integration and executable entry (2026-11-01)

Step 2 of the [isize plan](isize-implementation.md) is implemented. ProgramAnalyzer
accepts isize, signed fixed-width int and void for executable main; usize and all
fixed unsigned integers are rejected. C3037 names isize in its message/help.
Library main functions remain ordinary functions. README entry guidance and
the prior usize-main regression expectations now match the corrected policy.

The integration audit found that shared integer handling already supplies signed
comparisons, wrapping arithmetic, guarded division/remainder and exponentiation,
checked casts, native signed formatting, signatures/storage and checked indexing.
Those paths needed verification, not type-specific backend changes. Measurements
and array counts remain usize; step 1's exact identity and target isolation remain.
The native entry adapter retains signed PEC reporting and low-bit exit narrowing.

ISizeBackendTest adds five cases checking all supported target IR and real Windows
x64/x86 numeric behavior, casts, formatting, function/array transport, providers,
checked failures and CLI rejection before output/linker work. ProgramEntryTest
now executes isize main at signed boundaries with both argument forms, Unicode
arguments and positive/negative wide exit values. The focused ISizeTest,
ISizeBackendTest, ProgramEntryTest and USizeTest run passed all 50 tests across
4 suites, zero failures/errors/skips. The full regression run completed 638 tests
across 48 suites: 637 passed, one failed, zero errors/skips. The sole failure is
the existing CompilerSettingsTest Fib.co/Builtins.co default-path mismatch from
step 1, reconfirmed against HEAD; those files were preserved. Whitespace and
manual skill frontmatter/placeholders passed, and 44 local links resolve; the
standard skill validator still lacks PyYAML. Broader integration/once-only
effects and final cross-width coverage were left for step 3 above.

At step 2 user-edited examples/Builtins.co and examples/Fib.co still declared
usize main and received C3037. Step 3 migrates their main annotations to isize.

### isize step 1: primitive and semantic contract (2026-11-01)

The user requested a signed pointer-sized isize primitive and revoked the
earlier usize-main allowance. The [three-step isize plan](isize-implementation.md)
tracks the work. Step 1 adds CISize over CInteger, keyword/parser/resolver support,
target-specific bounds/literals, distinct identity and canonical metadata.
Size/rank/extent/width remain usize. Provider emission now rejects isize bound
recipes from a different target width, as it already did for usize results.

ISizeTest checks semantic contracts on Windows x64/x86 layouts and reuse of the
same AST across widths, signed bounds/static division errors, strict typing,
overloads, explicit conversion typing, provider results and verified LLVM
signatures/metadata. The focused ISizeTest/USizeTest/SharedIntegersTest run passed
20 tests across 3 suites, zero failures/errors/skips. The full regression run
completed 633 tests across 47 suites: 632 passed, one failed, zero errors/skips.
The existing CompilerSettingsTest default-path assertion expects Builtins.co
while CompilerSettings defaults to Fib.co; both are already present in commit
cc32806 and were preserved. All isize checks passed. At this milestone native
isize integration and executable entry changes remained steps 2 and 3. Whitespace, skill
frontmatter/placeholders and 43 local links were checked; the standard validator
requires PyYAML, absent from the available Python runtimes.

At step 1 ProgramAnalyzer and C3037 were unchanged: usize main was still accepted
and isize main rejected. Step 2 above corrects that intermediate policy and
replaces the prior acceptance tests.

### Earlier usize return support for main (superseded design, 2026-11-01)

The user explicitly extended the executable entry contract to accept usize in
addition to void and signed fixed-width int. ProgramAnalyzer accepts the distinct
CUSize descriptor, preserving rejection of fixed-width unsigned int and other
unsupported entry return types. C3037 guidance now includes usize.

The startup adapter applies the existing integer exit normalization: preserve
equal-width bits, keep low native C int bits when narrowing, and choose signed
or zero extension by source signedness if widening is needed. PEC remains a
signed native C int report, so usize::max - 1 reports -2 on supported Windows
x64 and x86 targets. Source function returns still retain exact usize identity.

ProgramEntryTest adds verified startup IR across supported platforms and native
execution on x64/x86 for both main parameter forms, expression/brace returns,
zero, ordinary provider results, signed-boundary bit patterns, maximum usize
and 64-bit-to-32-bit truncation. The earlier USizeTest main-rejection assertion
now checks acceptance. Focused entry/usize/x86/Unicode suites passed, followed by
the full `gradlew.bat :Compiler:test --offline --console=plain` run: 626 tests
across 46 suites, zero failures/errors/skips. The unchanged examples/Builtins.co
compiled through the CLI for native x64 and Windows x86; both executions printed
PEC: -2 and returned process status -2. Whitespace and skill/documentation link
checks passed. Foreign target IR checks remain separate from native execution.

### usize step 7: integration complete (2026-11-01)

Step 7 of the [usize implementation plan](usize-implementation.md) adds three
USizeIntegrationTest cases. One parsed source executes on x64, x86, then x64
again, checking target isolation together with exact measurement overloads,
provider chains, deferred receiver effects, assignment lists, nested copies,
returned-text lifetimes, empty maximum shapes and explicit 64-bit conversions.
Executable machine headers and printed runtime width verify the chosen target.

CLI fixtures check one diagnostic before linker/output work for both widths:
strict count typing, read-only results, invalid receiver/metadata, static extent
overflow and name-resolution errors. This preserves first detected error across
compiler passes rather than imposing source-order priority. Runtime fixtures
verify once-only operand effects and stopping at the first controlled failure.

The implementation review found no additional production changes necessary.
Focused integration/usize/provider/target/diagnostic checks passed, followed by
`gradlew.bat :Compiler:test --offline --console=plain`: all 624 tests across
46 suites passed with zero failures/errors/skips. The portable examples/USize.co
compiled through the CLI and ran for native x64 and explicit Windows x86,
printing 1, usize and PEC: 0 on each. Whitespace and skill/documentation link
checks passed.

At the step 7 milestone, the entry-point contract permitted void or signed
fixed-width int returns. A separate edit to examples/Builtins.co declaring
fn<usize> main was preserved and then reported C3037. The later explicit user
decision above extends that entry contract to usize.

All seven migration steps are complete. Current contracts are separated from
historical phase snapshots in the compiler-data-access note, and the skill,
plan, semantics and validation records agree. Execution coverage is Windows x64 and SSE2 Windows x86
on the Windows x64 host; foreign IR/object checks do not establish native
Linux/macOS execution, and other 32-bit ABI targets remain unsupported.

### usize step 6: real Windows x86 target (2026-11-01)

Step 6 of the [usize implementation plan](usize-implementation.md) is implemented
for the user's selected Windows x86/MSVC ABI. i686-pc-windows-msvc has a distinct
WINDOWS_ILP32 profile: pointers, size_t, usize, C int and C long are 32-bit;
explicit int<64>, C long long and binary64 remain 64-bit. Existing x86 LLVM
registration supports the new width; data layout installation/validation still
occurs before emission. Other 32-bit OS/ABI profiles remain unsupported.

Windows x86 linking selects x86 MSVC CRT/SDK libraries and machine flags, with
the matching MSVC linker host/target directory. Both Windows widths use wmain
startup. WideCharToMultiByte uses explicit x86 stdcall at declaration and call
sites; CRT calls use cdecl. The CLI accepts --target TRIPLE for both object and
executable modes, with existing supported-target/toolchain validation. Its
default remains the native LLVM target.

Real execution exposed generic x86 excess x87 precision in float32 arithmetic.
The x86 backend now requires SSE2 and preserves the declared-width rounding
contract. A regression checks float32(16,777,216) + 1. The internal assembly
emission hook uses the same generic/+sse2, default code-generation optimization,
PIC/default code-model settings as object emission; no IR optimization pipeline
was added. [Validation and assembly evidence](usize-x86-validation.md) records
observed count/index/copy code and explicit 64-bit carry arithmetic.

Five new WindowsX86Test cases check ABI/IR, actual PE32 machine/header values,
numeric and buffer operations, metadata, CLI selection and assembly artifacts.
They execute static/dynamic CRT, LLD/MSVC and console/GUI variants, both main
parameter forms and normalized process returns. Existing Unicode fixtures now
also execute valid/malformed UTF-16 and balanced startup cleanup as x86; allocation
guard fixtures execute real 32-bit overflow and negative-index failures before
memory access. ABI/layout/toolchain suites include the new profile.

Focused checks pass. The CLI compiled examples/USizeX86.co and its PE32 executable
ran with Unicode arguments, printing 32 and PEC: 0. The full
`gradlew.bat :Compiler:test --offline --console=plain` run passed all 621 tests
across 45 suites with zero failures/errors/skips. Whitespace and skill/documentation
link checks passed. Native execution is on the Windows x64 host for Windows
x64 and x86 binaries. This establishes neither other 32-bit targets nor native
execution on Linux/macOS. Final integration is recorded in step 7 above.

### usize step 5: compiler data result contracts (2026-11-01)

Step 5 of the [usize implementation plan](usize-implementation.md) is implemented.
Scalar/array/string size and metadata rank/extent/width results now have exact
target-selected CUSize identity through bindings, inference, arrays, function
arguments/returns and overload selection. Numeric min/max preserve the receiver
type. Predicates, type/element and str preserve their bool/typeinfo/string results.

Provider maps supply the selected target when creating storage/array/string
recipes and metadata queries. Metadata fields, checked constants and loads share
the target's native count width. The temporary unsigned-64 public count adapter
is removed; runtime array/string counts already use the matching native width.
Cross-target usize recipes fail an internal invariant before emission. The
unregistered raw ArrayExtent recipe alone retains its legacy 64-bit unknown-axis
encoding, independently of source metadata extent.

Static extents still use exact target-usize checks, including metadata-only
types and empty outer shapes with maximum inner counts. Array-size recipe
construction validates the selected width, and metadata constant construction
checks representability before creating integer constants. Fixed int widths,
existing integer index acceptance, once-only receiver effects and first-error
diagnostics remain unchanged.

Existing scalar/buffer/metadata/provider/target/usize regressions are migrated.
Two new USizeTest cases cover measurement identity and strict contracts on
interleaved synthetic 32-bit/native layouts, plus native overloads competing
with equally wide fixed integers, value effects and explicit conversions.
One new BufferCountsTest checks native metadata fields/constants/loads, maximum
extents and cross-target recipe rejection. Supported target IR checks include
dynamic size/rank/extent/width functions. Focused checks pass. The updated
examples/USize.co compiled through the CLI and ran successfully, printing 1,
usize and PEC: 0. The full `gradlew.bat :Compiler:test --offline --console=plain`
run passed all 615 tests across 44 suites with zero failures/errors/skips.
Changed-file whitespace and skill/documentation link checks passed.

At the step 5 milestone execution remained on Windows x64; synthetic 32-bit
semantic/helper IR and foreign target IR checks did not establish execution
there. Step 6 above supplies real Windows x86 ABI/toolchain validation; final
cross-target integration is recorded in step 7 above.

### usize step 4: native buffer counts

Step 4 of the [usize implementation plan](usize-implementation.md) is implemented.
The user confirmed that every static array extent must fit target usize,
including metadata-only extents. `TypeResolver` validates these with BigInteger;
32-bit semantic tests accept 4,294,967,295 and reject larger counts.

`BufferLayout` centralizes target-width array/string descriptors. Outer lengths
are iN, and each inner axis is `{iN, i1}` for count/knownness. This replaces i65
while retaining maximum counts after empty axes and preserving rectangular shape
checks. Literal/global/argument/return/copy producers and consumers agree on
this layout. UTF-8 scalar counting, byte output, recursive array copying and
Unicode startup use native-width indices; metadata text uses native descriptors.

`EmitMemory` checks stride multiplication and allocation sums against native
size range and LLVM's signed pointer-index byte limit before allocation. It uses
the installed module data layout's ABI strides and pads copied-array headers to
the element's ABI alignment (which may exceed pointer alignment). Header storage,
concatenation, stack buffers and native size_t/memcpy boundaries use coherent count widths;
oversized string globals fail with C3052. Bounds checks preserve wide indices
until after validation and explicitly reject signed negatives. UTF-8 startup
also bounds its scan position before loading a byte.

Seven new `BufferCountsTest` cases verify synthetic 32/64-bit descriptor layouts,
zero/maximum/unknown shapes, target-specific type-only extents, checked index
narrowing, string/array/metadata/provider integration, and both Unicode startup
paths. A tiny synthetic address space checks UTF-8 global limits including the
terminator and source-located C3052 errors. Native guard tests fail before memory
access for stride/header/concatenation overflow and negative indices; boundary
arithmetic passes. Existing string/conditional IR assertions now check the shared
pre-allocation guards and retain their short-circuit placement checks.
Focused buffer/provider/Unicode/usize tests and copy/cleanup regressions pass.
The full `gradlew.bat :Compiler:test --offline --console=plain` run passed all
612 tests across 44 suites with zero failures/errors/skips. Changed-file
whitespace and skill/documentation link checks passed.

At the step 4 milestone public size/rank/extent/width result types still returned
unsigned int<64>, with explicit widening adapters for smaller native counts.
Step 5 above supersedes that public contract and removes its adapter. Raw internal extent
recipes retain their legacy unknown-result encoding at that adapter only.
Existing integer index operands and the signed fixed-int main contract remain.
Native execution is on Windows. Synthetic 32-bit helper IR is separate from
real 32-bit ABI/toolchain/execution coverage, which remains step 6.

### usize step 3: primitive source support (2026-10-31)

Step 3 of the [usize implementation plan](usize-implementation.md) is implemented.
The user confirmed existing unsigned arithmetic/cast rules and canonical
metadata spelling `usize`, with the numeric width queried through `::width`.
`CUSize` extends `CInteger` with distinct data-class identity and target-resolved
width. `TypeResolver` uses the selected `TargetLayout.sizeBits`; type parsing
recognizes the existing usize keyword in annotations, casts, type checks, array
suffixes and type receivers. Explicit usize width arguments and the unsigned
modifier are rejected by existing type/modifier validation.

Generic integer/function/array paths provide contextual literals, strict
assignments/returns/arguments, overload selection, arithmetic, comparisons,
conversions, deferred blocks, name lists, independent array transport and
formatting. Min/max return the exact CUSize type. Metadata uses `usize` and
`usize[3]` spellings, retaining identity separately from equally wide fixed
integers, with numeric width/signedness facts from the descriptor. There is no
primitive boxing, source pointer syntax or runtime metadata allocation.

Eight new `USizeTest` cases check synthetic 32-bit/native target isolation,
range errors, same-width identity, overloads, all fixed-integer conversion widths
and floating formats, operators, arrays/blocks/copies/recursion, metadata/provider
effects, controlled runtime errors and verified IR for x86-64/AArch64 across
Windows/Linux/macOS. Focused usize/target/shared-integer/provider checks passed;
`gradlew.bat :Compiler:test --offline --console=plain` passed all 605 tests across
43 suites with zero failures/errors/skips. `examples/USize.co` compiled and ran
through the CLI, printing 1, usize and PEC: 0. Native execution is on Windows;
synthetic 32-bit semantic/LLVM scalar checks and foreign IR are separate coverage.
`examples/USize.co` is the runnable example.

At the step 3 milestone, internal lengths/descriptors still used their existing
representation; step 4 above supersedes that implementation snapshot.
At that milestone size accessors, including usize's storage size, and metadata
rank/extent/width still returned unsigned int<64>; step 5 supersedes that snapshot.
Existing integer index acceptance
and the signed fixed-int main policy are retained. Step 4 applies the user's
target-sized static extent limit. Real
32-bit ABI/toolchain support remains step 6.

### usize step 2: shared integer descriptors (2026-10-31)

Step 2 of the [usize implementation plan](usize-implementation.md) is implemented.
`CInteger` now owns integer width/signedness facts, exact bounds, contextual literal
validation, normalization for existing constant checks, min/max/size/str providers
and LLVM integer mapping. `CInt` retains its fixed-width data-class identity and
supported-width validation. Integer bound/extent recipes preserve the concrete
descriptor through the common contract.

Literal typing and diagnostics, static division checks, float-to-integer guards,
runtime division/remainder emission and native printf adaptation now accept
`CInteger`. Other arithmetic, comparisons, exponentiation, signatures, assignment,
arrays and metadata flags already use shared numeric facts or exact identity.
Canonical metadata spelling can be overridden by a future distinct primitive;
fixed-int spellings and equality remain unchanged. Native C representation types,
the signed fixed-int main policy and existing unsigned-64 size/extent/metadata
result contracts remain deliberate concrete CInt uses.

Three `SharedIntegersTest` cases exercise a test-only distinct integer across all
existing widths/signedness: common bounds and providers, exact source identity
despite equal LLVM representation, contextual literals and first-error diagnostics,
array element identity, and verified cast/division/power/formatting/metadata IR.
The parser's existing global literal limit still precedes semantic range checks;
descriptor checks cover values beyond it without changing parser behavior.

Focused existing numeric/cast/division/provider/metadata/call regressions passed,
and all three new shared-integer cases pass. The full
`gradlew.bat :Compiler:test --offline --console=plain` run passed all 597 tests
across 42 suites with zero failures/errors/skips. Changed-file whitespace and
skill-reference checks passed. Native regression execution is on Windows;
the distinct test descriptor has
semantic/verified-IR coverage. The usize primitive remains step 3 and provider
result migration remains step 5; no new source type has been implemented here.

### usize step 1: shared target layout (2026-10-31)

Step 1 of the [usize implementation plan](usize-implementation.md) is implemented.
`cloth.target.TargetLayout` records immutable target size/pointer widths, pointer
alignment, byte order and C numeric ABI facts without retaining LLVM handles.
`NativeCABI` supplies the selected layout and derives native numeric types from
it. The emitter selects that configuration before semantic analysis; declaration
and expression models, type resolution, overload forks, deferred blocks,
provider lookup (including built-in text adaptation), LLVM type/storage mappings
and runtime services share it. Explicitly mixed semantic targets are rejected.

LLVM module creation now installs the target-machine data layout before emitting
bodies or layout-dependent helpers. It validates pointer width/alignment and
endianness, and object emission rejects a conflicting existing target/layout.
The supported x86-64/AArch64 backends are registered for foreign layout queries;
an internal emitter constructor injects supported ABIs. Native defaults remain;
the CLI and accepted target matrix have not gained 32-bit compilation support.

Six new `TargetLayoutTest` cases check native ABI/default facts, interleaved
synthetic 32-bit/native semantic analyses, overload/fork/block/provider context,
fixed integer widths and storage, cross-target rejection, and module layouts for
both supported architectures across Windows/Linux/macOS. Existing provider-map
tests now supply a target; foreign Unicode startup fixtures select their ABI
before generating function bodies instead of changing the module target later.

Focused target/ABI/descriptor/provider/call tests and corrected Unicode startup
checks passed. `gradlew.bat :Compiler:test --offline --console=plain` passed all
594 tests across 41 suites with zero failures/errors/skips. `git diff --check`
passed. Native execution is verified on Windows; synthetic 32-bit semantic and
foreign layout/IR/object checks are separate from native execution coverage.
`usize` itself, provider result migration and internal size representation
changes remain steps 2 onward.

### usize migration plan recorded (2026-10-31; not implemented)

The user accepted a primitive target-sized unsigned integer and migration of
scalar/array/string size plus metadata rank/extent/width results to `usize`.
Explicit fixed integer widths and the plain-int 32-bit default remain part of
the intended contract. See [confirmed semantics](semantics.md) and the
[staged implementation plan](usize-implementation.md) for decisions, source
touchpoints, dependencies and per-step completion checks.

At the time of this planning entry, all steps remained incomplete and no compiler
source or implementation tests were changed. Step 1's later implementation is
recorded above. Existing unsigned-64 provider results,
i64/i65 buffer descriptors and 64-bit-only ABI support are the implementation
baseline, not a claim that `usize` or 32-bit compilation already works. Earlier
phase records below describe that pre-migration implementation.

### Completion output aligned with manual PEC edits (2026-10-31)

The user's manual edits selected `PEC: N`, including the colon, in the native
entry-point output and explicit entry/startup/error test checks. The shared
`processCompletion()` expectation still used `PEC N`; it now matches `PEC: N`.
README and confirmed semantics describe the same spelling, and the emitter's
completed TODO was removed. Multiple-assignment native tests again check the
complete expected output using the shared helper instead of ignoring the
completion banner. The earlier completion-mismatch failure below is historical.

Verified with `gradlew.bat :Compiler:test --offline --console=plain`: all 588
tests across 40 suites passed, with zero failures/errors/skips. Native tests ran
on Windows; entry/startup IR checks also cover Linux/macOS profiles. The files
changed for this completion-output fix pass `git diff --check`.

### Multiple assignment to existing bindings (2026-10-31)

`x, y, z = value` is implemented for existing named bindings. A
`MultiAssignmentStatement` retains one RHS expression and the original target
tokens. Name resolution applies ordinary target checks to every name, including
immutable/final bindings, deferred block bindings and function names. The shared
RHS is typed once using the first target's ordinary assignment context; every
target must accept that same result type. The user explicitly confirmed exact
result typing even when an integer literal fits several different integer types.

LLVM evaluates the RHS once before storing to targets in source order. Every
destination uses the ordinary assignment transfer path, including independent
recursive array copies and captured initial-shape checks. Checking each target
against the original RHS type preserves known array-shape diagnostics instead
of losing extent information by rereading the first assigned binding. Metadata
analysis propagates the one represented-type result to every target, and control
flow follows the one RHS so returns skip all stores. Semantic-model forks retain
the per-target types for overload/array-inference probes. Compilation still
fails at the first detected error.

The 13 `MultiAssignmentTest` tests pass, covering syntax, named-target rules,
first-error reporting, exact widths/signedness, contextual literals, metadata,
once-only calls and captures, repeated targets, parameter copies, nested scopes,
recursive array isolation, static/runtime shape errors, skipped bodies and early
returns. Native execution ran on Windows. `examples/Bindings.co` and README
include existing-binding assignment lists.

Verification: `gradlew.bat :Compiler:test --tests cloth.MultiAssignmentTest
--offline --console=plain` passed all 13 tests. The full suite ran 588 tests
across 40 suites: 461 passed and 127 failed, with zero errors/skips. Those
failures compare the expected `PEC N` completion banner with the existing
entry-point emitter's `Process finished with exit code N` output; that unrelated
edit was preserved. All new multiple-assignment tests passed in the full run.
The changed files pass `git diff --check`; unrelated existing whitespace in
`Operators.kt` prevents a clean repository-wide check.

### Multiple binding definitions (2026-10-31)

`def x, y, z = 10` now creates three mutable bindings, each inferred as `int`.
Explicit annotations and `:=` apply to every name. The initializer executes
once, as confirmed by the user; later names copy the first stored value through
ordinary binding machinery. Array copies retain independent recursive storage.
No new semantic or emission path is needed for stored values: the parser expands
the list into ordinary binding declarations, retaining the original name tokens
for duplicate and assignment diagnostics. Compilation still fails at its first
error.

Typed function blocks retain deferred execution. Each name receives a separate
body with distinct syntax identities so lexical resolution and semantic facts
cannot overwrite another block's analysis. Declaring the names executes no body.

`MultiBindingsTest` covers inference, annotations, mutable/immutable assignment,
duplicate-name locations, malformed lists, nested scopes, strings, recursive
array copies, once-only calls and deferred results, and multiple function blocks.
`examples/Bindings.co` now exercises inferred and explicitly typed name lists.

Verified with `gradlew.bat :Compiler:test --offline --console=plain`: all 575
tests across 39 suites passed, with zero failures/errors/skips, including all
10 multiple-binding tests. Native execution ran on Windows through the existing
shared LLVM copy/emission paths. `git diff --check` passed.

### Integer division and remainder guards (2026-10-31)

Integer `/` and `%` now validate both a zero divisor and signed minimum with -1
before emitting the native operation. `EmitIntegerDivision` shares this path
across all integer widths, including desugared `/=` and `%=`. Its failures use
the existing non-returning runtime-error path with the operator's source file
and span, exit status 1 and no PEC completion report. Operands have already
executed once, left to right; short circuits and returns still skip operations.
Floating-point arithmetic is unchanged.

Semantic analysis rejects literal/sign and integer-provider constant zero
divisors with C3050, and known signed minimum/-1 pairs with C3051, before LLVM
emission, toolchain lookup or output creation. This is deliberately limited
constant recognition, not binding propagation or general constant folding;
other values retain runtime guards. Overflow can reject an overload candidate
without preventing a valid wider contextual match. Intrinsic zero-divisor
diagnostics survive overload probes. Negative unsigned literals already use
the existing range diagnostic; signed negative values remain valid.

`IntegerDivisionTest` covers every signed/unsigned width, zero-over-zero,
literal/provider diagnostics, wrapped unary minima, semantic-before-output
ordering, first-error CLI reporting, runtime failures and valid boundary results,
computed constants, compound assignments, operand effects, skipped operations,
early returns and contextual overload selection. The focused division,
assignment-operator, return-contract and overload suites passed. Full validation
with `gradlew.bat :Compiler:test --offline --console=plain` passed all 565 tests
across 38 suites, with zero failures/errors/skips. Native execution ran on Windows;
the common integer guard path uses target-independent LLVM integer operations.
`git diff --check` passed.

### PEC completion format confirmed (2026-10-31)

The user confirmed `PEC N`, meaning Process Exit Code, as the intended completion
format. The native adapter already emits it; the shared native-output expectation,
entry/startup IR assertions, runtime-error absence check, README and confirmed
semantics now use the same spelling. Existing newline, exit-status and once-only
reporting behavior is unchanged. This resolves the format mismatch found in the
foundation audit below; its failed run describes the earlier test expectations.

Verified with `gradlew.bat :Compiler:test --offline --console=plain`: all 556
tests across 37 suites passed, with zero failures/errors/skips. Native execution
ran on Windows; startup IR checks also cover Linux/macOS profiles.
`git diff --check` passed.

### Foundation audit (2026-10-31)

Source inspection confirms the implemented procedural core: named functions,
overloads and recursion; executable `main` and object emission; strict return
contracts and control-flow validation; built-in output; typed compiler-data
providers and `typeinfo`; numeric, boolean, character and string values;
bindings, conditionals and rectangular value arrays. Source-file declarations
currently parse only functions. Classes, methods, object construction/destruction
and state-machine execution are intended language features still to implement.

At audit time, the working tree's completion format was `PEC %d`, whereas its
documented contract and test helper expected `Process finished with exit code N`.
The user's subsequent PEC confirmation above resolves that mismatch.

Verification: `gradlew.bat :Compiler:test --console=plain` completed 556 tests
across 37 suites: 437 passed, 119 failed, zero skipped. Inspection of every XML
failure attributes all reported failures to the completion format: 106 text
comparisons, 11 byte comparisons and two IR substring assertions. These failures
can stop test methods before their later scenarios/assertions, so this run does
not establish that all remaining native behavior passes. Compiler source was
left unchanged by the audit. `git diff --check` passed.

The audit also found unguarded integer division/remainder. The user's subsequent
guard decision and implementation above resolve that gap for zero divisors and
signed minimum with -1.

The older milestone snapshots below are historical. Current accessor contracts
and supported operations are recorded above them and in the semantics reference.
Native Windows console encoding, buffered output flush failures and native macOS
startup execution remain verification/design gaps as recorded in those sections.

### Executable completion status (2026-10-31)

The native startup adapter now prints `PEC N` (Process Exit Code) after
the entry-point call returns and argument storage is released. The reported
value is the same native C `int` returned by the adapter: void completion uses
0, narrower signed results are sign-extended and wider results retain their low
bits. A newline before and after the message separates it from explicit output.
Recursive/source calls to `main` do not emit their own completion messages.
Declaration/object emission, test snippet adapters and terminating startup/runtime
errors do not emit this normal-completion message. Compilation itself still
returns its own independent compiler status.

Native output expectations now include the completion message. Entry tests cover
void completion, explicit/bare/expression returns, every signed integer width,
wide truncation, negative Windows statuses, output ordering, recursive `main`,
and absence of completion output after a runtime error. Unicode adapter tests
also verify the completion format and single native printf declaration for
Windows, Linux and macOS ABI profiles.

Verified on 2026-10-31 with
`gradlew.bat :Compiler:test --offline --console=plain --no-daemon`: all 556 tests
across 37 suites passed with zero failures/errors/skips. Native execution ran on
Windows; Linux/macOS adapter IR was verified. The full run also exposed a stale
default-source test expecting `FunctionBlocks.co`; its expectation now matches
the compiler's existing `Builtins.co` default. `git diff --check` passed.

### Built-in function registry and explicit output (2026-10-31)

`BuiltinFunctions` now centrally declares `PRINT = register("print", ::PrintFunction)`
and `PRINTLN = register("println", ::PrintlnFunction)`. Factory registration
returns a typed `BuiltinDefinition<T>`, populates the read-only common list and
name index, and checks duplicate names/contracts as compiler invariants. Immutable
definitions own overload contracts and emission recipes without native handles.

Declaration lookup finds these functions automatically after ordinary lexical
lookup. Source function groups and bindings shadow built-ins, with no fallback
when a nearer function's arguments do not match. Names remain ordinary identifiers;
the historical `Console.IO.println` import was removed from `examples/Test.co`.
`examples/Builtins.co` demonstrates explicit output and array type metadata.

The semantic call model now commits either a source-function choice or a bound
built-in definition, overload and concrete argument conversions across overload
forks. `Exact` parameter contracts retain strict/contextual typing; `Text` uses
ordinary inference and selects a value `str` provider returning `string`. Existing
provider recipes perform conversion during emission. No boxing, runtime type
dispatch or function-name switches were added. `print(value)`, `println(value)`
and `println()` return void; unsupported array values fail with source-located
C3049. Existing arity, void-value and function-value diagnostics remain applicable.
Built-in diagnostic labels use real source occurrences, with no invented native
declaration locations. Compilation still stops on the first diagnostic.

Emission evaluates arguments once, respects early exits, and keeps buffered values
alive through adaptation and output. Built-in calls conservatively retain array
temporaries through their owning frame; source-call copy/cleanup behavior remains
unchanged. Metadata and control-flow passes already visit call arguments in the
required order and need no built-in-specific dispatch.

String output now shares one lazily emitted, length-aware stdout helper per
module. Byte output and newline output are separate; the existing string snippet
printer composes both. Scalar conversion shares `::str` and interpolation, while
older numeric/character snippet instrumentation remains test-only. The helper
preserves UTF-8 bytes and embedded NULs through the native text stream, interns
its native declaration and terminates on detected `putchar` failures. Buffered
flush failures and Windows native-console/code-page handling remain separate
follow-up work; redirected output is covered by native Windows tests. The same
C ABI path is used on Linux/macOS, whose native execution was not verified here.

`BuiltinFunctionsTest` covers registration identity and read-only enumeration,
resolved conversion plans, diagnostics, lexical shadowing, native output/newlines,
all numeric formats and full integer ranges, default/contextual precision, Unicode,
NUL/percent text, metadata, buffered results, once-only block effects, interrupted
arguments, nested overload probes, void returns and per-module helper reuse.

Verified on 2026-10-31: focused built-in/string/call/return tests passed, then
`gradlew.bat :Compiler:test --offline --console=plain --no-daemon` passed all
554 tests across 37 suites with zero failures/errors/skips. Native tests ran
on Windows. `git diff --check` passed. The CLI compiled `examples/Builtins.co`
and its executable printed the expected greeting, numeric limits, boolean,
Unicode character and normalized array type text.

### Provider integration follow-up, chunk 3.5 (2026-10-31)

The original phase 3 feature plan ended with chunk 3.4. The subsequent request
for 3.5 is implemented as an integration review of that completed provider set,
with no additional source syntax, provider contracts or deferred features.

The review found that `MetadataAnalysis` joined returning and skipped paths with
continuing paths. This discarded otherwise definite represented-type facts and
allowed some known-invalid `element`, `extent` and `width` accesses to reach
runtime validation. The pass now reuses the existing `ControlFlowModel`, including
its simple boolean proofs, when joining conditional results and mutable binding
facts. Only executable branches that continue contribute facts. Short-circuit
joins distinguish skipped right operands, necessarily evaluated right operands
and right operands that return; deferred block effects still invalidate mutable
facts when executed. All source remains checked; no diagnostic recovery or
accumulation is introduced. Unknown dynamic joins keep their controlled runtime
checks, and value receiver evaluation/LLVM provider dispatch are unchanged.

Six `ProviderIntegrationTest` cases reproduce the missing compile-time diagnostics
and cover valid continuing paths, skipped captured writes, dynamic runtime errors,
independent metadata copies, chained numeric queries and shared Unicode text.
The three diagnostic regression groups failed before the fix and passed afterward.
Focused integration, metadata, function-return and function-block regressions passed.
Verified on 2026-10-31 with
`gradlew.bat :Compiler:test --offline --console=plain --no-daemon`: all 541 tests
across 36 suites passed with zero failures/errors/skips. `git diff --check` passed.
Native integration tests ran on Windows using the shared LLVM and C ABI paths.

### Built-in type metadata, phase 3.4 (2026-10-31)

`CTypeInfo` is the built-in `typeinfo` value type, accepted in bindings, parameters,
returns and arrays. The common `ValueType` provider map now registers `type` for
source types (including `void`); the internal bottom type exposes no providers.
Both type and value receivers describe the expression's static type. Type
receivers construct no runtime instance, and executable receivers retain the
existing once-only evaluation, effects, bounds checks and terminating control flow.

Metadata values use handles to immutable compiler-generated native descriptor
globals, with no runtime metadata allocation or new ownership mechanism. The
descriptor stores complete canonical Cloth type text, immediate array element
metadata, rank, outer static extent, numeric width and the inspection flags.
Equality compares canonical represented-type text rather than handle addresses;
numeric widths, signedness and every static/unspecified array extent participate.
Numeric default widths are explicit in metadata text, while diagnostic spellings
retain their existing format. `str`, interpolation and snippet printing share
that text. Runtime array lengths never enter a type descriptor.

`CTypeInfo` registers value-only `element`, `isarray`, `rank`, `hasextent`,
`extent`, `width`, `isunsigned` and `isnumeric`, plus `str`; its inherited `type`
describes `typeinfo` itself. Query result types, preconditions and native field
layout are shared through `MetadataQuery`. Typed provider recipes own emission
and use per-emission services; the common provider emitter gains no dispatch
cases. Immutable handles pass through ordinary scalar storage/function paths;
arrays of metadata use ordinary array copying.

Invalid known `element`, `extent` and `width` accesses throw source-located
C3048 immediately. Direct provider facts survive overload forks. A separate
semantic facts pass follows expression evaluation order after type/call checking,
so type-inference previews cannot reorder mutable facts. It propagates definite
represented types through copies, assignments, identity casts, conditional
results and branch joins. Mutable captures are treated as unknown inside deferred
blocks, and block execution conservatively invalidates mutable facts. Parameters,
ordinary call results and array element contents start unknown; this pass is not
interprocedural specialization or general constant evaluation. Dynamic queries
check descriptor preconditions before loading their result and terminate through
the shared controlled runtime-error path, naming the source file and accessor.

Eleven `TypeInfoTest` cases cover all numeric formats and signedness, normalized
text, total predicates, recursive array metadata and full-range extents, exact
equality, static array types across erased signatures, function/array transport,
mutable copies, overloads, read-only targets, static diagnostics, runtime failures,
branch joins, captured writes, receiver effects, bounds errors and early returns.
Verified on 2026-10-31: focused metadata/provider/descriptor tests passed,
followed by `gradlew.bat :Compiler:test --offline --console=plain --no-daemon`:
all 535 tests across 35 suites passed, with zero failures/errors/skips.
`git diff --check` passed. Native tests ran on Windows through the shared LLVM
and C ABI paths. `addr`, class/state execution and array/class formatting remain
deferred. Phase 3 now covers its confirmed set for currently supported types.

### Array/string sizes and scalar text conversion, phase 3.3 (2026-10-31)

`CArray` now registers source `size`, returning the outer count as
`unsigned int<64>`. Value receivers always evaluate once, even when their count
is statically known. Type receivers are accepted exactly when the outer extent
is known, including 0 and partially specified types such as `int[2][]`.
Unspecified outer extents are rejected at the provider name. Static extents now
use `BigInteger` throughout descriptors and type resolution, bounded to the
unsigned 64-bit range; this removes the former signed-32-bit parsing limit and
allows full-range fixed type queries without constructing an instance. Counts
outside that range fail at compile time before any truncation or emission.

To preserve rectangular shapes across the entire range, inner native extents
now use i65, with an extra bit distinguishing unknown (-1 in i65) from the valid
unsigned 64-bit maximum. Array descriptors are `{ptr, i64, i65, ...}`: outer
lengths stay i64, and string/rank-one array layouts are unchanged. Shape creation,
constraints, matching and indexed child shapes use explicit widening/narrowing;
outer lengths are always known and are never treated as unknown sentinels.
An empty array with a known maximum inner extent retains it through erased
signatures, copying and assignment checks. This is compiler-private metadata,
not a source pointer/reference or allocation ownership feature.

`CString` registers value-only `size`, implemented by an allocation-free LLVM
loop counting UTF-8 leading bytes over the stored byte length. Validated UTF-8
is an existing Cloth invariant. Embedded NULs count as scalars; supplementary
characters, combining sequences and empty strings follow the agreed scalar-count
contract. The count cannot exceed the unsigned 64-bit byte length, so this loop
cannot overflow. Dynamic array size reads the existing unsigned 64-bit outer
length directly with no arithmetic or narrowing; static limits are checked above.

Integers, floats, booleans, characters and strings register value-only `str`,
returning `string` through the same formatter as interpolation. String receivers
preserve their contents, including embedded NULs. Array/class formatting, `addr`
and `typeinfo` remain outside this chunk. Existing provider emission keeps receiver
storage alive; outgoing strings use the ordinary buffered-return copy before
source-frame cleanup. Overload facts, strict result types, read-only targets,
control flow and once-only receiver evaluation continue through the shared path.

Eleven `BufferProvidersTest` cases exercise receiver restrictions and diagnostics,
known overflow and full-range constants, outer/empty/multidimensional/runtime
counts, maximum inner extents without sentinel collisions, Unicode scalar counts,
shared scalar formatting, buffered returns/copies, captured effects, overloads,
preserved shape/bounds failures and terminating receivers. Verified on 2026-10-31:
focused provider and array-shape regressions passed, followed by
`gradlew.bat :Compiler:test --offline --console=plain --no-daemon`: all 524 tests
passed with zero failures/errors/skips. Existing shape, deep-copy, Unicode startup
and temporary-cleanup tests passed. `git diff --check` passed. Native tests ran on
Windows using the shared LLVM and C ABI paths. The next chunk is built-in
`typeinfo` and its confirmed metadata operations.

### Floating bounds and scalar byte sizes, phase 3.2 (2026-10-31)

Chunk 3.2 registers floating `min`/`max` and scalar `size` on the existing
descriptors, reusing the source syntax, receiver forms and provider-owned LLVM
emission from earlier chunks. `CFloat.minimum`/`maximum` are exact `BigDecimal`
finite limits derived from significand width and maximum exponent, using
`BigInteger` arithmetic rather than host floating-point rounding. The bounds
support type/value receivers and preserve binary16/32/64 result types; `min`
is the most negative finite value, not a positive normal or subnormal threshold.

`CInt`, `CFloat`, `CBool` and `CChar` expose `size` for both receiver forms,
returning `unsigned int<64>` storage bytes. A shared scalar helper uses
`storageBits`: boolean values remain LLVM i1 with i8 storage and report 1 byte;
Unicode scalar characters report 4 bytes. Numeric sizes follow their selected
width. These constants do not depend on the native C ABI or register width.
No parser, semantic-dispatch or emitter cases were needed for these registrations.
Existing once-only receiver evaluation, overload facts, read-only targets and
strict assignment/return typing remain in force.

Nine `ScalarProvidersTest` cases cover exact host-independent bounds, IEEE LLVM
constant bit patterns for all formats, source result types, every scalar width
and signedness, half/regular function ABIs, finite runtime bounds, mutable result
copies, captured evaluation counts, NaN receiver contents, overload selection and
preserved indexed-receiver bounds failures. Existing provider tests were updated
only for the newly available scalar providers. Verified on 2026-10-31: focused
provider tests passed, followed by
`gradlew.bat :Compiler:test --offline --console=plain --no-daemon`: all 513 tests
passed with zero failures/errors/skips. `git diff --check` passed. Native tests
ran on Windows using the shared LLVM and C ABI paths. Array/string sizes, scalar
text conversion and `typeinfo` remain the subsequent chunks recorded in
[compiler data access](../../../../todo/compiler-data-access.md).

### Source accessor syntax and integer bounds, phase 3.1 (2026-10-31)

The user authorized implementing phase 3 in bounded chunks after the contracts
were settled. Chunk 3.1 adds `TypeReceiverExpression`, `ProviderAccessExpression`
and separate `StateCallExpression` syntax. Indexing and `::` share a postfix
loop, binding tightly and chaining left to right. Primitive type receivers parse
widths, unsigned modifiers and array suffixes. Data names remain identifiers;
uppercase spellings have no provider alias. A bare type receiver is not a value.

Integer `min` and `max` now work in source with both type and value receivers,
preserving width and signedness. `ProviderReceiver` distinguishes descriptor-only
type access from an evaluated LLVM value, and recipes advertise their supported
forms. Type access creates no runtime instance or dummy receiver. Value access
still evaluates once even when producing a constant, preserving captures, calls,
index bounds checks, temporary lifetimes and terminating control flow.

Semantic analysis stores resolved provider facts alongside expression types and
commits them across overload probes. Provider availability is independent of the
enclosing argument/return context; intrinsic lookup failures remain fatal during
probes instead of degrading to a no-matching-overload error. Typed accessor results
provide ordinary numeric operand context but cannot change their receiver's type
to satisfy an enclosing assignment or return annotation.

Dedicated source diagnostics cover unavailable providers, invalid receiver forms,
read-only accessor mutations, bare type receivers and unimplemented state calls.
Assignment, compound assignment and updates to accessor targets are rejected at
the accessor name. Parenthesized state calls are parsed separately and remain
unsupported pending class/state emission; there is no fallback to a provider call.

Thirteen `ProviderSyntaxTest` cases cover postfix parsing, receiver forms, exact
types, read-only targets, source locations, first-error behavior, overload probes,
type-only constant emission, all integer bounds, captured effects, mutable result
copies, contextual array inference, preserved indexing errors and early returns.
Verified on 2026-10-31: focused syntax and phase 2 provider tests passed, followed
by `gradlew.bat :Compiler:test --offline --console=plain --no-daemon`: all 504
tests passed with zero failures/errors/skips. `git diff --check` passed. Native
execution tests ran on Windows using the shared LLVM and C ABI paths. The next
chunk is floating bounds and scalar byte-size providers;
array/string operations and `typeinfo` follow. See the chunk plan in
[compiler data access](../../../../todo/compiler-data-access.md).

### Typed provider results and emission, phase 2 (2026-10-31)

`ProviderResult<T>` now carries an explicit receiver descriptor and typed result
descriptor. Integer constants use exact `BigInteger` values; floating constants
use `BigDecimal` values and the existing literal overflow rules. Raw array extent
recipes require an explicit axis and 64-bit result descriptor, folding known
extents and otherwise reading native descriptor fields. Scalar text recipes reuse
the existing interpolation formatter. Each recipe owns its LLVM emission method;
there is no central provider-kind switch. Emission validates native receiver and
result representations against the recorded descriptors and retains no LLVM
handles in reusable recipes.

`ValueType` supports contextual provider lookup through `CompilerDataProviders`
spellings. `CInt` registers its existing minimum/maximum bounds internally using
the receiver's exact width and signedness. Other source data contracts remain
unsettled and unregistered: floating bounds, `size`, `str`, `addr` and `type`.
The raw array extent operation preserves the existing -1 unwitnessed-inner-axis
sentinel and does not establish a public array-size meaning. Source accessor
syntax and its first-error diagnostics remain phase 3 work.

`SemanticModel.providerAccess` retains a resolved expression and recipe.
`EmitExpression.emitProvider` evaluates the receiver once even for constant
results, then delegates emission through per-emission services. It suppresses
early receiver temporary cleanup and conservatively retains buffers until the
owning invocation exits; text results can borrow receiver bytes. Existing buffered
return copying runs before source-frame release. Terminating receivers produce no
provider value, and the temporary-depth guard is restored on every exit path.

Eleven `ProviderResultsTest` cases cover contextual lookup, exact integer bounds,
floating formats/range rejection, reusable recipes, receiver mismatch rejection,
captured side effects, runtime/known/inner array extents and empty-axis sentinels,
scalar formatting with Unicode and embedded NULs, buffered text return/lifetime
and terminating receivers. Verified on 2026-10-31: focused tests passed, followed
by `gradlew.bat :Compiler:test --offline --console=plain --no-daemon`: all 491
tests passed with zero failures/errors/skips. `git diff --check` passed. Native
provider tests executed on Windows; emission uses the shared LLVM and C ABI paths.

### Primitive type descriptors, phase 1 (2026-10-31)

The user approved phased implementation of the primitive-descriptor design.
`cloth.data.ValueType` now supplies the shared compiler type contract, with a
sealed `CObject` descriptor base and `CInt`, `CFloat`, `CBool`, `CChar`, `CString`
and `CArray` implementations in `PrimitiveTypes.kt`. The former nested primitive
classes in `cloth.semantic.ValueType` and unused parallel primitive scaffolding
were consolidated. Semantic analysis, native C ABI profiles, diagnostics and
emission now use these classes directly.

Descriptors own their LLVM value/storage mappings. `LLVMTypes` is a small
context-bound adapter with no type switch. Descriptors hold language facts rather
than native handles and can be reused with independent LLVM contexts. Integer
width/signedness and exact bounds, floating literal thresholds and promotion,
boolean i1 values/i8 storage, Unicode scalar characters and native string/array
descriptors retain their previous representations. Array shape operations moved
onto `CArray`, retaining the existing recursive element/extents model. Singleton
aliases use getters to avoid static initialization cycles.

The unused `Any` provider API and placeholder addresses/strings/sizes were removed.
`CompilerDataProviders` retains explicit contextual spellings. Typed provider
results and emission were added in phase 2 above; source accessor parsing remains
later work.
Operator, cast and native formatting dispatch still use their existing rules.
No accessor result type, size meaning or formatting policy was adopted by this
refactor. See [compiler data access](../../../../todo/compiler-data-access.md).

Three `PrimitiveTypesTest` cases exercise semantic descriptor production, LLVM
scalar/aggregate layouts and descriptor reuse across independent LLVM contexts.
Verified on 2026-10-31: focused tests passed, followed by
`gradlew.bat :Compiler:test --offline --console=plain --no-daemon`: all 480 tests
passed with zero failures/errors/skips. `git diff --check` passed. Native tests
executed on Windows; the descriptor mappings remain shared across target ABIs.

### Conservative early array temporary cleanup (2026-10-31)

The user authorized earlier cleanup only with a compiler proof, otherwise
retaining function-lifetime storage. They reaffirmed that Cloth does not and
will not have GC, and excluded reference counting from this optimization.
The implementation uses statically selected allocation checkpoints in the
existing invocation arena. It adds no tracing, root scanning, runtime liveness
decisions, ownership counts or automatic class-object destruction.

`ArrayTemporaryAnalysis` has a closed whitelist of expressions that cannot
publish newly allocated buffers to surrounding bindings. Ordinary calls use
the existing value/return-copy contract; inline function-block reads are
inspected for captured writes. All declarations/writes and unrecognized cases
conservatively fail the proof. The analysis also requires possible caller-side
array allocation, avoiding checkpoints for ordinary nonallocating scalar reads.

`EmitExpression` wraps a proven expression only when its result is discarded
or has no buffer pointer. It saves the arena head before evaluation, then frees
exactly the new allocation-list segment after normal completion and restores
that head. Nested evaluation stays within the outer proven checkpoint. Early
function exits use ordinary frame cleanup after any outgoing return copy.
`EmitStringArena.releaseTo` simply unwinds recorded allocations to that known
checkpoint; it never searches for reachable objects or tests whether an object
is live. No additional heap allocation is needed to record a checkpoint.

Statement emission now distinguishes discarded expression results from the
last value needed by a conditional branch. Live array/string results, adopted
rows, borrowed string bytes and buffers exposed through captured writes retain
the old frame lifetime. Named bindings, replacements and lexical scope exits
are not shortened by this pass. Safe nested scalar operations can still reclaim
their own new temporaries while preserving the older live prefix. Existing
copy independence, shape/bounds checks and evaluation order remain in force.

Fourteen `ArrayTemporaryCleanupTest` cases observe live/peak requested bytes
during execution, count allocations and poison freed buffers. Eight discarded
64-element integer arrays still allocate eight times, but live compiler buffer
bytes return to zero between statements and peak at 264 bytes (256 data plus
the existing 8-byte allocation link), rather than retaining all eight buffers.
Tests also cover scalar/void argument snapshots, primitive indexing, nested
literal/string arrays, adopted rows and borrowed strings, captured array/string
writes, named/replaced/scoped fallback storage, repeated pure function-block
reads, dynamic conditionals, short circuits and early buffered return paths.
Verified on 2026-10-31: focused cleanup and copy-elision tests passed, followed
by `gradlew.bat :Compiler:test --offline --console=plain --no-daemon`: all 477
tests passed with zero failures/errors/skips. Existing shape, bounds, evaluation
order, capture, return-copy, Unicode startup and poisoned-storage regressions
also passed. `git diff --check` passed. Native memory probes executed on Windows;
the implementation uses shared LLVM/C-ABI paths without target-specific cleanup.

### Fresh array result copy elimination (2026-10-31)

The user authorized eliminating redundant physical copies of fresh array
results while preserving independent values, shape checks and source evaluation
order. `EmitExpression` now tracks one-use ownership of recursively independent
temporary buffers in the current physical frame. Function-call results become
fresh because the callee already copies them into that frame's arena. A binding,
argument, literal row or subarray replacement can consume a fresh result without
another copy. Storing it consumes freshness; binding reads never acquire it.

Conditional branch results and function-block returns snapshot borrowed values
or consume fresh results before their control-flow exits. Their merged result
is independently owned and transferable once, including mixed fresh/borrowed
paths and repeated function-block reads. Indexing a fresh temporary can transfer
its selected subarray; indexing an existing binding still snapshots that row
when transferring it elsewhere. Array literals retain the existing stack-backed
construction and recursive snapshot path.

Adoption checks every static destination extent using complete shape metadata.
Whole-binding replacement still checks its captured initial shape, and subarray
replacement still checks the parent's shape. Source arguments are transferred
in order before later arguments run; existing binding arguments remain copied
even for final parameters. Targets and indices still evaluate before assignment
RHS expressions. No read-only borrowing, lifetime shortening, COW or new source
syntax is introduced. Function returns continue recursively copying across
physical frame boundaries before the callee's arena is released.

Seventeen `ArrayCopyElisionTest` cases use actual allocation counters and poisoned
released buffers. The original flat identity example now allocates three times
instead of four. Three nested producer/transform calls allocate three return
buffers, without argument/result-binding snapshots in between. Tests also cover
stored/final copy isolation, row replacement/extraction, fresh subarray indexing,
dynamic conditional paths, stored results read by conditional branches, repeated
and captured function blocks, mixed block return paths, argument evaluation and
captured mutation order, early returns, recursive nested string lifetimes,
known/initial/empty-axis shape failures and target selection before RHS rebinding.
Focused allocation, shape, function-block and storage-lifetime tests passed.
Verified on 2026-10-31: a fresh, unexcluded
`gradlew.bat :Compiler:test --offline --console=plain --no-daemon` build passed
all 463 tests, with zero failures/errors/skips. This includes the existing CLI
examples, counted/poisoned storage tests and Unicode startup regressions; the
previously unfinished prototype no longer blocked ordinary source compilation.
`git diff --check` passed. Native optimization tests executed on Windows; this
change is shared LLVM emission with no target-specific optimization path.

### Array binding immutability confirmed (2026-10-31)

The user confirmed retaining the existing binding-only `:=`/`final` behavior.
Immutable locals and final parameters cannot be rebound, but their array
elements and nested subarrays can be changed under the existing strict type,
rectangular shape and independent-copy rules. Finality belongs to the binding,
not the array type, and does not imply a read-only function parameter.

README and confirmed semantics now state the rule explicitly. The existing
`ArrayAssignmentTest` case `final array bindings retain binding only immutability`
already covers native local element writes and final parameter copy isolation.
This is documentation of confirmed existing behavior; compiler code is unchanged.
The audit's binding/content immutability question is settled.

### Unicode command-line startup (2026-10-31)

The user confirmed lossless Unicode arguments on Windows, Linux and macOS and
first-invalid-argument failure before Cloth main. `EmitArguments` validates
user arguments sequentially, excluding the executable name, before both
`main()` and `main(string[] args)`. One diagnostic identifies the invalid
argument's one-based position and expected native encoding, then exits with
status 1. Later arguments and user code are not evaluated.

Windows executable adapters now export native `wmain(int, wchar_t**)`.
`WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, ...)` validates UTF-16 and
converts to UTF-8. A private helper sizes each conversion, uses temporary stack
storage for that argument and copies the result into a startup arena. The arena
is released after Cloth main returns. Parameterless main validates without
allocating converted buffers. GUI executable linking replaces the narrow CRT
override with `wmainCRTStartup`; console linkers discover `wmain`. Internal
snippet/test-driver startup remains ordinary `main`.

Linux/macOS keep native `main(int, char**)`. A shared validator follows Unicode
Table 3-7, rejecting isolated continuations, overlong forms, surrogate encodings,
out-of-range scalars and truncated sequences. It computes byte length while
reading sequentially through the first NUL, without out-of-bounds lookahead.
Valid UTF-8 bytes are borrowed unchanged for the main invocation. Both adapters
preserve empty arguments, Unicode sequences, ordering and existing exit statuses.

Ten `UnicodeArgumentsTest` cases cover native Unicode/quoting/empty inputs,
signed entry contracts, Windows CRT/subsystem/linker combinations, verified
startup IR for eight supported platform profiles, UTF-8 scalar boundaries and
malformed forms, invalid UTF-16, stopping before a later unreadable argument,
and balanced startup/copy/return allocations. Exact-code-unit native drivers
bypass JVM sanitization. Native launch tests temporarily select the JVM's strict
Windows quoting mode so literal quotes reach the CRT correctly.

Focused tests passed on Windows. Native Linux verification also passed all 51
checks using emitted ELF fixtures linked/executed in the installed Ubuntu WSL
environment. Mach-O fixtures and verified Darwin x86-64/ARM64 IR are available;
native macOS execution remains unverified. The reproducible Unix execution
driver is `Compiler/src/test/resources/unicode-arguments/verify-unix.py`.
All 446 compiler tests passed against the successfully compiled Unicode
implementation (`:Compiler:test -x :Compiler:compileKotlin -x
:Compiler:compileTestKotlin --offline --console=plain --no-daemon`). A regular
full build encountered errors in the unrelated, concurrently edited
`cloth/data/PrimitiveTypes.kt` prototype, which was left untouched. A separate
fresh build excluded only that file and used a separate output directory;
all 55 Unicode, entry-point and string-lifetime tests passed there as well.
Whitespace checks passed for the changed files outside that prototype.
See the updated
[argument TODO](../../../../todo/unicode-command-line-arguments.md) for the
remaining native macOS check and replay instructions.

### Fixed initial array shapes (2026-10-31)

The user confirmed that every array-valued binding preserves its initial shape,
including omitted dimensions initialized from function results. The existing
semantic checker rejects mismatches in known dimensions. Unknown dimensions
are captured from the initialized value and checked before each whole-binding
replacement, recursively across all dimensions. Equal element counts do not
permit a different rectangular shape. Same-shape returned updates remain valid
and continue copying recursively by value.

`EmitBindings` retains the initial descriptor as an immutable SSA shape template,
independent of the binding's mutable storage slot. `EmitExpression` compares
unknown binding dimensions before copying the replacement. Fully known types
keep their existing static checks and runtime destination constraints. This adds
no allocations or separate shape-storage lifetime. Branches, shadowed bindings,
captures and parameters use the correct binding identity and initial descriptor.
Unknown dimensions below empty axes retain the existing internal -1 policy;
known dimensions following empty axes remain fixed and checked.

Function signatures retain their declared contracts: `int[]` can accept or
return different lengths on separate calls. Each parameter binding preserves
its received shape for that invocation; each receiving local preserves its
own initial result. Function bodies are not inspected to invent static sizes
at call sites. Deferred function-block declarations still execute on every
read; only a stored result captures an initial array shape.

Fourteen `ArrayBindingShapeTest` cases cover compile-time versus runtime checks,
literal and returned initializers, copies and returned updates, parameters and
varying return shapes, partial extents, matrices and higher-rank equal-count
reshapes, branches/shadowing/captures, repeated function-block reads, empty axes,
once-only source evaluation and legacy snippets. A counted/poisoned allocation
test covers repeated unknown-shape returned replacements and normal/early exits.
README, confirmed semantics and `examples/ArrayAssignment.co` document the rule.

Verified on 2026-10-31: focused binding-shape and assignment tests passed,
followed by the complete `gradlew.bat :Compiler:test --offline --console=plain
--no-daemon` suite: 436 tests, zero failures/errors/skips. The suite includes
the expanded array-assignment CLI example and counted/poisoned storage checks.
`git diff --check` passed. Native execution was verified on Windows; the change
uses shared LLVM shape checks without target-specific backend code.

### Order-independent array inference (2026-10-31)

The user confirmed that all elements must participate in compile-time array
element inference before contextual literals receive their types. Already-typed
values retain exact width, signedness and primitive type; conflicts require
explicit casts. Literal-only arrays retain existing defaults and range rules.
This resolves the audit's `[n, 1]` versus `[1, n]` discrepancy without adding a
widest-type search, implicit numeric conversion or runtime element-type changes.

Shared `ExpressionTyping` now discovers fixed element constraints before the
ordinary source-ordered typing pass. Explicit element annotations take priority.
Integer literals, contextual literal arithmetic and unary expressions can defer
their type until constraints are available. Nested literal rows contribute typed
leaf constraints recursively. Calls, casts, indexing, ordinary bindings and
function-block reads contribute their established result types. Conditional
result branches contribute constraints with their own lexical scopes and local
binding types; non-result statements and enclosing return contracts still check
normally. No diagnostic recovery/accumulation is used. Existing overload forks
continue isolating rejected candidate facts, and default literal preference stays
in force. Inference checks compile-time facts only; runtime elements still evaluate
once, left to right, including function blocks and early returns.

Existing floating-literal policy is retained: floating literals default to
`float` in ordinary bindings/returns; call arguments can receive floating-width
context. Array discovery does not make a `float` literal or a typed `float`
binding implicitly convert to `float<64>` in an ordinary inferred binding.
Compatible known/unknown dimensions still merge under the rectangular contract.
An older rectangular regression that expected `[[1], [wide]]` to fail was updated
to use two differently typed bindings, preserving the intended exact-type check.

`examples/Arrays.co` demonstrates both element orders and a typed value in a
later row guiding earlier literal rows. README and confirmed semantics record
the rule. The later fixed-initial-shape work above settles the `[]` binding-size
policy. The array length API, equality and copy optimizations remain separate
audit items.

Verified on 2026-10-31: the focused inference tests passed, followed by the full
`gradlew.bat :Compiler:test --offline --console=plain --no-daemon` suite: 421 tests,
zero failures/errors/skips. Eighteen `ArrayInferenceTest` cases cover every integer
width/signedness and anchor position, rejected typed numeric conflicts, explicit
annotations/casts, literal defaults and range failures, existing floating literal
policies, nested rows and typed row values, literal arithmetic/unary expressions,
conditional branch/local binding constraints, calls/indexing/function blocks,
overload isolation/default preference, once-only runtime evaluation, early returns,
contextual empty rows, incompatible conditional branches and the shared legacy
snippet pipeline. Existing rectangular, function/return and counted/poisoned
storage-lifetime checks also passed. The normal CLI test executes the expanded
array example. `git diff --check` passed. Native execution was verified on Windows;
this change is in the shared semantic checker and introduces no target-specific
backend paths.

### Rectangular array contract (2026-10-30)

The user confirmed rectangular arrays for current Cloth, with jagged arrays
reserved for a future collection type. All rows/subarrays at each level must
have the same dimensions recursively, including values created or returned by
functions. Unsized dimensions are unknown sizes, not permission for jagged rows.
Replacing a row/plane must preserve that subarray's complete shape. The later
fixed-initial-shape work above extends this stability to whole-value rebinding,
including dimensions inferred from function results.

`ExpressionTyping` merges compatible known/unknown array extents while preserving
exact primitive element types. Differing known row dimensions still fail with
C3038; a literal row and a function-produced row with a compatible shape can now
coexist in either order. Scalar numeric inference/conversion rules are unchanged.

The private LLVM array descriptor now contains the data pointer followed by one
i64 extent per dimension. One-dimensional arrays and UTF-8 strings retain their
two-field representations. Full shape metadata survives copies, parameters,
conditional/block values and function returns, including dimensions following
an empty axis: an erased `int[0][3]` value cannot satisfy `int[0][4]`.
Indexed subarrays inherit their parent's shape constraints, including dimensions
learned from a sibling row of an immediately indexed literal temporary.
`EmitArray` checks every sibling shape during literal evaluation, even for an
immediately indexed temporary. `EmitStringArena` validates/normalizes dimensions
against declared contracts and parent shape before recursively copying children.
`EmitExpression` validates a replacement subarray against the existing target
shape before storing, preventing shape changes through unsized matrix parameters
or function-returned values. Matching replacements keep the existing independent
value-copy semantics. Runtime mismatches use the controlled error path and stop
evaluation of subsequent rows.

Empty-dimension implementation policy: a dimension with no runtime witness below
an empty axis and no declared extent is represented internally by -1. Declared
context can constrain it; explicitly known extents remain known through erasure.
Replacement preserves the target's shape, including unconstrained empty tails.
The sentinel is internal metadata, never an array length that can be indexed.
No GC, reference qualifiers or new source syntax are introduced.

The array audit's rectangular/jagged inconsistency and its known/unknown row
mixing issue are resolved by this work. Other findings below remain separate.

Verified on 2026-10-30: focused rectangular-array, assignment and counted/poisoned
storage-lifetime checks passed, followed by the full `gradlew.bat :Compiler:test
--offline --console=plain --no-daemon` run: 403 tests, zero failures/errors/skips.
Fourteen `RectangularArraysTest` cases cover compile-time ragged rejection,
mixed literal/function rows, runtime rejection in bindings/arguments/returns and
immediately indexed temporaries, recursive copy isolation, row/plane replacement,
higher-rank shape mismatches, preserved dimensions below empty axes, context for
unwitnessed empty dimensions, zero-length rows, complete-value rebinding,
evaluation stopping on the first mismatched row, and empty temporary subarray
reads/replacements. The existing `examples/Arrays.co` CLI test now exercises
rectangular matrices built from returned rows. Updated the prior row-replacement
diagnostic assertion to expect the new rectangular-shape guard. `git diff --check`
passed. Native execution was verified on the current Windows host; implementation
uses the shared LLVM backend and target ABI helpers without platform-specific code.

### Array behavior audit (2026-10-30)

This is a historical audit snapshot; the rectangular contract above supersedes
its shape-related observations and the previously unsettled shape decision.
The later order-independent inference work also supersedes its first-element
inference observations, and fixed-initial-shape work supersedes its differing
literal/function binding-stability observations.
The later Unicode startup work supersedes its Windows argument-encoding defect;
native macOS verification remains tracked separately.
The user also confirmed retaining the observed binding-only final-array policy.
Fresh-result copy elimination above reduces the measured identity sample's
allocation count from four to three. The original measurements below remain a
historical baseline; general copy/lifetime optimization is still ongoing work.

Audit only: compiler semantics were not changed. The complete 389-test suite
passed alongside two temporary audit drivers (391 JUnit cases total, zero
failures/errors/skips). The drivers ran 26 semantic/native behavior probes and
a native counted-allocation experiment on Windows. Reproducible source, LLVM IR,
`observations.tsv`, `allocations.txt` and the archived `ArrayAuditProbeTest.kt`
are under ignored `build/array-audit/`. The temporary driver was moved out of
the normal test tree after execution; its observations are not new contracts.

Confirmed-contract checks passed for recursive binding/parameter isolation,
return-and-reassign updates, exact known shapes, contextual empty arrays,
zero-length inner dimensions, bounds guards (including unsigned 64-bit maximum
and empty-array access), and runtime shape validation at sized parameter and
return boundaries. Existing counted/poisoning tests also passed.

Reproduced surprises and gaps:

- Shape consistency: `def<int[][]> a = [[1], [2, 3]]` is rejected, but
  `[row(0), row(1)]` succeeds when `row` returns `int[]` and those same row
  values. Its inferred type is `int[2][]`, and `a[1][1]` returns 3. A matrix
  obtained through an `int[][]` function can also have one row replaced with
  a different-length array. Known versus erased extents currently determine
  whether jagged values can be constructed. Rectangular versus jagged semantics
  have not been confirmed; this is an implementation-policy inconsistency to
  resolve before presenting a uniform multidimensional-array contract.
- Element inference: with `def<int<64>> n = 2`, `[1, n]` is rejected while
  `[n, 1]` infers `int<64>[2]`. A row-returning call mixed with an identical
  literal row is also rejected because the known/unknown row types differ.
  The first-element policy is documented as provisional, not confirmed design.
- Binding extents: `def<int[]> a = [1, 2]` retains `int[2]` and rejects
  `a = [3]`; replacing the initializer with an unsized `make()` returning the
  identical values yields `int[]` and permits that rebinding. This follows
  current static information, but the declaration/signature meaning of `[]`
  and the intended array-size stability should be made explicit.
- Length access is absent: `.length`, `.size`, and `len(args)` are all rejected.
  Generic code cannot query a received array's extent, including checking
  whether command-line arguments are empty before indexing. Length/count and
  iteration are expected collection facilities, not implemented contracts yet.
- The existing Windows Unicode argv defect remains reproducible: the argument
  `é中😀` compares unequal to the same Cloth string literal; the ASCII `Cloth`
  control succeeds. Continue tracking the cross-platform fix in
  `todo/unicode-command-line-arguments.md`; this audit did not settle invalid
  native-byte policy or establish execution on Linux/macOS.
- Final bindings remain element-mutable: `def a := [1]; a[0] = 10` (with
  newline statement separators) succeeds. This follows the present binding-only
  policy, but differs from immutable collection values in Swift; explicitly
  distinguish binding immutability from immutable array contents.
- Performance: `def a = [1, 2, 3]` followed by `def b = identity(a)` produces
  four native heap-copy allocations in a straight-line scalar-returning sample:
  declaration, argument snapshot, returned result, and result binding. This is
  not a value-semantics failure. Current eager copies and invocation-long
  retention need optimization before efficiency is comparable to mature
  collection implementations. No general performance benchmark was performed.
- Array equality, indexed compound/update operators and trailing literal commas
  remain unsupported; untyped empty arrays require context. These are capability
  gaps or deliberate provisional choices, rather than failures of confirmed
  copy/index semantics. Runtime-size construction and array iteration are also
  not implemented by the current grammar.

Comparison used primary language documentation. Go fixed arrays are values
whose lengths belong to their types; Go slices instead share backing arrays:
https://go.dev/doc/effective_go#arrays . Swift Array has value behavior and
copy-on-write optimization; immutable collection constants prevent element
mutation: https://docs.swift.org/swift-book/LanguageGuide/ClassesAndStructures.html
and https://docs.swift.org/swift-book/LanguageGuide/CollectionTypes.html . Java
and C# arrays are reference types, and Kotlin array `val` bindings permit element
mutation: https://docs.oracle.com/javase/specs/jls/se26/html/jls-10.html ,
https://learn.microsoft.com/en-us/dotnet/csharp/language-reference/language-specification/arrays ,
https://kotlinlang.org/docs/arrays.html . Rust fixed arrays include their length
in the type and enforce initialization/bounds in safe access:
https://doc.rust-lang.org/reference/types/array.html . C++ `std::array<T,N>`
provides fixed, contiguous storage and a size API:
https://eel.is/c++draft/array.overview . These comparisons support Cloth's chosen
value-copy model without importing reference sharing, pointer syntax, numeric
conversions, implicit initialization, or other languages' object lifetime rules.

### Indexed assignment implementation

Indexed element assignment and independent array value copies are implemented.
`ElementAssignmentStatement` parses `arr[index] = value`, including nested targets,
and participates in name resolution, strict element/index/shape checking and flow
analysis. `EmitArray.checkedAddress` shares the existing bounds guard with writes.
The entire target evaluates once, left to right, and each dimension is checked
before evaluating the RHS. The RHS evaluates once; stores preserve bool byte
storage and every supported primitive representation. Returns nested in target
indices or the RHS stop the remaining evaluation and emit no store.

The user confirmed that copied arrays and function arguments are independent,
recursively. Earlier parameter rules guaranteed local rebinding only; the old
array descriptors still shared backing storage. The emitter now copies arrays
at binding declarations/reassignments, each call argument (before subsequent
arguments run), subarray insertion/replacement, and block/conditional value
boundaries. Chained target indexing reads the original nested storage, while
extracting a row into a new binding copies it. `values = change(values)` applies
a returned update; calling `change(values)` alone cannot alter the caller.
Existing function-return copies and frame cleanup remain in place. Copies use
the existing invocation arena and recursive size/overflow/allocation guards;
replaced/discarded snapshots remain allocated until that invocation exits.
This is eager copying with no COW, GC or new programmer-visible qualifiers.

Initial implementation policies: unknown array extents can satisfy a known-size
binding, parameter, return or subarray assignment with recursive runtime size
checks during copying. Known incompatible extents remain compile-time errors.
Declared extents are retained; omitted extents infer from the initializer where
known. This enables assigning unsized function results back to inferred-size
bindings safely. Invalid runtime extents terminate with a size-mismatch error.
`:=`/`final` retain binding-only immutability: element writes are allowed but the
binding cannot be reassigned. Only plain indexed `=` is implemented; indexed
compound assignment and increment/decrement remain separate work. The legacy
snippet emitter now uses an ordinary function frame to release its array copies.

Verified on 2026-10-30: the focused indexed-assignment and storage-lifetime tests
passed, followed by all 389 tests in `gradlew.bat :Compiler:test --offline
--console=plain --no-daemon`, with no failures, errors or skips. Eighteen
`ArrayAssignmentTest` cases cover parsing/spans, strict types and dimensions,
all primitive storage representations, independent bindings and parameters,
returned updates, nested construction/extraction/replacement, argument snapshots,
once-only target/RHS order, recursive string-array lifetimes, block/conditional
temporary results, final bindings, interrupted writes, bounds failure precedence,
runtime shape validation, unsupported indexed compounds, legacy snippets and
the normal CLI example in `examples/ArrayAssignment.co`. An added counted and
poisoning allocator test verifies independent modified values and balanced
cleanup across normal/early exits, recursion and discarded results. The full
run initially found a stale settings assertion; it now matches the existing
`examples/FunctionBlocks.co` default. `git diff --check` passed. Native execution
was verified on Windows; implementation uses the existing shared LLVM/ABI paths.

Local function blocks are implemented. `def<Type> name = { ... }` declares a
parameterless computation; each evaluated read executes it again. The explicit
annotation is its return contract. There is no declaration-time execution or
automatic cache. An ordinary binding initialized from its name saves the result.
See the confirmed [function-block semantics](semantics.md#function-blocks),
`functionBlocks` in `../../../../examples/Test.co`, and the runnable
`../../../../examples/FunctionBlocks.co`.

`Parser.parseBinding` recognizes block initializers and reuses the function body
AST inside `FunctionBlockExpression`. Declaration resolution gives each body a
child scope; captures refer to visible enclosing binding identities and read their
current values. The body cannot see its own newly declared binding or later
locals. `BodyTyping` checks the block under its own return contract, including
unused blocks. Its returns do not contribute to the enclosing function's flow.
Existing diagnostics C3030-C3033 label block return failures; C3041 rejects a
missing annotation and C3042 rejects assignment/updates to block bindings.

Native emission expands a fresh body evaluation at each read, with separate local
storage and a block-specific return merge. Both `=` and `:=` declarations are
accepted; neither memoizes. Explicit `<void>` blocks support ordinary void-body
return rules and effect-only reads. Normal argument order, overload selection,
short circuits and strict numeric/result types remain in force. String and array
temporaries use the physical enclosing function's existing storage lifetime;
results returned from that function follow the ordinary deep-copy and cleanup
paths. No escaping closure representation, new public ABI or GC is introduced.
Future block replacement rules and escaping capture lifetimes remain open design
questions. The internal legacy snippet analyzer is not the function-block pipeline;
use source units containing function declarations.

Verified on 2026-10-30: the focused function-block tests and the full
`gradlew.bat :Compiler:test --offline --console=plain --no-daemon` suite passed.
The full suite reports 370 tests with no failures, errors or skips. Nineteen new
`FunctionBlocksTest` cases cover syntax and spans, mandatory types, strict return
contracts and first-error diagnostics, scope/captures, declaration-time deferral,
saved results, argument order and overloads, short circuits, nested returns,
recursion, all primitive representations, void blocks, buffered results and the
normal CLI example. A twentieth test in `StringFunctionTest` uses the existing
counted/poisoning allocator to verify buffered block results and balanced cleanup
across repeated reads, discarded values, recursion and early enclosing-function
returns. Native execution is verified on the current Windows host;
implementation uses the shared LLVM backend without new platform-specific ABI
paths. `git diff --check` passed.

The array-interpolation guard is implemented. Shared expression typing now
rejects unsupported hole values with source-located C3040 before toolchain
discovery, output creation or LLVM emission. Integer/float/bool/char/string
interpolation and non-continuing holes with nested returns remain supported.
Array literals, bindings, parameters, nested arrays, subarrays and array-returning
calls are checked even in skipped branches and overload arguments. Formatting
output and a future `toString()` API remain deferred; see the completed
[formatting guard TODO](../../../../todo/native-formatting-guards.md).

Verification: the focused `StringInterpolationTest` run and the full
`gradlew.bat :Compiler:test --offline --console=plain --no-daemon` suite passed.
The full suite reports 350 tests, no failures, errors or skips. Four new tests
cover semantic rejection, exact source labels, fail-fast CLI rendering,
toolchain/output ordering, and native interpolation/return regressions on the
current Windows host. `git diff --check` passed.

Cross-platform requirement reaffirmed on 2026-10-30: Windows, Linux and macOS
must remain first-class targets with the same Cloth feature contracts. Native
startup/encoding adapters may differ by platform. Unicode argv work is tracked
in [the cross-platform argument TODO](../../../../todo/unicode-command-line-arguments.md).
This requirement does not establish native verification on all three systems.
The later Unicode startup work above records the confirmed invalid-input policy,
implemented adapters and Windows/Linux execution evidence; older evidence below
describes the earlier Windows-only implementation.

Function implementation chunk 8 (array values and function boundaries) is
implemented. Array literals `[1, 2, 3]`, sized/unsized annotations in bindings,
parameters and returns, inferred element types and extents, zero-based indexed
reads, and nested arrays now parse, type-check and emit native code. The user's
exact examples are covered, including diagnostics for both too few and too many
initializers. `int[2][3]` uses C dimension order and nested literals such as
`[[1, 2, 3], [4, 5, 6]]`. Known sizes are retained in inferred bindings and
function signature keys. C3038 reports invalid construction, sizes or element
types; C3039 reports invalid indexing operand/index types. Compilation remains
fail-fast with one diagnostic.

Initial implementation policies (not separately confirmed language decisions):

- Unsized parameter/return dimensions accept matching element types of any
  extent, recursively. Known extents must match sized contracts exactly; the
  later element-assignment work adds runtime validation of unknown extents. A binding
  initialized from a known-size value retains its size even with `[]` in its
  annotation. An unsized function result retains its unknown static extent.
  Unsized contextual return contracts also allow conditional branches to yield
  different sizes; without such context, branch result types must still match.
- Literal-inferred known dimensions are homogeneous and rectangular; the first element supplies
  context for later integer literals. Numeric bindings are never implicitly
  converted. Call context reaches array elements, including floating literals;
  ordinary floating binding/return rules retain their previous behavior.
- `[]` needs contextual element typing; `def<int[0]> empty = []` and
  `fn<int[]> empty() = []` work. Inferred `def empty = []` is rejected.
- Exact default types/shapes win overload resolution; otherwise a unique match
  accepting ordinary array element types is preferred before literal adaptation.
  Distinct explicit array extents can distinguish overloads.
- All supported signed/unsigned integer widths may index an array. Invalid
  negative or past-end indices terminate through a controlled runtime error.
  Bounds are checked before deriving/dereferencing an element address.
- Literals and indexed operands evaluate once in source order. Returns nested
  in elements or indices stop subsequent evaluation. The later element-assignment
  work replaces descriptor-sharing transfers with recursive value copies and
  implements indexed writes. Array equality remains unimplemented.
- At chunk 8, native startup constructed `string[]` from `argc`/`argv`, excluding
  the executable name, and borrowed narrow-string bytes without transcoding.
  The Unicode startup adapters described above supersede that limitation.

At chunk 8, `EmitArray` stored a `{ ptr, i64 }` descriptor with storage-typed elements,
including byte storage for bools and descriptors for strings/nested arrays.
Local literals use stack buffers. Array-returning functions receive the same
private caller-arena parameter as string-returning functions. `EmitStringArena`
now recursively copies array storage and all nested string bytes into the caller's
arena, guarding size multiplication and allocation overflow. `EmitFunctionFrame`
copies the outgoing value before releasing the current invocation's buffers.
This remains temporary value storage, with no GC or changes to object lifetime
rules. Native array parameters and returns no longer report C4030.

`examples/Arrays.co` demonstrates construction, indexing, multidimensional
arrays and function calls/returns. README describes the executable support.

Verified on 2026-10-30: `gradlew.bat :Compiler:test --offline --console=plain
--no-daemon` passed all 346 tests, with no failures, errors or skips. The 18 new
`ArraysTest` cases cover the user's declarations, size inference and mismatches,
strict element types and immutability, malformed syntax and source spans,
sized/default-element overload selection, C dimension order, native primitive
storage, nested string-array returns across recursion, source evaluation order,
interrupted elements/indices, empty arrays, unsigned indexed-operand context,
different-size conditional returns, controlled bounds failures, command-line
arguments, library emission and the normal CLI example. An additional counted
and poisoning allocator test checks deep copies and balanced cleanup on normal,
early and discarded array-return paths, null allocations and multiplication
overflow before allocation. Updated prior capability tests now expect supported
array object/startup emission. A stale settings assertion was aligned with the
existing `examples/StringFunctions.co` default. `git diff --check` passed.
Native execution is verified on the current Windows host.

Review audit on 2026-10-30: the existing full compiler suite passed again using
`gradlew.bat :Compiler:test --offline --console=plain --no-daemon`. Additional
temporary probes found follow-up gaps that are not covered by those passing tests:

- Array interpolation, including `fn main() { #"{[1]}" }`, passed semantic
  checking but fails with C9000 (`Array printing is not implemented`) during
  emission. Unsupported interpolation operands need a source-located semantic
  diagnostic unless array formatting is separately designed and implemented.
  The user deferred native type-formatting design and requested a dedicated
  guard TODO: [native formatting guards](../../../../todo/native-formatting-guards.md).
  This guard is now implemented as C3040; see the current-work record above.
- Native `main(string[] args)` did not preserve the argument `é中😀` on the
  current Windows host: comparison against the same source literal returned
  status 1; the ASCII argument control returned 0. This reproduces the previously
  documented native narrow-string/Unicode-transcoding limitation. Windows argv
  construction needs lossless Unicode input converted to Cloth UTF-8 storage.
- There is no source-level array length query; `args.length` reports C2003.
  A length/count contract remains needed before programs can safely inspect
  variable-length command-line input without assuming a minimum argument count.
  Element mutation and array equality remain explicitly deferred design work.

No implementation fixes were made during this review. Probe observations are
saved in the ignored `build/review-chunk8/observations.txt`; the temporary probe
test source was removed after execution.

## Earlier milestone snapshots

The following records describe prior chunks. Array limitations in those records
are superseded by chunk 8 above; consult the current source map as well.

Function implementation chunk 7 (string function boundaries and return lifetimes)
is implemented. Native functions now accept and return the existing immutable
UTF-8 `{ ptr, i64 }` string descriptor. Parameters borrow the caller's bytes for
the duration of a call; reassignment still changes only the local binding.
String return values, including literals, borrowed parameters, local concatenations
and interpolation buffers, are copied before the callee returns. They remain
valid across later calls, reassignment, direct/mutual recursion and early returns.
String semantic types and overload rules are unchanged; C4030 now guards array
boundaries only.

Initial internal storage policy: a string-returning function has an additional
private LLVM parameter pointing to its caller's return-buffer list. At each
return it allocates and copies bytes into that list. Each invocation that
receives string results has its own lazily introduced head slot, initialized in
entry even when first used in a branch. `EmitStringArena` generates module-local
copy/release helpers, uses the target C ABI's size_t for malloc sizes, checks
size-addition overflow and null allocation, and releases buffers with free.
An allocation failure is a controlled runtime error rather than an unchecked
null dereference. Empty strings retain a valid address and zero byte length.

`EmitFunctionFrame` joins every normal exit at one return block. It merges
actual return predecessors, copies an outgoing string into the caller's storage,
then releases the current invocation's received results before returning.
Void fallthrough, bare/void-expression returns, scalar results and returns
nested inside expressions/call arguments all use this path. Runtime failures
terminate the process through the existing error path; they do not unwind frames.
Argument snapshots still evaluate once, left to right, before the callee runs.

This temporary value-storage policy adds no GC, reference counting, automatic
object destruction, programmer-visible ownership qualifiers or object lifetime
API. Local concatenation/formatting buffers still use their creating stack frame;
only returned strings require heap-backed copies. Every string return currently
copies, including literals and forwarded arguments, and received buffers live
until their receiving invocation exits, including ignored/discarded results.
This trades allocation/copy efficiency for a simple invocation lifetime rule;
zero-copy/escape optimization and strings stored in future objects or
containers remain separate work. The descriptor and hidden argument are a
provisional internal ABI, not a public C or cross-module export contract.

`examples/StringFunctions.co` exercises returned interpolation/concatenation and
recursive construction through the normal CLI with silent status checks. The
README and unsupported-boundary guidance now describe string support. Arrays,
including native construction for `main(string[] args)`, remain chunk 8 work.

Verified on 2026-10-30: `gradlew.bat :Compiler:test --offline --console=plain
--no-daemon` passed all 327 tests with no failures, errors or skips. The 17 new
`StringFunctionTest` cases cover borrowed parameters/rebinding, final parameters,
literal/raw/empty/Unicode/NUL returns, local concatenation and single-hole
interpolation, retained copies across subsequent calls, argument snapshots/order,
direct/mutual recursion, conditional return merges, interrupted arguments,
overloads, runtime-name collisions, object emission and dynamic CRT linkage.
Allocation wrappers count live buffers and poison bytes before free; normal,
early, scalar, void and discarded-result exits leave no outstanding allocations.
Injected null allocations and oversized internal descriptors exercise controlled
runtime failure and overflow rejection before allocation. Skipped code allocates
no return buffers, and array capability errors still precede output creation.
After adding branch-only allocation and pre-allocation early-return cases to the
cleanup coverage, a focused rerun of all 17 string tests also passed. The full run
also executed `examples/StringFunctions.co` through the CLI. Native execution was
verified on the current Windows host; other platforms' execution is not claimed.
`git diff --check` passed.

Function implementation chunk 6 (program entry and CLI migration) is implemented.
`Parser.parse()` now returns a declaration `SourceUnit`; the CLI runs the complete
function pipeline and starts an executable through its selected Cloth `main`.
File-level executable statements are rejected. Internal `parseSnippet`,
`compileSnippet` and `emitSnippet` helpers retain isolated body-fragment tests
and their result-printing instrumentation, without exposing snippet compilation
as a production source-file mode.

`ProgramAnalyzer` validates executable versus library/object mode on top of
`FunctionAnalyzer`. Executables require exactly one `main`, accepting no parameters
or one `string[]` parameter, and void or signed int8/16/32/64 returns. Parameter
names and `final` do not affect entry selection. Library mode requires no entry
and treats `main` as an ordinary function name. New entry diagnostics are C3034
(missing main, with an EOF label), C3035 (multiple entry declarations), C3036
(invalid parameters) and C3037 (invalid return type). Same-signature duplicate
functions still fail earlier with C3019. Declaration/body/entry and boundary
capability errors precede linker discovery and output creation; compilation
continues to stop at the first error.

`EmitEntryPoint` emits the native C `main` adapter and invokes the selected,
mangled Cloth function. Void completion returns zero. Integer results become
the native status: narrower signed results are sign-extended to C `int`; wider
results retain their low bits. The user confirmed low-bit truncation rather than
a runtime error for out-of-range int64 statuses on 2026-10-30. Host operating
systems may further constrain the status observed by a parent process. Native
programs do not implicitly print expression results.

`LLVMEmitter.compile(SourceUnit, Path)` emits and links executables;
`emitExecutable(SourceUnit)` produces verified startup IR. `emit(SourceUnit)`
still emits a declaration module without startup. CLI `--emit executable|object`
defaults to executable; object mode performs full function checking and object
emission without requiring main or discovering a linker. Its default filename
has a `.o` suffix. This is not yet an archive/shared-library or export ABI:
function symbols retain chunk 5's provisional internal linkage.

Runnable examples now contain explicit `main` declarations and verify their
calculations using process status 0/1. `examples/Functions.co` adds forward calls,
factorial and mutual recursion. CLI example tests check successful execution and
empty output. No console I/O syntax or library was invented. `examples/Test.co`
remains the broad design example, not a fully supported source file.

The `main(string[] args)` signature is semantically valid, but native array
construction remains chunk 8 work. It currently fails with C4030 before output
creation, as do other array function boundaries. String function boundaries and
return lifetimes are now supported by chunk 7 above. Chunk 6 introduced no heap
or object lifetime behavior.

Verified on 2026-10-30: the focused `ProgramEntryTest`, `CompilerSettingsTest`
and `DiagnosticsTest` run passed all 36 tests. The 24 new entry tests cover
declaration-only source parsing, missing/duplicate/invalid main diagnostics,
valid signature forms and signed widths, library entry independence, native
startup IR, silence without implicit printing, void and integer exit statuses,
wide low-bit truncation, narrow sign extension, recursion, source snapshots/EOF,
errors before output/linker discovery, CLI path handling, object output/defaults,
and unsupported argument arrays. The full
`gradlew.bat :Compiler:test --offline --console=plain --no-daemon` run passed all
310 tests with no failures, errors or skips, including the migrated runnable
examples and all prior function, scalar/string, diagnostics and toolchain tests.
Native execution was verified on the current Windows host; execution on other
platforms is not claimed. `git diff --check` also passed.

Function implementation chunk 5 (LLVM functions, calls and recursion) is
implemented through `LLVMEmitter.emit(SourceUnit)` and
`LLVMEmitter.compileObject(SourceUnit, Path)`. Both run the full function
analyzer and verify generated LLVM. Declaration modules do not synthesize a
native `main` or choose a Cloth entry point; object emission works without main
and does not invoke a linker. Chunk 6 above adds CLI startup separately.
Native tests compose a separate LLVM runner through an internal module
hook; its output printing and zero process status are test instrumentation, not
Cloth startup or implicit output behavior.

`EmitFunctions` declares every LLVM signature before emitting bodies, using the
selected function symbols from semantic analysis. Overloads have distinct,
deterministic module-local names (`cloth.fn.<index>.<name>`) and internal linkage,
avoiding native runtime-name collisions. This provisional naming does not define
cross-module exports or a C interop ABI. Direct, forward and mutual calls now
execute natively. Arguments are evaluated once, left to right, and their values
are captured before entering the callee; parameter reassignment changes only
that invocation's storage.

Scalar bindings are keyed by stable symbols in function emission, preserving
parameter/local/branch shadowing. Each function has independent parameter/local
storage, with scalar allocas inserted in its entry block and initializers/stores
left at their source execution points. Boolean storage remains one byte with
i1 values. All signed/unsigned integer widths, float16/32/64, bool, char and void
boundaries are supported. Float16 calls pass/return their raw i16 bits internally
and bitcast at the boundary, avoiding external half-conversion ABI helpers while
preserving Cloth's half type and rounding behavior.

Expression emission distinguishes void/non-continuing evaluation from values.
Explicit and implicit returns, bare/void-expression returns, and `-> void` now
emit without invented numeric placeholders. Emission stops after a terminator,
including returns nested in operands, initializers or call arguments, so later
operands/arguments and the callee are skipped when required. Conditional/logical
merges include only actual continuing predecessors; returning paths add no phi
input or extra branch. Merge blocks with no predecessors end in `unreachable`.
Simple boolean facts from semantic flow analysis prune infeasible alternatives
only after evaluating the condition/left operand, preserving its side effects.
Nested casts, strings and power guards retain their actual LLVM predecessor blocks.

Native string boundaries are now supported by chunk 7 above; array boundaries
remain unsupported pending their storage chunk. C4030 reports an unsupported
boundary type with its source span before output creation. Function-local strings,
interpolation and string operators remain supported; returned bytes are copied
into caller storage before the creating invocation exits. Chunk 5 itself added
no heap, GC, object lifetime mechanism or automatic object cleanup.

Verified on 2026-10-30: focused `FunctionEmissionTest` passed all 26 tests using
`gradlew.bat :Compiler:test --tests cloth.FunctionEmissionTest --offline
--console=plain --no-daemon`. Coverage includes verified IR/object modules without
entry synthesis, overload targets, forward calls, factorial/Fibonacci/mutual
recursion, parameter isolation, once-only argument ordering/snapshots, every
integer width/signedness boundary, booleans/Unicode characters, floating precision
and binary16 rounding/ABI, entry allocations and shadowing, early-return phi
predecessors, nested/non-continuing operands/arguments, short circuits and boolean
proofs, void/discard emission, skipped unreachable code, cast guards, local strings
across recursion, runtime-name collisions, unsupported boundaries before output,
and dynamic-CRT power calls. Native execution was verified on the current Windows
host; other platforms' native function execution is not claimed.
The full `gradlew.bat :Compiler:test --offline --console=plain --no-daemon` run
then passed all 286 tests with no failures, errors or skips, including the existing
semantic, native scalar/string, diagnostics, toolchain and CLI regressions.

Function implementation chunk 4 (return validation and control flow) is
implemented through `FunctionAnalyzer.analyze(SourceUnit)` or
`FunctionAnalyzer.analyze(DeclarationModel)`. This combines the implemented
declaration, call and return stages and returns `FunctionSemanticModel` with
`controlFlow` metadata. The public `CallAnalyzer()` remains a stage 3 entry point
without return validation, so earlier-stage tests and signature-only body
fixtures retain their meaning. This checker does not introduce a CLI mode or
itself emit native functions; chunk 5 above provides declaration-based emission.

The full checker enforces omitted/explicit void returns, bare early returns,
explicit returns in braces, implicit `=` returns, exact result types and
contextual integer literals. Void functions may return void expressions, as
newly confirmed by the user (`return cleanup()` or `return calculate() -> void`).
They cannot return a produced value, including through an `=` body without an
explicit discard. Typed numeric results require casts to change width, signedness
or kind; floating return literals retain existing binding rules, while the
contextual floating parameter rules from chunk 3 remain intact. Return context
still does not select overloads. Non-void brace bodies cannot fall through, and
their final expressions do not implicitly return.

`ControlFlowModel` tracks continuing and returning paths for expressions,
statements and function bodies. It handles ordered evaluation, nested conditionals,
returns in initializers/call arguments/interpolation, and short circuits. Simple
boolean literals, negation and logical combinations can prove branch reachability;
arbitrary arithmetic comparisons, binding values and callee termination remain
conservative. It does not attempt to prove recursive calls terminate or diverge.
Boolean facts and flow summaries use identity caches to avoid repeated traversal.

Value-producing conditional branches that return from the enclosing function do
not also need a final value expression. Continuing branches retain the existing
final-expression and compatible-result rules. An internal `ValueType.Never`
marks expressions with no continuing result; it has no Cloth syntax and is not
a new programmer-visible type. This allows all-returning branches to compose
inside calls, assignments, arithmetic and other existing expressions without
inventing a value or claiming fallthrough. LLVM value/storage/printing helpers
guard this internal type; chunk 5's emitter now handles flow without requesting
a value or storage for non-continuing expressions.

Initial implementation policy: unreachable statements and skipped branches remain
name/type/return checked, and otherwise valid unreachable code is accepted without
a new warning or error. `=` conditional bodies are checked as expressions rather
than statement conditionals, so their continuing branches must supply compatible
final expressions, including void expressions where appropriate. The existing
final-else rule for value expressions is retained when they can continue. These
details do not settle broader future unreachable-code or conditional design.

New structured diagnostics are C3030 (bare return requires a value), C3031
(produced value in void function), C3032 (return type mismatch), and C3033
(non-void body can reach its closing brace). They label the returned expression
or closing brace and the function's declared return contract. Return-expression
errors inside argument probes stay fatal instead of becoming overload mismatches.
Compilation still stops at the first detected error; no recovery or diagnostic
accumulation is added.

Verified on 2026-10-30: the focused `FunctionReturnsTest`, `CallResolutionTest`,
`DeclarationScopesTest`, `FunctionParsingTest` and `DiagnosticsTest` run passed
all 103 tests. The 30 new return/flow tests cover both body forms, void expressions
and discards, exact result types, every integer width/signedness boundary,
conditional path coverage, nested/non-continuing expressions, short circuits,
constant boolean facts, long logical chains, checked unreachable code, recursive
calls, probe diagnostics, overload preference preservation, array/string type
identity, source spans/CRLF, first-error rendering, analyzer reuse and earlier
stage boundaries.
The final full `gradlew.bat :Compiler:test --offline --console=plain --no-daemon`
run passed all 260 tests with no failures, errors or skips, including existing
native and CLI regressions. Native execution was verified on the current Windows
host. Native scalar function emission is now implemented by chunk 5 above.

The CLI now uses chunk 6's declaration and entry pipeline. Native scalar function
calls/returns are available through chunk 5, and string boundaries through chunk 7.
Argument-array construction remains a subsequent chunk; semantic array return
checking does not establish runtime array storage support.

Function implementation chunk 3 (call resolution and argument validation) is
implemented through `CallAnalyzer.analyze(SourceUnit)` or
`CallAnalyzer.analyze(DeclarationModel)`. It resolves primitive signature types
and semantic array identities, then checks function bodies using chunk 2's stable
symbols. `FunctionSemanticModel` records parameter/local types, expression and
statement types, and the selected `FunctionSymbol` for each call. Forward, direct
and mutual calls use declared return types without recursively analyzing the
called body's return paths. Unknown nominal types still receive an unsupported
type diagnostic; class/type declaration resolution is a later feature.

Calls first filter overloads by argument count, then prefer an exact match for
the arguments' ordinary types. Thus integer literals prefer default `int`, and
floating literals prefer default `float` (`float<32>`), independently of source
order. Without a whole-argument exact match, candidates are checked with their
parameter types as literal context. A unique viable candidate wins; two viable
candidates are ambiguous, and none produces a no-matching-call error. Integer
and floating literals receive only their own numeric kind of context. Already
typed bindings, casts and call results never implicitly change type to fit a
parameter. Return types and surrounding assignment/return expectations do not
select overloads. Initial implementation policy for multiple arguments: prefer
a whole exact match, otherwise require one viable candidate; there is no summed
conversion score or preference based on the number of contextual matches.

The user confirmed contextual floating literals at call sites as well as default
`float` preference. They retain original decimal text until a width is selected;
range checks use that width, including float16 overflow and literals beyond the
default float32 range that fit float64. The declaration parser therefore defers
floating literal range checks until typing. The legacy snippet parser retains
its existing default-float range diagnostics. This change supplies floating
parameter context; it does not broaden the existing floating literal rules for
binding assignments or return values. Integer literal context remains unchanged.

Overload probes use separate type/binding/call maps; only the selected probe's
facts are committed. A nonviable candidate does not construct a diagnostic or
leave literal types, branch-local types or nested call selections behind. Probe
rejection is a private control signal, not compiler error recovery or diagnostic
collection. Fatal call failures still throw one structured diagnostic immediately.
New codes are C3023 (argument count), C3024 (no match), C3025 (ambiguity), C3026
(void used as a value), C3027 (function used as a binding value), C3028 (floating
literal range) and C3029 (invalid void parameter/binding/array element type).
Call diagnostics label the call and relevant declarations.

`ExpressionTyping` and `TypeResolver` now share existing primitive expression,
binding, assignment and conditional typing rules with the legacy analyzer.
Calls can compose with expressions, inferred bindings, conditionals, interpolation
and casts; skipped code is checked. Standalone calls may discard values, and the
declaration checker supports explicit `-> void`. Void cannot supply an argument
or binding value. Array element/rank identity supports strict parameter passing,
but array storage, indexing and command-line argument construction are not
implemented; existing LLVM/C printing paths explicitly guard unsupported arrays.

The CLI now uses chunk 6's declaration and entry pipeline. Return-contract/flow checks are
available through chunk 4's `FunctionAnalyzer`, and function emission through
chunk 5 for scalar/void boundaries and chunk 7 for strings. Entry validation is
implemented by chunk 6; command-line argument construction remains pending. The stage 3 entry point
alone does not perform return checks or execute functions; chunk 5 enforces the
confirmed once-only evaluation order without changing AST argument order.

Verified on 2026-10-30: the focused `CallResolutionTest`, `DeclarationScopesTest`,
`FunctionParsingTest` and `DiagnosticsTest` run passed all 73 tests. The 29 new
call tests cover default int/float preferences, contextual widths, ambiguity,
numeric bounds, strict typed arguments/casts, arity, nonnumeric types, multiple
arguments, return-context independence, nesting, recursion, probe isolation,
contextual arithmetic, arrays, discards/void, skipped code, signature diagnostics,
deferred return/entry checks, source locations and analyzer reuse. The full
`gradlew.bat :Compiler:test --offline --console=plain --no-daemon` run then passed
all 230 tests with no failures, errors or skips, including existing numeric,
string, native and CLI regressions. Native execution was verified on the current
Windows host, without claiming function code generation.

Function implementation chunk 2 (declaration collection and lexical scopes) is
implemented through `DeclarationAnalyzer.analyze(SourceUnit)`. It collects all
function signatures and overload groups before resolving any bodies. Signature
keys include ordered parameter types, normalizing default numeric widths (so
`int` and `int<32>` conflict), while preserving signedness, explicit widths,
array rank and nominal type names. Return types, visibility, parameter names and
`final` do not distinguish overloads. These are structural declaration keys;
type names and widths are not yet semantically resolved or validated.

`DeclarationModel` records identity-based symbols for functions, parameters and
local bindings, lexical scopes, name uses, assignment/update targets, and visible
overload groups for calls. Every function gets its own scope beneath file scope;
parameters and outermost body locals share it. Nested conditional branches get
separate child scopes and may shadow enclosing bindings. Locals become visible
after their initializer is resolved, preserving the snippet analyzer's existing
behavior; a shadow's initializer can read the enclosing declaration. Branch
locals do not escape to sibling conditions/bodies, outer scopes or other functions.
The user confirmed one namespace: nearer bindings shadow functions even in call
position, producing a not-callable error rather than bypassing the binding.

The resolver checks duplicate function signatures/parameter/local names, unknown
names, non-callable binding calls, attempts to assign to functions, and immutable
binding/final-parameter writes. It traverses current expression and statement
forms, including nested calls, interpolation and skipped branches. Diagnostics
stop immediately and preserve original declaration/use spans; new codes are
C3019 (duplicate function), C3020 (unknown name), C3021 (not callable) and C3022
(assignment requires a binding). Final-parameter diagnostics reuse C3002 with
parameter-specific guidance. Calls retain their whole overload group without
argument-count filtering, type validation or overload selection in this stage;
the chunk 3 checker above handles those later. Direct and mutual
recursion now resolve at the name level, without claiming native function execution.

The declaration pipeline powers the CLI; internal snippet test helpers remain separate.
Call argument/type validation and overload selection are now available through
chunk 3, return checks through chunk 4, and scalar function emission through
chunk 5, and entry-point validation through chunk 6. Invoking only the chunk 2 analyzer may still leave
type-invalid calls/returns or both `main` forms; later stages enforce those contracts.

Verified on 2026-10-30: the focused `DeclarationScopesTest`, `FunctionParsingTest`
and `DiagnosticsTest` run passed all 44 tests using `gradlew.bat :Compiler:test
--tests cloth.DeclarationScopesTest --tests cloth.FunctionParsingTest --tests
cloth.DiagnosticsTest --offline --console=plain --no-daemon`. The 20 new
declaration/scope tests cover forward/direct/mutual calls, overload groups and
canonical duplicate signatures, parameter/local duplicates, branch shadowing and
isolation, initializer visibility, symbol identity for reads/writes, function-name
shadowing, immutability, skipped-code traversal, analyzer reuse, deferred later
checks, source spans/CRLF and first-error behavior.
The full `gradlew.bat :Compiler:test --offline --console=plain --no-daemon` run
then passed all 201 tests with no failures, errors or skips, including existing
native and CLI regressions. Native execution was verified on the current Windows
host; this does not imply function code generation is implemented.

Function implementation chunk 1 (declaration AST and body parsing) is implemented.
`Parser.parseDeclarations()` returns a `SourceUnit` containing function
declarations, separate from the existing executable-snippet `ASTStructure`.
It parses optional `pub`, omitted or explicit return annotations, typed parameters
with optional leading `final`, brace bodies, and `= expression` bodies. Omitted
return annotations remain absent in the AST, with the confirmed meaning of void.
Function-signature types preserve numeric widths/signedness and unsized `[]`
suffixes, including `main(string[] args)`, without implementing array values.

Function and conditional branches share brace-body parsing. `ReturnStatement`
preserves bare and value returns; `ExpressionBody` remains distinct from a brace
body's final expression. Named calls preserve argument order, nest inside ordinary
expressions and interpolation, and retain their closing-parenthesis source spans.
Function, parameter, body, return, and array-suffix spans retain the source snapshot,
including CRLF input. `final` is now a keyword. Word operators `for`/`while`/`do`
require identifier boundaries, fixing names such as `format` and `doubleValue`
that were previously split into operator prefixes.

The declaration entry point rejects file-level executable statements and nested
`fn`/`mt` declarations. It currently supports only `fn` declarations, not the
broader class/module/import grammar in `Test.co`. Parameter and argument lists
allow empty lists and multiline contents; trailing commas are currently rejected.
These list-format details are initial parser choices, not separately confirmed
language decisions. Return statements are recognized only within function bodies.

`Parser.parse()` and the CLI now use declarations, following chunk 6's entry-point
migration. The distinct `SourceUnit` type cannot be
passed accidentally to the existing snippet analyzer/emitter. Declaration
collection and lexical resolution are now available through the chunk 2 API
above, typed calls through chunk 3, and return validation through chunk 4.
Scalar function LLVM emission is available through chunk 5. Syntactically valid but semantically invalid functions can still
be parsed independently of those stages. Internal snippet test instrumentation
synthesizes a native runner; declaration modules add startup only when explicitly
compiled as executables. The confirmed [function contract](semantics.md) remains the
design authority. Local dynamic string buffers remain in their creating function;
chunk 7 implements safe string returns by copying into caller-owned storage.

Verified on 2026-10-30: the focused `FunctionParsingTest` run passed all 15 tests
using `gradlew.bat :Compiler:test --tests cloth.FunctionParsingTest --offline
--console=plain --no-daemon`. Coverage includes declaration/body structure,
visibility, mutable/final parameters, numeric and array signature syntax,
multiline lists/comments, nested statements/conditionals/calls/interpolation,
return boundaries, precedence, explicit void casts, deliberately deferred semantic
checks, malformed syntax and declaration contexts, source spans/CRLF/EOF,
first-error reporting, and identifier boundaries for word operators.
The full `gradlew.bat :Compiler:test --offline --console=plain --no-daemon` run
then passed all 181 tests with no failures, errors, or skips, including the 15
new parser tests and existing native/CLI regressions. The first full run exposed
a stale `StringInterpolationTest` expectation: committed `examples/EX.co` then
printed `The answer is: 400.200012`, not `The answer is: 20`. Updating that test's
expected output resolved the failure; the example and backend were unchanged.
Native execution was verified on the current Windows host.

Compound assignments `name += value`, `-=`, `*=`, `/=`, and `%=` now parse as
ordinary local binding assignments whose values use the corresponding binary
operation. Standalone `name++` and `name--` add or subtract a same-type one,
including floating types without promotion of half values to float32 bindings.
Existing exact binding-type, mutability, contextual integer literal, signedness,
width, and arithmetic rules apply; string `+=` uses ordinary concatenation.
The target's current value is read before the right operand, which runs once.
Only simple local binding targets are currently implemented. Update operators
are standalone postfix statements only, as explicitly confirmed by the user.
`x ** y` exponentiation is implemented. Integer results retain their operand
type and use exponentiation by squaring with ordinary wrapping multiplication,
preserving uint64 bits without a floating-point conversion. Direct negative
integer exponent literals report C3018; other negative integer exponents raise
a controlled runtime error. Both operands run once, left to right. Floating
and mixed powers use ordinary promotion, C `powf`/`pow`, and half result rounding.
Initial choices not separately confirmed as design: powers associate right,
bind above unary signs and multiplication, and zero to the zero power is one.
Existing matching integer width/signedness requirements apply. `//` remains a comment.
`examples/EX.co` uses `x += 1`; chunk 6 now checks its result through main status.

Verified on 2026-10-30: `gradlew.bat :Compiler:test --offline --console=plain
--no-daemon` passed all 166 tests with no failures, errors, or skips. The 14 new
`AssignmentOperatorsTest` cases cover compound precedence and types, mutable
and immutable targets, unsigned wrapping, string concatenation and copies,
target/RHS evaluation order, branch scopes, numeric updates including half
storage, rejection of prefix/expression updates, power grouping, all integer
widths/signedness, exact uint64 results, float promotion and special values,
static and runtime negative-exponent failures, nested control flow, short
circuits, and dynamic CRT power calls. At that stage CLI tests verified EX.co output.
Native execution was verified on the current Windows host.

String interpolation is implemented for `#"Hello, {name}!"`. The original
`#"The answer is: {x}"` example produced `The answer is: 20` in the snippet
runner; the current `examples/EX.co` checks its updated calculation inside main.
The lexer emits text and expression delimiters with original
source spans, recursively lexing holes through the ordinary scanner. Nested
interpolation, quoted/raw strings and characters, comments, casts, arithmetic,
and value-producing conditionals work inside holes. Embedded expressions are
checked normally, even in skipped code, and evaluated once, left to right.
Ordinary string `+` remains restricted to two strings.

Initial implementation choices, not separately confirmed language decisions:
`{{` and `}}` escape literal braces; unescaped `}` is C1012 and an unclosed
hole is C1011. Surrounding text uses ordinary quoted-string escapes and cannot
contain literal line breaks; hole expressions may span lines. There is no raw
interpolated-string form or custom format-specifier syntax. All currently
supported primitive values can be embedded. Strings preserve their UTF-8 bytes
and lengths, characters encode one scalar including NUL, booleans become
`true`/`false`, and numbers use the same ABI-aware decimal formats and precision
as native result printing. Numeric formatting uses C `snprintf` with a checked
64-byte buffer, enough for every supported integer and `%g` float representation.
Generated text uses the existing local string-buffer lifetime; this does not
settle dynamic/escaping string lifetime design. Empty text segments are skipped.

Verified on 2026-10-30: the full compiler suite passed 149 tests using
`gradlew.bat :Compiler:test --offline --console=plain --no-daemon`. After adding
three final regression cases, the focused `--tests cloth.StringInterpolationTest`
run passed all 14 interpolation tests with no failures, errors, or skips. Coverage
includes source spans, malformed syntax and Unicode, strict/skipped-code checks,
all numeric widths and signedness, native formatting precision, UTF-8 boundaries,
NULs, braces/escapes, nested strings/comments/conditionals, once-only evaluation
order, short circuits, reassignment/copies, large buffers, first-error behavior,
the C ABI declaration, static/default and dynamic CRT execution, and EX.co via CLI.
Native execution was verified on the current Windows host.

The user selected structured compiler diagnostics through Yvette 0.2.0. The
`cloth.diagnostics` package now defines stable codes, source labels, predefined
lexer/syntax/semantic/CLI/toolchain/backend factories, and one graphical reporter.
`ClothDiagnostic` implements Yvette's `Diagnostic` directly; syntax and semantic
exception types remain available as structured subclasses. Jansi supplies the
renderer dependency, and prettier4j 0.3.1 is explicit because Yvette's public API
exposes `Doc` but declares prettier4j only as a runtime dependency.

The source snapshot travels with token spans. Expressions, statements, literal
nodes, types, conditional bodies, and binding declarations preserve locations.
Internal positions are one-based UTF-16 with exclusive ends; the renderer converts
to zero-based Yvette ranges and expands tabs at four-column stops. Its source
adapter preserves CRLF input and empty final lines, including EOF labels.
Yvette's wide/combining Unicode display-width limitations remain. The reporter
uses terminal detection for colours/links and falls back to ASCII drawing when
stderr's encoding cannot represent the Unicode theme.

String-plus-integer expressions report C3017, label both operands, and explain
that concatenation requires two strings. Assignment, duplicate, and immutable
binding failures include the original declaration. Lexer, parser, semantic,
CLI/file, ABI, toolchain, linker, and LLVM failures use the shared diagnostics.
Internal compiler invariants use C9xxx; low-level type-constructor assertions stay
internal and are converted at the CLI boundary if reached during compilation.
`runCompiler` returns a status for embedding/tests; main exits unsuccessfully for
errors. Expected failures return 1, internal failures return 2, and `--debug` shows
retained causes and stack traces. The CLI defaults to `examples/EX.co`, preserving
the user's selected source. Semantic checks run before target/toolchain resolution
or output creation. Compilation stops at the first detected error by explicit
user decision and Cloth philosophy. Error recovery and multi-error collection
must not be introduced; they are not pending features. Multiple source labels
may explain the same error. Native runtime cast and string-length errors retain
their existing C ABI reporting path.

Verified on 2026-10-30: `gradlew.bat :Compiler:test --console=plain` passed
all 138 tests with zero failures, errors, or skips. Nine diagnostic tests cover the
EX.co rendering snapshot, code uniqueness, severity/help, operand and declaration
ranges, literal/composite spans, CRLF/tabs, trailing-newline and empty-source EOF,
multiline lexical failures, CLI file/argument failures, opt-in debug traces,
semantic errors preceding an invalid linker, and Windows ASCII fallback. Existing
language tests now assert structured source locations instead of message suffixes;
ABI/toolchain tests check diagnostic codes. The conditional CLI example's stale
expected output was corrected to its existing `It is unsigned!` result. The EX.co
CLI also rendered C3017 and exited with status 1, without a compiler stack trace.

The user selected `if` / `else if` / `else` conditionals next and requested
implementation. Inline and multiline chains, nested conditionals, boolean
conditions, branch scopes, semantic checks, and LLVM emission are implemented.
A conditional can initialize or reassign a binding and compose with ordinary
expressions. A standalone conditional executes its branch without an implied
value or automatic last-value output; it may omit `else` and have empty branches.
Conditions run once in order until the first true condition, and only the
selected body executes. Skipped conditions and bodies are still type checked.

The initial implementation uses these rules for details not separately settled
by the user: a value-producing chain requires a final `else`; every branch must
end with an expression, which supplies its value; and all branch results must
have exactly the same type, including numeric width and signedness. Declared
integer binding types provide context for branch-result integer literals, using
the existing literal rules. There is no implicit branch-result numeric promotion.
Branches have separate lexical scopes, may shadow enclosing names, and may
assign enclosing mutable bindings. Local names do not escape into sibling
conditions, sibling branches, or the enclosing scope. These initial choices
are recorded as implementation behavior, not additional confirmed user decisions.

Primitive `is Type` checks are implemented, including `a is unsigned int`, exact
numeric widths/signedness, and `bool`/`char`/`string` targets. The operand is
evaluated once even when its type is statically known. Checks use comparison
precedence, below casts/arithmetic and above equality/logical operators. Class
runtime type checks, state checks, and class-type narrowing remain unimplemented.
`../../../../examples/Conditionals.co` is runnable and checks `It is unsigned!`;
the user's original initializer in `../../../../examples/Test.co` is covered by
a native test, without claiming that the rest of that design file compiles.

The user selected string definitions after characters. Explicit `def<string>`
and inferred string bindings, mutable `=` / immutable `:=` declarations, copies,
reassignment, parenthesized literals, and identity casts are implemented. The
user then selected equality and concatenation: string `==` / `!=` and `+` are
implemented with strict string operands, ordinary precedence, and once-only
left-to-right operand evaluation. Equality returns `bool`; concatenation returns
`string` without changing either input.
Ordinary double-quoted literals decode `\n`, `\r`, `\t`, `\0`, `\\`, `\'`, and
`\"`; unknown escapes, line breaks, unterminated strings, and isolated UTF-16
surrogates are rejected. Triple-quoted raw strings preserve their contents,
including newlines, quotes, and backslashes, while validating Unicode scalars.

LLVM string values and binding storage currently use `{ ptr, i64 }`: a pointer
to UTF-8 bytes and their byte length. Literal bytes use private constant storage
for the program's lifetime. Concatenation checks length-addition overflow before
allocating a fresh local byte buffer, then copies both operands with LLVM memcpy
intrinsics. These buffers last for the creating function, including separate
recursive invocations. Function returns now copy into caller-owned buffers through
chunk 7; strings escaping into future objects remain separate work. Copying a binding
copies its descriptor; reassignment does not change previous copies. There is
no heap allocation for local concatenations; chunk 7 uses malloc/free for return
buffers without adding GC, reference counting or language-level object destruction.
This is an internal implementation choice, not a decision about dynamic string
APIs or language-level object lifetime. Internal test result output walks the stored
length through ABI-typed `putchar`, preserving Unicode, empty strings, embedded
NULs, and percent signs. Equality checks lengths before ABI-typed C `memcmp`
using the profile's unsigned pointer-width `size_t`, and compares exact contents
without Unicode normalization or case folding. Empty strings and NULs work in
both operators. Comparison branches compose with nested short-circuit logic;
skipped concatenations are not evaluated. A shared `EmitRuntimeError` emits
existing numeric-cast failures and new concatenation length-overflow failures.
String ordering, other arithmetic, logical operations on strings, cross-type
conversions outside interpolation, indexing, and nullable annotations are not implemented.
Unsupported operations are rejected
before LLVM emission. `../../../../examples/Strings.co` demonstrates bindings and
checks `Unicode: é 中 😀`; it also demonstrates concatenation, equality, and
copies preserved across reassignment. Runnable examples and CLI regression paths use
the repository's `examples` directory.

The user selected `char` next and confirmed 32-bit Unicode scalar storage.
Explicit `def<char>` and inferred bindings, immutable and mutable declarations,
copies, reassignment, parenthesized literals, character `==`/`!=`, and identity
casts are implemented. Characters are distinct from numbers and booleans;
arithmetic, ordering, logical operators on characters, cross-type comparisons,
and casts to/from other types are rejected pending language design. `unsigned`
and width parameters are not accepted for `char`.

The lexer accepts a single Unicode scalar between single quotes, including a
UTF-16 surrogate pair in the host's source string, and rejects isolated
surrogates, empty/multiple-scalar literals, and raw line breaks. Existing escapes
`\n`, `\r`, `\t`, `\0`, `\\`, `\'`, and `\"` are decoded. String scanning now
correctly uses double quotes; its previous single-quote check had shadowed the
character scanner. LLVM character values and local storage use `i32`. Native
test-result character output emits UTF-8 bytes through ABI-typed `putchar` calls,
independently of the C locale and native `wchar_t` width. `../../../../examples/Characters.co`
demonstrates the feature and checks `😀` inside main.

As of 2026-10-29, the user selected explicit integer bindings as the first
compiler milestone after arithmetic. It is implemented and verified: `def<int>`
with mutable `=` or immutable `:=`, binding reads in expressions, and reassignment
with `=`. The user then selected type inference, now implemented and verified:
`def x = 10` and `def y := x + 2` infer from their initializers, and reassignment
must satisfy the original inferred type. Current expression types allow inferred
`int`, `float`, `bool`, `char`, and `string` bindings. Explicit `def<float>` bindings have now also been
implemented and verified, alongside `def<int>` and inferred declarations, with
the same assignment checks and mutable/immutable binding behavior.
Reusable parsing of delimited contents is an explicit implementation requirement.

Binding rules, object deletion, state dispatch, class/state
signature collisions, initial-state construction, state re-entry, absence of exit
hooks, and payload binding for running methods have been settled; see
[confirmed semantics](semantics.md).

Reusable top-level states, their host contracts, declaration and attachment
syntax, attached initial-state selection, and automatic external-transition
permission for attached public states are also confirmed in that reference.

State calls now use `object::method(...)`; ordinary instance method calls use
`object.method(...)`. `../../../../examples/Test.co`'s attack call has been updated accordingly.

The user selected boolean bindings next. Explicit `def<bool>` and inferred
boolean bindings are implemented and verified, including `true`/`false`, reads,
copies, mutable reassignment, strict types, and immutable bindings. The user
subsequently selected one byte per boolean for now, replacing shared-byte bit
packing. LLVM expression values use `i1`; each local binding has its own `i8`
allocation. Stores zero-extend to a canonical byte value of 0 or 1; loads truncate
back to `i1`. Assignments directly store the new byte without loading its previous
contents. Fields, arrays, atomics, and foreign-interface layouts remain future work.

Prefix boolean negation is implemented and verified: `!` accepts only `bool`
operands and emits logical inversion of an `i1` value. It supports literals,
binding reads, repeated negation, parentheses, initializers, and assignments.
The user next selected `&&` and `||` with short circuiting, now implemented and
verified for `bool` operands. Precedence is `!` above `==`/`!=` above `&&` above `||`; binary
logical operators associate left to right, and parentheses override precedence.
The emitter uses conditional branches and `i1` phi values so the right operand
is evaluated only when needed. Nested expressions use their actual ending
blocks as phi predecessors. Both operands are checked before code generation.
The user next selected `==` and `!=` for booleans, then integers and floats;
all are implemented and verified. Equality returns `bool`, evaluating left then
right without short circuiting. Equality operators have equal precedence and
associate left to right. Boolean/numeric comparisons are rejected.
Integer and boolean comparisons emit `LLVMBuildICmp` with `LLVMIntEQ` or
`LLVMIntNE`. Float comparisons emit `LLVMBuildFCmp` with `LLVMRealOEQ` for `==`
and `LLVMRealUNE` for `!=`: exact value equality, signed zeros equal, and NaN
unequal to every value including itself. Infinities compare by sign and value.
Mixed `int`/`float` comparisons follow existing arithmetic promotion: convert the
integer to the current 32-bit float before comparing. Large integers can round
in that conversion (for example, `16777217 == 16777216.0` is `true` under this
policy), while `int`/`int` equality remains exact. This conversion is specific to
expressions; strict binding assignment types are unchanged.

The user then selected `<`, `>`, `<=`, and `>=` for integers and floats, now
implemented and verified. All require two numeric operands and return `bool`;
booleans are rejected. Integer predicates are signed (`SLT`, `SGT`, `SLE`, `SGE`),
and float predicates are ordered (`OLT`, `OGT`, `OLE`, `OGE`), so every ordering
comparison with NaN is false. Signed zeros compare equal for `<=` and `>=`, and
infinities follow their numeric ordering. Mixed comparisons use the same float32
promotion described above. Arithmetic binds above ordering, ordering above
equality, equality above `&&`, and `&&` above `||`; prefix unary signs bind
above these operators but below exponentiation. Ordering operators associate left to right; mathematical
chains such as `1 < x < 3` are rejected because the first result is `bool`.
Use `1 < x && x < 3` for a range check.

The user then selected unsigned integer integration. `def<unsigned int>` uses
32 bits by default; explicit `int<32>`, `unsigned int<32>`, `int<64>`, and
`unsigned int<64>` bindings are implemented. Width syntax reuses the generic
delimiter parser. Unsigned types survive inferred copies, arithmetic results,
assignments, comparisons, and final output. Literals take integer context from
typed operands or binding declarations/assignments and must fit that type;
otherwise they default to signed 32-bit. Named bindings never change type to
fit a context. Signed/unsigned and different-width integer expressions require
explicit conversion, and binding assignments retain exact type checks.
Unsigned arithmetic wraps modulo the selected width, as confirmed by the user.
Unsigned division/remainder and ordering emit `udiv`/`urem` and unsigned `icmp`
predicates; float promotion uses `uitofp` for unsigned inputs. Native output uses
the appropriate signed/unsigned format, extending 8/16-bit values to 32 bits for
the native variadic calling convention. `unsigned` on another type is
rejected. `../../../../examples/Unsigned.co` demonstrates the supported integration.

Explicit `->` casts are now implemented between all eleven numeric types: signed
and unsigned 8/16/32/64-bit integers and 16/32/64-bit floats. The user confirmed integer
wrapping/truncation, float-to-integer truncation toward zero with an error on
invalid results, and keeping booleans separate from numbers. The user also
confirmed that `1 + 2 -> float` casts the entire sum. Cast precedence sits below
arithmetic and above ordering, and chains associate left to right.
`CastExpression` retains the operator and target type. Its operand is checked
independently of the target and surrounding assignment; default integer literals
remain signed 32-bit inside casts. Larger integer literals still require an
appropriate typed binding before conversion.

`EmitCast` emits integer truncation or source-signedness-aware extension,
signed/unsigned integer-to-float conversion, and float widening/narrowing.
Float-to-integer conversions branch through ordered range checks before
`fptosi`/`fptoui`; invalid executed casts print a diagnostic with the cast's
location and exit with status 1. Checks permit fractions whose truncation fits,
including small negative fractions converting to unsigned zero. Checks occur
inside the operand's runtime path, so short circuiting skips invalid casts.
Identity casts, including `bool -> bool`, preserve the value. Boolean/numeric
casts are rejected before emission.

`ValueType` represents integer and floating families with validated width data,
plus a distinct boolean type. Numeric and boolean spellings derive from `Keywords`;
the semantic resolver uses the same keywords and supported-width sets. Explicit
widths use the required `<number>` syntax already handled by `TypeParser`.
Signed and unsigned `int<8>`, `int<16>`, `int<32>`, and `int<64>` are supported;
unannotated integers still default to signed 32-bit.

The remaining compiler type audit centralizes integer bounds, floating format
metadata, literal overflow thresholds, and display precision in `ValueType`.
Integer literal parsing derives its maximum width from the supported integer
widths, and emission passes decimal text to LLVM rather than narrowing through a
JVM `Long`. Floating literals retain decimal text, validate against the default
floating type's exact overflow threshold, and let LLVM round directly to the
semantic type. Parsing no longer fixes literals to a JVM float32 representation.
Diagnostics and emitted boolean text use `Keywords` or the resolved type spelling.

`ValueType.FloatingFormat` declares IEEE half, single, and double widths and
exponent sizes once; supported floating widths derive from those definitions.
`ValueType` also provides canonical `HALF`, `SINGLE`, and `DOUBLE` types alongside
the default `FLOAT`, and distinguishes logical `bits` from binding `storageBits`.
Booleans have one logical bit and eight storage bits; numeric storage uses the
numeric width. These language definitions do not depend on JVM type sizes.

`LLVMTypes` only maps semantic value/storage representations to LLVM constructors.
Its floating format mapping is exhaustive, so introducing a format requires an
explicit backend mapping. It defines no types, storage-width policies, C ABI
rules, or arithmetic promotion rules.

The user selected explicit target ABI profiles without adding a Clang frontend.
`NativeCABI` is now an instance selected from LLVM's normalized target triple,
with Windows LLP64, Linux LP64, and Darwin LP64 profiles. Initial supported
architectures are x86-64 and AArch64 (including Apple's `arm64` spelling): Windows
requires the MSVC environment, Linux requires GNU or musl, and macOS accepts Darwin
or macOS versioned OS names. LLVM normalizes abbreviated triples first, including
its default MSVC environment for a Windows triple without an explicit environment.
Unsupported architectures, OSes, and environments receive a diagnostic containing
the original/normalized triple and supported targets; there is no profile fallback.
This includes x32/ILP32, Android, MinGW, iOS, and bare-metal targets.

Profiles declare C int/long/long-long and pointer widths, double format, and
executable format. Windows uses a 32-bit long; Linux/macOS use a 64-bit long.
These are explicit ABI facts, not automatic discovery or a derivation from JVM or
Cloth default widths. Profile sources are Microsoft's
[LLP64 model](https://learn.microsoft.com/en-us/windows/win32/winprog64/abstract-data-models)
and [type ranges](https://learn.microsoft.com/en-us/cpp/cpp/data-type-ranges), the
[x86-64 psABI scalar types](https://gitlab.com/x86-psABIs/x86-64-ABI/-/blob/master/x86-64-ABI/low-level-sys-info.tex),
[AAPCS64](https://github.com/ARM-software/abi-aa/blob/main/aapcs64/aapcs64.rst), and
[Apple's 64-bit data model](https://developer.apple.com/library/archive/documentation/Darwin/Conceptual/64bitPorting/transition/transition.html).

`LLVMEmitter` resolves the native ABI once before generating IR and before creating
output files. It records that normalized triple in the module, uses the selected
C int type for the entry point and library declarations, and passes the ABI into
`EmitCast` for runtime error calls. Object generation uses the same triple and
checks the profile's pointer width against LLVM's target data layout. Printing
argument selection and C conversion specifiers belong to the selected ABI;
display precision derives from shared floating metadata and uses ordinary floating
conversions. Output suffixes and linker selection follow the profile's executable
format; the CLI leaves suffix selection to the emitter. Compilation remains native
only; matching profiles for other targets is not a cross-compilation interface.

The user then selected separation of linker/toolchain configuration from process
execution. `LinkOptions` carries executable/driver overrides, default/static/dynamic
runtime linkage, console/Windows subsystem, ordered user library directories and
libraries, sysroot, MSVC tools root, Windows SDK root, and optional SDK version.
`CompilerSettings` exposes these through CLI options while retaining the two
positional source/output arguments and default source. Long options accept separate
or `=` values; `-L`/`-l` are repeatable, and `--` permits dash-prefixed filenames.
The README documents the supported options.

`ToolchainResolver` resolves and validates these options for the selected ABI before
IR or output-directory creation. Windows uses bundled generic LLD by default or an
explicit MSVC-compatible driver. It discovers MSVC through `VCToolsInstallDir` or
`vswhere` and Windows SDKs through SDK environment variables or the Windows Kits
installation. Tools/SDK versions sort numerically. It selects x64/ARM64 library
directories from the ABI architecture and resolves CRT, compatibility, and kernel32
libraries to actual files. The chosen paths become explicit `/libpath` arguments
and the link process's `LIB` environment. Static CRT remains the default; dynamic
CRT selects the corresponding import libraries. Machine/subsystem flags are
explicit, and GUI output selects `mainCRTStartup` to initialize the CRT before
calling Cloth's generated `main`. Microsoft LINK's help/no-input status is handled
without treating arbitrary failed probes as successful.

Unix resolution uses `--linker`, `CC`, or `cc`, identifies GCC/Clang, and validates
the driver's reported target architecture, OS profile, and Linux GNU/musl
environment against LLVM's selected ABI. Clang receives the selected target triple;
fixed-target GCC does not receive Clang-specific options. Library paths come from
explicit options, sysroot/SDK, and driver search-directory queries. Linux resolves
its math runtime, including a static archive for static linkage; macOS resolves
the SDK's System library and obtains the SDK from `--sysroot`, `SDKROOT`, or `xcrun`.
Unsupported driver/subsystem/runtime/SDK combinations and missing target files
fail during resolution. This does not add a Clang frontend or a cross-target CLI.

`LinkerConfiguration` holds the resolved executable, flags, runtime files, library
paths, user libraries, environment, and target identity, and constructs an argument
list without shell quoting. `NativeLinker` only executes that command and checks
its result. Default configurations are cached per target within the compiler
process; explicit options resolve independently. Toolchain probes have bounded
waits. The implementation follows the
[LLD Windows interface](https://lld.llvm.org/windows_support.html),
[MSVC CRT choices](https://learn.microsoft.com/en-us/cpp/c-runtime-library/crt-library-features),
[CRT entry points](https://learn.microsoft.com/en-us/cpp/build/reference/entry-entry-point-symbol),
and [GCC target/search queries](https://gcc.gnu.org/onlinedocs/gcc/Developer-Options.html).
The software half arithmetic working type is selected by `EmitHalf.arithmeticType`;
semantic expression promotion remains in `ValueType.commonFloating`.
`EmitBindings` retains the semantic type instead of reconstructing boolean storage
rules from LLVM widths. `EmitCast` uses the model's integer bounds and tests whether
the exclusive lower bound is representable instead of special-casing 64 bits.
Half conversion masks and thresholds derive from the shared floating metadata,
and binding storage follows the widths declared in `ValueType`.

`float<16>`, `float<32>`, and `float<64>` are supported in binding annotations and
cast targets. Two floats use the wider format, including half results for two
half operands. Mixing with integers uses at least float32; the presence of a
64-bit float promotes mixed numeric expressions to double. Decimal literals still start as
float32, and widening them cannot recover digits already rounded. Explicit casts
are required for float-width changes in binding assignments. All floating widths
support storage, arithmetic, negation, comparisons, and native printing.
`EmitHalf` converts IEEE binary16 values through integer bit operations, with
round-to-nearest-even for float32/64 inputs (including subnormals, signed zero,
infinities, and NaN). It converts double directly to half to avoid double rounding.
Half bindings use LLVM `half` storage; half arithmetic computes in float32 and
rounds back to half. This avoids LLVM half runtime helpers absent from the current
Windows link setup. Native printing widens half/float32 to double.
`../../../../examples/Casts.co` demonstrates widening before arithmetic and evaluates to `4294967294`.
`../../../../examples/Test.co` remains a broader design example: primitive `int::MAX` lookup and other
unsupported declarations in that file are not implemented by this milestone.

The user selected character and then string bindings after numeric widths.
The user then selected string equality and concatenation. The milestone after
these string operators has not been chosen. Do not treat the order
of open questions or missing features as an approved plan.

## Implementation snapshot

Inspected from repository source through 2026-10-30; recheck before relying on it:

- The compiler is Kotlin/JVM in `Compiler`, built with Gradle. Its module declares
  Kotlin 2.4.20, JDK toolchain 25, and Bytedeco LLVM bindings.
- `Compiler/src/main/kotlin/cloth/Entry.kt` wires source loading, lexer, parser,
  and native LLVM compilation. The CLI settings currently default to `examples/Builtins.co` and accept
  `[source.co] [output] [--emit executable|object]`, preserving source paths and using a separate output.
- `cloth/lexer/Lexer.kt`, `cloth/token/Keywords.kt`, and `Operators.kt` contain
  broader lexical machinery. Recognizing a token does not establish parser,
  type-checker, or emitter support for that language feature.
- `cloth/parser/Parser.kt` uses `parse()` / `parseDeclarations()` as declaration entry points,
  producing `SourceUnit` and the function/parameter/body AST in
  `cloth/parser/declaration/Declarations.kt`. Internal `parseSnippet()` and function bodies
  parse expression statements, explicit or inferred binding
  declarations, simple and compound assignment statements, standalone postfix
  `name++`/`name--` updates, and `if` chains
  with brace-delimited branch statement lists. Statements currently require
  newline separation (or a closing branch brace/EOF). A chain accepts `else` on
  the same line or after newlines/comments without consuming a subsequent
  unrelated statement's separator.
  `BindingDeclaration.type` is nullable to represent an omitted annotation.
  `cloth/parser/subs/ExpressionParser.kt` handles integer and float literals,
  boolean, character, ordinary/interpolated/raw string literals, binding names, parentheses, unary `+`/`-`/`!`, and binary `+`, `-`, `*`, `/`, `%`, `**`, `&&`, `||`, `==`, `!=`, `<`, `>`, `<=`, `>=`
  with precedence, plus `-> Type` casts between arithmetic and comparison
  precedence levels, primitive `is Type` checks at comparison precedence, and
  conditional expressions, named function calls with comma-separated arguments,
  array literals and chained indexed reads. Calls have separate name and typed
  resolution stages and native scalar, string and array emission.
- `cloth/parser/subs/DelimitedParser.kt` provides a generic delimiter wrapper
  with caller-supplied content parsing, including angle and parenthesis helpers.
  Binding type annotations, parenthesized expressions/conditions, and branch
  braces use this wrapper;
  recursive callbacks can handle nested delimiters. `TypeParser` reads an optional
  `unsigned` modifier, a type name, and an optional nested numeric width.
  Bindings and function headers enable sized `[N]` and unsized `[]` suffixes.
  In a cast, only a complete `<number>` suffix is consumed as a width; other
  following `<` tokens remain available for ordering comparisons.
  General generic type arguments are not implemented.
- `cloth/semantic/declarations/DeclarationAnalyzer.kt` collects declaration signatures and
  resolves function-body names through `DeclarationModel`, using the symbols
  and lexical scopes in `cloth/semantic/symbols`. This path consumes `SourceUnit`
  separately from the legacy snippet analyzer and does not select overloads or
  type-check expressions yet.
- `cloth/semantic/CallAnalyzer.kt` and `FunctionSemanticModel.kt` resolve typed
  calls on top of `DeclarationModel`, using shared `ExpressionTyping.kt` and
  `TypeResolver.kt`. The selected callee is recorded independently of its return
  context; primitive numeric width/signedness and array rank remain strict.
  Contextual floating literals are enabled for call arguments, while legacy
  snippet binding behavior is retained. Return-flow checks are available through
  `FunctionAnalyzer`; native scalar function emission is implemented through
  the declaration-based LLVM entry points.
- `cloth/semantic/typing/FunctionAnalyzer.kt` enables the full implemented function
  checker, adding return contracts and `ControlFlowModel` completion facts for
  function emission. Earlier declaration/call APIs retain their stage
  boundaries.
- `cloth/semantic/ProgramAnalyzer.kt` applies executable entry rules separately
  from library validation. `EmitEntryPoint` adds native startup only for executable
  emission. The CLI defaults to executable mode; object mode requires no main
  and does not invoke a linker.
- `cloth/semantic/SemanticAnalyzer.kt` checks the executable scope and nested
  branch scopes before LLVM emission. It rejects unknown names, duplicate declarations,
  immutable reassignment, and assignment type mismatches. An omitted annotation
  infers signed/unsigned 8/16/32/64-bit integers, 16/32/64-bit floats, `bool`, `char`, or `string` from the initializer once, preserving that type on all
  later assignments. Current assignment checks require exact types; no new
  numeric binding conversions were introduced. Integer literals can take an integer
  context without converting named bindings. A `SemanticModel` records each
  expression and statement's language type for emission, using identity keys.
  Explicit signed/unsigned 8/16/32/64-bit integers, 16/32/64-bit floats, `bool`, `char`, and `string` annotations are implemented;
  other explicit type names receive an unsupported-feature diagnostic. Casts
  and primitive `is` checks resolve their target through the same type resolver
  as binding annotations. Standalone conditionals have `void` statement type;
  value-producing chains require exhaustive, compatible branch results.
  Boolean/numeric conversions and arithmetic on booleans are rejected before emission; prefix `!` requires a boolean operand and returns `bool`. `&&` and `||` require boolean operands. Equality accepts two booleans, two characters, two strings, or two numbers, including mixed int/float values, and returns `bool`. Ordering comparisons require two numbers and return `bool`.
  Bindings become available after their initializer is checked;
  forward/self reads are currently rejected. This is implementation coverage,
  not a decision about declaration resolution for the broader language.
- `cloth/emitter/callables/EmitFunctions.kt` declares every scalar/void function
  signature before emitting bodies. `LLVMEmitter.emit(SourceUnit)` and
  `compileObject(SourceUnit, Path)` use `FunctionAnalyzer` and this emitter;
  they generate verified declaration modules without synthesizing a native
  entry point. Scalar storage uses stable symbols and entry allocas, calls use
  selected symbols, and returns/merges respect continuing control flow. String
  parameters/returns work through chunk 7; array parameters/returns work through
  chunk 8, including recursive copies of nested arrays and string elements.
- `EmitFunctionFrame` joins normal exits, copies outgoing strings and arrays into
  the caller's return storage, and releases the current frame's received buffers.
  `EmitStringArena` emits checked malloc/copy and free helpers using the native
  size_t ABI. Local formatting/concatenation buffers remain on the creating stack.
- `cloth/emitter/LLVM.kt` and `cloth/emitter/types/EmitExpression.kt` emit LLVM
  code for arithmetic, boolean negation (`LLVMBuildNot` on `i1`), short-circuit
  logical expressions (conditional branches and phi values), boolean equality and numeric equality/ordering
  comparisons (`icmp` on `i1`/`i8`/`i16`/`i32`/`i64` or `fcmp` on `float`/`double`), casts
  through `EmitCast`, binding reads, and conditional chains. Conditional value
  merges use phi nodes with each branch's actual ending block, including nested
  expressions and runtime guards. Statement conditionals have no value phi.
  Integer signedness comes from semantic types, since LLVM integer types do not
  encode it. `EmitBindings` allocates typed local
  storage and emits loads/stores (with a separate byte per boolean and conversion
  between `i1` values and `i8` storage); `EmitStatement` handles declarations and
  assignments. Branch emission restores lexical binding names without undoing
  stores to enclosing bindings. Internal snippet test instrumentation prints the
  last value when requested; production programs use explicit main exit status
  and do not implicitly print values.
- `../../../../examples/Math.co` is the arithmetic example; `../../../../examples/Bindings.co` demonstrates explicit and
  inferred integer and float bindings and checks `76.25`. It includes
  commented examples of invalid type-changing and immutable reassignments. `../../../../examples/Booleans.co` demonstrates boolean inference, explicit types, copies, negation, short-circuit logic, boolean/numeric equality and inequality, numeric ordering, and reassignment, and checks a true result.
  `../../../../examples/Unsigned.co` demonstrates unsigned widths, wrapping, copies, division, float
  promotion, and comparisons, and checks a true result.
  `../../../../examples/Casts.co` demonstrates numeric conversions and checks `4294967294`.
  These examples have explicit main functions, return 0/1 for pass/fail, and do
  not print implicitly. `../../../../examples/Functions.co` exercises recursion.
  `../../../../examples/Test.co` describes the broader target
  language; it is not currently an end-to-end compiler test.
- Verified on 2026-10-30: the full compiler suite passed all 129 tests with no
  failures, errors, or skips using
  `gradlew.bat :Compiler:test --offline --console=plain --no-daemon`.
  All 15 new `ConditionalsTest.kt` cases also passed in a focused run. Coverage
  includes inline/multiline and nested chains, malformed syntax, exhaustive
  value branches, strict condition/result types, skipped-code checking, branch
  scopes and shadowing, mutability, all current primitive storage types,
  expression composition, actual LLVM predecessor blocks after nested guards,
  once-only condition evaluation, skipped alternatives/bodies, primitive `is`
  checks with operand side effects, absent output from standalone conditionals,
  the user's exact unsigned initializer, and the runnable conditional CLI example.
- Verified on 2026-10-30: 114 test cases checked for string equality and
  concatenation. The full run passed 113 cases and found a stale default-source
  assertion; after updating it to the current `examples/Strings.co` default,
  all three settings tests passed in a focused rerun. Operator tests cover strict
  types, precedence, immutable bindings, exact content equality, differing
  lengths, Unicode and normalization distinctions, empty strings, NULs, raw text,
  chained/nested concatenation, copied values, self-reassignment, short circuits,
  generated length guards and ABI declarations, and native concatenation growth
  across stack pages. The full run also passed all numeric-cast error tests
  after their failure emission was shared with concatenation guards.
- Verified on 2026-10-29: the full compiler suite passed all 106 tests across
  `BindingsTest.kt`, `UnsignedIntegersTest.kt`,
  `CastsTest.kt`, `NumericWidthsTest.kt`, `NativeCABITest.kt`,
  `CompilerSettingsTest.kt`, `ToolchainResolverTest.kt`, `CharactersTest.kt`, and
  `StringsTest.kt`. The full run used `--no-daemon` and a temporary init script
  under `build` to isolate test reports after Gradle reported corrupt/missing
  files in its default report directory. Standard focused string, character,
  and settings tests also passed. String tests cover literal decoding, raw
  text/newline preservation, Unicode validation, strict binding types and
  mutability, copies across reassignment, rejected operators/conversions, and
  byte-exact native UTF-8 output including empty strings, NULs, and percent signs.
  Character tests cover literal/escape decoding, string-quote separation, malformed
  and isolated-surrogate rejection, exact 32-bit storage, strict binding types,
  immutability, rejected operators/conversions, native copies and equality, and
  byte-exact UTF-8 output for literals and loaded bindings across every encoding
  boundary through U+10FFFF, including NUL, escapes, and emoji.
  Tests cover syntax, nested delimiter callbacks, semantic diagnostics, native
  arithmetic/assignment/copy behavior, inferred integer and float bindings,
  explicit floats with strict type and immutability checks, fixed inferred types
  on reassignment, mixed arithmetic, and CLI source/output paths. CLI tests
  compile and run all seven runnable examples from `examples` (`76.25`, `6689`,
  `true`, `true`, `4294967294`, `😀`, and `Unicode: é 中 😀`). New boolean
  checks cover syntax, strict types, arithmetic rejection, immutable bindings,
  separate byte allocations at 1/2/8/9/16/17 boolean bindings with interleaved
  integers and floats, native independent reassignment and copied values, and
  literal/assignment output. Negation checks cover prefix parsing, malformed
  operands, bool-only typing, nested negation, native literal and binding
  inversion, assignment, snapshots, and lexer separation of negation, equality, inequality, and assignment.
  Logical-operator tests cover malformed syntax, precedence and associativity,
  strict operand types (including skipped operands), generated branches that
  bypass the right operand's load, native truth tables using bindings, nested
  left/right logical expressions, negation, assignments, snapshots, and later
  numeric statements. Equality tests cover syntax, precedence, left associativity,
  boolean/numeric mixing rejection, fixed binding types, immutability, native truth
  tables for literals and bindings, negation, snapshots, assignments, and nested
  short-circuit expressions on either side of comparisons. Numeric equality
  tests cover exact integer values and boundaries, float values, signed zeros,
  infinities, NaN on either side, mixed promotion in both directions (including
  float32 rounding), arithmetic precedence, bool result storage and immutability,
  and short-circuit guards that skip integer division by zero. Ordering tests
  cover each operator for less/equal/greater cases, signed integer boundaries,
  float and mixed values, signed zeros, infinities, NaN on either side, float32
  rounding, precedence, malformed syntax, bool result storage and immutability,
  rejected bool operands and comparison chains, and guarded division by zero.
  Unsigned tests cover modifier and nested-width parsing, literal range checks,
  signedness and width preservation through inference/copy/reassignment, rejected
  implicit conversions, invalid modifiers, immutable bindings, unsigned LLVM
  instructions, native 32/64-bit boundary output, wrapping arithmetic, exact
  unsigned division/remainder/ordering/equality, positive unsigned float promotion
  and float32 rounding, short-circuit guards, independent storage alongside all
  existing primitive types, and signed 64-bit arithmetic/printing regressions.
  Cast tests cover syntax, agreed precedence and associativity, width/comparison
  disambiguation, exact binding rules, all 121 numeric source/target pairs, integer
  wrapping and source-signedness-aware widening, float32/64 precision and mixed
  promotion, float-to-integer boundary fractions and rounding, controlled errors
  for out-of-range values/NaN/infinities, bool separation and identity casts,
  runtime guards inside nested short circuits, copies, and generated conversion
  instructions and error paths. Float64 tests also cover arithmetic, negation,
  remainder, comparison, infinities, NaN, signed zero, storage, and printing.
  Numeric-width tests cover signed/unsigned 8/16-bit literal bounds, wrapping,
  storage, copies, printing, signed/unsigned arithmetic and comparisons, explicit
  conversions, strict binding types, keyword-derived spelling, width validation,
  half arithmetic and mixed promotion, and small float-to-integer range guards.
  Half conversion checks compare native results against JDK IEEE binary16
  conversions around normal/subnormal, tie, signed-zero, and overflow boundaries;
  double-to-half tests check direct rounding at ties and the overflow threshold.
  Default floating literal tests also cover exact decimal midpoint rounding,
  values immediately around overflow, and preservation of rounded values through
  inferred copies and native comparisons.
  ABI profile tests cover the architecture/OS/environment selection matrix,
  Windows versus Unix long widths, executable formats, LLVM triple normalization,
  versioned Darwin/macOS/MSVC spellings, unsupported targets and ABI variants, and
  the native emitted module's triple and C declarations. Native execution is
  verified on the current host; other platforms' profile selection is tested
  without claiming cross-platform executable verification.
  Toolchain tests use installed-directory fixtures and probe results for Windows,
  GNU/musl Linux mismatch detection, and macOS. They cover numeric version selection,
  architecture-specific runtime paths, static/dynamic choices, GUI CRT startup,
  SDK/sysroot selection, driver compatibility, explicit overrides, library ordering,
  paths containing spaces, CLI parsing, missing dependencies, and rejection before
  output directories are created. Native Windows tests additionally build and run
  programs with the dynamic CRT, GUI subsystem, and actual Microsoft LINK driver;
  the existing native suite exercises default static-CRT linking through LLD.
  CLI smoke checks also compiled `../../../../examples/Unsigned.co` with default and dynamic runtime
  options; both executables printed `true`.
  The test directory is now
  exempted from the broad test-directory ignore rule so tests can be tracked.
  `gradlew.bat :Compiler:run --args="Bindings.co" --offline --console=plain`
  also succeeded before examples were moved; that source now lives at
  `examples/Bindings.co`.

Repository-relative paths above identify current implementation locations; they
are not language specification boundaries.

## Open design questions

The following have not yet been settled. Ask about them when relevant, keeping
the user's preference for one question at a time:

- **Unsafe memory and nullable integration:** the standalone
  [deferred plan](../../../../todo/unsafe-memory-plan.md) records accepted unsafe
  acknowledgement direction, proposed handle access/return syntax, compile-time
  origin analysis, nullable/coalescing dependencies and conditional future stages.
  Saving the plan implements none of them; `addr` remains unavailable. Final
  qualifier semantics, native contracts and the boundary with ordinary deletion
  guarantees remain unresolved.
- **Compiler data access:** the implemented `::` contracts, result types and size
  meanings are settled for the supported types; see the dedicated
  [design note](../../../../todo/compiler-data-access.md). `addr`, array/class text
  formatting and the exploratory soft/hard system-call categories remain deferred.
  Array iteration syntax and array equality semantics also remain unresolved.
- Type-narrowing behavior after reassignment and outside successful `if` branches.
- Function-block capture escape/lifetime rules beyond reading surrounding locals
  while their enclosing scope is active; whether blocks can be stored or escape
  as computations rather than being evaluated to their results.
- Assignment to a function-block binding: replacing its body, assigning a value,
  and whether `:=` may make the computation binding immutable.
- Default equality semantics for class instances: identity or field values.
- Whether there are restrictions on individual source-to-target transition paths.
  The confirmed phrase "permitted states" means class-supported states, not an
  approved transition-graph restriction mechanism.
- Whether state payloads can escape through stored or returned bindings, and
  their lifetime after the originating method finishes.
- The exact diagnostics/error model for invalid-state calls and deleted-object access.
- Deleting an already-invalid binding or intentionally null value.
- Character ordering, arithmetic, and conversions to/from numeric types.
- String indexing, content mutation, and dynamic/escaping string
  construction/lifetime rules beyond the current literal-backed bindings.
- Whether a value-producing `if` requires a final `else` in all cases.
- Branch result type compatibility/inference for value-producing `if` chains.
- How a value-producing branch selects its result when it contains multiple
  statements (for example, whether its last expression supplies the value).

This list is a set of unresolved areas, not an exhaustive specification checklist
or a set of required features.
