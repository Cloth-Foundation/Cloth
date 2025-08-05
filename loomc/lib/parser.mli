(** Main parser for the Loom language *)

(** Parser state *)
type t

(** Create a new parser *)
val create : Token.t list -> t

(** Check if we're at the end of tokens *)
val is_at_end : t -> bool

(** Get the current token *)
val peek : t -> Token.t option

(** Get the previous token *)
val previous : t -> Token.t option

(** Advance to the next token *)
val advance : t -> Token.t option

(** Check if the current token matches a type *)
val check : t -> Token_types.t -> bool

(** Check if the current token matches a type and value *)
val match_token : t -> Token_types.t -> string -> bool

(** Consume a token of the expected type *)
val consume : t -> Token_types.t -> string -> Token.t option

(** Report a parsing error *)
val report_error : t -> Token.t -> string -> unit

(** Synchronize the parser after an error *)
val synchronize : t -> unit

(** Parse a program (list of statements) *)
val parse : t -> Ast.Statements.t list

(** Parse a single statement *)
val parse_statement : t -> Ast.Statements.t option

(** Parse an expression *)
val parse_expression : t -> Ast.Expressions.t option

(** Get all parsing errors *)
val get_errors : t -> string list

(** Check if the parser has errors *)
val has_errors : t -> bool 