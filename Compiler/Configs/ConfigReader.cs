// Copyright (c) 2026.The Cloth contributors.
// 
// ConfigReader.cs is part of the Cloth Compiler.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

using System.Text.Json;
using Tomlyn;

namespace Compiler.Configs;

public static class ConfigReader {

	private static readonly TomlSerializerOptions Options = new() {
		PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
		WriteIndented = true
	};

	public static ClothConfig Read(string path) {
		var text = File.ReadAllText(path);
		var config = TomlSerializer.Deserialize<ClothConfig>(text, Options);
		if (config is null) throw new InvalidOperationException($"Failed to read config file: {path}");
		return config;
	}

}