(** AST statement types *)

open Token_span

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
let return value span = Return (value, span)

(** Create an expression statement *)
let expression expr span = Expression (expr, span)

(** Create a block statement *)
let block statements span = Block (statements, span)

(** Create an import statement *)
let import module_name span = Import (module_name, span)

(** Create an if statement *)
let if_stmt condition then_stmt else_stmt span = If (condition, then_stmt, else_stmt, span)

(** Create a while statement *)
let while_stmt condition body span = While (condition, body, span)

(** Create a for statement *)
let for_stmt init condition increment body span = For (init, condition, increment, body, span)

(** Create a do-while statement *)
let do_while body condition span = DoWhile (body, condition, span)

(** Create a break statement *)
let break span = Break span

(** Create a continue statement *)
let continue span = Continue span

(** Get the span of a statement *)
let span = function
  | Return (_, span) -> span
  | Expression (_, span) -> span
  | Block (_, span) -> span
  | Import (_, span) -> span
  | If (_, _, _, span) -> span
  | While (_, _, span) -> span
  | For (_, _, _, _, span) -> span
  | DoWhile (_, _, span) -> span
  | Break span -> span
  | Continue span -> span

(** Accept a visitor for a statement *)
let accept visitor stmt =
  match stmt with
  | Return (value, span) -> visitor#visit_return_stmt value span
  | Expression (expr, span) -> visitor#visit_expression_stmt expr span
  | Block (statements, span) -> visitor#visit_block_stmt statements span
  | Import (module_name, span) -> visitor#visit_import_stmt module_name span
  | If (condition, then_stmt, else_stmt, span) -> visitor#visit_if_stmt condition then_stmt else_stmt span
  | While (condition, body, span) -> visitor#visit_while_stmt condition body span
  | For (init, condition, increment, body, span) -> visitor#visit_for_stmt init condition increment body span
  | DoWhile (body, condition, span) -> visitor#visit_do_while_stmt body condition span
  | Break span -> visitor#visit_break_stmt span
  | Continue span -> visitor#visit_continue_stmt span 