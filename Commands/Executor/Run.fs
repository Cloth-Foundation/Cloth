module Commands.Executor.Run

open System
open System.Diagnostics
open Commands.Cleanup
open Commands.DispatchResult
open Compiler.Configs

let clean (dir: string) =
    cleanup (dir, CLEANUP_EXTENSIONS)

let runRun (path: string, _args: string[]) =
    let tomlPath = IO.Path.Combine(path, "build.toml")

    if not (IO.File.Exists(tomlPath)) then
        Failure $"build.toml not found in '{path}'"
    else
        let compiler = Compiler.Compiler(path)
        compiler.Compile() |> ignore

        let config = ConfigReader.Read(tomlPath)

        if config.Build.OutputType <> OutputType.Executable then
            Failure $"cannot run a project with output='{ClothConfig.OutputTypeToString config.Build.OutputType}' (only 'executable' is runnable)"
        else
            let buildDir = IO.Path.Combine(path, "build")

            let exeName =
                if OperatingSystem.IsWindows() then
                    config.Project.Name + ".exe"
                else
                    config.Project.Name

            let exePath = IO.Path.Combine(buildDir, exeName)

            if not (IO.File.Exists(exePath)) then
                clean buildDir
                Failure $"expected binary '{exePath}' was not produced by build"
            else
                let psi = ProcessStartInfo()
                psi.FileName <- exePath
                psi.WorkingDirectory <- buildDir
                psi.UseShellExecute <- false
                psi.CreateNoWindow <- false

                use proc = Process.Start(psi)
                proc.WaitForExit()
                clean buildDir

                if proc.ExitCode = 0 then
                    Success ""
                else
                    Failure $"program exited with code {proc.ExitCode}"
