(** TokenType module defining all token types for the Loom language *)
open Base

(** Token types for the Loom language *)
type t =
  (* Identifiers and literals *)
  | Identifier
  | Number
  | String
  | Null
  
  (* Keywords *)
  | Keyword
  
  (* Symbols and punctuation *)
  | LParen      (* ( *)
  | RParen      (* ) *)
  | LBrace      (* { *)
  | RBrace      (* } *)
  | LBracket    (* [ *)
  | RBracket    (* ] *)
  | Comma       (* , *)
  | Semicolon   (* ; *)
  | Colon       (* : *)
  | DoubleColon (* :: *)
  | Question    (* ? *)
  | Dot         (* . *)
  | Arrow       (* -> *)
  
  (* Operators *)
  | Plus        (* + *)
  | Minus       (* - *)
  | Star        (* * *)
  | Slash       (* / *)
  | Modulo      (* % *)
  | Eq          (* = *)
  | EqEq        (* == *)
  | Bang        (* ! *)
  | BangEq      (* != *)
  | Lt          (* < *)
  | LtEq        (* <= *)
  | Gt          (* > *)
  | GtEq        (* >= *)
  | And         (* && *)
  | Or          (* || *)
  | PlusPlus    (* ++ *)
  | MinusMinus  (* -- *)
  | PlusEq      (* += *)
  | MinusEq     (* -= *)
  | StarEq      (* *= *)
  | SlashEq     (* /= *)
  | ModuloEq    (* %= *)
  
  (* Bitwise operators *)
  | BitwiseAnd  (* & *)
  | BitwiseOr   (* | *)
  | BitwiseXor  (* ^ *)
  | BitwiseNot  (* ~ *)
  | BitwiseLShift (* << *)
  | BitwiseRShift (* >> *)
  | BitwiseURShift (* >>> *)
  
  (* Comments and whitespace *)
  | Comment
  | Whitespace
  
  (* Other *)
  | Unknown
  | Eof
[@@deriving show]

(** Convert token type to string for display *)
let to_string = function
  | Identifier -> "Identifier"
  | Number -> "Number"
  | String -> "String"
  | Keyword -> "Keyword"
  | Null -> "Null"
  
  | LParen -> "("
  | RParen -> ")"
  | LBrace -> "{"
  | RBrace -> "}"
  | LBracket -> "["
  | RBracket -> "]"
  | Comma -> ","
  | Semicolon -> ";"
  | Colon -> ":"
  | DoubleColon -> "::"
  | Question -> "?"
  | Dot -> "."
  | Arrow -> "->"
  
  | Plus -> "+"
  | Minus -> "-"
  | Star -> "*"
  | Slash -> "/"
  | Modulo -> "%"
  | Eq -> "="
  | EqEq -> "=="
  | Bang -> "!"
  | BangEq -> "!="
  | Lt -> "<"
  | LtEq -> "<="
  | Gt -> ">"
  | GtEq -> ">="
  | And -> "&&"
  | Or -> "||"
  | PlusPlus -> "++"
  | MinusMinus -> "--"
  | PlusEq -> "+="
  | MinusEq -> "-="
  | StarEq -> "*="
  | SlashEq -> "/="
  | ModuloEq -> "%="
  
  | BitwiseAnd -> "&"
  | BitwiseOr -> "|"
  | BitwiseXor -> "^"
  | BitwiseNot -> "~"
  | BitwiseLShift -> "<<"
  | BitwiseRShift -> ">>"
  | BitwiseURShift -> ">>>"
  
  | Comment -> "Comment"
  | Whitespace -> "Whitespace"
  | Unknown -> "Unknown"
  | Eof -> "EOF"

(** Check if token type is a keyword *)
let is_keyword = function
  | Keyword -> true
  | _ -> false

(** Check if token type is a literal *)
let is_literal = function
  | Number | String | Null -> true
  | _ -> false

(** Check if token type is an identifier *)
let is_identifier = function
  | Identifier -> true
  | _ -> false

(** Check if token type is an operator *)
let is_operator = function
  | Plus | Minus | Star | Slash | Modulo
  | Eq | EqEq | Bang | BangEq
  | Lt | LtEq | Gt | GtEq
  | And | Or
  | PlusPlus | MinusMinus
  | PlusEq | MinusEq | StarEq | SlashEq | ModuloEq
  | BitwiseAnd | BitwiseOr | BitwiseXor | BitwiseNot
  | BitwiseLShift | BitwiseRShift | BitwiseURShift -> true
  | _ -> false

(** Check if token type is a punctuation *)
let is_punctuation = function
  | LParen | RParen | LBrace | RBrace
  | LBracket | RBracket | Comma | Semicolon
  | Colon | DoubleColon | Question | Dot | Arrow -> true
  | _ -> false

(** Check if token type is a comment or whitespace *)
let is_whitespace_or_comment = function
  | Comment | Whitespace -> true
  | _ -> false

(** Check if token type is the end of file *)
let is_eof = function
  | Eof -> true
  | _ -> false

(** Get the precedence of an operator token *)
let precedence = function
  | Plus | Minus -> 4
  | Star | Slash | Modulo -> 5
  | Eq | EqEq | Bang | BangEq | Lt | LtEq | Gt | GtEq -> 2
  | And -> 1
  | Or -> 0
  | _ -> -1

(** Check if token type is a compound assignment operator *)
let is_compound_assignment = function
  | PlusEq | MinusEq | StarEq | SlashEq | ModuloEq -> true
  | _ -> false

(** Check if token type is an increment/decrement operator *)
let is_increment_decrement = function
  | PlusPlus | MinusMinus -> true
  | _ -> false

(** Compare two token types *)
let compare t1 t2 = Stdlib.compare t1 t2

(** Check if two token types are equal *)
let equal t1 t2 = compare t1 t2 = 0 