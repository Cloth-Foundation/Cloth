module Commands.Executor.Help

open Commands.DispatchResult

let compilerVersion = "0.1.0"

let runHelp () : DispatchResult =
    eprintfn $"Cloth Compiler {compilerVersion}"
    eprintfn ""

    eprintfn "Usage:"
    eprintfn "  cloth <command> [options] <file>"
    eprintfn "  cloth <file>                    (defaults to run)"
    eprintfn ""

    eprintfn "Commands:"
    eprintfn "  help, ?                         Show this help message"
    eprintfn "  version                         Show compiler version"
    eprintfn "  lexer <flags> <file>            Run lexer on a source file"
    eprintfn "  parse <flags> <file>            Parse source and report syntax errors"
    eprintfn "  check <flags> <file>            Run semantic/type checks"
    eprintfn "  run <flags> <build_file>        Compile and execute"
    eprintfn "  build <flags> <build_file>      Compile to output artifact"
    eprintfn "  doc <flags> <build_file>        Generate documentation"
    eprintfn ""

    eprintfn "Options:"
    eprintfn "  -o <path>                       Set output path"
    eprintfn "  -target <triple>                Select target/backend"
    eprintfn "  -O0|-O1|-O2|-O3                 Optimization level"
    eprintfn "  -g                              Emit debug information"
    eprintfn "  -Werror                         Treat warnings as errors"
    eprintfn "  -I <dir>                        Add import/include directory"
    eprintfn "  -color <mode>                   Diagnostic color: always|auto|never"
    eprintfn ""

    eprintfn "Debug:"
    eprintfn "  --dump-tokens <flags>           Dump lexer tokens"
    eprintfn "  --dump-ast <flags>              Print parsed AST"
    eprintfn "  --dump-ir <flags>               Print lowered IR"
    eprintfn "  --dump-symbols <flags>          Print symbol table/resolution data"
    eprintfn ""

    eprintfn "Examples:"
    eprintfn "  cloth main.co"
    eprintfn "  cloth lexer main.co"
    eprintfn "  cloth parse src/main.co"
    eprintfn "  cloth build \"C:\\path\\to\\build.toml\" -o out.exe"
    eprintfn "  cloth doc \"C:\\path\\to\\build.toml\""
    eprintfn ""

    eprintfn "For more help:"
    eprintfn "  cloth <command> -help"

    Success ""
