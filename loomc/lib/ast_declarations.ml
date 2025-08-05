(** AST declaration types *)

open Token_span

(** Declaration types *)
type t =
  | Function of string * string list * t option * t * Token_span.t
  | Var of string * string * t option * Token_span.t
  | Class of string * t list * Token_span.t
  | Enum of string * (string * t option) list * Token_span.t
  | Struct of string * (string * string) list * Token_span.t
  | Constructor of string list * t * Token_span.t

(** Create a function declaration *)
let function_decl name params return_type body span = Function (name, params, return_type, body, span)

(** Create a variable declaration *)
let var_decl name var_type init span = Var (name, var_type, init, span)

(** Create a class declaration *)
let class_decl name members span = Class (name, members, span)

(** Create an enum declaration *)
let enum_decl name variants span = Enum (name, variants, span)

(** Create a struct declaration *)
let struct_decl name fields span = Struct (name, fields, span)

(** Create a constructor declaration *)
let constructor_decl params body span = Constructor (params, body, span)

(** Get the span of a declaration *)
let span = function
  | Function (_, _, _, _, span) -> span
  | Var (_, _, _, span) -> span
  | Class (_, _, span) -> span
  | Enum (_, _, span) -> span
  | Struct (_, _, span) -> span
  | Constructor (_, _, span) -> span

(** Accept a visitor for a declaration *)
let accept visitor decl =
  match decl with
  | Function (name, params, return_type, body, span) -> visitor#visit_function_decl name params return_type body span
  | Var (name, var_type, init, span) -> visitor#visit_var_decl name var_type init span
  | Class (name, members, span) -> visitor#visit_class_decl name members span
  | Enum (name, variants, span) -> visitor#visit_enum_decl name variants span
  | Struct (name, fields, span) -> visitor#visit_struct_decl name fields span
  | Constructor (params, body, span) -> visitor#visit_constructor_decl params body span 