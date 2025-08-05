(** Lexer for the Loom language *)

(** Lexer state *)
type lexer_state = {
  source : string;
  file_name : string;
  mutable tokens : Token.t list;
  mutable index : int;
  mutable line : int;
  mutable column : int;
}

(** Create a new lexer *)
let create source file_name =
  { source; file_name; tokens = []; index = 0; line = 1; column = 0 }

(** Check if we're at the end of input *)
let is_at_end state = state.index >= String.length state.source

(** Peek at the current character *)
let peek state =
  if is_at_end state then '\000'
  else String.get state.source state.index

(** Peek at the next character *)
let peek_next state =
  if state.index + 1 >= String.length state.source then '\000'
  else String.get state.source (state.index + 1)

(** Advance to the next character *)
let advance state =
  if is_at_end state then '\000'
  else (
    let c = String.get state.source state.index in
    state.index <- state.index + 1;
    state.column <- state.column + 1;
    c
  )

(** Match and consume a character if it matches *)
let match_char state expected =
  if is_at_end state then false
  else if String.get state.source state.index = expected then (
    let _ = advance state in
    true
  ) else false

(** Add a token to the lexer state *)
let add_token state token_type value start_line start_col =
  let span = Token_span.single_line start_line start_col state.column (Some state.file_name) in
  let token = Token.make token_type value span in
  state.tokens <- token :: state.tokens

(** Check if a character can start an identifier *)
let is_identifier_start c =
  (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c = '_'

(** Check if a character can be part of an identifier *)
let is_identifier_part c =
  (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || 
  (c >= '0' && c <= '9') || c = '_'

(** Read a string literal *)
let read_string state start_line start_col =
  let buf = Buffer.create 16 in
  let rec loop () =
    if is_at_end state then (
      (* Unterminated string - we'll handle this as an error later *)
      ()
    ) else (
      let c = advance state in
      match c with
      | '"' -> ()
      | '\\' -> (
          if is_at_end state then (
            (* Unterminated escape sequence *)
            ()
          ) else (
            let next = advance state in
            match next with
            | 'n' -> Buffer.add_char buf '\n'
            | 't' -> Buffer.add_char buf '\t'
            | 'r' -> Buffer.add_char buf '\r'
            | '\\' -> Buffer.add_char buf '\\'
            | '"' -> Buffer.add_char buf '"'
            | _ -> Buffer.add_char buf next
          );
          loop ()
        )
      | _ -> Buffer.add_char buf c; loop ()
    )
  in
  loop ();
  let value = Buffer.contents buf in
  add_token state Token_types.String value start_line start_col

(** Read a number literal *)
let read_number state first start_line start_col =
  let buf = Buffer.create 16 in
  Buffer.add_char buf first;
  
  let rec loop () =
    if is_at_end state then ()
    else (
      let c = peek state in
      if (c >= '0' && c <= '9') || c = '.' || c = 'e' || c = 'E' || c = '+' || c = '-' then (
        let _ = advance state in
        Buffer.add_char buf c;
        loop ()
      )
    )
  in
  loop ();
  
  let value = Buffer.contents buf in
  add_token state Token_types.Number value start_line start_col

(** Read an identifier or keyword *)
let read_identifier state first start_line start_col =
  let buf = Buffer.create 16 in
  Buffer.add_char buf first;
  
  let rec loop () =
    if is_at_end state then ()
    else (
      let c = peek state in
      if is_identifier_part c then (
        let _ = advance state in
        Buffer.add_char buf c;
        loop ()
      )
    )
  in
  loop ();
  
  let value = Buffer.contents buf in
  let token_type = if Keywords.is_keyword value then Token_types.Keyword else Token_types.Identifier in
  add_token state token_type value start_line start_col

(** Read a block comment *)
let read_block_comment state start_line start_col =
  let rec loop () =
    if is_at_end state then (
      (* Unterminated comment - we'll handle this as an error later *)
      ()
    ) else (
      let c = advance state in
      match c with
      | '*' -> (
          if not (is_at_end state) && peek state = '/' then (
            let _ = advance state in
            ()
          ) else loop ()
        )
      | '\n' -> state.line <- state.line + 1; state.column <- 0; loop ()
      | _ -> loop ()
    )
  in
  loop ();
  
  let span = Token_span.make start_line start_col state.line state.column (Some state.file_name) in
  let token = Token.make Token_types.Comment "" span in
  state.tokens <- token :: state.tokens

(** Tokenize the source code *)
let tokenize state =
  while not (is_at_end state) do
    let start_line = state.line in
    let start_col = state.column in
    
    let c = advance state in
    
    match c with
    | ' ' | '\r' | '\t' -> () (* Skip whitespace *)
    | '\n' -> state.line <- state.line + 1; state.column <- 0
    | '/' -> (
        if match_char state '/' then (
          (* Single-line comment *)
          while not (is_at_end state) && peek state != '\n' do
            let _ = advance state in ()
          done
        ) else if match_char state '*' then (
          (* Block comment *)
          read_block_comment state start_line start_col
        ) else if match_char state '=' then (
          add_token state Token_types.Slasheq "/=" start_line start_col
        ) else (
          add_token state Token_types.Slash "/" start_line start_col
        )
      )
    | '"' -> read_string state start_line start_col
    | '+' -> (
        if match_char state '+' then (
          add_token state Token_types.Plusplus "++" start_line start_col
        ) else if match_char state '=' then (
          add_token state Token_types.Pluseq "+=" start_line start_col
        ) else (
          add_token state Token_types.Plus "+" start_line start_col
        )
      )
    | '-' -> (
        if match_char state '-' then (
          add_token state Token_types.Minusminus "--" start_line start_col
        ) else if match_char state '=' then (
          add_token state Token_types.Minuseq "-=" start_line start_col
        ) else if match_char state '>' then (
          add_token state Token_types.Arrow "->" start_line start_col
        ) else (
          add_token state Token_types.Minus "-" start_line start_col
        )
      )
    | '*' -> (
        if match_char state '=' then (
          add_token state Token_types.Stareq "*=" start_line start_col
        ) else (
          add_token state Token_types.Star "*" start_line start_col
        )
      )
    | '%' -> (
        if match_char state '=' then (
          add_token state Token_types.Moduloeq "%=" start_line start_col
        ) else (
          add_token state Token_types.Modulo "%" start_line start_col
        )
      )
    | '!' -> (
        if match_char state '=' then (
          add_token state Token_types.Bangeq "!=" start_line start_col
        ) else (
          add_token state Token_types.Bang "!" start_line start_col
        )
      )
    | '&' -> (
        if match_char state '&' then (
          add_token state Token_types.And "&&" start_line start_col
        ) else (
          add_token state Token_types.Bitwise_and "&" start_line start_col
        )
      )
    | '|' -> (
        if match_char state '|' then (
          add_token state Token_types.Or "||" start_line start_col
        ) else (
          add_token state Token_types.Bitwise_or "|" start_line start_col
        )
      )
    | '^' -> add_token state Token_types.Bitwise_xor "^" start_line start_col
    | '~' -> add_token state Token_types.Bitwise_not "~" start_line start_col
    | '<' -> (
        if match_char state '<' then (
          add_token state Token_types.Bitwise_lshift "<<" start_line start_col
        ) else if match_char state '=' then (
          add_token state Token_types.Lteq "<=" start_line start_col
        ) else (
          add_token state Token_types.Lt "<" start_line start_col
        )
      )
    | '>' -> (
        if match_char state '>' then (
          if match_char state '>' then (
            add_token state Token_types.Bitwise_urshift ">>>" start_line start_col
          ) else (
            add_token state Token_types.Bitwise_rshift ">>" start_line start_col
          )
        ) else if match_char state '=' then (
          add_token state Token_types.Gteq ">=" start_line start_col
        ) else (
          add_token state Token_types.Gt ">" start_line start_col
        )
      )
    | '=' -> (
        if match_char state '=' then (
          add_token state Token_types.Eqeq "==" start_line start_col
        ) else (
          add_token state Token_types.Eq "=" start_line start_col
        )
      )
    | '(' -> add_token state Token_types.Lparen "(" start_line start_col
    | ')' -> add_token state Token_types.Rparen ")" start_line start_col
    | '{' -> add_token state Token_types.Lbrace "{" start_line start_col
    | '}' -> add_token state Token_types.Rbrace "}" start_line start_col
    | '[' -> add_token state Token_types.Lbracket "[" start_line start_col
    | ']' -> add_token state Token_types.Rbracket "]" start_line start_col
    | ',' -> add_token state Token_types.Comma "," start_line start_col
    | ';' -> add_token state Token_types.Semicolon ";" start_line start_col
    | ':' -> (
        if match_char state ':' then (
          add_token state Token_types.Double_colon "::" start_line start_col
        ) else (
          add_token state Token_types.Colon ":" start_line start_col
        )
      )
    | '?' -> add_token state Token_types.Question "?" start_line start_col
    | '.' -> add_token state Token_types.Dot "." start_line start_col
    | c when is_identifier_start c -> read_identifier state c start_line start_col
    | c when (c >= '0' && c <= '9') -> read_number state c start_line start_col
    | c -> add_token state Token_types.Unknown (String.make 1 c) start_line start_col
  done;
  
  (* Add EOF token *)
  let eof_span = Token_span.single_line state.line state.column state.column (Some state.file_name) in
  let eof_token = Token.make Token_types.Eof "" eof_span in
  state.tokens <- eof_token :: state.tokens;
  
  (* Return tokens in reverse order (they were added in reverse) *)
  List.rev state.tokens

(** Tokenize a string *)
let tokenize_string source file_name =
  let state = create source file_name in
  tokenize state 