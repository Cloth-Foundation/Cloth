module Commands.Executor.Parser

open System
open FrontEnd.File
open FrontEnd.Lexer
open FrontEnd.Parser
open FrontEnd.Utilities
open Commands.DispatchResult
open Commands.Flags

let runParser (path: string, args: string[]) =
    let name = IO.Path.GetFileName(path)
    let file = ClothFile(path, name)
    let _compilationUnit = Parser(Lexer(file)).Parse()
    let relevantFlags = getRelevantFlags (args, "parser")

    if relevantFlags |> Array.contains "--dump" then
        printfn $"{JsonDump(_compilationUnit).ToJson()}"

    Success "Parser completed."
