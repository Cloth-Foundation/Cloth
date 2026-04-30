// Copyright (c) 2026.The Cloth contributors.
//
// TokenSpan.cs is part of the Cloth Frontend.
//
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

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

	public int Start { get; }

	public int End { get; }

	public int StartLine { get; }

	public int EndLine { get; }

	public int StartColumn { get; }

	public int EndColumn { get; }

	public ClothFile? File { get; }

	public static TokenSpan Merge(TokenSpan start, TokenSpan end) =>
		new TokenSpan(start.Start, end.End, start.StartLine, end.EndLine, start.StartColumn, end.EndColumn, start.File);
}