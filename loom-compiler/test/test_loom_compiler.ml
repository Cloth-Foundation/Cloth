(** Test suite for the Loom compiler *)
open Base
open Stdio
open OUnit2

(** Test lexer functionality *)
module LexerTests = struct
  (** Test basic tokenization *)
  let test_basic_tokens _test_ctxt =
    let source = "var x = 42;" in
    match Lexer.tokenize_with_errors source with
    | Result.Ok tokens ->
        assert_equal ~printer:Int.to_string 6 (List.length tokens);
        (* Check first token is 'var' keyword *)
        let first_token = List.hd_exn tokens in
        assert_bool "First token should be 'var' keyword" 
          (Token.is_specific_keyword first_token Keywords.Var)
    | Result.Error errors ->
        assert_failure ("Lexer failed with errors: " ^ 
                       String.concat ~sep:"\n" (List.map ~f:Loom_error.to_string errors))

  (** Test number literals *)
  let test_number_literals _test_ctxt =
    let source = "42 3.14 0xFF 0b1010" in
    match Lexer.tokenize_with_errors source with
    | Result.Ok tokens ->
        let number_tokens = List.filter tokens ~f:(fun t -> 
          Token.is_of_type t TokenType.Number) in
        assert_equal ~printer:Int.to_string 4 (List.length number_tokens)
    | Result.Error errors ->
        assert_failure ("Lexer failed with errors: " ^ 
                       String.concat ~sep:"\n" (List.map ~f:Loom_error.to_string errors))

  (** Test string literals *)
  let test_string_literals _test_ctxt =
    let source = "\"Hello, World!\"" in
    match Lexer.tokenize_with_errors source with
    | Result.Ok tokens ->
        let string_tokens = List.filter tokens ~f:(fun t -> 
          Token.is_of_type t TokenType.String) in
        assert_equal ~printer:Int.to_string 1 (List.length string_tokens);
        let string_token = List.hd_exn string_tokens in
        assert_equal ~printer:Fn.id "Hello, World!" (Token.string_value string_token)
    | Result.Error errors ->
        assert_failure ("Lexer failed with errors: " ^ 
                       String.concat ~sep:"\n" (List.map ~f:Loom_error.to_string errors))

  (** Test operators *)
  let test_operators _test_ctxt =
    let source = "+ - * / % == != < <= > >= && || ++ --" in
    match Lexer.tokenize_with_errors source with
    | Result.Ok tokens ->
        let operator_tokens = List.filter tokens ~f:(fun t -> 
          Token.is_operator t) in
        assert_equal ~printer:Int.to_string 13 (List.length operator_tokens)
    | Result.Error errors ->
        assert_failure ("Lexer failed with errors: " ^ 
                       String.concat ~sep:"\n" (List.map ~f:Loom_error.to_string errors))

  (** Test identifiers *)
  let test_identifiers _test_ctxt =
    let source = "variable_name _private_var camelCase" in
    match Lexer.tokenize_with_errors source with
    | Result.Ok tokens ->
        let identifier_tokens = List.filter tokens ~f:(fun t -> 
          Token.is_identifier t) in
        assert_equal ~printer:Int.to_string 3 (List.length identifier_tokens)
    | Result.Error errors ->
        assert_failure ("Lexer failed with errors: " ^ 
                       String.concat ~sep:"\n" (List.map ~f:Loom_error.to_string errors))

  (** Test keywords *)
  let test_keywords _test_ctxt =
    let source = "var func class struct enum if else while for" in
    match Lexer.tokenize_with_errors source with
    | Result.Ok tokens ->
        let keyword_tokens = List.filter tokens ~f:(fun t -> 
          Token.is_keyword t) in
        assert_equal ~printer:Int.to_string 9 (List.length keyword_tokens)
    | Result.Error errors ->
        assert_failure ("Lexer failed with errors: " ^ 
                       String.concat ~sep:"\n" (List.map ~f:Loom_error.to_string errors))

  (** Test comments *)
  let test_comments _test_ctxt =
    let source = "// This is a comment\nvar x = 42; /* Multi-line\ncomment */" in
    match Lexer.tokenize_with_errors source with
    | Result.Ok tokens ->
        (* Comments should be skipped, so we should only get tokens for "var x = 42;" *)
        let non_eof_tokens = List.filter tokens ~f:(fun t -> 
          not (Token.is_eof t)) in
        assert_equal ~printer:Int.to_string 4 (List.length non_eof_tokens)
    | Result.Error errors ->
        assert_failure ("Lexer failed with errors: " ^ 
                       String.concat ~sep:"\n" (List.map ~f:Loom_error.to_string errors))

  (** Test complex expression *)
  let test_complex_expression _test_ctxt =
    let source = "var result = (a + b) * 2;" in
    match Lexer.tokenize_with_errors source with
    | Result.Ok tokens ->
        let non_eof_tokens = List.filter tokens ~f:(fun t -> 
          not (Token.is_eof t)) in
        assert_equal ~printer:Int.to_string 9 (List.length non_eof_tokens);
        (* Check that we have the expected tokens *)
        let token_strings = List.map non_eof_tokens ~f:Token.string_value in
        let expected = ["var"; "result"; "="; "("; "a"; "+"; "b"; ")"; "*"; "2"; ";"] in
        assert_equal ~printer:(String.concat ~sep:" ") expected token_strings
    | Result.Error errors ->
        assert_failure ("Lexer failed with errors: " ^ 
                       String.concat ~sep:"\n" (List.map ~f:Loom_error.to_string errors))

  (** Test error handling *)
  let test_error_handling _test_ctxt =
    let source = "\"Unterminated string" in
    match Lexer.tokenize_with_errors source with
    | Result.Ok _ ->
        assert_failure "Should have failed with unterminated string error"
    | Result.Error errors ->
        assert_equal ~printer:Int.to_string 1 (List.length errors);
        let error = List.hd_exn errors in
        assert_bool "Should be a lexical error" 
          (match error.Loom_error.error_type with
           | Loom_error.LexicalError _ -> true
           | _ -> false)
