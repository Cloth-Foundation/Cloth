// Copyright (c) 2026.The Cloth contributors.
// 
// TokenSpan.cs is part of the Cloth Frontend.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

using System.Text.Json.Serialization;
using FrontEnd.File;

namespace FrontEnd.Token;

public class TokenSpan {
	public TokenSpan() {
		File = null;
	}

	public TokenSpan(int start, int end, int startLine, int endLine, int startColumn, int endColumn, ClothFile? file) {
		Start = start;
		End = end;
		StartLine = startLine;
		EndLine = endLine;
		StartColumn = startColumn;
		EndColumn = endColumn;
		File = file;
	}

	[JsonIgnore] public int Start { get; }

	[JsonIgnore] public int End { get; }

	[JsonIgnore] public int StartLine { get; }

	[JsonIgnore] public int EndLine { get; }

	[JsonIgnore] public int StartColumn { get; }

	[JsonIgnore] public int EndColumn { get; }

	[JsonIgnore] public ClothFile? File { get; }

	[JsonInclude] public string SpanRange => $"{StartLine}:{StartColumn}-{EndLine}:{EndColumn}";

	public static TokenSpan Merge(TokenSpan start, TokenSpan end) => new(start.Start, end.End, start.StartLine, end.EndLine, start.StartColumn, end.EndColumn, start.File);
}