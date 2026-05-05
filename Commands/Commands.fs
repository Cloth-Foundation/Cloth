module Commands.Entry

open System
open Commands.DispatchResult
open Commands.Executor.Build
open Commands.Executor.Lexer
open Commands.Executor.Help
open Commands.Executor.Parser
open Commands.Executor.Run
open Commands.Executor.NewProject

let dispatch (args: string[]) =
    if args.Length = 0 then
        Failure "Usage: cloth <command>"

    else
        match args[0] with
        | "?"
        | "help" -> runHelp ()

        | "build" ->
            if args.Length < 2 then
                Failure "Expected project directory. Example: cloth build ./my-project"
            else
                runBuild (args[1], args)

        | "run" ->
            if args.Length < 2 then
                Failure "Expected project directory. Example: cloth run ./my-project"
            else
                runRun (args[1], args)

        | "test" -> Success "Test Called"

        | "lexer" ->
            if args.Length < 2 then
                Failure "Expected file path. Example: cloth lexer ./src/Main.co"
            else
                runLexer (args[1], args)

        | "parser" ->
            if args.Length < 2 then
                Failure "Expected file path. Example: cloth parser ./src/Main.co"
            else
                runParser (args[1], args)

        | "new" ->
            if args.Length < 2 then
                Failure "Expected project directory. Example: cloth new ./my-project"
            else
                runNewProject (args[1], args)

        | unknown -> Failure $"Unknown command: {unknown}"

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
