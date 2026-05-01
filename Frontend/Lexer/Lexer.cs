// Copyright (c) 2026.The Cloth contributors.
// 
// Lexer.cs is part of the Cloth Frontend.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

using System.Text;
using FrontEnd.Error.Lexer;
using FrontEnd.File;
using FrontEnd.Token;

namespace FrontEnd.Lexer;

public class Lexer {
	private readonly string _input;

	private readonly ClothFile _sourceFile;
	private bool _afterColonColon;
	private int _column;
	private int _index;
	private int _line;

	public Lexer(ClothFile sourceFile) {
		if (!sourceFile.Validate()) {
			LexerError.InvalidFile.WithMessage($"Invalid source file: {sourceFile.Path}").Render();
		}

		sourceFile.Read();
		var normalized = new StringBuilder(sourceFile.Content.Length);
		for (var i = 0; i < sourceFile.Content.Length; i++) {
			var ch = sourceFile.Content[i];
			if (ch == '\r') {
				if (i + 1 < sourceFile.Content.Length && sourceFile.Content[i + 1] == '\n') i++;
				normalized.Append('\n');
			}
			else {
				normalized.Append(ch);
			}
		}

		_sourceFile = sourceFile;
		_input = normalized.ToString();
		_index = 0;
		_line = 1;
		_column = 1;
		_afterColonColon = false;
	}

	public ClothFile GetSourceFile() {
		return _sourceFile;
	}

	public List<Token.Token> LexAll() {
		if (!ClothFile.Exists(_sourceFile)) {
			LexerError.FileNotFound.WithMessage($"File not found: {_sourceFile}").Render();
		}

		List<Token.Token> tokens = new();

		while (true)
			try {
				var token = NextTokenInternal();
				var isEof = token.Type == TokenType.Eof;
				tokens.Add(token);
				if (isEof) break;
			}
			catch (LexerError err) {
				err.Render();
			}

		return tokens;
	}

	private Token.Token NextTokenInternal() {
		SkipTrivia();

		if (IsEof())
			return MakeToken(TokenType.Eof, string.Empty, string.Empty, _index, _index, _line, _column, _line, _column);

		var startIndex = _index;
		var startLine = _line;
		var startCol = _column;
		var ch = PeekChar() ?? throw ErrorAtCurrent(LexerError.UnexpectedEof);

		if (IsIdentStart(ch)) {
			var lexeme = LexIdentifier();
			var (kind, keyword, metaKeyword) = ClassifyIdentifier(lexeme);
			var endIndex = _index;
			var endLine = _line;
			var endCol = _column;
			_afterColonColon = false;
			return MakeToken(kind, lexeme, lexeme, startIndex, endIndex, startLine, startCol, endLine, endCol, keyword, metaKeyword);
		}

		if (char.IsDigit(ch)) {
			var token = LexNumberOrDotPrefixedLiteral();
			_afterColonColon = false;
			return token;
		}

		if (ch == '\'') {
			var token = LexCharLiteral();
			_afterColonColon = false;
			return token;
		}

		if (ch == '"') {
			var token = LexStringLiteral();
			_afterColonColon = false;
			return token;
		}

		var opToken = LexOperatorOrPunctuation();
		return opToken;
	}

	private void SkipTrivia() {
		while (true) {
			var ch = PeekChar();
			if (!ch.HasValue) return;

			if (IsWhitespace(ch.Value)) {
				BumpOne();
				continue;
			}

			if (IsForbiddenControl(ch.Value)) {
				var err = ErrorAtCurrent(LexerError.IllegalControlChar);
				BumpOne();
				throw err;
			}

			if (StartsWith("//")) {
				BumpN(2);
				while (true) {
					var c = PeekChar();
					if (!c.HasValue || c.Value == '\n') break;
					BumpOne();
				}

				continue;
			}

			if (StartsWith("/*")) {
				var blockStartIndex = _index;
				var blockStartLine = _line;
				var blockStartCol = _column;
				BumpN(2);
				while (true) {
					if (IsEof()) {
						var span = new TokenSpan(blockStartIndex, _index, blockStartLine, _line, blockStartCol, _column, _sourceFile);
						throw LexerError.UnterminatedBlockComment.WithSpan(span);
					}

					if (StartsWith("*/")) {
						BumpN(2);
						break;
					}

					BumpOne();
				}

				continue;
			}

			break;
		}
	}

