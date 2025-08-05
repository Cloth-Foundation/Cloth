(** Keywords for the Loom language *)

(** Keyword type with properties *)
type keyword =
  | As
  | Async
  | Atomic
  | Await
  | Break
  | Bool
  | Char
  | Class
  | Const
  | Constructor
  | Continue
  | Do
  | Double
  | Else
  | Enum
  | F8
  | F16
  | F32
  | F64
  | Final
  | For
  | Func
  | I8
  | I16
  | I32
  | I64
  | If
  | Import
  | In
  | Namespace
  | New
  | Null
  | Private
  | Protected
  | Public
  | Return
  | Self
  | String
  | Struct
  | True
  | False
  | Type
  | Var
  | Void
  | While

(** Check if a keyword is a type keyword *)
let is_type_keyword = function
  | Bool | Char | Double | F8 | F16 | F32 | F64 | I8 | I16 | I32 | I64 | String | Void -> true
  | _ -> false

(** Get the string representation of a keyword *)
let to_string = function
  | As -> "as"
  | Async -> "async"
  | Atomic -> "atomic"
  | Await -> "await"
  | Break -> "break"
  | Bool -> "bool"
  | Char -> "char"
  | Class -> "class"
  | Const -> "const"
  | Constructor -> "constructor"
  | Continue -> "continue"
  | Do -> "do"
  | Double -> "double"
  | Else -> "else"
  | Enum -> "enum"
  | F8 -> "f8"
  | F16 -> "f16"
  | F32 -> "f32"
  | F64 -> "f64"
  | Final -> "fin"
  | For -> "for"
  | Func -> "func"
  | I8 -> "i8"
  | I16 -> "i16"
  | I32 -> "i32"
  | I64 -> "i64"
  | If -> "if"
  | Import -> "import"
  | In -> "in"
  | Namespace -> "namespace"
  | New -> "new"
  | Null -> "null"
  | Private -> "priv"
  | Protected -> "prot"
  | Public -> "pub"
  | Return -> "return"
  | Self -> "self"
  | String -> "string"
  | Struct -> "struct"
  | True -> "true"
  | False -> "false"
  | Type -> "type"
  | Var -> "var"
  | Void -> "void"
  | While -> "while"

(** List of all keyword strings *)
let all_keywords =
  [ As; Async; Atomic; Await; Break; Bool; Char; Class; Const; Constructor;
    Continue; Do; Double; Else; Enum; F8; F16; F32; F64; Final; For; Func;
    I8; I16; I32; I64; If; Import; In; Namespace; New; Null; Private;
    Protected; Public; Return; Self; String; Struct; True; False; Type;
    Var; Void; While ]
  |> List.map to_string

(** Check if a string is a type keyword *)
let is_type_keyword_string s =
  List.exists (fun kw -> to_string kw = s && is_type_keyword kw)
    [ Bool; Char; Double; F8; F16; F32; F64; I8; I16; I32; I64; String; Void ]

(** Check if a string is a keyword *)
let is_keyword s =
  List.mem s all_keywords 