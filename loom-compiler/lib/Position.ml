(** Position module for tracking source code locations *)
open Base

(** Represents a position in source code *)
type t = {
  line : int;
  column : int;
} [@@deriving show]

(** Create a new position *)
let create ~line ~column = { line; column }

(** The beginning of a file *)
let start = create ~line:1 ~column:1

(** Advance position by one character *)
let advance_char pos =
  { pos with column = pos.column + 1 }

(** Advance position to next line *)
let advance_line pos =
  { line = pos.line + 1; column = 1 }

(** Advance position by a string (for multi-line strings) *)
let advance_string pos str =
  let lines = String.split_lines str in
  match lines with
  | [] -> pos
  | [single_line] -> 
      { pos with column = pos.column + String.length single_line }
  | _ :: rest ->
      let last_line = List.last_exn rest in
      { line = pos.line + List.length lines - 1;
        column = String.length last_line + 1 }

(** Convert position to string for error reporting *)
let to_string pos =
  Printf.sprintf "%d:%d" pos.line pos.column

(** Compare two positions *)
let compare pos1 pos2 =
  let line_cmp = Int.compare pos1.line pos2.line in
  if line_cmp <> 0 then line_cmp
  else Int.compare pos1.column pos2.column

(** Check if position is before another *)
let is_before pos1 pos2 = compare pos1 pos2 < 0

(** Check if position is after another *)
let is_after pos1 pos2 = compare pos1 pos2 > 0

(** Check if two positions are equal *)
let equal pos1 pos2 = compare pos1 pos2 = 0 