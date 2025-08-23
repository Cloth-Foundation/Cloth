package tokens

import (
	"fmt"
)

// TokenType mirrors the C++ loom::TokenType
type TokenType uint16

const (
	// Literals
	TokenChar TokenType = iota
	TokenFalse
	TokenIdentifier
	TokenNull
	TokenNumber
	TokenString
	TokenTrue

	// Keywords
	TokenAlias
	TokenAs
	TokenAtomic
	TokenBit
	TokenBool
	TokenBreak
	TokenBuilder
	TokenCase
	TokenClass
	TokenConst
	TokenContinue
	TokenDefault
	TokenDo
	TokenElif
	TokenElse
	TokenEnum
	TokenFin
	TokenFor
	TokenFunc
	TokenIf
	TokenImport
	TokenIn
	TokenInternal
	TokenLet
	TokenLoop
	TokenMod
	TokenNew
	TokenPriv
	TokenProt
	TokenPub
	TokenRet
	TokenRev
	TokenSelf
	TokenStep
	TokenStruct
	TokenSuper
	TokenSwitch
	TokenThis
	TokenVar
	TokenWhile

	// Built-in Types
	TokenByte
	TokenF16
	TokenF32
	TokenF64
	TokenI8
	TokenI16
	TokenI32
	TokenI64
	TokenU8
	TokenU16
	TokenU32
	TokenU64

	// Operators
	TokenAnd
	TokenArrow
	TokenDoubleEqual
	TokenEqual
	TokenGreater
	TokenGreaterEqual
	TokenLess
	TokenLessEqual
	TokenMinus
	TokenNot
	TokenNotEqual
	TokenOr
	TokenPercent
	TokenPlus
	TokenRange
	TokenRangeInclusive
	TokenSlash
	TokenStar

	// Symbols
	TokenColon
	TokenComma
	TokenDoubleColon
	TokenDot
	TokenLBrace
	TokenLBracket
	TokenLParen
	TokenQuestion
	TokenRBrace
	TokenRBracket
	TokenRParen
	TokenSemicolon

	// Special
	TokenEndOfFile
	TokenInvalid
)

// TokenCategory mirrors loom::TokenCategory
type TokenCategory uint8

const (
	CategoryLiteral TokenCategory = iota
	CategoryKeyword
	CategoryOperator
	CategoryPunctuation
	CategoryIdentifier
	CategoryWhitespace
	CategoryComment
	CategoryError
	CategoryEof
)

// NumericLiteral preserves numeric metadata
type NumericLiteral struct {
	Digits  string
	Base    int
	IsFloat bool
	Suffix  string
}

// TokenValue value uses a tagged union style via Go interface{} with helpers
type TokenValue interface{}

// Token mirrors loom::Token
type Token struct {
	Type     TokenType
	Text     string
	Span     TokenSpan
	Value    TokenValue
	Category TokenCategory
}

// NewToken constructs a token; if category is CategoryError but type != TokenInvalid, auto-classify
func NewToken(typ TokenType, lexeme string, span TokenSpan, value TokenValue, category TokenCategory) Token {
	if category == CategoryError && typ != TokenInvalid {
		category = ClassifyTokenType(typ)
	}
	return Token{Type: typ, Text: lexeme, Span: span, Value: value, Category: category}
}

func (t Token) Is(typ TokenType) bool             { return t.Type == typ }
func (t Token) IsCategory(cat TokenCategory) bool { return t.Category == cat }

func (t Token) String() string {
	return fmt.Sprintf("Token {\n\ttype: %s \n\ttext: \"%s\" \n\tspan: %s \n\tcategory: %s \n\tvalue: %s\n}",
		TokenTypeName(t.Type), t.Text, t.Span.String(), TokenCategoryName(t.Category), tokenValueString(t.Value))
}

func tokenValueString(v TokenValue) string {
	switch val := v.(type) {
	case nil:
		return "none"
	case string:
		return fmt.Sprintf("\"%s\"", val)
	case int, int8, int16, int32, int64:
		return fmt.Sprintf("%d", val)
	case uint, uint8, uint16, uint32, uint64:
		return fmt.Sprintf("%d", val)
	case float32, float64:
		return fmt.Sprintf("%g", val)
	case bool:
		if val {
			return "true"
		}
		return "false"
	case NumericLiteral:
		return fmt.Sprintf("NumericLiteral{digits=\"%s\", base=%d, isFloat=%t, suffix=\"%s\"}", val.Digits, val.Base, val.IsFloat, val.Suffix)
	default:
		return fmt.Sprintf("%v", val)
	}
}

// ClassifyTokenType maps a TokenType to its category
func ClassifyTokenType(t TokenType) TokenCategory {
	switch t {
	// Literals
	case TokenChar, TokenFalse, TokenNull, TokenNumber, TokenString, TokenTrue:
		return CategoryLiteral
	// Keywords
	case TokenAlias, TokenAs, TokenAtomic, TokenBit, TokenBool, TokenBreak, TokenBuilder, TokenCase, TokenClass, TokenConst, TokenContinue, TokenDefault, TokenDo, TokenElif, TokenElse, TokenEnum, TokenFin, TokenFor, TokenFunc, TokenIf, TokenImport, TokenIn, TokenInternal, TokenLet, TokenLoop, TokenMod, TokenNew, TokenPriv, TokenProt, TokenPub, TokenRet, TokenRev, TokenSelf, TokenStep, TokenStruct, TokenSuper, TokenSwitch, TokenThis, TokenVar, TokenWhile:
		return CategoryKeyword
	// Built-in types -> treat as keywords
	case TokenByte, TokenF16, TokenF32, TokenF64, TokenI8, TokenI16, TokenI32, TokenI64, TokenU8, TokenU16, TokenU32, TokenU64:
		return CategoryKeyword
	// Operators
	case TokenAnd, TokenArrow, TokenDoubleEqual, TokenEqual, TokenGreater, TokenGreaterEqual, TokenLess, TokenLessEqual, TokenMinus, TokenNot, TokenNotEqual, TokenOr, TokenPercent, TokenPlus, TokenRange, TokenRangeInclusive, TokenSlash, TokenStar:
		return CategoryOperator
	// Punctuation
	case TokenColon, TokenComma, TokenDoubleColon, TokenDot, TokenLBrace, TokenLBracket, TokenLParen, TokenQuestion, TokenRBrace, TokenRBracket, TokenRParen, TokenSemicolon:
		return CategoryPunctuation
	case TokenIdentifier:
		return CategoryIdentifier
	case TokenEndOfFile:
		return CategoryEof
	case TokenInvalid:
		return CategoryError
	}
	return CategoryError
}

