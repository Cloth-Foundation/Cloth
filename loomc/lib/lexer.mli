(** Lexer for the Loom language *)

(** Lexer state *)
type lexer_state

(** Create a new lexer *)
val create : string -> string -> lexer_state

(** Check if we're at the end of input *)
val is_at_end : lexer_state -> bool

(** Peek at the current character *)
val peek : lexer_state -> char

(** Peek at the next character *)
val peek_next : lexer_state -> char

(** Advance to the next character *)
val advance : lexer_state -> char

(** Match and consume a character if it matches *)
val match_char : lexer_state -> char -> bool

(** Add a token to the lexer state *)
val add_token : lexer_state -> Token_types.t -> string -> int -> int -> unit

(** Check if a character can start an identifier *)
val is_identifier_start : char -> bool

(** Check if a character can be part of an identifier *)
val is_identifier_part : char -> bool

(** Read a string literal *)
val read_string : lexer_state -> int -> int -> unit

(** Read a number literal *)
val read_number : lexer_state -> char -> int -> int -> unit

(** Read an identifier or keyword *)
val read_identifier : lexer_state -> char -> int -> int -> unit

(** Read a block comment *)
val read_block_comment : lexer_state -> int -> int -> unit

(** Tokenize the source code *)
val tokenize : lexer_state -> Token.t list

(** Tokenize a string *)
val tokenize_string : string -> string -> Token.t list 