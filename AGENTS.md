# Working on Cloth

Cloth is a modern, strongly typed, object-oriented language for application and
library development. Its defining feature is first-class state machines in classes.
It has explicit object creation and destruction, no GC, and no programmer-visible
pointer, reference, or `mut` syntax.

Cloth fails at the first error. Stop compilation on the first detected error
and render that diagnostic. Do not add error recovery, diagnostic accumulation,
or multi-error reporting. This is deliberate Cloth philosophy, not an
implementation limitation. Multiple source labels explaining the same error
are appropriate.

`examples/Test.co` describes the intended language. The compiler works toward that design;
its current limitations do not define Cloth. Explicit design decisions from the
user refine and extend the examples.

For Cloth language design, examples, documentation, or compiler behavior, read
[the Cloth language skill](.agents/skills/cloth-language/SKILL.md). It links to
confirmed semantics, unresolved questions, and the current implementation status.
Keep those records current as decisions are made and features are implemented.
