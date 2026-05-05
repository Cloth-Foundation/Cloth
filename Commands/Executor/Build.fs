module Commands.Executor.Build

open System
open Commands.DispatchResult
open Commands.Flags
open Commands.Cleanup

let clean (dir: string) =
    cleanup (dir, CLEANUP_EXTENSIONS)

let runBuild (path: string, args: string[]) =
    let tomlPath = IO.Path.Combine(path, "build.toml")

    if not (IO.File.Exists(tomlPath)) then
        clean (path + "/build")
        Failure $"build.toml not found in '{path}'"
    else
        let compiler = Compiler.Compiler(path)
        let cirModule = compiler.Compile()
        let relevantFlags = getRelevantFlags (args, "build")

        if relevantFlags |> Array.contains "--dump" then
            printfn $"{Compiler.CIR.CirPrinter.Print(cirModule)}"

        clean (path + "/build")

        Success "Build completed."
