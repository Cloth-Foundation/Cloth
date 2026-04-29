module Commands.Entry

open System
open Commands.DispatchResult
open Commands.Executor.Lexer

let dispatch (args: string[]) =
    if args.Length = 0 then
        Failure "Usage: cloth <command>"

    else
        match args.[0] with
        | "help" ->
            Success "Help Called"

        | "build" ->
            Success "Build Called"

        | "run" ->
            Success "Run Called"

        | "test" ->
            Success "Test Called"

        | "lexer" ->
            if args.Length < 2 then
                Failure "Expected file path. Example: cloth lexer ./src/Main.co"
            else
                runLexer args.[1]

        | "parser" ->
            Success "Parser Called"

        | unknown ->
            Failure $"Unknown command: {unknown}"

[<EntryPoint>]
let main (args: string[]) =
    match dispatch args with
    | Success message ->
        if not (String.IsNullOrWhiteSpace(message)) then
            printfn $"{message}"
        0

    | Failure error ->
        eprintfn $"Error {error}"
        1