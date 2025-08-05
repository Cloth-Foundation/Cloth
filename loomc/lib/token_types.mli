(** Token types for the Loom language lexer *)

type t =
  (* Identifiers and literals *)
  | Identifier
  | Number
  | String
  | Null

  (* Keywords *)
  | Keyword

  (* Symbols & punctuation *)
  | Lparen      (* ( *)
  | Rparen      (* ) *)
  | Lbrace      (* { *)
  | Rbrace      (* } *)
  | Lbracket    (* [ *)
  | Rbracket    (* ] *)
  | Comma       (* , *)
  | Semicolon   (* ; *)
  | Colon       (* : *)
  | Double_colon (* :: *)
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
  | Eqeq        (* == *)
  | Bang        (* ! *)
  | Bangeq      (* != *)
  | Lt          (* < *)
  | Lteq        (* <= *)
  | Gt          (* > *)
  | Gteq        (* >= *)
  | And         (* && *)
  | Or          (* || *)
  | Plusplus    (* ++ *)
  | Minusminus  (* -- *)
  | Pluseq      (* += *)
  | Minuseq     (* -= *)
  | Stareq      (* *= *)
  | Slasheq     (* /= *)
  | Moduloeq    (* %= *)

  (* Bitwise operators *)
  | Bitwise_and  (* & *)
  | Bitwise_or   (* | *)
  | Bitwise_xor  (* ^ *)
  | Bitwise_not  (* ~ *)
  | Bitwise_lshift  (* << *)
  | Bitwise_rshift  (* >> *)
  | Bitwise_urshift (* >>> *)

  (* Comments and whitespace *)
  | Comment
  | Whitespace

  (* Other *)
  | Unknown
  | Eof

(** Convert token type to string representation *)
val to_string : t -> string 