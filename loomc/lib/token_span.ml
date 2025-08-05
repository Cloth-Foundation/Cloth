(** Reeeeeeturn {

{ "mason-org/mason.nvim", version = "1.11.0" },

{ "mason-org/mason-lspconfig.nvim", version = "1.32.0" },

}turn {

{ "mason-org/mason.nvim", version = "1.11.0" },

{ "mason-org/mason-lspconfig.nvim", version = "1.32.0" },

}turn {

{ "mason-org/mason.nvim", version = "1.11.0" },

{ "mason-org/mason-lspconfig.nvim", version = "1.32.0" },

}turn {

{ "mason-org/mason.nvim", version = "1.11.0" },

{ "mason-org/mason-lspconfig.nvim", version = "1.32.0" },

}turn {

{ "mason-org/mason.nvim", version = "1.11.0" },

{ "mason-org/mason-lspconfig.nvim", version = "1.32.0" },

}presents a source location span (range of lines/columns) in a source file *)

type t = {
  start_line : int;
  start_column : int;
  end_line : int;
  end_column : int;
  source_file : string option;
}

(** Create a single-line span *)
let single_line line start_col end_col source_file =
  { start_line = line; start_column = start_col; end_line = line; 
    end_column = end_col; source_file }

(** Create a span *)
let make start_line start_col end_line end_col source_file =
  { start_line; start_column = start_col; end_line; end_column = end_col; source_file }

(** Get start line *)
let start_line span = span.start_line

(** Get start column *)
let start_column span = span.start_column

(** Get end line *)
let end_line span = span.end_line

(** Get end column *)
let end_column span = span.end_column

(** Get source file *)
let source_file span = span.source_file

(** Check if span is on a single line *)
let is_single_line span = span.start_line = span.end_line

(** Get length of span (only valid for single-line spans) *)
let length span =
  if is_single_line span then
    span.end_column - span.start_column
  else
    -1

(** Get position string for error reporting *)
let position_string span =
  match span.source_file with
  | Some file -> Printf.sprintf "%s:%d:%d" file span.start_line span.start_column
  | None -> Printf.sprintf "line %d, column %d" span.start_line span.start_column

(** Merge two spans to create a span that covers both *)
let merge span1 span2 =
  let file = match span1.source_file, span2.source_file with
    | Some f1, Some f2 when f1 <> f2 ->
        failwith "Cannot merge spans from different files"
    | Some f, _ | _, Some f -> Some f
    | None, None -> None
  in
  
  let new_start_line = min span1.start_line span2.start_line in
  let new_start_col = 
    if span1.start_line < span2.start_line then span1.start_column
    else if span2.start_line < span1.start_line then span2.start_column
    else min span1.start_column span2.start_column
  in
  
  let new_end_line = max span1.end_line span2.end_line in
  let new_end_col = 
    if span1.end_line > span2.end_line then span1.end_column
    else if span2.end_line > span1.end_line then span2.end_column
    else max span1.end_column span2.end_column
  in
  
  { start_line = new_start_line; start_column = new_start_col;
    end_line = new_end_line; end_column = new_end_col; source_file = file }

(** Create a caret line for highlighting *)
let create_caret_line line line_num span =
  let line_length = String.length line in
  
  let caret_start = 
    if line_num = span.start_line then span.start_column else 0
  in
  let caret_end = 
    if line_num = span.end_line then span.end_column else line_length
  in
  
  let caret_start = max 0 (min caret_start line_length) in
  let caret_end = max caret_start (min caret_end line_length) in
  
  let buf = Buffer.create line_length in
  
  (* Pad up to caret_start *)
  for j = 0 to caret_start - 1 do
    let c = String.get line j in
    Buffer.add_char buf (if c = '\t' then '\t' else ' ')
  done;
  
  (* Draw carets *)
  for _ = caret_start to caret_end - 1 do
    Buffer.add_char buf '^'
  done;
  
  Buffer.contents buf

(** Highlight the span across one or more lines of source code *)
let highlight_source_line span source_code =
  let lines = String.split_on_char '\n' source_code in
  let lines = Array.of_list lines in
  let num_lines = Array.length lines in
  
  let safe_start_line = max 1 span.start_line in
  let safe_end_line = min span.end_line num_lines in
  
  let buf = Buffer.create 256 in
  
  (* If the span starts beyond the source code, show what we can *)
  if safe_start_line > num_lines then (
    Buffer.add_string buf "(Token starts beyond the end of the source code)\n";
    (* Still try to show the source code if we have any lines *)
    if num_lines > 0 then (
      for i = 1 to min num_lines 3 do
        Buffer.add_string buf lines.(i-1);
        Buffer.add_char buf '\n'
      done
    )
  ) else (
    for i = safe_start_line to safe_end_line do
      let line = lines.(i-1) in
      Buffer.add_string buf line;
      Buffer.add_char buf '\n';
      
      let caret_line = create_caret_line line i span in
      Buffer.add_string buf caret_line;
      Buffer.add_char buf '\n'
    done
  );
  
  if span.end_line > num_lines then (
    Buffer.add_string buf "(Span extends past end of file)\n"
  );
  
  Buffer.contents buf

(** Format a full error message showing file, position, and a highlighted line *)
let format_error span source_code message =
  Printf.sprintf "Error at %s: %s\n%s"
    (position_string span) message (highlight_source_line span source_code)

(** Convert span to string representation *)
let to_string span =
  let file_str = match span.source_file with
    | Some file -> Printf.sprintf ", file='%s'" file
    | None -> ""
  in
  Printf.sprintf "Span [%d:%d -> %d:%d%s]"
    span.start_line span.start_column span.end_line span.end_column file_str 
