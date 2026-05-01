// Copyright (c) 2026.The Cloth contributors.
//
// ClothFile.cs is part of the Cloth Frontend.
//
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

using System.Text.Json.Serialization;

namespace FrontEnd.File;

public sealed class ClothFile {
	public ClothFile(string path, string name, string content, bool isValid) {
		Path = path;
		Name = name;
		Content = content;
		IsValid = isValid;
	}

	public ClothFile(string path, string name) {
		if (!System.IO.File.Exists(path)) throw new FileNotFoundException("File not found", path);

		var fileEnding = System.IO.Path.GetExtension(path);
		if (!Utilities.ValidEndings.Contains(fileEnding)) throw new InvalidDataException($"Invalid file type: {fileEnding}");

		Path = path;
		Name = name;
		Content = string.Empty;
		IsValid = true;
	}

	public string Path { get; }

	public string Name { get; }

	[JsonIgnore] public string NameWithoutExtension => System.IO.Path.GetFileNameWithoutExtension(Name);

	[JsonIgnore] public string Content { get; private set; }

	[JsonIgnore] public bool IsValid { get; private set; }

	[JsonIgnore] public ClothFileType Type => GetFileType();

	[JsonInclude] public string FileType => GetFileType().ToString();

	public static ClothFile FromPath(string path) {
		var name = System.IO.Path.GetFileName(path);
		if (string.IsNullOrWhiteSpace(name)) name = path;

		return new ClothFile(path, name);
	}

	public static bool Exists(ClothFile file) {
		return Exists(file.Path);
	}

	public static bool Exists(string path) {
		return System.IO.File.Exists(path);
	}

	public void Read() {
		Content = System.IO.File.ReadAllText(Path);
	}

	public bool Validate() {
		IsValid = System.IO.File.Exists(Path) && IsClothFile();
		return IsValid;
	}

	public string GetFileEnding() {
		return System.IO.Path.GetExtension(Path);
	}

	public bool IsClothObject() {
		return GetFileEnding() == ".co";
	}

	public bool IsClothLibrary() {
		return GetFileEnding() == ".cl";
	}

	public bool IsClothFile() {
		return IsClothObject() || IsClothLibrary();
	}

	public ClothFileType GetFileType() {
		var ending = GetFileEnding();
		if (ending == ".co") return ClothFileType.ClothObject;
		if (ending == ".cl") return ClothFileType.ClothLibrary;

		throw new InvalidDataException($"Unknown file type: {ending}");
	}
}

public enum ClothFileType {
	ClothObject,
	ClothLibrary
}

public static class Utilities {
	public static readonly string[] ValidEndings = [
		".co",
		".cl"
	];
}