// TokenTypeName returns a stable string name for a token type
func TokenTypeName(t TokenType) string {
	switch t {
	case TokenChar:
		return "Char"
	case TokenFalse:
		return "False"
	case TokenIdentifier:
		return "Identifier"
	case TokenNull:
		return "Null"
	case TokenNumber:
		return "Number"
	case TokenString:
		return "String"
	case TokenTrue:
		return "True"
	case TokenAlias:
		return "Alias"
	case TokenAs:
		return "As"
	case TokenAtomic:
		return "Atomic"
	case TokenBit:
		return "Bit"
	case TokenBool:
		return "Bool"
	case TokenBreak:
		return "Break"
	case TokenBuilder:
		return "Builder"
	case TokenCase:
		return "Case"
	case TokenClass:
		return "Class"
	case TokenConst:
		return "Const"
	case TokenContinue:
		return "Continue"
	case TokenDefault:
		return "Default"
	case TokenDo:
		return "Do"
	case TokenElif:
		return "Elif"
	case TokenElse:
		return "Else"
	case TokenEnum:
		return "Enumerator"
	case TokenFin:
		return "Final"
	case TokenFor:
		return "For"
	case TokenFunc:
		return "Function"
	case TokenIf:
		return "If"
	case TokenImport:
		return "Import"
	case TokenIn:
		return "In"
	case TokenInternal:
		return "Internal"
	case TokenLet:
		return "Let"
	case TokenLoop:
		return "Loop"
	case TokenMod:
		return "Module"
	case TokenNew:
		return "New"
	case TokenPriv:
		return "Private"
	case TokenProt:
		return "Protected"
	case TokenPub:
		return "Public"
	case TokenRet:
		return "Return"
	case TokenRev:
		return "Reverse"
	case TokenSelf:
		return "Self"
	case TokenStep:
		return "Step"
	case TokenStruct:
		return "Structure"
	case TokenSuper:
		return "Super"
	case TokenSwitch:
		return "Switch"
	case TokenThis:
		return "This"
	case TokenVar:
		return "Variable"
	case TokenWhile:
		return "While"
	case TokenByte:
		return "Byte"
	case TokenF16:
		return "f16"
	case TokenF32:
		return "f32"
	case TokenF64:
		return "f64"
	case TokenI8:
		return "i8"
	case TokenI16:
		return "i16"
	case TokenI32:
		return "i32"
	case TokenI64:
		return "i64"
	case TokenU8:
		return "u8"
	case TokenU16:
		return "u16"
	case TokenU32:
		return "u32"
	case TokenU64:
		return "u64"
	case TokenAnd:
		return "And"
	case TokenArrow:
		return "Arrow"
	case TokenDoubleEqual:
		return "DoubleEqual"
	case TokenEqual:
		return "Equal"
	case TokenGreater:
		return "Greater"
	case TokenGreaterEqual:
		return "GreaterEqual"
	case TokenLess:
		return "Less"
	case TokenLessEqual:
		return "LessEqual"
	case TokenMinus:
		return "Minus"
	case TokenNot:
		return "Not"
	case TokenNotEqual:
		return "NotEqual"
	case TokenOr:
		return "Or"
	case TokenPercent:
		return "Percent"
	case TokenPlus:
		return "Plus"
	case TokenRange:
		return "Range"
	case TokenRangeInclusive:
		return "Range Inclusive"
	case TokenSlash:
		return "Slash"
	case TokenStar:
		return "Star"
	case TokenColon:
		return "Colon"
	case TokenComma:
		return "Comma"
	case TokenDoubleColon:
		return "DoubleColon"
	case TokenDot:
		return "Dot"
	case TokenLBrace:
		return "Left Brace"
	case TokenLBracket:
		return "Left Bracket"
	case TokenLParen:
		return "Left Paren"
	case TokenQuestion:
		return "Question"
	case TokenRBrace:
		return "Right Brace"
	case TokenRBracket:
		return "Right Bracket"
	case TokenRParen:
		return "Right Paren"
	case TokenSemicolon:
		return "Semicolon"
	case TokenEndOfFile:
		return "End Of File"
	case TokenInvalid:
		return "Invalid"
	}
	return "Unknown"
}

// TokenCategoryName returns string name for category
func TokenCategoryName(c TokenCategory) string {
	switch c {
	case CategoryLiteral:
		return "Literal"
	case CategoryKeyword:
		return "Keyword"
	case CategoryOperator:
		return "Operator"
	case CategoryPunctuation:
		return "Punctuation"
	case CategoryIdentifier:
		return "Identifier"
	case CategoryWhitespace:
		return "Whitespace"
	case CategoryComment:
		return "Comment"
	case CategoryError:
		return "Error"
	case CategoryEof:
		return "Eof"
	}
	return "Unknown"
}
