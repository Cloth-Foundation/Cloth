(** Keywords module defining all keywords for the Loom language *)
open Base

(** Keyword types *)
type keyword =
  (* Access modifiers *)
  | As
  | Async
  | Atomic
  | Await
  | Private
  | Protected
  | Public
  
  (* Type declarations *)
  | Bool
  | Char
  | Class
  | Const
  | Constructor
  | Double
  | Enum
  | F8
  | F16
  | F32
  | F64
  | Final
  | Func
  | I8
  | I16
  | I32
  | I64
  | Import
  | In
  | Namespace
  | New
  | Null
  | Return
  | Self
  | String
  | Struct
  | True
  | False
  | Type
  | Var
  | Void
  
  (* Control flow *)
  | Break
  | Continue
  | Do
  | Else
  | For
  | If
  | While
[@@deriving show]

(** Convert keyword to string *)
let to_string = function
  | As -> "as"
  | Async -> "async"
  | Atomic -> "atomic"
  | Await -> "await"
  | Private -> "priv"
  | Protected -> "prot"
  | Public -> "pub"
  | Bool -> "bool"
  | Char -> "char"
  | Class -> "class"
  | Const -> "const"
  | Constructor -> "constructor"
  | Double -> "double"
  | Enum -> "enum"
  | F8 -> "f8"
  | F16 -> "f16"
  | F32 -> "f32"
  | F64 -> "f64"
  | Final -> "fin"
  | Func -> "func"
  | I8 -> "i8"
  | I16 -> "i16"
  | I32 -> "i32"
  | I64 -> "i64"
  | Import -> "import"
  | In -> "in"
  | Namespace -> "namespace"
  | New -> "new"
  | Null -> "null"
  | Return -> "return"
  | Self -> "self"
  | String -> "string"
  | Struct -> "struct"
  | True -> "true"
  | False -> "false"
  | Type -> "type"
  | Var -> "var"
  | Void -> "void"
  | Break -> "break"
  | Continue -> "continue"
  | Do -> "do"
  | Else -> "else"
  | For -> "for"
  | If -> "if"
  | While -> "while"

(** Check if keyword is a type keyword *)
let is_type_keyword = function
  | Bool | Char | Double | F8 | F16 | F32 | F64
  | I8 | I16 | I32 | I64 | String | Void -> true
  | _ -> false

(** Check if keyword is an access modifier *)
let is_access_modifier = function
  | Private | Protected | Public -> true
  | _ -> false

(** Check if keyword is a control flow keyword *)
let is_control_flow = function
  | Break | Continue | Do | Else | For | If | While -> true
  | _ -> false

(** Check if keyword is a declaration keyword *)
let is_declaration = function
  | Class | Enum | Func | Struct | Var -> true
  | _ -> false

(** Check if keyword is a boolean literal *)
let is_boolean_literal = function
  | True | False -> true
  | _ -> false

(** Check if keyword is the null literal *)
let is_null_literal = function
  | Null -> true
  | _ -> false

(** Get all keywords as strings *)
let all_keywords =
  [ As; Async; Atomic; Await; Private; Protected; Public;
    Bool; Char; Class; Const; Constructor; Double; Enum;
    F8; F16; F32; F64; Final; Func; I8; I16; I32; I64;
    Import; In; Namespace; New; Null; Return; Self;
    String; Struct; True; False; Type; Var; Void;
    Break; Continue; Do; Else; For; If; While ]
  |> List.map ~f:to_string

(** Check if a string is a keyword *)
let is_keyword str =
  List.mem all_keywords str ~equal:String.equal

(** Get keyword from string *)
let from_string str =
  List.find_map [ As; Async; Atomic; Await; Private; Protected; Public;
                  Bool; Char; Class; Const; Constructor; Double; Enum;
                  F8; F16; F32; F64; Final; Func; I8; I16; I32; I64;
                  Import; In; Namespace; New; Null; Return; Self;
                  String; Struct; True; False; Type; Var; Void;
                  Break; Continue; Do; Else; For; If; While ]
    ~f:(fun kw -> if String.equal (to_string kw) str then Some kw else None)

(** Get type keywords *)
let type_keywords =
  [ Bool; Char; Double; F8; F16; F32; F64;
    I8; I16; I32; I64; String; Void ]
  |> List.map ~f:to_string

(** Get access modifier keywords *)
let access_modifiers =
  [ Private; Protected; Public ]
  |> List.map ~f:to_string

(** Get control flow keywords *)
let control_flow_keywords =
  [ Break; Continue; Do; Else; For; If; While ]
  |> List.map ~f:to_string

(** Get declaration keywords *)
let declaration_keywords =
  [ Class; Enum; Func; Struct; Var ]
  |> List.map ~f:to_string

(** Get boolean literal keywords *)
let boolean_literals =
  [ True; False ]
  |> List.map ~f:to_string

(** Compare two keywords *)
let compare kw1 kw2 = Stdlib.compare kw1 kw2

(** Check if two keywords are equal *)
let equal kw1 kw2 = compare kw1 kw2 = 0 