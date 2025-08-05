(** AST expression types *)

open Token_span

(** Expression types *)
type t =
  | Literal of string * Token_span.t
  | Binary of t * string * t * Token_span.t
  | Variable of string * Token_span.t
  | Unary of string * t * Token_span.t
  | Call of t * t list * Token_span.t
  | New of string * t list * Token_span.t
  | MemberAccess of t * string * Token_span.t
  | Get of t * string * Token_span.t
  | Assign of t * t * Token_span.t
  | Increment of t * Token_span.t
  | Decrement of t * Token_span.t
  | Ternary of t * t * t * Token_span.t
  | CompoundAssign of t * string * t * Token_span.t
  | Struct of string * (string * t) list * Token_span.t

(** Create a literal expression *)
let literal value span = Literal (value, span)

(** Create a binary expression *)
let binary left operator right span = Binary (left, operator, right, span)

(** Create a variable expression *)
let variable name span = Variable (name, span)

(** Create a unary expression *)
let unary operator operand span = Unary (operator, operand, span)

(** Create a function call expression *)
let call func args span = Call (func, args, span)

(** Create a new object expression *)
let new_expr class_name args span = New (class_name, args, span)

(** Create a member access expression *)
let member_access object_expr member span = MemberAccess (object_expr, member, span)

(** Create a get expression *)
let get object_expr field span = Get (object_expr, field, span)

(** Create an assignment expression *)
let assign target value span = Assign (target, value, span)

(** Create an increment expression *)
let increment expr span = Increment (expr, span)

(** Create a decrement expression *)
let decrement expr span = Decrement (expr, span)

(** Create a ternary expression *)
let ternary condition then_expr else_expr span = Ternary (condition, then_expr, else_expr, span)

(** Create a compound assignment expression *)
let compound_assign target operator value span = CompoundAssign (target, operator, value, span)

(** Create a struct expression *)
let struct_expr struct_name fields span = Struct (struct_name, fields, span)

(** Get the span of an expression *)
let span = function
  | Literal (_, span) -> span
  | Binary (_, _, _, span) -> span
  | Variable (_, span) -> span
  | Unary (_, _, span) -> span
  | Call (_, _, span) -> span
  | New (_, _, span) -> span
  | MemberAccess (_, _, span) -> span
  | Get (_, _, span) -> span
  | Assign (_, _, span) -> span
  | Increment (_, span) -> span
  | Decrement (_, span) -> span
  | Ternary (_, _, _, span) -> span
  | CompoundAssign (_, _, _, span) -> span
  | Struct (_, _, span) -> span

(** Accept a visitor for an expression *)
let accept visitor expr =
  match expr with
  | Literal (value, span) -> visitor#visit_literal_expr value span
  | Binary (left, operator, right, span) -> visitor#visit_binary_expr left operator right span
  | Variable (name, span) -> visitor#visit_variable_expr name span
  | Unary (operator, operand, span) -> visitor#visit_unary_expr operator operand span
  | Call (func, args, span) -> visitor#visit_call_expr func args span
  | New (class_name, args, span) -> visitor#visit_new_expr class_name args span
  | MemberAccess (object_expr, member, span) -> visitor#visit_member_access_expr object_expr member span
  | Get (object_expr, field, span) -> visitor#visit_get_expr object_expr field span
  | Assign (target, value, span) -> visitor#visit_assign_expr target value span
  | Increment (expr, span) -> visitor#visit_increment_expr expr span
  | Decrement (expr, span) -> visitor#visit_decrement_expr expr span
  | Ternary (condition, then_expr, else_expr, span) -> visitor#visit_ternary_expr condition then_expr else_expr span
  | CompoundAssign (target, operator, value, span) -> visitor#visit_compound_assign_expr target operator value span
  | Struct (struct_name, fields, span) -> visitor#visit_struct_expr struct_name fields span 