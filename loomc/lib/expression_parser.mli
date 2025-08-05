(** Expression parser with operator precedence *)

(** Operator precedence levels *)
type precedence =
  | Assignment    (* =, +=, -=, etc. *)
  | LogicalOr     (* || *)
  | LogicalAnd    (* && *)
  | Equality      (* ==, != *)
  | Comparison    (* <, <=, >, >= *)
  | Term          (* +, - *)
  | Factor        (* *, /, % *)
  | Unary         (* !, -, ++, -- *)
  | Primary       (* literals, variables, function calls *)

(** Get precedence for a token *)
val get_precedence : Token_types.t -> precedence

(** Parse a primary expression *)
val parse_primary : Parser.t -> Ast.Expressions.t option

(** Parse a unary expression *)
val parse_unary : Parser.t -> Ast.Expressions.t option

(** Parse a binary expression with given precedence *)
val parse_binary : Parser.t -> precedence -> Ast.Expressions.t option

(** Parse an expression *)
val parse_expression : Parser.t -> Ast.Expressions.t option 