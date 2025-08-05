(** AST expression types *)

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
val literal : string -> Token_span.t -> t

(** Create a binary expression *)
val binary : t -> string -> t -> Token_span.t -> t

(** Create a variable expression *)
val variable : string -> Token_span.t -> t

(** Create a unary expression *)
val unary : string -> t -> Token_span.t -> t

(** Create a function call expression *)
val call : t -> t list -> Token_span.t -> t

(** Create a new object expression *)
val new_expr : string -> t list -> Token_span.t -> t

(** Create a member access expression *)
val member_access : t -> string -> Token_span.t -> t

(** Create a get expression *)
val get : t -> string -> Token_span.t -> t

(** Create an assignment expression *)
val assign : t -> t -> Token_span.t -> t

(** Create an increment expression *)
val increment : t -> Token_span.t -> t

(** Create a decrement expression *)
val decrement : t -> Token_span.t -> t

(** Create a ternary expression *)
val ternary : t -> t -> t -> Token_span.t -> t

(** Create a compound assignment expression *)
val compound_assign : t -> string -> t -> Token_span.t -> t

(** Create a struct expression *)
val struct_expr : string -> (string * t) list -> Token_span.t -> t

(** Get the span of an expression *)
val span : t -> Token_span.t

(** Accept a visitor for an expression *)
val accept : < visit_literal_expr : string -> Token_span.t -> 'a;
               visit_binary_expr : t -> string -> t -> Token_span.t -> 'a;
               visit_variable_expr : string -> Token_span.t -> 'a;
               visit_unary_expr : string -> t -> Token_span.t -> 'a;
               visit_call_expr : t -> t list -> Token_span.t -> 'a;
               visit_new_expr : string -> t list -> Token_span.t -> 'a;
               visit_member_access_expr : t -> string -> Token_span.t -> 'a;
               visit_get_expr : t -> string -> Token_span.t -> 'a;
               visit_assign_expr : t -> t -> Token_span.t -> 'a;
               visit_increment_expr : t -> Token_span.t -> 'a;
               visit_decrement_expr : t -> Token_span.t -> 'a;
               visit_ternary_expr : t -> t -> t -> Token_span.t -> 'a;
               visit_compound_assign_expr : t -> string -> t -> Token_span.t -> 'a;
               visit_struct_expr : string -> (string * t) list -> Token_span.t -> 'a > -> t -> 'a 