end

(** Test token types *)
module TokenTypeTests = struct
  (** Test token type classification *)
  let test_token_type_classification _test_ctxt =
    let span = Span.from_position Position.start in
    
    (* Test keyword classification *)
    let keyword_token = Token.keyword Keywords.Var span in
    assert_bool "Should be a keyword" (Token.is_keyword keyword_token);
    assert_bool "Should be a specific keyword" (Token.is_specific_keyword keyword_token Keywords.Var);
    
    (* Test identifier classification *)
    let identifier_token = Token.identifier "variable" span in
    assert_bool "Should be an identifier" (Token.is_identifier identifier_token);
    
    (* Test literal classification *)
    let number_token = Token.number "42" span in
    assert_bool "Should be a literal" (Token.is_literal number_token);
    
    let string_token = Token.string "hello" span in
    assert_bool "Should be a literal" (Token.is_literal string_token);
    
    (* Test operator classification *)
    let operator_token = Token.operator TokenType.Plus span in
    assert_bool "Should be an operator" (Token.is_operator operator_token);
    
    (* Test punctuation classification *)
    let punctuation_token = Token.punctuation TokenType.LParen span in
    assert_bool "Should be punctuation" (Token.is_punctuation punctuation_token)

  (** Test operator precedence *)
  let test_operator_precedence _test_ctxt =
    let span = Span.from_position Position.start in
    
    let plus_token = Token.operator TokenType.Plus span in
    let star_token = Token.operator TokenType.Star span in
    let eq_token = Token.operator TokenType.Eq span in
    
    assert_equal ~printer:Int.to_string 4 (Token.precedence plus_token);
    assert_equal ~printer:Int.to_string 5 (Token.precedence star_token);
    assert_equal ~printer:Int.to_string 2 (Token.precedence eq_token)
end

