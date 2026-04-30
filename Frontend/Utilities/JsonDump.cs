// Copyright (c) 2026.The Cloth contributors.
// 
// JsonDump.cs is part of the Cloth Frontend.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

namespace FrontEnd.Utilities;

using System.Text.Json;

public class JsonDump<T>(T data) {

	private readonly JsonSerializerOptions _options = new() {
		WriteIndented = true
	};

	public string ToJson() {
		return JsonSerializer.Serialize(data, _options);
	}
}