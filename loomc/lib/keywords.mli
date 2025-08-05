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
val is_type_keyword : keyword -> bool

(** Get the string representation of a keyword *)
val to_string : keyword -> string

(** List of all keyword strings *)
val all_keywords : string list

(** Check if a string is a type keyword *)
val is_type_keyword_string : string -> bool

(** Check if a string is a keyword *)
val is_keyword : string -> bool 