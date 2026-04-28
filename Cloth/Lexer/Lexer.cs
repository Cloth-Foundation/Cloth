using Cloth.File;
using Cloth.Token;
using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;

namespace Cloth.Lexer {
	public class Lexer {

		private ClothFile file;
		private string input;
		private int index;
		private int line;
		private int column;
		private bool afterColonColon;

		public Lexer(ClothFile file) {
			file.Read();
			StringBuilder normalized = new StringBuilder(file.Content.Length);
			for (int i = 0; i < file.Content.Length; i++) {
				char ch = file.Content[i];
				if (ch == '\r') {
					if (i + 1 < file.Content.Length && file.Content[i + 1] == '\n') {
						i++;
					}
					normalized.Append('\n');
				} else {
					normalized.Append(ch);
				}
			}

			this.file = file;
			this.input = normalized.ToString();
			this.index = 0;
			this.line = 1;
			this.column = 1;
			this.afterColonColon = false;
		}

		public List<Token.Token> LexAll() {
			List<Token.Token> tokens = new List<Token.Token>();

			while (true) {
				try {
					Token.Token token = NextTokenInternal();
					bool isEof = token.Type == TokenType.Eof;
					tokens.Add(token);
					if (isEof) {
						break;
					}
				} catch (LexError err) {
					Console.Error.WriteLine($"Lexer error: {err.Kind} at line {err.Span}");
					Environment.Exit(1);
				}
			}

			return tokens;
		}

		private Token.Token NextTokenInternal() {
			SkipTrivia();

			if (IsEof()) {
				return MakeToken(
					TokenType.Eof,
					string.Empty,
					string.Empty,
					index, index,
					line, column,
					line, column
				);
			}

			int startIndex = index;
			int startLine = line;
			int startCol = column;
			char ch = PeekChar() ?? throw ErrorAtCurrent(LexErrorKind.UnexpectedEof);

			if (IsIdentStart(ch)) {
				string lexeme = LexIdentifier();
				(TokenType kind, Keyword? keyword, MetaKeyword? metaKeyword) = ClassifyIdentifier(lexeme);
				int endIndex = index;
				int endLine = line;
				int endCol = column;
				afterColonColon = false;
				return MakeToken(kind, lexeme, lexeme, startIndex, endIndex, startLine, startCol, endLine, endCol, keyword, metaKeyword);
			}

			if (char.IsDigit(ch)) {
				Token.Token token = LexNumberOrDotPrefixedLiteral();
				afterColonColon = false;
				return token;
			}

			if (ch == '\'') {
				Token.Token token = LexCharLiteral();
				afterColonColon = false;
				return token;
			}

			if (ch == '"') {
				Token.Token token = LexStringLiteral();
				afterColonColon = false;
				return token;
			}

			Token.Token opToken = LexOperatorOrPunct();
			return opToken;
		}

