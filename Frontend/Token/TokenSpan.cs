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

	public TokenSpan(int start, int end, int startLine, int endLine, int startColumn, int endColumn, ClothFile file) {
		Start = start;
		End = end;
		StartLine = startLine;
		EndLine = endLine;
		StartColumn = startColumn;
		EndColumn = endColumn;
		File = file;
	}

	private int Start { get; }

	private int End { get; }

	private int StartLine { get; }

	private int EndLine { get; }

	private int StartColumn { get; }

	private int EndColumn { get; }

	private ClothFile File { get; }
}