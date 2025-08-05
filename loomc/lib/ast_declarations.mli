(** AST declaration types *)

(** Declaration types *)
type t =
  | Function of string * string list * t option * t * Token_span.t
  | Var of string * string * t option * Token_span.t
  | Class of string * t list * Token_span.t
  | Enum of string * (string * t option) list * Token_span.t
  | Struct of string * (string * string) list * Token_span.t
  | Constructor of string list * t * Token_span.t

(** Create a function declaration *)
val function_decl : string -> string list -> t option -> t -> Token_span.t -> t

(** Create a variable declaration *)
val var_decl : string -> string -> t option -> Token_span.t -> t

(** Create a class declaration *)
val class_decl : string -> t list -> Token_span.t -> t

(** Create an enum declaration *)
val enum_decl : string -> (string * t option) list -> Token_span.t -> t

(** Create a struct declaration *)
val struct_decl : string -> (string * string) list -> Token_span.t -> t

(** Create a constructor declaration *)
val constructor_decl : string list -> t -> Token_span.t -> t

(** Get the span of a declaration *)
val span : t -> Token_span.t

(** Accept a visitor for a declaration *)
val accept : < visit_function_decl : string -> string list -> t option -> t -> Token_span.t -> 'a;
               visit_var_decl : string -> string -> t option -> Token_span.t -> 'a;
               visit_class_decl : string -> t list -> Token_span.t -> 'a;
               visit_enum_decl : string -> (string * t option) list -> Token_span.t -> 'a;
               visit_struct_decl : string -> (string * string) list -> Token_span.t -> 'a;
               visit_constructor_decl : string list -> t -> Token_span.t -> 'a > -> t -> 'a 