(** Span module for tracking source code ranges *)
open Base

(** Represents a span in source code *)
type t = {
  start_pos : Position.t;
  end_pos : Position.t;
} [@@deriving show]

(** Create a new span *)
let create ~start_pos ~end_pos = { start_pos; end_pos }

(** Create a span from a single position *)
let from_position pos = create ~start_pos:pos ~end_pos:pos

(** Create a span from two positions *)
let from_positions start_pos end_pos = create ~start_pos ~end_pos

(** Get the length of the span in characters *)
let length span =
  if Position.equal span.start_pos span.end_pos then 0
  else
    (* This is a simplified calculation - in practice you'd need the source text *)
    span.end_pos.column - span.start_pos.column

(** Check if a position is within this span *)
let contains_position span pos =
  Position.compare span.start_pos pos <= 0 && 
  Position.compare pos span.end_pos <= 0

(** Check if two spans overlap *)
let overlaps span1 span2 =
  contains_position span1 span2.start_pos ||
  contains_position span1 span2.end_pos ||
  contains_position span2 span1.start_pos ||
  contains_position span2 span1.end_pos

(** Merge two spans *)
let merge span1 span2 =
  let start_pos = 
    if Position.compare span1.start_pos span2.start_pos <= 0 
    then span1.start_pos else span2.start_pos in
  let end_pos = 
    if Position.compare span1.end_pos span2.end_pos >= 0 
    then span1.end_pos else span2.end_pos in
  create ~start_pos ~end_pos

(** Convert span to string for error reporting *)
let to_string span =
  if Position.equal span.start_pos span.end_pos then
    Position.to_string span.start_pos
  else
    Printf.sprintf "%s-%s" 
      (Position.to_string span.start_pos)
      (Position.to_string span.end_pos)

(** Get the start position *)
let start_position span = span.start_pos

(** Get the end position *)
let end_position span = span.end_pos

(** Check if span is empty (start and end are the same) *)
let is_empty span = Position.equal span.start_pos span.end_pos

(** Compare two spans *)
let compare span1 span2 =
  let start_cmp = Position.compare span1.start_pos span2.start_pos in
  if start_cmp <> 0 then start_cmp
  else Position.compare span1.end_pos span2.end_pos

(** Check if two spans are equal *)
let equal span1 span2 = compare span1 span2 = 0 