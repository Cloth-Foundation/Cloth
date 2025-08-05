(** Token representation for the Loom language lexer *)

type t = {
  token_type : Token_types.t;
  value : string;
  span : Token_span.t;
}

(** Create a token *)
let make token_type value span = { token_type; value; span }

(** Get token type *)
let token_type token = token.token_type

(** Get token value *)
let value token = token.value

(** Get token span *)
let span token = token.span

(** Check if token is of a specific type *)
let is_of_type token expected_type = token.token_type = expected_type

(** Get token length *)
let length token = Token_span.length token.span

(** Get position string for error reporting *)
let position_string token = Token_span.position_string token.span

(** Convert token to string representation *)
let to_string token =
  Printf.sprintf "TOKEN [%s, value='%s', %s]"
    (String.uppercase_ascii (Token_types.to_string token.token_type))
    token.value
    (Token_span.to_string token.span)

(** Compare tokens by position *)
let compare token1 token2 =
  let result = compare token1.span.start_line token2.span.start_line in
  if result = 0 then
    compare token1.span.start_column token2.span.start_column
  else
    result 