(** Test keywords *)
module KeywordsTests = struct
  (** Test keyword classification *)
  let test_keyword_classification _test_ctxt =
    (* Test type keywords *)
    assert_bool "bool should be a type keyword" (Keywords.is_type_keyword Keywords.Bool);
    assert_bool "i32 should be a type keyword" (Keywords.is_type_keyword Keywords.I32);
    assert_bool "string should be a type keyword" (Keywords.is_type_keyword Keywords.String);
    assert_bool "var should not be a type keyword" (not (Keywords.is_type_keyword Keywords.Var));
    
    (* Test access modifiers *)
    assert_bool "pub should be an access modifier" (Keywords.is_access_modifier Keywords.Public);
    assert_bool "priv should be an access modifier" (Keywords.is_access_modifier Keywords.Private);
    assert_bool "prot should be an access modifier" (Keywords.is_access_modifier Keywords.Protected);
    assert_bool "var should not be an access modifier" (not (Keywords.is_access_modifier Keywords.Var));
    
    (* Test control flow keywords *)
    assert_bool "if should be a control flow keyword" (Keywords.is_control_flow Keywords.If);
    assert_bool "while should be a control flow keyword" (Keywords.is_control_flow Keywords.While);
    assert_bool "var should not be a control flow keyword" (not (Keywords.is_control_flow Keywords.Var));
    
    (* Test declaration keywords *)
    assert_bool "class should be a declaration keyword" (Keywords.is_declaration Keywords.Class);
    assert_bool "func should be a declaration keyword" (Keywords.is_declaration Keywords.Func);
    assert_bool "var should be a declaration keyword" (Keywords.is_declaration Keywords.Var);
    assert_bool "if should not be a declaration keyword" (not (Keywords.is_declaration Keywords.If))

  (** Test keyword string conversion *)
  let test_keyword_string_conversion _test_ctxt =
    assert_equal ~printer:Fn.id "var" (Keywords.to_string Keywords.Var);
    assert_equal ~printer:Fn.id "func" (Keywords.to_string Keywords.Func);
    assert_equal ~printer:Fn.id "class" (Keywords.to_string Keywords.Class);
    assert_equal ~printer:Fn.id "pub" (Keywords.to_string Keywords.Public);
    assert_equal ~printer:Fn.id "priv" (Keywords.to_string Keywords.Private);
    assert_equal ~printer:Fn.id "prot" (Keywords.to_string Keywords.Protected);
    assert_equal ~printer:Fn.id "fin" (Keywords.to_string Keywords.Final)

  (** Test keyword lookup *)
  let test_keyword_lookup _test_ctxt =
    assert_equal ~printer:(Option.value ~default:"None") (Some Keywords.Var) 
      (Keywords.from_string "var");
    assert_equal ~printer:(Option.value ~default:"None") (Some Keywords.Func) 
      (Keywords.from_string "func");
    assert_equal ~printer:(Option.value ~default:"None") None 
      (Keywords.from_string "notakeyword")
end

(** Test position and span *)
module PositionSpanTests = struct
  (** Test position operations *)
  let test_position_operations _test_ctxt =
    let pos1 = Position.create ~line:1 ~column:1 in
    let pos2 = Position.create ~line:1 ~column:5 in
    let pos3 = Position.create ~line:2 ~column:1 in
    
    assert_bool "pos1 should be before pos2" (Position.is_before pos1 pos2);
    assert_bool "pos2 should be after pos1" (Position.is_after pos2 pos1);
    assert_bool "pos3 should be after pos1" (Position.is_after pos3 pos1);
    
    let advanced_pos = Position.advance_char pos1 in
    assert_equal ~printer:Int.to_string 2 advanced_pos.column;
    assert_equal ~printer:Int.to_string 1 advanced_pos.line

  (** Test span operations *)
  let test_span_operations _test_ctxt =
    let pos1 = Position.create ~line:1 ~column:1 in
    let pos2 = Position.create ~line:1 ~column:5 in
    let span = Span.create ~start_pos:pos1 ~end_pos:pos2 in
    
    assert_bool "span should contain pos1" (Span.contains_position span pos1);
    assert_bool "span should contain pos2" (Span.contains_position span pos2);
    assert_bool "span should not be empty" (not (Span.is_empty span));
    
    let empty_span = Span.from_position pos1 in
    assert_bool "empty span should be empty" (Span.is_empty empty_span)
end

