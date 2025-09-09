package lexer

import (
	"compiler/src/tokens"
	"unicode"
	"unicode/utf8"
)

type Lexer struct {
	source string
	file   string
	pos    int
	line   uint64
	col    uint64

	lookahead *tokens.Token
}

func New(source string, fileName string) *Lexer {
	l := &Lexer{source: source, file: fileName, pos: 0, line: 1, col: 1}
	// Skip UTF-8 BOM if present
	if len(source) >= 3 && source[0] == 0xEF && source[1] == 0xBB && source[2] == 0xBF {
		l.pos = 3
	}
	return l
}

func (l *Lexer) Eof() bool { return l.isAtEnd() }

// Peek returns the next token without consuming it
func (l *Lexer) Peek() tokens.Token {
	if l.lookahead == nil {
		t := l.scanToken()
		l.lookahead = &t
	}
	return *l.lookahead
}

// Next consumes and returns the next token
func (l *Lexer) Next() tokens.Token {
	if l.lookahead != nil {
		t := *l.lookahead
		l.lookahead = nil
		return t
	}
	return l.scanToken()
}

// TokenizeAll tokenizes all input and appends an EOF token
func (l *Lexer) TokenizeAll() []tokens.Token {
	var out []tokens.Token
	for !l.isAtEnd() {
		out = append(out, l.scanToken())
	}
	sp := tokens.TokenSpan{File: l.file, StartLine: l.line, StartColumn: l.col, EndLine: l.line, EndColumn: l.col}
	out = append(out, tokens.NewToken(tokens.TokenEndOfFile, "", sp, nil, tokens.CategoryEof))
	return out
}

// --- core helpers ---

func (l *Lexer) isAtEnd() bool { return l.pos >= len(l.source) }

func (l *Lexer) current() byte {
	if l.isAtEnd() {
		return 0
	}
	return l.source[l.pos]
}

func (l *Lexer) lookaheadBytes(n int) byte {
	if l.pos+n >= len(l.source) {
		return 0
	}
	return l.source[l.pos+n]
}

func (l *Lexer) advance() byte {
	c := l.current()
	if c == '\n' {
		l.line++
		l.col = 1
	} else {
		l.col++
	}
	l.pos++
	return c
}

func (l *Lexer) match(expected byte) bool {
	if l.isAtEnd() || l.source[l.pos] != expected {
		return false
	}
	l.advance()
	return true
}

// --- scanning ---

func (l *Lexer) scanToken() tokens.Token {
	l.skipWhitespaceAndComments()
	if l.isAtEnd() {
		sp := tokens.TokenSpan{File: l.file, StartLine: l.line, StartColumn: l.col, EndLine: l.line, EndColumn: l.col}
		return tokens.NewToken(tokens.TokenEndOfFile, "", sp, nil, tokens.CategoryEof)
	}

	startPos := l.pos
	startLine := l.line
	startCol := l.col

	// Unicode-aware identifier scanning
	if r, size := decodeUtf8At(l.source, l.pos); size > 0 && isIdentifierStartCp(r) {
		l.pos += size
		l.col++
		for {
			r2, sz := decodeUtf8At(l.source, l.pos)
			if sz == 0 || !isIdentifierPartCp(r2) {
				break
			}
			l.pos += sz
			l.col++
		}
		text := l.source[startPos:l.pos]
		if kw, ok := lookupKeyword(text); ok {
			return l.makeTokenFromRange(kw, startPos, startLine, startCol, nil)
		}
		return l.makeTokenFromRange(tokens.TokenIdentifier, startPos, startLine, startCol, text)
	}

	// Number scanning (no pre-advance)
	if isDecimalDigit(l.current()) {
		return l.scanNumber()
	}

	c := l.advance()
	switch c {
	case '\'':
		return l.scanCharLiteral()
	case '"':
		return l.scanString()
	default:
		// operators and punctuation handling, including multi-char
		l.pos--
		l.col--
		return l.scanOperatorOrPunctuation()
	}
}

func (l *Lexer) skipWhitespaceAndComments() {
	for {
		switch c := l.current(); c {
		case ' ', '\r', '\t', '\n':
			l.advance()
		case '#':
			if l.lookaheadBytes(1) == '|' {
				// multi-line comment starting with #|
				l.advance() // '#'
				l.advance() // '|'
				for !l.isAtEnd() {
					if l.current() == '#' && l.lookaheadBytes(1) == '|' {
						l.advance()
						l.advance()
						break
					}
					l.advance()
				}
			} else {
				// single-line comment starting with #
				for !l.isAtEnd() && l.current() != '\n' {
					l.advance()
				}
			}
		default:
			return
		}
	}
}

