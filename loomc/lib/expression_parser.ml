(** Expression parser with operator precedence *)

open Token_types
open Token
open Ast.Expressions

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

(** Parse a primary expression *)
let parse_primary parser =
  match Parser.peek parser with
  | Some token ->
      (match Token.token_type token with
       | Number -> 
           let _ = Parser.advance parser in
           Some (Ast.Expressions.literal (Token.value token) (Token.span token))
       | String ->
           let _ = Parser.advance parser in
           Some (Ast.Expressions.literal (Token.value token) (Token.span token))
       | Identifier ->
           let _ = Parser.advance parser in
           Some (Ast.Expressions.variable (Token.value token) (Token.span token))
       | Lparen ->
           let _ = Parser.advance parser in
           (* Parse parenthesized expression *)
           (match parse_expression parser with
            | Some expr ->
                (match Parser.consume parser Rparen "Expected ')' after expression" with
                 | Some _ -> Some expr
                 | None -> None)
            | None -> None)
       | _ -> None)
  | None -> None

(** Parse a unary expression *)
let parse_unary parser =
  match Parser.peek parser with
  | Some token ->
      (match Token.token_type token with
       | Bang | Minus | Plusplus | Minusminus ->
           let operator = Token.value token in
           let _ = Parser.advance parser in
           (match parse_unary parser with
            | Some operand -> Some (Ast.Expressions.unary operator operand (Token.span token))
            | None -> None)
       | _ -> parse_primary parser)
  | None -> parse_primary parser

(** Parse a binary expression with given precedence *)
let rec parse_binary parser min_precedence =
  let rec parse_binary_right left =
    match Parser.peek parser with
    | Some token ->
        let token_type = Token.token_type token in
        let precedence = get_precedence token_type in
        
        if precedence <= min_precedence then Some left
        else (
          let operator = Token.value token in
          let _ = Parser.advance parser in
          
          let right = parse_binary parser precedence in
          match right with
          | Some right_expr ->
              let span = Token_span.merge (Ast.Expressions.span left) (Ast.Expressions.span right_expr) in
              let binary_expr = Ast.Expressions.binary left operator right_expr span in
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
let parse_expression parser = parse_binary parser Assignment 