	private (TokenType, Keyword?, MetaKeyword?) ClassifyIdentifier(string lexeme) {
		if (_afterColonColon && IsAllUpperOrUnderscore(lexeme)) {
			var metaKw = Keywords.GetMetaKeywordFromLexeme(lexeme);
			if (metaKw.HasValue) return (TokenType.Meta, null, metaKw);
		}

		var kw = Keywords.GetKeywordFromLexeme(lexeme);
		if (kw.HasValue) return (TokenType.Keyword, kw, null);

		return (TokenType.Identifier, null, null);
	}

	private string LexIdentifier() {
		var start = _index;
		BumpOne();
		while (true) {
			var ch = PeekChar();
			if (ch.HasValue && IsIdentPart(ch.Value))
				BumpOne();
			else
				break;
		}

		return Slice(start, _index);
	}

	private Token.Token LexNumberOrDotPrefixedLiteral() {
		var startIndex = _index;
		var startLine = _line;
		var startCol = _column;

		if ((PeekChar() == '0' || PeekChar() == '1') && (PeekNthChar(1) == 't' || PeekNthChar(1) == 'T')) {
			BumpOne();
			BumpOne();
			var endIndex = _index;
			var endLine = _line;
			var endCol = _column;
			var lexeme = Slice(startIndex, endIndex);
			return MakeToken(TokenType.Literal, lexeme, lexeme, startIndex, endIndex, startLine, startCol, endLine, endCol);
		}

		var radix = 10;
		if (StartsWith("0b") || StartsWith("0B")) {
			radix = 2;
			BumpN(2);
		}
		else if (StartsWith("0o") || StartsWith("0O")) {
			radix = 8;
			BumpN(2);
		}
		else if (StartsWith("0d") || StartsWith("0D")) {
			radix = 10;
			BumpN(2);
		}
		else if (StartsWith("0x") || StartsWith("0X")) {
			radix = 16;
			BumpN(2);
		}

		var sawDigits = false;
		while (true) {
			var ch = PeekChar();
			if (!ch.HasValue) break;

			if (ch.Value == '_') {
				BumpOne();
			}
			else if (DigitInRadix(ch.Value, radix)) {
				sawDigits = true;
				BumpOne();
			}
			else {
				break;
			}
		}

		if (!sawDigits) throw ErrorSpan(LexerError.RadixWithoutDigits, startIndex, startLine, startCol, _index, _line, _column);

		if (PeekChar() == '.') {
			if (StartsWith("..") || StartsWith("...")) {
				var endIndex = _index;
				var endLine = _line;
				var endCol = _column;
				var lexeme = Slice(startIndex, endIndex);
				return MakeToken(TokenType.Literal, lexeme, lexeme, startIndex, endIndex, startLine, startCol, endLine, endCol);
			}

			BumpOne();
			while (true) {
				var ch = PeekChar();
				if (ch.HasValue && char.IsDigit(ch.Value))
					BumpOne();
				else
					break;
			}

			if (PeekChar() == 'e' || PeekChar() == 'E') {
				BumpOne();
				if (PeekChar() == '+' || PeekChar() == '-') BumpOne();
				var expDigits = 0;
				while (true) {
					var ch = PeekChar();
					if (ch.HasValue && char.IsDigit(ch.Value)) {
						expDigits++;
						BumpOne();
					}
					else {
						break;
					}
				}

				if (expDigits == 0) throw ErrorAtCurrent(LexerError.EmptyExponent);
			}

			if (PeekChar() == 'f' || PeekChar() == 'F' || PeekChar() == 'd' || PeekChar() == 'D') BumpOne();

			var endIndex2 = _index;
			var endLine2 = _line;
			var endCol2 = _column;
			var lexeme2 = Slice(startIndex, endIndex2);
			return MakeToken(TokenType.Literal, lexeme2, lexeme2, startIndex, endIndex2, startLine, startCol, endLine2, endCol2);
		}

		if (PeekChar() == 'b' || PeekChar() == 'B' || PeekChar() == 'i' || PeekChar() == 'I' || PeekChar() == 'l' || PeekChar() == 'L' || PeekChar() == 'u' || PeekChar() == 'U')
			BumpOne();

		var endIndex3 = _index;
		var endLine3 = _line;
		var endCol3 = _column;
		var lexeme3 = Slice(startIndex, endIndex3);
		return MakeToken(TokenType.Literal, lexeme3, lexeme3, startIndex, endIndex3, startLine, startCol, endLine3, endCol3);
	}

