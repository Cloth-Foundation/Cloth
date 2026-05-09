module Commands.Cleanup

open System

// This is the artifacts left by LLVM and the Cloth compiler.
// Since there is no point in keeping them, we can delete them.
// Only the executable stays.
let CLEANUP_EXTENSIONS: string[] = [| "o"; "ll" |]

// Deletes all files with the given extensions in the given directory.
// This is intended to only ever target the build directory, although it is not forced.
// Use with caution, as it is not reversible. You should probably only ever run with the
// above CLEANUP_EXTENSIONS.
let cleanup (path: string, file_extensions: string[]) =
    let path = IO.Path.GetFullPath(path)

    for file_extension in file_extensions do
        let files = IO.Directory.GetFiles(path, $"*.{file_extension}")

        for file in files do
            IO.File.Delete(file)
