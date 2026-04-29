module Commands.Executor.Lexer

open System
open FrontEnd.File
open FrontEnd.Lexer
open Commands.DispatchResult

let runLexer (path: string) =
    let name = IO.Path.GetFileName(path)
    let file = ClothFile(path, name)
    let tokens = Lexer(file).LexAll()

    for i = 0 to tokens.Count - 1 do
        printfn $"{(i + 1)}: {tokens.[i]}"

    Success "Lexer completed."