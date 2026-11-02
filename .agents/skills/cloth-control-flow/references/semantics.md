# Cloth control flow: confirmed semantics

These decisions were recorded in the shared Cloth semantics reference.
User-confirmed rules govern intended behavior; historical implementation
notes and unresolved choices retain their original qualifications. Read
the parent skill for current implementation boundaries and source/test routing.

## Type checks, state checks, and equality

- `is` supports both active-state checks, such as `player is Alive`, and runtime
  type checks, such as `entity is Player`. The target declaration determines
  which check is requested.
- `==` is reserved for equality between values. Do not use it as a type-check or
  active-state-check operator.
- Numeric `==` and `!=` support `int` and `float` operands and return `bool`.
  As with boolean equality, both operands are evaluated once, left to right.
  Boolean/numeric comparisons are type errors; numbers are not converted to
  booleans for equality. Assignment type checks remain unchanged.
- Numeric `<`, `>`, `<=`, and `>=` support `int` and `float` operands and return
  `bool`. Both operands are evaluated once, left to right. Boolean operands are
  type errors; Cloth does not order `false` and `true` as numbers.
- `instanceof` is not Cloth's type-check syntax.
- A successful type check narrows the binding's type inside its `if` branch.
  In `if (entity is Player) { ... }`, `entity` is treated as `Player` without an
  explicit cast, subject to ordinary member visibility.

## Conditional expressions and statements

- The user selected `if` / `else if` / `else` conditionals as the next feature
  after string equality and concatenation.
- An `if` chain can produce a value used as a binding initializer. The branch
  expressions in the following example yield their values implicitly, without
  an explicit `return`:

  ```cloth
  def<string> isUnsigned = if (a is unsigned int) { "is unsigned" } else if (a > 25) { "is above 25" } else { ":(" }
  ```

- An `if` can also stand alone as a statement. In that context it has no implied
  return. Branch value production in an initializer does not imply returning
  from the enclosing function or method.
- Exhaustiveness requirements for value-producing conditionals, branch result
  type rules, and value selection in branches containing multiple statements
  have not been separately confirmed. See [development status](../../cloth-language/references/development.md)
  for the initial implementation's final-`else`, matching-type, final-expression
  rules and the remaining design questions.
