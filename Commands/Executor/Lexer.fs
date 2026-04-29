module Commands.Executor.Lexer

open System
open FrontEnd.File
open FrontEnd.Lexer
open Commands.DispatchResult
open Commands.Flags

let runLexer (path: string, args: string[]) =
    let name = IO.Path.GetFileName(path)
    let file = ClothFile(path, name)
    let tokens = Lexer(file).LexAll()
    let relevantFlags = getRelevantFlags(args, "lexer")

    if relevantFlags |> Array.contains "--dump" then
        for i = 0 to tokens.Count - 1 do
            printfn $"{(i + 1)}: {tokens[i]}"

    Success "Lexer completed."