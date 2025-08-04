(** Lexer module for tokenizing Loom source code *)
open Base
open Stdio

(** Lexer state *)
type state = {
  source : string;
  source_file : string option;
  position : Position.t;
  current : int;
  start : int;
  errors : Loom_error.t list;
} [@@deriving show]

(** Create initial lexer state *)
let create_state ?source_file source =
  { source; source_file; position = Position.start; current = 0; start = 0; errors = [] }

(** Check if we've reached the end of input *)
let is_at_end state = state.current >= String.length state.source

(** Get current character *)
let current_char state =
  if is_at_end state then '\000'
  else String.get state.source state.current

(** Get next character without advancing *)
let peek state =
  if state.current + 1 >= String.length state.source then '\000'
  else String.get state.source (state.current + 1)

(** Get character at offset from current position *)
let peek_at state offset =
  let pos = state.current + offset in
  if pos >= String.length state.source then '\000'
  else String.get state.source pos

(** Advance to next character *)
let advance state =
  let char = current_char state in
  let new_position = 
    if Char.equal char '\n' then Position.advance_line state.position
    else Position.advance_char state.position in
  { state with 
    current = state.current + 1;
    position = new_position }

(** Match current character and advance if it matches *)
let match_char state expected =
  if is_at_end state then false
  else if Char.equal (current_char state) expected then
    let _ = advance state in true
  else false

(** Skip whitespace *)
let skip_whitespace state =
  let rec skip state =
    if is_at_end state then state
    else
      let char = current_char state in
      if Char.is_whitespace char then
        skip (advance state)
      else state in
  skip state

(** Skip comments *)
let skip_comment state =
  let rec skip_single_line state =
    if is_at_end state then state
    else
      let char = current_char state in
      if Char.equal char '\n' then advance state
      else skip_single_line (advance state) in
  
  let rec skip_multi_line state =
    if is_at_end state then state
    else
      let char = current_char state in
      let next_char = peek state in
      if Char.equal char '*' && Char.equal next_char '/' then
        advance (advance state)
      else skip_multi_line (advance state) in
  
  if match_char state '/' then
    if match_char state '/' then
      skip_single_line state
    else if match_char state '*' then
      skip_multi_line state
    else state
  else state

(** Create a span from current lexeme *)
let make_span state =
  let start_pos = { state.position with column = state.position.column - (state.current - state.start) } in
  Span.create ~start_pos ~end_pos:state.position

(** Add error to state *)
let add_error state error =
  { state with errors = error :: state.errors }

(** Create identifier or keyword token *)
let identifier_or_keyword state =
  let rec scan state =
    if is_at_end state then state
    else
      let char = current_char state in
      if Char.is_alphanum char || Char.equal char '_' then
        scan (advance state)
      else state in
  
  let end_state = scan state in
  let lexeme = String.sub state.source ~pos:state.start ~len:(end_state.current - state.start) in
  let span = make_span end_state in
  
  (* Check if it's a keyword *)
  match Keywords.from_string lexeme with
  | Some kw -> 
      (Token.keyword kw span, end_state)
  | None -> 
      (Token.identifier lexeme span, end_state)

(** Create number token *)
let number state =
  let rec scan_digits state =
    if is_at_end state then state
    else
      let char = current_char state in
      if Char.is_digit char then
        scan_digits (advance state)
      else state in
  
  let rec scan_hex state =
    if is_at_end state then state
    else
      let char = current_char state in
      if Char.is_digit char || 
         (Char.is_alpha char && Char.is_lowercase char) ||
         (Char.is_alpha char && Char.is_uppercase char) then
        scan_hex (advance state)
      else state in
  
  let rec scan_octal state =
    if is_at_end state then state
    else
      let char = current_char state in
      if Char.is_digit char && char >= '0' && char <= '7' then
        scan_octal (advance state)
      else state in
  
  let rec scan_binary state =
    if is_at_end state then state
    else
      let char = current_char state in
      if Char.equal char '0' || Char.equal char '1' then
        scan_binary (advance state)
      else state in
  
  let state_after_digits = scan_digits state in
  
  (* Check for hex, octal, or binary *)
  let final_state = 
    if state.current < String.length state.source - 1 then
      let next_char = String.get state.source (state.current + 1) in
      if Char.equal (current_char state) '0' then
        match next_char with
        | 'x' | 'X' -> 
            let state_after_prefix = advance (advance state) in
            scan_hex state_after_prefix
        | 'o' | 'O' -> 
            let state_after_prefix = advance (advance state) in
            scan_octal state_after_prefix
        | 'b' | 'B' -> 
            let state_after_prefix = advance (advance state) in
            scan_binary state_after_prefix
        | _ -> state_after_digits
      else state_after_digits
    else state_after_digits in
  
  let lexeme = String.sub state.source ~pos:state.start ~len:(final_state.current - state.start) in
  let span = make_span final_state in
  
  (Token.number lexeme span, final_state)

(** Create string token *)
let string state =
  let rec scan state =
    if is_at_end state then 
      let error = Loom_error.lexical_error ~span:(make_span state) ~source_file:state.source_file 
        "Unterminated string literal" in
      (None, add_error state error)
    else
      let char = current_char state in
      if Char.equal char '"' then
        let next_state = advance state in
        let lexeme = String.sub state.source ~pos:(state.start + 1) ~len:(next_state.current - state.start - 2) in
        let span = make_span next_state in
        (Some (Token.string lexeme span), next_state)
      else if Char.equal char '\\' then
        (* Handle escape sequences *)
        let next_state = advance state in
        if is_at_end next_state then
          let error = Loom_error.lexical_error ~span:(make_span state) ~source_file:state.source_file 
            "Unterminated string literal" in
          (None, add_error state error)
        else
          scan (advance next_state)
      else
        scan (advance state) in
  
  scan state

(** Create character literal token *)
let character state =
  let rec scan state =
    if is_at_end state then 
      let error = Loom_error.lexical_error ~span:(make_span state) ~source_file:state.source_file 
        "Unterminated character literal" in
      (None, add_error state error)
    else
      let char = current_char state in
      if Char.equal char '\'' then
        let next_state = advance state in
        let lexeme = String.sub state.source ~pos:(state.start + 1) ~len:(next_state.current - state.start - 2) in
        let span = make_span next_state in
        (Some (Token.string lexeme span), next_state)
      else if Char.equal char '\\' then
        (* Handle escape sequences *)
        let next_state = advance state in
        if is_at_end next_state then
          let error = Loom_error.lexical_error ~span:(make_span state) ~source_file:state.source_file 
            "Unterminated character literal" in
          (None, add_error state error)
        else
          scan (advance next_state)
      else
        scan (advance state) in
  
  scan state

(** Scan operator or punctuation *)
let operator_or_punctuation state =
  let char = current_char state in
  let next_char = peek state in
  let next_next_char = peek_at state 2 in
  
  (* Three character operators *)
  if state.current + 2 < String.length state.source then
    let three_char = String.sub state.source ~pos:state.current ~len:3 in
    match three_char with
    | ">>>" -> 
        let new_state = advance (advance (advance state)) in
        (Token.operator TokenType.BitwiseURShift (make_span new_state), new_state)
    | ">>=" -> 
        let new_state = advance (advance (advance state)) in
        (Token.operator TokenType.BitwiseRShift (make_span new_state), new_state)
    | _ ->
        (* Two character operators *)
        let two_char = String.sub state.source ~pos:state.current ~len:2 in
        match two_char with
        | "==" -> 
            let new_state = advance (advance state) in
            (Token.operator TokenType.EqEq (make_span new_state), new_state)
        | "!=" -> 
            let new_state = advance (advance state) in
            (Token.operator TokenType.BangEq (make_span new_state), new_state)
        | "<=" -> 
            let new_state = advance (advance state) in
            (Token.operator TokenType.LtEq (make_span new_state), new_state)
        | ">=" -> 
            let new_state = advance (advance state) in
            (Token.operator TokenType.GtEq (make_span new_state), new_state)
        | "&&" -> 
            let new_state = advance (advance state) in
            (Token.operator TokenType.And (make_span new_state), new_state)
        | "||" -> 
            let new_state = advance (advance state) in
            (Token.operator TokenType.Or (make_span new_state), new_state)
        | "++" -> 
            let new_state = advance (advance state) in
            (Token.operator TokenType.PlusPlus (make_span new_state), new_state)
        | "--" -> 
            let new_state = advance (advance state) in
            (Token.operator TokenType.MinusMinus (make_span new_state), new_state)
        | "+=" -> 
            let new_state = advance (advance state) in
            (Token.operator TokenType.PlusEq (make_span new_state), new_state)
        | "-=" -> 
            let new_state = advance (advance state) in
            (Token.operator TokenType.MinusEq (make_span new_state), new_state)
        | "*=" -> 
            let new_state = advance (advance state) in
            (Token.operator TokenType.StarEq (make_span new_state), new_state)
        | "/=" -> 
            let new_state = advance (advance state) in
            (Token.operator TokenType.SlashEq (make_span new_state), new_state)
        | "%=" -> 
            let new_state = advance (advance state) in
            (Token.operator TokenType.ModuloEq (make_span new_state), new_state)
        | "<<" -> 
            let new_state = advance (advance state) in
            (Token.operator TokenType.BitwiseLShift (make_span new_state), new_state)
        | ">>" -> 
            let new_state = advance (advance state) in
            (Token.operator TokenType.BitwiseRShift (make_span new_state), new_state)
        | "->" -> 
            let new_state = advance (advance state) in
            (Token.punctuation TokenType.Arrow (make_span new_state), new_state)
        | "::" -> 
            let new_state = advance (advance state) in
            (Token.punctuation TokenType.DoubleColon (make_span new_state), new_state)
        | _ ->
            (* Single character operators/punctuation *)
            let new_state = advance state in
            let token_type = match char with
              | '(' -> TokenType.LParen
              | ')' -> TokenType.RParen
              | '{' -> TokenType.LBrace
              | '}' -> TokenType.RBrace
              | '[' -> TokenType.LBracket
              | ']' -> TokenType.RBracket
              | ',' -> TokenType.Comma
              | ';' -> TokenType.Semicolon
              | ':' -> TokenType.Colon
              | '?' -> TokenType.Question
              | '.' -> TokenType.Dot
              | '+' -> TokenType.Plus
              | '-' -> TokenType.Minus
              | '*' -> TokenType.Star
              | '/' -> TokenType.Slash
              | '%' -> TokenType.Modulo
              | '=' -> TokenType.Eq
              | '!' -> TokenType.Bang
              | '<' -> TokenType.Lt
              | '>' -> TokenType.Gt
              | '&' -> TokenType.BitwiseAnd
              | '|' -> TokenType.BitwiseOr
              | '^' -> TokenType.BitwiseXor
              | '~' -> TokenType.BitwiseNot
              | _ -> TokenType.Unknown in
            (Token.operator token_type (make_span new_state), new_state)
  else
    (* Handle two character operators at end of input *)
    let new_state = advance state in
    let token_type = match char with
      | '(' -> TokenType.LParen
      | ')' -> TokenType.RParen
      | '{' -> TokenType.LBrace
      | '}' -> TokenType.RBrace
      | '[' -> TokenType.LBracket
      | ']' -> TokenType.RBracket
      | ',' -> TokenType.Comma
      | ';' -> TokenType.Semicolon
      | ':' -> TokenType.Colon
      | '?' -> TokenType.Question
      | '.' -> TokenType.Dot
      | '+' -> TokenType.Plus
      | '-' -> TokenType.Minus
      | '*' -> TokenType.Star
      | '/' -> TokenType.Slash
      | '%' -> TokenType.Modulo
      | '=' -> TokenType.Eq
      | '!' -> TokenType.Bang
      | '<' -> TokenType.Lt
      | '>' -> TokenType.Gt
      | '&' -> TokenType.BitwiseAnd
      | '|' -> TokenType.BitwiseOr
      | '^' -> TokenType.BitwiseXor
      | '~' -> TokenType.BitwiseNot
      | _ -> TokenType.Unknown in
    (Token.operator token_type (make_span new_state), new_state)

(** Scan next token *)
let scan_token state =
  let state = skip_whitespace state in
  let state = skip_comment state in
  
  if is_at_end state then
    (Token.eof (make_span state), state)
  else
    let char = current_char state in
    let state = { state with start = state.current } in
    
    if Char.is_alpha char || Char.equal char '_' then
      identifier_or_keyword state
    else if Char.is_digit char then
      number state
    else if Char.equal char '"' then
      let _ = advance state in
      let result, new_state = string state in
      (match result with
       | Some token -> (token, new_state)
       | None -> (Token.eof (make_span new_state), new_state))
    else if Char.equal char '\'' then
      let _ = advance state in
      let result, new_state = character state in
      (match result with
       | Some token -> (token, new_state)
       | None -> (Token.eof (make_span new_state), new_state))
    else
      operator_or_punctuation state

(** Tokenize entire source *)
let tokenize ?source_file source =
  let rec tokenize_loop state tokens =
    let token, new_state = scan_token state in
    let new_tokens = token :: tokens in
    
    if Token.is_eof token then
      (List.rev new_tokens, new_state.errors)
    else
      tokenize_loop new_state new_tokens in
  
  let initial_state = create_state ?source_file source in
  tokenize_loop initial_state []

(** Tokenize with error handling *)
let tokenize_with_errors ?source_file source =
  let tokens, errors = tokenize ?source_file source in
  if List.is_empty errors then
    Result.ok tokens
  else
    Result.errors errors 