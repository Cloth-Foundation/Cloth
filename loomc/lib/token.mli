(** Token representation for the Loom language lexer *)

type t = {
  token_type : Token_types.t;
  value : string;
  span : Token_span.t;
}

(** Create a token *)
val make : Token_types.t -> string -> Token_span.t -> t

(** Get token type *)
val token_type : t -> Token_types.t

(** Get token value *)
val value : t -> string

(** Get token span *)
val span : t -> Token_span.t

(** Check if token is of a specific type *)
val is_of_type : t -> Token_types.t -> bool

(** Get token length *)
val length : t -> int

(** Get position string for error reporting *)
val position_string : t -> string

(** Convert token to string representation *)
val to_string : t -> string

(** Compare tokens by position *)
val compare : t -> t -> int 