func (l *Lexer) scanIdentifierOrKeyword() tokens.Token {
	startPos := l.pos
	//startLine := l.line
	//startCol := l.col
	for {
		r, sz := decodeUtf8At(l.source, l.pos)
		if sz == 0 || !isIdentifierPartCp(r) {
			break
		}
		l.pos += sz
		l.col++
	}
	text := l.source[startPos:l.pos]
	if kw, ok := lookupKeyword(text); ok {
		return l.makeToken(kw, text, nil)
	}
	return l.makeToken(tokens.TokenIdentifier, text, text)
}

func (l *Lexer) scanNumber() tokens.Token {
	startPos := l.pos
	startLine := l.line
	startCol := l.col

	consumeUnderscoredDigits := func(isValidDigit func(byte) bool) {
		for {
			ch := l.current()
			if ch == '_' {
				l.advance()
				continue
			}
			if !isValidDigit(ch) {
				break
			}
			l.advance()
		}
	}

	stripUnderscores := func(s string) string {
		out := make([]byte, 0, len(s))
		for i := 0; i < len(s); i++ {
			if s[i] != '_' {
				out = append(out, s[i])
			}
		}
		return string(out)
	}

	// Base prefixes 0x / 0b / 0o
	if l.current() == '0' {
		b := l.lookaheadBytes(1)
		if b == 'x' || b == 'X' || b == 'b' || b == 'B' || b == 'o' || b == 'O' {
			baseCh := b
			l.advance() // '0'
			l.advance() // base char
			digitsStart := l.pos
			if baseCh == 'x' || baseCh == 'X' {
				consumeUnderscoredDigits(func(ch byte) bool { return isHexDigit(ch) })
			} else if baseCh == 'b' || baseCh == 'B' {
				consumeUnderscoredDigits(func(ch byte) bool { return ch == '0' || ch == '1' })
			} else { // 'o' or 'O'
				consumeUnderscoredDigits(func(ch byte) bool { return ch >= '0' && ch <= '7' })
			}
			digitsEnd := l.pos
			for isAlphaNum(l.current()) {
				l.advance()
			}
			digitsClean := stripUnderscores(l.source[digitsStart:digitsEnd])
			base := 8
			if baseCh == 'x' || baseCh == 'X' {
				base = 16
			} else if baseCh == 'b' || baseCh == 'B' {
				base = 2
			}
			lit := tokens.NumericLiteral{Digits: digitsClean, Base: base, IsFloat: false, Suffix: l.source[digitsEnd:l.pos]}
			return l.makeTokenFromRange(tokens.TokenNumber, startPos, startLine, startCol, lit)
		}
	}

	// Decimal or float with underscores
	consumeUnderscoredDigits(func(ch byte) bool { return isDecimalDigit(ch) })
	isFloat := false
	numericEnd := l.pos
	if l.current() == '.' && isDecimalDigit(l.lookaheadBytes(1)) {
		isFloat = true
		l.advance() // '.'
		consumeUnderscoredDigits(func(ch byte) bool { return isDecimalDigit(ch) })
		numericEnd = l.pos
	}
	// Optional scientific exponent (e.g., 1e10, 1.2E-3). Only for decimal numbers
	if l.current() == 'e' || l.current() == 'E' {
		isFloat = true
		l.advance() // 'e' or 'E'
		if l.current() == '+' || l.current() == '-' {
			l.advance()
		}
		consumeUnderscoredDigits(func(ch byte) bool { return isDecimalDigit(ch) })
		numericEnd = l.pos
	}
	for isAlphaNum(l.current()) {
		l.advance()
	}
	if isFloat {
		numericClean := stripUnderscores(l.source[startPos:numericEnd])
		lit := tokens.NumericLiteral{Digits: numericClean, Base: 10, IsFloat: true, Suffix: l.source[numericEnd:l.pos]}
		return l.makeTokenFromRange(tokens.TokenNumber, startPos, startLine, startCol, lit)
	}
	numericClean := stripUnderscores(l.source[startPos:numericEnd])
	lit := tokens.NumericLiteral{Digits: numericClean, Base: 10, IsFloat: false, Suffix: l.source[numericEnd:l.pos]}
	return l.makeTokenFromRange(tokens.TokenNumber, startPos, startLine, startCol, lit)
}

