(** Statement parser for basic statements *)

open Token_types
open Token
open Ast.Statements
open Ast.Expressions

(** Parse a return statement *)
let parse_return parser =
  match Parser.peek parser with
  | Some token when Token.token_type token = Keyword && Token.value token = "return" ->
      let start_span = Token.span token in
      let _ = Parser.advance parser in
      
      (* Parse optional return value *)
      let return_value = 
        if Parser.check parser Semicolon then None
        else (
          match Expression_parser.parse_expression parser with
          | Some expr -> Some expr
          | None -> None
        )
      in
      
      let _ = Parser.consume parser Semicolon "Expected ';' after return statement" in
      Some (Ast.Statements.return return_value start_span)
  | _ -> None

(** Parse an expression statement *)
let parse_expression_statement parser =
  match Expression_parser.parse_expression parser with
  | Some expr ->
      let _ = Parser.consume parser Semicolon "Expected ';' after expression" in
      Some (Ast.Statements.expression expr (Ast.Expressions.span expr))
  | None -> None

(** Parse a block statement *)
let parse_block parser =
  match Parser.peek parser with
  | Some token when Token.token_type token = Lbrace ->
      let start_span = Token.span token in
      let _ = Parser.advance parser in
      
      let rec parse_statements acc =
        if Parser.is_at_end parser then List.rev acc
        else (
          match Parser.peek parser with
          | Some token when Token.token_type token = Rbrace ->
              let _ = Parser.advance parser in
              List.rev acc
          | _ ->
              (match parse_statement parser with
               | Some stmt -> parse_statements (stmt :: acc)
               | None -> parse_statements acc)
        )
      in
      
      let statements = parse_statements [] in
      Some (Ast.Statements.block statements start_span)
  | _ -> None

(** Parse a single statement *)
let parse_statement parser =
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