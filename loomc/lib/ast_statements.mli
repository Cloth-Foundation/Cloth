(** AST statement types *)

(** Statement types *)
type t =
  | Return of Ast_expressions.t option * Token_span.t
  | Expression of Ast_expressions.t * Token_span.t
  | Block of t list * Token_span.t
  | Import of string * Token_span.t
  | If of Ast_expressions.t * t * t option * Token_span.t
  | While of Ast_expressions.t * t * Token_span.t
  | For of Ast_expressions.t option * Ast_expressions.t option * Ast_expressions.t option * t * Token_span.t
  | DoWhile of t * Ast_expressions.t * Token_span.t
  | Break of Token_span.t
  | Continue of Token_span.t

(** Create a return statement *)
val return : Ast_expressions.t option -> Token_span.t -> t

(** Create an expression statement *)
val expression : Ast_expressions.t -> Token_span.t -> t

(** Create a block statement *)
val block : t list -> Token_span.t -> t

(** Create an import statement *)
val import : string -> Token_span.t -> t

(** Create an if statement *)
val if_stmt : Ast_expressions.t -> t -> t option -> Token_span.t -> t

(** Create a while statement *)
val while_stmt : Ast_expressions.t -> t -> Token_span.t -> t

(** Create a for statement *)
val for_stmt : Ast_expressions.t option -> Ast_expressions.t option -> Ast_expressions.t option -> t -> Token_span.t -> t

(** Create a do-while statement *)
val do_while : t -> Ast_expressions.t -> Token_span.t -> t

(** Create a break statement *)
val break : Token_span.t -> t

(** Create a continue statement *)
val continue : Token_span.t -> t

(** Get the span of a statement *)
val span : t -> Token_span.t

(** Accept a visitor for a statement *)
val accept : < visit_return_stmt : Ast_expressions.t option -> Token_span.t -> 'a;
               visit_expression_stmt : Ast_expressions.t -> Token_span.t -> 'a;
               visit_block_stmt : t list -> Token_span.t -> 'a;
               visit_import_stmt : string -> Token_span.t -> 'a;
               visit_if_stmt : Ast_expressions.t -> t -> t option -> Token_span.t -> 'a;
               visit_while_stmt : Ast_expressions.t -> t -> Token_span.t -> 'a;
               visit_for_stmt : Ast_expressions.t option -> Ast_expressions.t option -> Ast_expressions.t option -> t -> Token_span.t -> 'a;
               visit_do_while_stmt : t -> Ast_expressions.t -> Token_span.t -> 'a;
               visit_break_stmt : Token_span.t -> 'a;
               visit_continue_stmt : Token_span.t -> 'a > -> t -> 'a 