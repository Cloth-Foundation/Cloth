package parser

import "compiler/src/tokens"

// Pratt parser precedence levels
const (
	precLowest   = 1
	precAssign   = 2  // =, +=, -=, *=, /=, %=
	precOr       = 3  // ||
	precAnd      = 4  // &&
	precBitOr    = 5  // |
	precBitXor   = 6  // ^
	precBitAnd   = 7  // &
	precEquality = 8  // ==, !=
	precCompare  = 9  // <, <=, >, >=
	precRange    = 10 // .., ..=
	precShift    = 11 // <<, >>
	precAdd      = 12 // +, -
	precMul      = 13 // *, /, %
	precPrefix   = 14
	precPostfix  = 15 // ++, --
)

func precedenceFor(tt tokens.TokenType) int {
	switch tt {
	case tokens.TokenEqual, tokens.TokenPlusEqual, tokens.TokenMinusEqual, tokens.TokenStarEqual, tokens.TokenSlashEqual, tokens.TokenPercentEqual:
		return precAssign
	case tokens.TokenOr:
		return precOr
	case tokens.TokenAnd:
		return precAnd
	case tokens.TokenBitOr:
		return precBitOr
	case tokens.TokenBitXor:
		return precBitXor
	case tokens.TokenBitAnd:
		return precBitAnd
	case tokens.TokenDoubleEqual, tokens.TokenNotEqual:
		return precEquality
	case tokens.TokenLess, tokens.TokenLessEqual, tokens.TokenGreater, tokens.TokenGreaterEqual:
		return precCompare
	case tokens.TokenRange, tokens.TokenRangeInclusive:
		return precRange
	case tokens.TokenShiftLeft, tokens.TokenShiftRight:
		return precShift
	case tokens.TokenPlus, tokens.TokenMinus:
		return precAdd
	case tokens.TokenStar, tokens.TokenSlash, tokens.TokenPercent:
		return precMul
	case tokens.TokenPlusPlus, tokens.TokenMinusMinus:
		return precPostfix
	}
	return 0
}
