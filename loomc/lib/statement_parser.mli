(** Statement parser for basic statements *)

(** Parse a return statement *)
val parse_return : Parser.t -> Ast.Statements.t option

(** Parse an expression statement *)
val parse_expression_statement : Parser.t -> Ast.Statements.t option

(** Parse a block statement *)
val parse_block : Parser.t -> Ast.Statements.t option

(** Parse a single statement *)
val parse_statement : Parser.t -> Ast.Statements.t option 