	private Token.Token LexCharLiteral() {
		var startIndex = _index;
		var startLine = _line;
		var startCol = _column;
		BumpOne();

		if (IsEof() || PeekChar() == '\n') throw ErrorAtSpanStart(LexerError.UnterminatedCharLiteral, startIndex, startLine, startCol);

		if (PeekChar() == '\\') {
			BumpOne();
			if (IsEof()) throw ErrorAtSpanStart(LexerError.UnterminatedCharLiteral, startIndex, startLine, startCol);
			var escapeCh = PeekChar() ?? throw ErrorAtSpanStart(LexerError.UnterminatedCharLiteral, startIndex, startLine, startCol);
			if (!IsValidEscape(escapeCh)) throw ErrorAtCurrent(LexerError.UnknownEscapeInChar);
		}

		BumpOne();

		if (PeekChar() != '\'') throw ErrorAtCurrent(LexerError.CharLiteralMultipleScalars);
		BumpOne();

		var endIndex = _index;
		var endLine = _line;
		var endCol = _column;
		var lexeme = Slice(startIndex, endIndex);
		return MakeToken(TokenType.Literal, lexeme, lexeme, startIndex, endIndex, startLine, startCol, endLine, endCol);
	}

	private Token.Token LexStringLiteral() {
		var startIndex = _index;
		var startLine = _line;
		var startCol = _column;
		BumpOne();

		while (true) {
			if (IsEof()) throw ErrorAtSpanStart(LexerError.UnterminatedString, startIndex, startLine, startCol);
			var ch = PeekChar() ?? throw ErrorAtSpanStart(LexerError.UnterminatedString, startIndex, startLine, startCol);
			if (ch == '\n') throw ErrorAtSpanStart(LexerError.UnterminatedString, startIndex, startLine, startCol);
			if (ch == '"') {
				BumpOne();
				break;
			}

			if (ch == '\\') {
				BumpOne();
				if (IsEof()) throw ErrorAtSpanStart(LexerError.UnterminatedString, startIndex, startLine, startCol);
				var escapeCh = PeekChar() ?? throw ErrorAtSpanStart(LexerError.UnterminatedString, startIndex, startLine, startCol);
				if (!IsValidEscape(escapeCh)) throw ErrorAtCurrent(LexerError.UnknownEscapeInString);
			}

			BumpOne();
		}

		var endIndex = _index;
		var endLine = _line;
		var endCol = _column;
		var lexeme = Slice(startIndex, endIndex);
		return MakeToken(TokenType.Literal, lexeme, lexeme, startIndex, endIndex, startLine, startCol, endLine, endCol);
	}

	private Token.Token LexOperatorOrPunctuation() {
		var startIndex = _index;
		var startLine = _line;
		var startCol = _column;

		foreach (var op in Operators.MultiCharOperators.OrderByDescending(o => o.Length))
			if (StartsWith(op)) {
				BumpN(op.Length);
				var endIndex = _index;
				var endLine = _line;
				var endCol = _column;
				var lexeme = op;
				_afterColonColon = op == "::";
				var opKind = Operators.GetOperatorFromLexeme(op);
				return MakeToken(TokenType.Operator, lexeme, lexeme, startIndex, endIndex, startLine, startCol, endLine, endCol, op: opKind);
			}

		var ch = PeekChar();
		if (ch.HasValue && Operators.SingleCharOperators.Contains(ch.Value)) {
			BumpOne();
			var endIndex = _index;
			var endLine = _line;
			var endCol = _column;
			var lexeme = Slice(startIndex, endIndex);
			_afterColonColon = false;
			var opKind = Operators.GetOperatorFromLexeme(lexeme);
			return MakeToken(TokenType.Operator, lexeme, lexeme, startIndex, endIndex, startLine, startCol, endLine, endCol, op: opKind);
		}

		throw ErrorAtCurrent(LexerError.IllegalCharacter);
	}

