(** Loom_error module for compilation error handling *)

(** Error severity levels *)
type severity = 
  | Fatal
  | Warning
  | Info
[@@deriving show]

(** Error types *)
type error_type =
  | LexicalError of string
  | SyntaxError of string
  | SemanticError of string
  | TypeError of string
  | ImportError of string
  | InternalError of string
[@@deriving show]

(** Compilation error *)
type t = {
  error_type : error_type;
  severity : severity;
  message : string;
  span : Span.t option;
  source_file : string option;
} [@@deriving show]

(** Create a new error *)
let create ?span ?source_file ~error_type ~severity ~message () =
  { error_type; severity; message; span; source_file }

(** Create a lexical error *)
let lexical_error ?span ?source_file message =
  create ?span ?source_file ~error_type:(LexicalError message) ~severity:Fatal ~message ()

(** Create a syntax error *)
let syntax_error ?span ?source_file message =
  create ?span ?source_file ~error_type:(SyntaxError message) ~severity:Fatal ~message ()

(** Create a semantic error *)
let semantic_error ?span ?source_file message =
  create ?span ?source_file ~error_type:(SemanticError message) ~severity:Fatal ~message ()

(** Create a type error *)
let type_error ?span ?source_file message =
  create ?span ?source_file ~error_type:(TypeError message) ~severity:Fatal ~message ()

(** Create an import error *)
let import_error ?span ?source_file message =
  create ?span ?source_file ~error_type:(ImportError message) ~severity:Fatal ~message ()

(** Create an internal error *)
let internal_error ?span ?source_file message =
  create ?span ?source_file ~error_type:(InternalError message) ~severity:Fatal ~message ()

(** Create a warning *)
let warning ?span ?source_file message =
  create ?span ?source_file ~error_type:(SemanticError message) ~severity:Warning ~message ()

(** Convert error to string for display *)
let to_string error =
  let severity_str = match error.severity with
    | Fatal -> "ERROR"
    | Warning -> "WARNING"
    | Info -> "INFO" in
  
  let location_str = match error.span, error.source_file with
    | Some span, Some file -> 
        Printf.sprintf "%s:%s" file (Span.to_string span)
    | Some span, None -> 
        Span.to_string span
    | None, Some file -> 
        file
    | None, None -> 
        "unknown location" in
  
  let error_type_str = match error.error_type with
    | LexicalError _ -> "Lexical"
    | SyntaxError _ -> "Syntax"
    | SemanticError _ -> "Semantic"
    | TypeError _ -> "Type"
    | ImportError _ -> "Import"
    | InternalError _ -> "Internal" in
  
  Printf.sprintf "%s [%s] at %s: %s" 
    severity_str error_type_str location_str error.message

(** Check if error is fatal (Fatal severity) *)
let is_fatal error = error.severity = Fatal

(** Check if error is a warning *)
let is_warning error = error.severity = Warning

(** Check if error is informational *)
let is_info error = error.severity = Info

(** Compare two errors *)
let compare error1 error2 =
  let severity_cmp = Stdlib.compare error1.severity error2.severity in
  if severity_cmp <> 0 then severity_cmp
  else Stdlib.compare error1.message error2.message

(** Check if two errors are equal *)
let equal error1 error2 = compare error1 error2 = 0