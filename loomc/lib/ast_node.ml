(** Base AST node type and visitor pattern *)

open Token_span

(** Base node type that all AST nodes implement *)
type 'a node = {
  span : Token_span.t;
  data : 'a;
}

(** Create a node with span and data *)
let make span data = { span; data }

(** Get the span of a node *)
let span node = node.span

(** Get the data of a node *)
let data node = node.data

(** Node visitor pattern - defines operations on AST nodes *)
module type Visitor = sig
  type t

  (** Expression visitors *)
  val visit_literal_expr : string -> Token_span.t -> t
  val visit_binary_expr : t -> string -> t -> Token_span.t -> t
  val visit_variable_expr : string -> Token_span.t -> t
  val visit_unary_expr : string -> t -> Token_span.t -> t
  val visit_call_expr : t -> t list -> Token_span.t -> t
  val visit_new_expr : string -> t list -> Token_span.t -> t
  val visit_member_access_expr : t -> string -> Token_span.t -> t
  val visit_get_expr : t -> string -> Token_span.t -> t
  val visit_assign_expr : t -> t -> Token_span.t -> t
  val visit_increment_expr : t -> Token_span.t -> t
  val visit_decrement_expr : t -> Token_span.t -> t
  val visit_ternary_expr : t -> t -> t -> Token_span.t -> t
  val visit_compound_assign_expr : t -> string -> t -> Token_span.t -> t
  val visit_struct_expr : string -> (string * t) list -> Token_span.t -> t

  (** Statement visitors *)
  val visit_return_stmt : t option -> Token_span.t -> t
  val visit_expression_stmt : t -> Token_span.t -> t
  val visit_block_stmt : t list -> Token_span.t -> t
  val visit_import_stmt : string -> Token_span.t -> t
  val visit_if_stmt : t -> t -> t option -> Token_span.t -> t
  val visit_while_stmt : t -> t -> Token_span.t -> t
  val visit_for_stmt : t option -> t option -> t option -> t -> Token_span.t -> t
  val visit_do_while_stmt : t -> t -> Token_span.t -> t
  val visit_break_stmt : Token_span.t -> t
  val visit_continue_stmt : Token_span.t -> t

  (** Declaration visitors *)
  val visit_function_decl : string -> string list -> t option -> t -> Token_span.t -> t
  val visit_var_decl : string -> string -> t option -> Token_span.t -> t
  val visit_class_decl : string -> t list -> Token_span.t -> t
  val visit_enum_decl : string -> (string * t option) list -> Token_span.t -> t
  val visit_struct_decl : string -> (string * string) list -> Token_span.t -> t
  val visit_constructor_decl : string list -> t -> Token_span.t -> t
end 