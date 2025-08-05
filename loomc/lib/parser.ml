(** Main parser for the Loom language *)

open Token_types
open Token
open Ast.Expressions
open Ast.Statements
open Ast.Declarations

(** Parser state *)
type t = {
  tokens : Token.t list;
  mutable current : int;
  mutable errors : string list;
}

(** Create a new parser *)
let create tokens =
  { tokens; current = 0; errors = [] }

(** Check if we're at the end of tokens *)
let is_at_end parser = parser.current >= List.length parser.tokens

(** Get the current token *)
let peek parser =
  if is_at_end parser then None
  else Some (List.nth parser.tokens parser.current)

(** Get the previous token *)
let previous parser =
  if parser.current = 0 then None
  else Some (List.nth parser.tokens (parser.current - 1))

(** Advance to the next token *)
let advance parser =
  if is_at_end parser then None
  else (
    let token = List.nth parser.tokens parser.current in
    parser.current <- parser.current + 1;
    Some token
  )

(** Check if the current token matches a type *)
let check parser token_type =
  match peek parser with
  | Some token -> Token.token_type token = token_type
  | None -> false

(** Check if the current token matches a type and value *)
let match_token parser token_type value =
  match peek parser with
  | Some token -> Token.token_type token = token_type && Token.value token = value
  | None -> false

(** Consume a token of the expected type *)
let consume parser token_type message =
  match peek parser with
  | Some token when Token.token_type token = token_type ->
      advance parser
  | Some token ->
      parser.errors <- message :: parser.errors;
      advance parser
  | None ->
      parser.errors <- message :: parser.errors;
      None

(** Report a parsing error *)
let report_error parser token message =
  parser.errors <- Printf.sprintf "Error at %s: %s" (Token.position_string token) message :: parser.errors

(** Synchronize the parser after an error *)
let synchronize parser =
  let rec loop () =
    match advance parser with
    | Some token ->
        (match Token.token_type token with
         | Semicolon -> ()
         | Eof -> ()
         | _ -> loop ())
    | None -> ()
  in
  loop ()

(** Parse a program (list of statements) *)
let parse parser =
  let rec parse_statements acc =
    if is_at_end parser then List.rev acc
    else (
      match parse_statement parser with
      | Some stmt -> parse_statements (stmt :: acc)
      | None -> parse_statements acc
    )
  in
  parse_statements []

(** Parse a single statement *)
let parse_statement parser =
  Statement_parser.parse_statement parser

(** Parse an expression *)
let parse_expression parser =
  Expression_parser.parse_expression parser

(** Get all parsing errors *)
let get_errors parser = List.rev parser.errors

(** Check if the parser has errors *)
let has_errors parser = List.length parser.errors > 0 