func (l *Lexer) scanString() tokens.Token {
	startPos := l.pos - 1 // include opening quote
	startLine := l.line
	startCol := l.col - 1
	value := make([]rune, 0)
	for !l.isAtEnd() {
		c := l.advance()
		if c == '"' {
			break
		}
		if c == '\\' {
			e := l.advance()
			switch e {
			case 'n':
				value = append(value, '\n')
			case 't':
				value = append(value, '\t')
			case 'r':
				value = append(value, '\r')
			case '\\':
				value = append(value, '\\')
			case '"':
				value = append(value, '"')
			default:
				value = append(value, rune(e))
			}
		} else {
			value = append(value, rune(c))
		}
	}
	return l.makeTokenFromRange(tokens.TokenString, startPos, startLine, startCol, string(value))
}

func (l *Lexer) scanCharLiteral() tokens.Token {
	startPos := l.pos - 1
	startLine := l.line
	startCol := l.col - 1
	c := l.advance()
	if c == '\\' {
		e := l.advance()
		switch e {
		case 'n':
			c = '\n'
		case 't':
			c = '\t'
		case 'r':
			c = '\r'
		case '\\':
			c = '\\'
		case '\'':
			c = '\''
		default:
			c = e
		}
	}
	if l.current() != '\'' {
		return l.makeInvalidToken("unterminated char", startPos, startLine, startCol)
	}
	l.advance() // closing quote
	return l.makeTokenFromRange(tokens.TokenChar, startPos, startLine, startCol, string([]byte{c}))
}

