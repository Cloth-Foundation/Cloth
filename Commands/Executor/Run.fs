module Commands.Executor.Run

open System
open System.Diagnostics
open Commands.DispatchResult

let runRun (path: string, _args: string[]) =
    let tomlPath = IO.Path.Combine(path, "build.toml")

    if not (IO.File.Exists(tomlPath)) then
        Failure $"build.toml not found in '{path}'"
    else
        let compiler = Compiler.Compiler(path)
        compiler.Compile() |> ignore

        let config = Compiler.BuildConfig.FromFile(tomlPath)

        if config.Output <> "executable" then
            Failure $"cannot run a project with output='{config.Output}' (only 'executable' is runnable)"
        else
            let buildDir = IO.Path.Combine(path, "build")

            let exeName =
                if OperatingSystem.IsWindows() then
                    config.ProjectName + ".exe"
                else
                    config.ProjectName

            let exePath = IO.Path.Combine(buildDir, exeName)

            if not (IO.File.Exists(exePath)) then
                Failure $"expected binary '{exePath}' was not produced by build"
            else
                let psi = ProcessStartInfo()
                psi.FileName <- exePath
                psi.WorkingDirectory <- buildDir
                psi.UseShellExecute <- false
                psi.CreateNoWindow <- false

                use proc = Process.Start(psi)
                proc.WaitForExit()

                if proc.ExitCode = 0 then
                    Success ""
                else
                    Failure $"program exited with code {proc.ExitCode}"