		private void SkipTrivia() {
			while (true) {
				char? ch = PeekChar();
				if (!ch.HasValue) return;

				if (IsWhitespace(ch.Value)) {
					BumpOne();
					continue;
				}

				if (IsForbiddenControl(ch.Value)) {
					LexError err = ErrorAtCurrent(LexErrorKind.IllegalControlChar);
					BumpOne();
					throw err;
				}

				if (StartsWith("//")) {
					BumpN(2);
					while (true) {
						char? c = PeekChar();
						if (!c.HasValue || c.Value == '\n') {
							break;
						}
						BumpOne();
					}
					continue;
				}

				if (StartsWith("/*")) {
					int blockStartIndex = index;
					int blockStartLine = line;
					int blockStartCol = column;
					BumpN(2);
					while (true) {
						if (IsEof()) {
							TokenSpan span = new TokenSpan(
								blockStartIndex, index,
								blockStartLine, blockStartCol,
								line, column,
								file
							);
							throw new LexError(LexErrorKind.UnterminatedBlockComment, span);
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
			if (afterColonColon && IsAllUpperOrUnderscore(lexeme)) {
				MetaKeyword? metaKw = Keywords.GetMetaKeywordFromLexeme(lexeme);
				if (metaKw.HasValue) {
					return (TokenType.Meta, null, metaKw);
				}
			}

			Keyword? kw = Keywords.GetKeywordFromLexeme(lexeme);
			if (kw.HasValue) {
				return (TokenType.Keyword, kw, null);
			}

			return (TokenType.Identifier, null, null);
		}

		private string LexIdentifier() {
			int start = index;
			BumpOne();
			while (true) {
				char? ch = PeekChar();
				if (ch.HasValue && IsIdentPart(ch.Value)) {
					BumpOne();
				} else {
					break;
				}
			}
			return Slice(start, index);
		}

		private Token.Token LexNumberOrDotPrefixedLiteral() {
			int startIndex = index;
			int startLine = line;
			int startCol = column;

			if ((PeekChar() == '0' || PeekChar() == '1') && (PeekNthChar(1) == 't' || PeekNthChar(1) == 'T')) {
				BumpOne();
				BumpOne();
				int endIndex = index;
				int endLine = line;
				int endCol = column;
				string lexeme = Slice(startIndex, endIndex);
				return MakeToken(TokenType.Literal, lexeme, lexeme, startIndex, endIndex, startLine, startCol, endLine, endCol);
			}

			int radix = 10;
			if (StartsWith("0b") || StartsWith("0B")) {
				radix = 2;
				BumpN(2);
			} else if (StartsWith("0o") || StartsWith("0O")) {
				radix = 8;
				BumpN(2);
			} else if (StartsWith("0d") || StartsWith("0D")) {
				radix = 10;
				BumpN(2);
			} else if (StartsWith("0x") || StartsWith("0X")) {
				radix = 16;
				BumpN(2);
			}

			bool sawDigits = false;
			while (true) {
				char? ch = PeekChar();
				if (!ch.HasValue) break;

				if (ch.Value == '_') {
					BumpOne();
				} else if (DigitInRadix(ch.Value, radix)) {
					sawDigits = true;
					BumpOne();
				} else {
					break;
				}
			}

			if (!sawDigits) {
				throw ErrorSpan(LexErrorKind.RadixWithoutDigits, startIndex, startLine, startCol, index, line, column);
			}

			if (PeekChar() == '.') {
				if (StartsWith("..") || StartsWith("...")) {
					int endIndex = index;
					int endLine = line;
					int endCol = column;
					string lexeme = Slice(startIndex, endIndex);
					return MakeToken(TokenType.Literal, lexeme, lexeme, startIndex, endIndex, startLine, startCol, endLine, endCol);
				}

				BumpOne();
				while (true) {
					char? ch = PeekChar();
					if (ch.HasValue && char.IsDigit(ch.Value)) {
						BumpOne();
					} else {
						break;
					}
				}

				if (PeekChar() == 'e' || PeekChar() == 'E') {
					BumpOne();
					if (PeekChar() == '+' || PeekChar() == '-') {
						BumpOne();
					}
					int expDigits = 0;
					while (true) {
						char? ch = PeekChar();
						if (ch.HasValue && char.IsDigit(ch.Value)) {
							expDigits++;
							BumpOne();
						} else {
							break;
						}
					}
					if (expDigits == 0) {
						throw ErrorAtCurrent(LexErrorKind.EmptyExponent);
					}
				}

				if (PeekChar() == 'f' || PeekChar() == 'F' || PeekChar() == 'd' || PeekChar() == 'D') {
					BumpOne();
				}

				int endIndex2 = index;
				int endLine2 = line;
				int endCol2 = column;
				string lexeme2 = Slice(startIndex, endIndex2);
				return MakeToken(TokenType.Literal, lexeme2, lexeme2, startIndex, endIndex2, startLine, startCol, endLine2, endCol2);
			}

			if (PeekChar() == 'b' || PeekChar() == 'B' || PeekChar() == 'i' || PeekChar() == 'I' ||
				PeekChar() == 'l' || PeekChar() == 'L' || PeekChar() == 'u' || PeekChar() == 'U') {
				BumpOne();
			}

			int endIndex3 = index;
			int endLine3 = line;
			int endCol3 = column;
			string lexeme3 = Slice(startIndex, endIndex3);
			return MakeToken(TokenType.Literal, lexeme3, lexeme3, startIndex, endIndex3, startLine, startCol, endLine3, endCol3);
		}

		private Token.Token LexCharLiteral() {
			int startIndex = index;
			int startLine = line;
			int startCol = column;
			BumpOne();

			if (IsEof() || PeekChar() == '\n') {
				throw ErrorAtSpanStart(LexErrorKind.UnterminatedCharLiteral, startIndex, startLine, startCol);
			}

			if (PeekChar() == '\\') {
				BumpOne();
				if (IsEof()) {
					throw ErrorAtSpanStart(LexErrorKind.UnterminatedCharLiteral, startIndex, startLine, startCol);
				}
				char escapeCh = PeekChar().Value;
				if (!IsValidEscape(escapeCh)) {
					throw ErrorAtCurrent(LexErrorKind.UnknownEscapeInChar);
				}
				BumpOne();
			} else {
				BumpOne();
			}

			if (PeekChar() != '\'') {
				throw ErrorAtCurrent(LexErrorKind.CharLiteralMultipleScalars);
			}
			BumpOne();

			int endIndex = index;
			int endLine = line;
			int endCol = column;
			string lexeme = Slice(startIndex, endIndex);
			return MakeToken(TokenType.Literal, lexeme, lexeme, startIndex, endIndex, startLine, startCol, endLine, endCol);
		}

		private Token.Token LexStringLiteral() {
			int startIndex = index;
			int startLine = line;
			int startCol = column;
			BumpOne();

			while (true) {
				if (IsEof()) {
					throw ErrorAtSpanStart(LexErrorKind.UnterminatedString, startIndex, startLine, startCol);
				}
				char ch = PeekChar().Value;
				if (ch == '\n') {
					throw ErrorAtSpanStart(LexErrorKind.UnterminatedString, startIndex, startLine, startCol);
				}
				if (ch == '"') {
					BumpOne();
					break;
				}
				if (ch == '\\') {
					BumpOne();
					if (IsEof()) {
						throw ErrorAtSpanStart(LexErrorKind.UnterminatedString, startIndex, startLine, startCol);
					}
					char escapeCh = PeekChar().Value;
					if (!IsValidEscape(escapeCh)) {
						throw ErrorAtCurrent(LexErrorKind.UnknownEscapeInString);
					}
					BumpOne();
					continue;
				}
				BumpOne();
			}

			int endIndex = index;
			int endLine = line;
			int endCol = column;
			string lexeme = Slice(startIndex, endIndex);
			return MakeToken(TokenType.Literal, lexeme, lexeme, startIndex, endIndex, startLine, startCol, endLine, endCol);
		}

		private Token.Token LexOperatorOrPunct() {
			int startIndex = index;
			int startLine = line;
			int startCol = column;

			foreach (string op in Operators.MultiCharOperators) {
				if (StartsWith(op)) {
					BumpN(op.Length);
					int endIndex = index;
					int endLine = line;
					int endCol = column;
					string lexeme = op;
					if (op == "::") {
						afterColonColon = true;
					} else {
						afterColonColon = false;
					}
					Operator opKind = Operators.GetOperatorFromLexeme(op);
					return MakeToken(TokenType.Operator, lexeme, lexeme, startIndex, endIndex, startLine, startCol, endLine, endCol, op: opKind);
				}
			}

			char? ch = PeekChar();
			if (ch.HasValue && Operators.SingleCharOperators.Contains(ch.Value)) {
				BumpOne();
				int endIndex = index;
				int endLine = line;
				int endCol = column;
				string lexeme = Slice(startIndex, endIndex);
				afterColonColon = false;
				Operator opKind = Operators.GetOperatorFromLexeme(lexeme);
				return MakeToken(TokenType.Operator, lexeme, lexeme, startIndex, endIndex, startLine, startCol, endLine, endCol, op: opKind);
			}

			char ch2 = PeekChar().Value;
			throw ErrorAtCurrent(LexErrorKind.IllegalCharacter);
		}

		private Token.Token MakeToken(
			TokenType kind,
			string literal,
			string lexeme,
			int start,
			int end,
			int startLine,
			int startCol,
			int endLine,
			int endCol,
			Keyword? keyword = null,
			MetaKeyword? metaKeyword = null,
			Operator? op = null
		) {
			TokenSpan span = new TokenSpan(
				start, end,
				startLine, startCol,
				endLine, endCol,
				file
			);
			return new Token.Token(kind, literal, span, lexeme, keyword, metaKeyword, op);
		}

		private LexError ErrorAtCurrent(LexErrorKind kind) {
			TokenSpan span = new TokenSpan(
				index, index,
				line, column,
				line, column,
				file
			);
			return new LexError(kind, span);
		}

		private LexError ErrorAtSpanStart(LexErrorKind kind, int start, int startLine, int startCol) {
			TokenSpan span = new TokenSpan(
				start, index,
				startLine, startCol,
				line, column,
				file
			);
			return new LexError(kind, span);
		}

		private LexError ErrorSpan(
			LexErrorKind kind,
			int start,
			int startLine,
			int startCol,
			int end,
			int endLine,
			int endCol
		) {
			TokenSpan span = new TokenSpan(
				start, end,
				startLine, startCol,
				endLine, endCol,
				file
			);
			return new LexError(kind, span);
		}

		private bool IsEof() {
			return index >= input.Length;
		}

		private string Slice(int start, int end) {
			return input.Substring(start, end - start);
		}

		private bool StartsWith(string s) {
			if (index + s.Length > input.Length) return false;
			return input.Substring(index, s.Length) == s;
		}

		private char? PeekChar() {
			if (IsEof()) return null;
			return input[index];
		}

		private char? PeekNthChar(int n) {
			int pos = index + n;
			if (pos >= input.Length) return null;

			int currentPos = index;
			int charCount = 0;
			while (currentPos < input.Length && charCount < n) {
				currentPos++;
				charCount++;
			}
			if (currentPos >= input.Length) return null;
			return input[currentPos];
		}

		private void BumpOne() {
			char? ch = PeekChar();
			if (!ch.HasValue) return;

			index++;
			if (ch.Value == '\n') {
				line++;
				column = 1;
			} else {
				column++;
			}
		}

		private void BumpN(int nBytes) {
			int target = Math.Min(index + nBytes, input.Length);
			while (index < target) {
				char ch = input[index];
				index++;
				if (ch == '\n') {
					line++;
					column = 1;
				} else {
					column++;
				}
			}
		}

		private static bool IsWhitespace(char ch) {
			return ch == ' ' || ch == '\t' || ch == '\n';
		}

		private static bool IsIdentStart(char ch) {
			return ch == '_' || (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z');
		}

		private static bool IsIdentPart(char ch) {
			return IsIdentStart(ch) || (ch >= '0' && ch <= '9') || ch == '$';
		}

		private static bool DigitInRadix(char ch, int radix) {
			return radix switch {
				2 => ch == '0' || ch == '1',
				8 => ch >= '0' && ch <= '7',
				10 => ch >= '0' && ch <= '9',
				16 => (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f') || (ch >= 'A' && ch <= 'F'),
				_ => false
			};
		}

		private static bool IsAllUpperOrUnderscore(string s) {
			if (string.IsNullOrEmpty(s)) return false;
			return s.All(c => (c >= 'A' && c <= 'Z') || c == '_');
		}

		private static bool IsForbiddenControl(char ch) {
			int cp = ch;
			return (cp < 0x20 && ch != '\t' && ch != '\n')
				|| cp == 0x7F
				|| cp == 0x00A0
				|| cp == 0xFEFF
				|| (cp >= 0x200B && cp <= 0x200F)
				|| (cp >= 0x202A && cp <= 0x202E)
				|| (cp >= 0x2060 && cp <= 0x2064)
				|| cp == 0x206F;
		}

		private static bool IsValidEscape(char ch) {
			return ch == 'n' || ch == 'r' || ch == 't' || ch == '"' || ch == '\'' || ch == '\\' || ch == '0';
		}
	}
}
