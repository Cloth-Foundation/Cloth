(** Result module for unified error handling *)
open Base
open Stdio

(** Result type for compiler operations *)
type 'a t = 
  | Ok of 'a
  | Error of Loom_error.t list
[@@deriving show]

(** Create a successful result *)
let ok value = Ok value

(** Create an error result *)
let error error = Error [error]

(** Create an error result with multiple errors *)
let errors error_list = Error error_list

(** Map over successful results *)
let map f = function
  | Ok value -> Ok (f value)
  | Error errors -> Error errors

(** Bind over successful results *)
let bind f = function
  | Ok value -> f value
  | Error errors -> Error errors

(** Map over error lists *)
let map_error f = function
  | Ok value -> Ok value
  | Error errors -> Error (List.map ~f errors)

(** Combine two results *)
let combine r1 r2 =
  match r1, r2 with
  | Ok v1, Ok v2 -> Ok (v1, v2)
  | Error e1, Ok _ -> Error e1
  | Ok _, Error e2 -> Error e2
  | Error e1, Error e2 -> Error (e1 @ e2)

(** Combine a list of results *)
let combine_list results =
  let rec aux acc = function
    | [] -> Ok (List.rev acc)
    | Ok value :: rest -> aux (value :: acc) rest
    | Error errors :: rest -> 
        let all_errors = List.fold_left rest ~init:errors ~f:(fun acc r ->
          match r with
          | Ok _ -> acc
          | Error new_errors -> acc @ new_errors) in
        Error all_errors in
  aux [] results

(** Get the value from a successful result, or raise an exception *)
let get_exn = function
  | Ok value -> value
  | Error errors -> 
      let error_msg = String.concat ~sep:"\n" (List.map ~f:Loom_error.to_string errors) in
      failwith ("Result.get_exn: " ^ error_msg)

(** Check if result is successful *)
let is_ok = function
  | Ok _ -> true
  | Error _ -> false

(** Check if result is an error *)
let is_error = function
  | Ok _ -> false
  | Error _ -> true

(** Get the value from a successful result, or return a default *)
let value_or default = function
  | Ok value -> value
  | Error _ -> default

(** Get the errors from a result *)
let errors = function
  | Ok _ -> []
  | Error errors -> errors

(** Convert to option *)
let to_option = function
  | Ok value -> Some value
  | Error _ -> None

(** Convert from option *)
let of_option = function
  | Some value -> Ok value
  | None -> Error [Loom_error.internal_error "Expected Some value, got None"]

(** Apply a function to a result if it's successful *)
let apply f result =
  match result with
  | Ok value -> f value
  | Error errors -> Error errors 