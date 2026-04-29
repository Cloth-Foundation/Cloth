module Commands.Flags

let FLAG_DELIMITER = "--"

let MAIN_COMMANDS =
    set [
        "lexer"
        "run"
        "build"
        "parse"
    ]

let getRelevantFlags(args: string[], command: string) : string[] =
    let commandIndex =
        args
        |> Array.tryFindIndex (fun arg -> arg = command)

    match commandIndex with
    | None -> [||]
    | Some index ->
        args
        |> Array.skip (index + 1)
        |> Array.takeWhile (fun arg -> not (MAIN_COMMANDS.Contains arg))
        |> Array.filter (fun arg -> arg.StartsWith(FLAG_DELIMITER))