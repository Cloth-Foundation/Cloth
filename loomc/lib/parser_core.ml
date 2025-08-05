(** Unified parser for expressions and statements *)

open Token_types
open Token
open Ast_expressions
open Ast_statements

(** Operator precedence levels *)
type precedence =
  | Assignment    (* =, +=, -=, etc. *)
  | LogicalOr     (* || *)
  | LogicalAnd    (* && *)
  | Equality      (* ==, != *)
  | Comparison    (* <, <=, >, >= *)
  | Term          (* +, - *)
  | Factor        (* *, /, % *)
  | Unary         (* !, -, ++, -- *)
  | Primary       (* literals, variables, function calls *)

(** Get precedence for a token *)
let get_precedence token_type =
  match token_type with
  | Eq | Pluseq | Minuseq | Stareq | Slasheq | Moduloeq -> Assignment
  | Or -> LogicalOr
  | And -> LogicalAnd
  | Eqeq | Bangeq -> Equality
  | Lt | Lteq | Gt | Gteq -> Comparison
  | Plus | Minus -> Term
  | Star | Slash | Modulo -> Factor
  | Bang | Plusplus | Minusminus -> Unary
  | _ -> Primary

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
    | Some _token ->
        (match Token.token_type _token with
         | Semicolon -> ()
         | Eof -> ()
         | _ -> loop ())
    | None -> ()
  in
  loop ()

(** Parse a primary expression *)
let rec parse_primary parser =
  match peek parser with
  | Some token ->
      (match Token.token_type token with
       | Number -> 
           let _ = advance parser in
           Some (Ast_expressions.literal (Token.value token) (Token.span token))
       | String ->
           let _ = advance parser in
           Some (Ast_expressions.literal (Token.value token) (Token.span token))
       | Identifier ->
           let _ = advance parser in
           Some (Ast_expressions.variable (Token.value token) (Token.span token))
       | Lparen ->
           let _ = advance parser in
           (* Parse parenthesized expression *)
           (match parse_expression parser with
            | Some expr ->
                (match consume parser Rparen "Expected ')' after expression" with
                 | Some _ -> Some expr
                 | None -> None)
            | None -> None)
       | _ -> None)
  | None -> None

(** Parse a unary expression *)
and parse_unary parser =
  match peek parser with
  | Some token ->
      (match Token.token_type token with
       | Bang | Minus | Plusplus | Minusminus ->
           let operator = Token.value token in
           let _ = advance parser in
           (match parse_unary parser with
            | Some operand -> Some (Ast_expressions.unary operator operand (Token.span token))
            | None -> None)
       | _ -> parse_primary parser)
  | None -> parse_primary parser

(** Parse a binary expression with given precedence *)
and parse_binary parser min_precedence =
  let rec parse_binary_right left =
    match peek parser with
    | Some token ->
        let token_type = Token.token_type token in
        let precedence = get_precedence token_type in
        
        if precedence <= min_precedence then Some left
        else (
          let operator = Token.value token in
          let _ = advance parser in
          
          let right = parse_binary parser precedence in
          match right with
          | Some right_expr ->
              let span = Token_span.merge (Ast_expressions.span left) (Ast_expressions.span right_expr) in
              let binary_expr = Ast_expressions.binary left operator right_expr span in
              parse_binary_right binary_expr
          | None -> None
        )
    | None -> Some left
  in
  
  let left = parse_unary parser in
  match left with
  | Some left_expr -> parse_binary_right left_expr
  | None -> None

(** Parse an expression *)
and parse_expression parser = parse_binary parser Assignment

(** Parse a return statement *)
let parse_return parser =
  match peek parser with
  | Some token when Token.token_type token = Keyword && Token.value token = "return" ->
      let start_span = Token.span token in
      let _ = advance parser in
      
      (* Parse optional return value *)
      let return_value = 
        if check parser Semicolon then None
        else (
          match parse_expression parser with
          | Some expr -> Some expr
          | None -> None
        )
      in
      
      let _ = consume parser Semicolon "Expected ';' after return statement" in
      Some (Ast_statements.return return_value start_span)
  | _ -> None

(** Parse an expression statement *)
let parse_expression_statement parser =
  match parse_expression parser with
  | Some expr ->
      let _ = consume parser Semicolon "Expected ';' after expression" in
      Some (Ast_statements.expression expr (Ast_expressions.span expr))
  | None -> None

(** Parse a block statement *)
let rec parse_block parser =
  match peek parser with
  | Some token when Token.token_type token = Lbrace ->
      let start_span = Token.span token in
      let _ = advance parser in
      
      let rec parse_statements acc =
        if is_at_end parser then List.rev acc
        else (
          match peek parser with
          | Some token when Token.token_type token = Rbrace ->
              let _ = advance parser in
              List.rev acc
          | _ ->
              (match parse_statement parser with
               | Some stmt -> parse_statements (stmt :: acc)
               | None -> parse_statements acc)
        )
      in
      
      let statements = parse_statements [] in
      Some (Ast_statements.block statements start_span)
  | _ -> None

(** Parse a single statement *)
and parse_statement parser =
  (* Try different statement types in order *)
  match parse_return parser with
  | Some stmt -> Some stmt
  | None ->
      (match parse_block parser with
       | Some stmt -> Some stmt
       | None ->
           (match parse_expression_statement parser with
            | Some stmt -> Some stmt
            | None -> None))

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

(** Get all parsing errors *)
let get_errors parser = List.rev parser.errors

(** Check if the parser has errors *)
let has_errors parser = List.length parser.errors > 0 