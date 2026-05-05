// Copyright (c) 2026.The Cloth contributors.
// 
// ConfigWriter.cs is part of the Cloth Compiler.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

using System.Text.Json;
using Tomlyn;

namespace Compiler.Configs;

public static class ConfigWriter {
	private static readonly TomlSerializerOptions Options = new() {
		PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
		WriteIndented = true,
		IndentSize = 4
	};

	public static void Write(string path, ClothConfig config) {
		var toml = TomlSerializer.Serialize(config, Options);
		File.WriteAllText(path, toml);
	}
}