func (l *Lexer) scanOperatorOrPunctuation() tokens.Token {
	startPos := l.pos
	startLine := l.line
	startCol := l.col

	c := l.advance()
	two := func(next byte, twoType tokens.TokenType, oneType tokens.TokenType) tokens.Token {
		if l.current() == next {
			l.advance()
			return l.makeTokenFromRange(twoType, startPos, startLine, startCol, nil)
		}
		return l.makeTokenFromRange(oneType, startPos, startLine, startCol, nil)
	}

	switch c {
	case '+':
		if l.current() == '+' {
			l.advance()
			return l.makeTokenFromRange(tokens.TokenPlusPlus, startPos, startLine, startCol, nil)
		}

		if l.current() == '=' {
			l.advance()
			return l.makeTokenFromRange(tokens.TokenPlusEqual, startPos, startLine, startCol, nil)
		}

		return l.makeTokenFromRange(tokens.TokenPlus, startPos, startLine, startCol, nil)
	case '-':
		if l.current() == '>' {
			l.advance()
			return l.makeTokenFromRange(tokens.TokenArrow, startPos, startLine, startCol, nil)
		}

		if l.current() == '-' {
			l.advance()
			return l.makeTokenFromRange(tokens.TokenMinusMinus, startPos, startLine, startCol, nil)

		}

		if l.current() == '=' {
			l.advance()
			return l.makeTokenFromRange(tokens.TokenMinusEqual, startPos, startLine, startCol, nil)
		}

		return l.makeTokenFromRange(tokens.TokenMinus, startPos, startLine, startCol, nil)
	case '*':

		if l.current() == '=' {
			l.advance()
			return l.makeTokenFromRange(tokens.TokenStarEqual, startPos, startLine, startCol, nil)
		}

		return l.makeTokenFromRange(tokens.TokenStar, startPos, startLine, startCol, nil)
	case '/':

		if l.current() == '=' {
			l.advance()
			return l.makeTokenFromRange(tokens.TokenSlashEqual, startPos, startLine, startCol, nil)
		}

		return l.makeTokenFromRange(tokens.TokenSlash, startPos, startLine, startCol, nil)
	case '%':

		if l.current() == '=' {
			l.advance()
			return l.makeTokenFromRange(tokens.TokenPercentEqual, startPos, startLine, startCol, nil)
		}

		return l.makeTokenFromRange(tokens.TokenPercent, startPos, startLine, startCol, nil)
	case '!':
		return two('=', tokens.TokenNotEqual, tokens.TokenNot)
	case '=':
		return two('=', tokens.TokenDoubleEqual, tokens.TokenEqual)
	case '<':
		if l.current() == '<' {
			l.advance()
			return l.makeTokenFromRange(tokens.TokenShiftLeft, startPos, startLine, startCol, nil)
		}
		if l.current() == '=' {
			l.advance()
			return l.makeTokenFromRange(tokens.TokenLessEqual, startPos, startLine, startCol, nil)
		}
		return l.makeTokenFromRange(tokens.TokenLess, startPos, startLine, startCol, nil)
	case '>':
		if l.current() == '>' {
			l.advance()
			return l.makeTokenFromRange(tokens.TokenShiftRight, startPos, startLine, startCol, nil)
		}
		if l.current() == '=' {
			l.advance()
			return l.makeTokenFromRange(tokens.TokenGreaterEqual, startPos, startLine, startCol, nil)
		}
		return l.makeTokenFromRange(tokens.TokenGreater, startPos, startLine, startCol, nil)
	case '&':
		if l.current() == '&' {
			l.advance()
			return l.makeTokenFromRange(tokens.TokenAnd, startPos, startLine, startCol, nil)
		}
		return l.makeTokenFromRange(tokens.TokenBitAnd, startPos, startLine, startCol, nil)
	case '|':
		return l.makeTokenFromRange(tokens.TokenBitOr, startPos, startLine, startCol, nil)
	case '^':
		return l.makeTokenFromRange(tokens.TokenBitXor, startPos, startLine, startCol, nil)
	case '~':
		return l.makeTokenFromRange(tokens.TokenBitNot, startPos, startLine, startCol, nil)
	case '.':
		if l.current() == '.' {
			l.advance()
			if l.current() == '=' {
				l.advance()
				return l.makeTokenFromRange(tokens.TokenRangeInclusive, startPos, startLine, startCol, nil)
			}
			return l.makeTokenFromRange(tokens.TokenRange, startPos, startLine, startCol, nil)
		}
		return l.makeTokenFromRange(tokens.TokenDot, startPos, startLine, startCol, nil)
	case ':':
		if l.current() == ':' {
			l.advance()
			return l.makeTokenFromRange(tokens.TokenDoubleColon, startPos, startLine, startCol, nil)
		}
		return l.makeTokenFromRange(tokens.TokenColon, startPos, startLine, startCol, nil)
	case ';':
		return l.makeTokenFromRange(tokens.TokenSemicolon, startPos, startLine, startCol, nil)
	case ',':
		return l.makeTokenFromRange(tokens.TokenComma, startPos, startLine, startCol, nil)
	case '?':
		return l.makeTokenFromRange(tokens.TokenQuestion, startPos, startLine, startCol, nil)
	case '(':
		return l.makeTokenFromRange(tokens.TokenLParen, startPos, startLine, startCol, nil)
	case ')':
		return l.makeTokenFromRange(tokens.TokenRParen, startPos, startLine, startCol, nil)
	case '[':
		return l.makeTokenFromRange(tokens.TokenLBracket, startPos, startLine, startCol, nil)
	case ']':
		return l.makeTokenFromRange(tokens.TokenRBracket, startPos, startLine, startCol, nil)
	case '{':
		return l.makeTokenFromRange(tokens.TokenLBrace, startPos, startLine, startCol, nil)
	case '}':
		return l.makeTokenFromRange(tokens.TokenRBrace, startPos, startLine, startCol, nil)
	}
	return l.makeInvalidToken("unexpected character", startPos, startLine, startCol)
}

// --- builders ---

func (l *Lexer) makeToken(tt tokens.TokenType, lexeme string, value tokens.TokenValue) tokens.Token {
	sp := tokens.TokenSpan{File: l.file, StartLine: l.line, StartColumn: l.col, EndLine: l.line, EndColumn: l.col}
	return tokens.NewToken(tt, lexeme, sp, value, tokens.CategoryError)
}

func (l *Lexer) makeTokenFromRange(tt tokens.TokenType, startPos int, startLine, startCol uint64, value tokens.TokenValue) tokens.Token {
	lexeme := l.source[startPos:l.pos]
	sp := tokens.TokenSpan{File: l.file, StartLine: startLine, StartColumn: startCol, EndLine: l.line, EndColumn: l.col}
	return tokens.NewToken(tt, lexeme, sp, value, tokens.CategoryError)
}

