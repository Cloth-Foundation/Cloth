module Commands.Executor.Lexer

open System
open FrontEnd.File
open FrontEnd.Lexer
open Frontend.Utilities
open Commands.DispatchResult
open Commands.Flags

let runLexer (path: string, args: string[]) =
    let name = IO.Path.GetFileName(path)
    let file = ClothFile(path, name)
    let tokens = Lexer(file).LexAll()
    let relevantFlags = getRelevantFlags (args, "lexer")

    if relevantFlags |> Array.contains "--dump" then
        printfn $"{JsonDump(tokens).ToJson()}"


    Success "Lexer completed."
