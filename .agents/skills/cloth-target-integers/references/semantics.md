# Cloth target-sized integers: confirmed semantics

These decisions were recorded in the shared Cloth semantics reference.
User-confirmed rules govern intended behavior; historical implementation
notes and unresolved choices retain their original qualifications. Read
the parent skill for current implementation boundaries and source/test routing.

## Target-sized unsigned integer (usize)

- On 2026-10-31 the user accepted `usize` as a primitive type and requested a
  staged implementation plan. It is an unsigned integer with its own Cloth
  identity and a width selected from the compilation target. It is not an alias
  of a fixed-width integer just because their representations match.
- Conventional 32-bit targets use a 32-bit `usize`; conventional 64-bit targets
  use a 64-bit `usize`. Width selection happens during compilation, independently
  of the compiler host and without runtime adaptation.
- Explicit `int<8>`, `int<16>`, `int<32>` and `int<64>` retain exactly those
  widths, signed or unsigned, on every target. Plain `int` stays 32-bit. The
  user explicitly checked and accepted this distinction before requesting the plan.
- During step 3 the user confirmed the existing unsigned numeric rules for
  usize: arithmetic wraps at the target width, integer casts retain low bits
  when narrowing and extend according to source signedness when widening, and
  float-to-integer conversions use the existing checked, truncation-toward-zero
  contract. This does not relax exact typing for bindings, arguments or returns;
  equally wide fixed unsigned integers require explicit conversions.
- The user confirmed normalized metadata text `"usize"`, with array spellings
  such as `"usize[3]"`. `usize::type::width` reports the target-selected width;
  `isunsigned` and `isnumeric` are true. Metadata equality retains usize's own
  identity even on a target where its bits match a fixed unsigned integer.
- Scalar/array/string `size` and metadata `rank`, `extent` and `width` return
  `usize`. Numeric `min` and `max` retain the exact
  receiver type. This changes measurement result types, not their meanings.
- The migration includes internal counts, descriptors, loops, bounds checks and
  allocation operations, rather than only changing the source provider names.
  It introduces no source pointers, pointer arithmetic facility or `addr` contract.
- During step 4 the user confirmed that every static array extent must fit the
  compilation target's usize, including extents used only in type metadata.
  A 32-bit target therefore accepts extents through 4,294,967,295 and rejects
  larger ones at type resolution. Empty shapes can retain a maximum inner count;
  type/shape representability does not imply that corresponding storage can be
  allocated. Actual allocation byte calculations remain checked separately.
- Existing signed/unsigned integer indexing and fixed-int conversions continue;
  adding usize does not authorize rejecting those indices. Negative and
  out-of-range indices fail before native-width narrowing. Checked size
  production remains required independently of ordinary numeric casts.
- The user selected Windows x86/MSVC as the first real 32-bit validation target
  during step 6. The implemented i686-pc-windows-msvc profile uses 32-bit usize
  and requires SSE2 for the existing floating-point rounding contract. This is
  target implementation scope, not a claim that all 32-bit OS/ABI variants work.
- All seven migration steps are implemented and integrated: target layout,
  shared integer behavior,
  the primitive, native buffer counts and public measurement provider contracts.
  The implemented spelling is `usize`, without a width suffix or unsigned
  modifier. Size/rank/extent/width results have exact usize identity and follow
  ordinary strict assignment/argument/return rules; conversion to an equally
  wide fixed integer still requires an explicit cast. Internal descriptors,
  metadata fields and loops use target-width counts with separate inner-shape
  known flags. Current ABI profiles include the validated Windows x86 target
  and the existing 64-bit Windows/Linux/macOS targets. Other 32-bit targets
  require their own ABI/toolchain/execution validation. See
  [development status](../../cloth-language/references/development.md) for verified coverage.
- Integration checks preserve Cloth's first detected error across compiler
  passes. This does not promise source-order priority between different passes,
  diagnostic recovery or reporting multiple errors.

## Target-sized signed integer (isize)

- On 2026-11-01 the user requested `isize` as a signed pointer-sized integer,
  alongside unsigned pointer-sized `usize`. Its width comes from the compilation
  target: 32 bits on supported 32-bit profiles and 64 bits on supported 64-bit
  profiles. Explicit fixed integer widths and plain int's default do not change.
- Isize has its own primitive identity, distinct from same-width fixed signed
  int and usize. Typed assignments, arguments, returns and mixed integer
  operations retain exact-type rules; conversions require explicit casts.
- It follows existing signed integer arithmetic and conversion rules. Bounds
  are -2^(N-1) through 2^(N-1)-1; arithmetic wraps at N bits. Division/remainder
  retain zero-divisor and minimum/-1 guards. Integer casts retain low bits when
  narrowing and extend by source signedness when widening. Float-to-integer
  casts remain checked and truncate toward zero.
- Source spelling is `isize`, with no numeric width suffix or unsigned modifier.
  Normalized metadata uses `"isize"` and array forms such as `"isize[3]"`.
  Its metadata is numeric and signed; equality preserves its own identity.
  Min/max return isize, while size/rank/extent/width still return usize.
- The user explicitly retained all other usize behavior, including unsigned
  array/string counts and metadata measurements. Signed isize indices use
  existing checked indexing; negative indices remain invalid. This adds no
  source pointer or pointer-arithmetic facility.
- All three implementation stages are complete: the primitive, signed
  numeric/entry integration and broader cross-width validation. See the
  [isize implementation record](../../cloth-language/references/isize-implementation.md) for verified coverage.