func (l *Lexer) makeInvalidToken(message string, startPos int, startLine, startCol uint64) tokens.Token {
	lexeme := l.source[startPos:l.pos]
	sp := tokens.TokenSpan{File: l.file, StartLine: startLine, StartColumn: startCol, EndLine: l.line, EndColumn: l.col}
	return tokens.NewToken(tokens.TokenInvalid, lexeme, sp, message, tokens.CategoryError)
}

// --- keyword lookup ---

func lookupKeyword(text string) (tokens.TokenType, bool) {
	switch text {
	case "alias":
		return tokens.TokenAlias, true
	case "as":
		return tokens.TokenAs, true
	case "atomic":
		return tokens.TokenAtomic, true
	case "bit":
		return tokens.TokenBit, true
	case "bool":
		return tokens.TokenBool, true
	case "break":
		return tokens.TokenBreak, true
	case "case":
		return tokens.TokenCase, true
	case "class":
		return tokens.TokenClass, true
	case "continue":
		return tokens.TokenContinue, true
	case "default":
		return tokens.TokenDefault, true
	case "do":
		return tokens.TokenDo, true
	case "elif":
		return tokens.TokenElif, true
	case "else":
		return tokens.TokenElse, true
	case "enum":
		return tokens.TokenEnum, true
	case "fin":
		return tokens.TokenFin, true
	case "func":
		return tokens.TokenFunc, true
	case "if":
		return tokens.TokenIf, true
	case "import":
		return tokens.TokenImport, true
	case "in":
		return tokens.TokenIn, true
	case "let":
		return tokens.TokenLet, true
	case "loop":
		return tokens.TokenLoop, true
	case "mod":
		return tokens.TokenMod, true
	case "new":
		return tokens.TokenNew, true
	case "priv":
		return tokens.TokenPriv, true
	case "prot":
		return tokens.TokenProt, true
	case "pub":
		return tokens.TokenPub, true
	case "ret":
		return tokens.TokenRet, true
	case "rev":
		return tokens.TokenRev, true
	case "template":
		return tokens.TokenTemplate, true
	case "self":
		return tokens.TokenSelf, true
	case "override":
		return tokens.TokenOverride, true
	case "struct":
		return tokens.TokenStruct, true
	case "super":
		return tokens.TokenSuper, true
	case "switch":
		return tokens.TokenSwitch, true
	case "var":
		return tokens.TokenVar, true
	case "while":
		return tokens.TokenWhile, true
	// logical keywords
	case "and":
		return tokens.TokenAnd, true
	case "or":
		return tokens.TokenOr, true
	// builtins
	case "byte":
		return tokens.TokenByte, true
	case "f16":
		return tokens.TokenF16, true
	case "f32":
		return tokens.TokenF32, true
	case "f64":
		return tokens.TokenF64, true
	case "i8":
		return tokens.TokenI8, true
	case "i16":
		return tokens.TokenI16, true
	case "i32":
		return tokens.TokenI32, true
	case "i64":
		return tokens.TokenI64, true
	case "u8":
		return tokens.TokenU8, true
	case "u16":
		return tokens.TokenU16, true
	case "u32":
		return tokens.TokenU32, true
	case "u64":
		return tokens.TokenU64, true
	// literals
	case "true":
		return tokens.TokenTrue, true
	case "false":
		return tokens.TokenFalse, true
	case "null":
		return tokens.TokenNull, true
	}
	return 0, false
}

// --- char and classification helpers ---

func decodeUtf8At(s string, i int) (rune, int) {
	if i >= len(s) {
		return 0, 0
	}
	r, size := utf8.DecodeRuneInString(s[i:])
	if r == utf8.RuneError && size == 1 {
		return '\u007f', 1 // sentinel; still consume 1 byte
	}
	return r, size
}

func isIdentifierStartCp(r rune) bool {
	if r < 0x80 {
		return r == '_' || r == '$' || unicode.IsLetter(r)
	}
	return true
}

func isIdentifierPartCp(r rune) bool {
	if r < 0x80 {
		return r == '_' || r == '$' || unicode.IsLetter(r) || unicode.IsDigit(r)
	}
	return true
}

func isDecimalDigit(b byte) bool { return b >= '0' && b <= '9' }

func isHexDigit(b byte) bool {
	return (b >= '0' && b <= '9') || (b >= 'a' && b <= 'f') || (b >= 'A' && b <= 'F')
}

func isAlphaNum(b byte) bool {
	return (b >= '0' && b <= '9') || (b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z')
}
