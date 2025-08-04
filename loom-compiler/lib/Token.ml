(** Token module representing lexical tokens *)
open Base

(** Token value type *)
type value =
  | String of string
  | Number of string
  | Keyword of Keywords.keyword
  | Identifier of string
  | Null
[@@deriving show]

(** Token representation *)
type t = {
  token_type : TokenType.t;
  value : value;
  span : Span.t;
} [@@deriving show]

(** Create a new token *)
let create ~token_type ~value ~span = { token_type; value; span }

(** Create a string token *)
let string str span = 
  create ~token_type:TokenType.String ~value:(String str) ~span

(** Create a number token *)
let number num span = 
  create ~token_type:TokenType.Number ~value:(Number num) ~span

(** Create a keyword token *)
let keyword kw span = 
  create ~token_type:TokenType.Keyword ~value:(Keyword kw) ~span

(** Create an identifier token *)
let identifier id span = 
  create ~token_type:TokenType.Identifier ~value:(Identifier id) ~span

(** Create a null token *)
let null span = 
  create ~token_type:TokenType.Null ~value:Null ~span

(** Create a punctuation token *)
let punctuation token_type span = 
  create ~token_type ~value:(String (TokenType.to_string token_type)) ~span

(** Create an operator token *)
let operator token_type span = 
  create ~token_type ~value:(String (TokenType.to_string token_type)) ~span

(** Create an EOF token *)
let eof span = 
  create ~token_type:TokenType.Eof ~value:(String "EOF") ~span

(** Get the token type *)
let token_type token = token.token_type

(** Get the token value *)
let value token = token.value

(** Get the token span *)
let span token = token.span

(** Get the string value of a token *)
let string_value token = 
  match token.value with
  | String s -> s
  | Number n -> n
  | Keyword kw -> Keywords.to_string kw
  | Identifier id -> id
  | Null -> "null"

(** Check if token is of a specific type *)
let is_of_type token expected_type = 
  TokenType.equal token.token_type expected_type

(** Check if token is a keyword *)
let is_keyword token = TokenType.is_keyword token.token_type

(** Check if token is a specific keyword *)
let is_specific_keyword token kw = 
  match token.value with
  | Keyword k -> Keywords.equal k kw
  | _ -> false

(** Check if token is an identifier *)
let is_identifier token = TokenType.is_identifier token.token_type

(** Check if token is a literal *)
let is_literal token = TokenType.is_literal token.token_type

(** Check if token is an operator *)
let is_operator token = TokenType.is_operator token.token_type

(** Check if token is punctuation *)
let is_punctuation token = TokenType.is_punctuation token.token_type

(** Check if token is EOF *)
let is_eof token = TokenType.is_eof token.token_type

(** Get the precedence of an operator token *)
let precedence token = TokenType.precedence token.token_type

(** Check if token is a compound assignment operator *)
let is_compound_assignment token = TokenType.is_compound_assignment token.token_type

(** Check if token is an increment/decrement operator *)
let is_increment_decrement token = TokenType.is_increment_decrement token.token_type

(** Convert token to string for display *)
let to_string token =
  let value_str = match token.value with
    | String s -> Printf.sprintf "'%s'" s
    | Number n -> Printf.sprintf "'%s'" n
    | Keyword kw -> Printf.sprintf "'%s'" (Keywords.to_string kw)
    | Identifier id -> Printf.sprintf "'%s'" id
    | Null -> "'null'" in
  
  Printf.sprintf "TOKEN [%s, value=%s, %s]"
    (TokenType.to_string token.token_type)
    value_str
    (Span.to_string token.span)

(** Get the length of the token *)
let length token = Span.length token.span

(** Get the position string for error reporting *)
let position_string token = Span.to_string token.span

(** Compare two tokens *)
let compare token1 token2 =
  let type_cmp = TokenType.compare token1.token_type token2.token_type in
  if type_cmp <> 0 then type_cmp
  else Span.compare token1.span token2.span

(** Check if two tokens are equal *)
let equal token1 token2 = compare token1 token2 = 0 