	private Token.Token MakeToken(TokenType kind, string literal, string lexeme, int start, int end, int startLine, int startCol, int endLine, int endCol, Keyword? keyword = null, MetaKeyword? metaKeyword = null, Operator? op = null) {
		var span = new TokenSpan(start, end, startLine, endLine, startCol, endCol, _sourceFile);
		return new Token.Token(kind, literal, span, lexeme, keyword, metaKeyword, op);
	}

	private LexerError ErrorAtCurrent(LexerError error) {
		var span = new TokenSpan(_index, _index, _line, _line, _column, _column, _sourceFile);
		return error.WithSpan(span);
	}

	private LexerError ErrorAtSpanStart(LexerError error, int start, int startLine, int startCol) {
		var span = new TokenSpan(start, _index, startLine, _line, startCol, _column, _sourceFile);
		return error.WithSpan(span);
	}

	private LexerError ErrorSpan(LexerError error, int start, int startLine, int startCol, int end, int endLine, int endCol) {
		var span = new TokenSpan(start, end, startLine, endLine, startCol, endCol, _sourceFile);
		return error.WithSpan(span);
	}

	private bool IsEof() {
		return _index >= _input.Length;
	}

	private string Slice(int start, int end) {
		return _input.Substring(start, end - start);
	}

	private bool StartsWith(string s) {
		if (_index + s.Length > _input.Length) return false;
		return _input.Substring(_index, s.Length) == s;
	}

	private char? PeekChar() {
		if (IsEof()) return null;
		return _input[_index];
	}

	private char? PeekNthChar(int n) {
		var pos = _index + n;
		if (pos >= _input.Length) return null;

		var currentPos = _index;
		var charCount = 0;
		while (currentPos < _input.Length && charCount < n) {
			currentPos++;
			charCount++;
		}

		if (currentPos >= _input.Length) return null;
		return _input[currentPos];
	}

	private void BumpOne() {
		var ch = PeekChar();
		if (!ch.HasValue) return;

		_index++;
		if (ch.Value == '\n') {
			_line++;
			_column = 1;
		}
		else {
			_column++;
		}
	}

	private void BumpN(int nBytes) {
		var target = Math.Min(_index + nBytes, _input.Length);
		while (_index < target) {
			var ch = _input[_index];
			_index++;
			if (ch == '\n') {
				_line++;
				_column = 1;
			}
			else {
				_column++;
			}
		}
	}

	private static bool IsWhitespace(char ch) {
		return ch is ' ' or '\t' or '\n';
	}

	private static bool IsIdentStart(char ch) {
		return ch is '_' or >= 'a' and <= 'z' or >= 'A' and <= 'Z';
	}

	private static bool IsIdentPart(char ch) {
		return IsIdentStart(ch) || ch is >= '0' and <= '9' || ch is '$';
	}

	private static bool DigitInRadix(char ch, int radix) {
		return radix switch {
			2 => ch is '0' or '1',
			8 => ch is >= '0' and <= '7',
			10 => ch is >= '0' and <= '9',
			16 => ch is >= '0' and <= '9' || ch is >= 'a' and <= 'f' || ch is >= 'A' and <= 'F',
			_ => false
		};
	}

	private static bool IsAllUpperOrUnderscore(string s) {
		if (string.IsNullOrEmpty(s)) return false;
		return s.All(c => c is >= 'A' and <= 'Z' or '_');
	}

	private static bool IsForbiddenControl(char ch) {
		int cp = ch;
		return (cp < 0x20 && ch != '\t' && ch != '\n') || cp == 0x7F || cp == 0x00A0 || cp == 0xFEFF || cp is >= 0x200B and <= 0x200F || cp is >= 0x202A and <= 0x202E || cp is >= 0x2060 and <= 0x2064 || cp == 0x206F;
	}

	private static bool IsValidEscape(char ch) {
		return ch == 'n' || ch == 'r' || ch == 't' || ch == '"' || ch == '\'' || ch == '\\' || ch == '0';
	}
}