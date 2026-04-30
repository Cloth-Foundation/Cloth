// Copyright (c) 2026.The Cloth contributors.
// 
// JsonDump.cs is part of the Cloth Frontend.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

namespace FrontEnd.Utilities;

using System.Text.Json;
using System.Collections.Generic;

public class JsonDump<T>(T data) {
	public string ToJson(bool indented = true) {
		return JsonSerializer.Serialize(data, new JsonSerializerOptions {
			WriteIndented = indented
		});
	}

	public static JsonDump<T> Create<T>(T data) {
		return new JsonDump<T>(data);
	}
}