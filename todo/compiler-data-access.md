# Compiler data access

Status: deferred design. Working name and direction recorded 2026-10-31.

Compiler data access is the working name for compiler-provided data associated
with particular Cloth types or values. The user intends this facility to extend
beyond arrays, with the available data depending on the receiver's type.

The proposed receiver-based spelling is `value::name`, without parentheses for
data access. The motivating example is this function-body fragment:

```cloth
def a = [1, 2]
def count = a::size // Produces 2 in the proposed design.
```

Defer array length/count access until this broader facility is designed.
Neither `.length` nor a free-standing `::size(a)` has been adopted. This note
records design direction; compiler data access is not implemented.

## Questions to resolve later

- Which data each receiver type exposes, and whether types themselves can also
  serve as receivers.
- Result types, mutability and diagnostics for unsupported data names.
- Which queries are resolved at compile time, which read runtime metadata, and
  how receiver evaluation and effects are preserved.
- The exact meaning of array `size`, including multidimensional and empty arrays.
- How data access relates to existing `::` construction, deletion and state
  dispatch, including member-name collisions.
- Its relationship to the user's exploratory soft/hard system-call idea; those
  categories have no confirmed definitions yet.

The earlier recommendation of an `int` result and outer-dimension array length
remains a proposal. Do not implement those choices as settled language rules.