(** Test error handling *)
module ErrorTests = struct
  (** Test error creation *)
  let test_error_creation _test_ctxt =
    let span = Span.from_position Position.start in
    let error = Loom_error.lexical_error ~span ~source_file:(Some "test.lm") "Test error" in
    
    assert_bool "Should be a lexical error" 
      (match error.Loom_error.error_type with
       | Loom_error.LexicalError _ -> true
       | _ -> false);
    assert_bool "Should be an error severity" (Loom_error.is_fatal error);
    assert_bool "Should not be a warning" (not (Loom_error.is_warning error))

  (** Test error string conversion *)
  let test_error_string_conversion _test_ctxt =
    let span = Span.from_position Position.start in
    let error = Loom_error.syntax_error ~span ~source_file:(Some "test.lm") "Test syntax error" in
    let error_str = Loom_error.to_string error in
    
    assert_bool "Error string should contain 'ERROR'" (String.is_substring error_str ~substring:"ERROR");
    assert_bool "Error string should contain 'Syntax'" (String.is_substring error_str ~substring:"Syntax");
    assert_bool "Error string should contain error message" (String.is_substring error_str ~substring:"Test syntax error")
end

(** Test result handling *)
module ResultTests = struct
  (** Test result operations *)
  let test_result_operations _test_ctxt =
    let ok_result = Result.ok 42 in
    let error = Loom_error.internal_error "Test error" in
    let error_result = Result.error error in
    
    assert_bool "ok_result should be ok" (Result.is_ok ok_result);
    assert_bool "error_result should be error" (Result.is_error error_result);
    assert_equal ~printer:Int.to_string 42 (Result.value_or 0 ok_result);
    assert_equal ~printer:Int.to_string 0 (Result.value_or 0 error_result);
    
    let mapped_result = Result.map (fun x -> x * 2) ok_result in
    assert_equal ~printer:Int.to_string 84 (Result.get_exn mapped_result)

  (** Test result combination *)
  let test_result_combination _test_ctxt =
    let ok1 = Result.ok 1 in
    let ok2 = Result.ok 2 in
    let error = Loom_error.internal_error "Test error" in
    let error_result = Result.error error in
    
    let combined_ok = Result.combine ok1 ok2 in
    assert_bool "Combined result should be ok" (Result.is_ok combined_ok);
    assert_equal ~printer:(fun (a, b) -> Printf.sprintf "(%d, %d)" a b) (1, 2) (Result.get_exn combined_ok);
    
    let combined_error = Result.combine ok1 error_result in
    assert_bool "Combined result should be error" (Result.is_error combined_error)
end

(** Test suite *)
let suite =
  "Loom Compiler Tests" >:::
  [
    "Lexer" >:::
    [
      "Basic tokens" >:: LexerTests.test_basic_tokens;
      "Number literals" >:: LexerTests.test_number_literals;
      "String literals" >:: LexerTests.test_string_literals;
      "Operators" >:: LexerTests.test_operators;
      "Identifiers" >:: LexerTests.test_identifiers;
      "Keywords" >:: LexerTests.test_keywords;
      "Comments" >:: LexerTests.test_comments;
      "Complex expression" >:: LexerTests.test_complex_expression;
      "Error handling" >:: LexerTests.test_error_handling;
    ];
    
    "TokenType" >:::
    [
      "Token type classification" >:: TokenTypeTests.test_token_type_classification;
      "Operator precedence" >:: TokenTypeTests.test_operator_precedence;
    ];
    
    "Keywords" >:::
    [
      "Keyword classification" >:: KeywordsTests.test_keyword_classification;
      "Keyword string conversion" >:: KeywordsTests.test_keyword_string_conversion;
      "Keyword lookup" >:: KeywordsTests.test_keyword_lookup;
    ];
    
    "Position and Span" >:::
    [
      "Position operations" >:: PositionSpanTests.test_position_operations;
      "Span operations" >:: PositionSpanTests.test_span_operations;
    ];
    
    "Error" >:::
    [
      "Error creation" >:: ErrorTests.test_error_creation;
      "Error string conversion" >:: ErrorTests.test_error_string_conversion;
    ];
    
    "Result" >:::
    [
      "Result operations" >:: ResultTests.test_result_operations;
      "Result combination" >:: ResultTests.test_result_combination;
    ];
  ]

(** Run tests *)
let () = run_test_tt_main suite
