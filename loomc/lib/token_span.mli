(** Represents a source location span (range of lines/columns) in a source file *)

type t = {
  start_line : int;
  start_column : int;
  end_line : int;
  end_column : int;
  source_file : string option;
}

(** Create a single-line span *)
val single_line : int -> int -> int -> string option -> t

(** Create a span *)
val make : int -> int -> int -> int -> string option -> t

(** Get start line *)
val start_line : t -> int

(** Get start column *)
val start_column : t -> int

(** Get end line *)
val end_line : t -> int

(** Get end column *)
val end_column : t -> int

(** Get source file *)
val source_file : t -> string option

(** Check if span is on a single line *)
val is_single_line : t -> bool

(** Get length of span (only valid for single-line spans) *)
val length : t -> int

(** Get position string for error reporting *)
val position_string : t -> string

(** Merge two spans to create a span that covers both *)
val merge : t -> t -> t

(** Create a caret line for highlighting *)
val create_caret_line : string -> int -> t -> string

(** Highlight the span across one or more lines of source code *)
val highlight_source_line : t -> string -> string

(** Format a full error message showing file, position, and a highlighted line *)
val format_error : t -> string -> string -> string

(** Convert span to string representation *)
val to_string : t -> string 