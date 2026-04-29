namespace Commands

type Command =
    | Help
    | Build
    | Run
    | Test
    | Lexer
    | Parser
    | Unknown of string

type DispatchResult =
    | Success of Command
    | Failure of string

module entry =

    let dispatch (args: string[]) : DispatchResult =
        if args.Length = 0 then
            Failure "Usage: cloth <command> <flags>"
        elif args.[0] <> "cloth" then
            Failure $"Expected 'cloth', got '{args.[0]}'"
        elif args.Length < 2 then
            Failure "Usage: cloth <command> <flags>"
        else
            match args.[1] with
            | "help" -> Success Help
            | "?" -> Success Help
            | "build" -> Success Build
            | "run" -> Success Run
            | "test" -> Success Test
            | "lexer" -> Success Lexer
            | "parser" -> Success Parser
            | _ -> Failure "Usage: cloth <command> <flags>"
