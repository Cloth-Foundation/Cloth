module Commands.Executor.NewProject

open System
open System.IO
open System.Collections.Generic

open Commands.DispatchResult
open Compiler.Configs
open Compiler.Configs.Sections

let private normalizeProjectName (path: string) =
    let name = Path.GetFileName(Path.GetFullPath(path).TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar))
    if String.IsNullOrWhiteSpace(name) then
        "MyProject"
    else
        name

let private createBuildToml (projectDir: string) =
    let buildTomlPath = Path.Combine(projectDir, "build.toml")
    let projectName = normalizeProjectName projectDir

    let project =
        ProjectSection(
            Name = projectName,
            Version = "0.1.0",
            Authors = [| "me" |],
            Description = "A Cloth project",
            Url = null
        )

    let build =
        BuildSection(
            Target = "x86_64",
            OutputType = OutputType.Executable,
            Source = "src"
        )

    let dependencies = Dictionary<string, string>()
    dependencies.Add(("cloth", "2026.0.1A"))

    let config =
        ClothConfig(
            Project = project,
            Build = build,
            Dependencies = dependencies
        )
    ConfigWriter.Write(buildTomlPath, config)


let runNewProject (path: string, args: string[]) =
    try
        let projectDir = Path.GetFullPath(path)
        let srcDir = Path.Combine(projectDir, "src")
        let buildTomlPath = Path.Combine(projectDir, "build.toml")

        if Directory.Exists(projectDir) then
            Failure $"Directory '{projectDir}' already exists"
        else
            Directory.CreateDirectory(projectDir) |> ignore
            Directory.CreateDirectory(srcDir) |> ignore

            if File.Exists(buildTomlPath) then
                Failure $"File '{buildTomlPath}' already exists"
            else
                createBuildToml projectDir
                Success $"Created new Cloth project: {projectDir}"
    with
    | ex -> Failure $"Error creating project: {ex.Message}"