package semantic

import "compiler/src/tokens"

// evalTypeMetadataTok resolves metadata using a token type.
// Supported names: MIN, MAX, BITS, BYTES
func evalTypeMetadataTok(tt tokens.TokenType, name string) (any, bool) {
	bits := func(t tokens.TokenType) (int64, bool) {
		switch t {
		case tokens.TokenI8, tokens.TokenU8, tokens.TokenByte, tokens.TokenBool, tokens.TokenBit:
			return 8, true
		case tokens.TokenI16, tokens.TokenU16:
			return 16, true
		case tokens.TokenI32, tokens.TokenU32, tokens.TokenF32:
			return 32, true
		case tokens.TokenI64, tokens.TokenU64, tokens.TokenF64:
			return 64, true
		}
		return 0, false
	}
	bytes := func(t tokens.TokenType) (int64, bool) {
		if b, ok := bits(t); ok {
			return b / 8, true
		}
		return 0, false
	}
	min := func(t tokens.TokenType) (any, bool) {
		switch t {
		case tokens.TokenI8:
			return int64(-128), true
		case tokens.TokenI16:
			return int64(-32768), true
		case tokens.TokenI32:
			return int64(-2147483648), true
		case tokens.TokenI64:
			return int64(-9223372036854775808), true
		case tokens.TokenU8, tokens.TokenByte, tokens.TokenU16, tokens.TokenU32, tokens.TokenU64:
			return int64(0), true
		case tokens.TokenF32:
			return -3.4028234663852886e+38, true
		case tokens.TokenF64:
			return -1.7976931348623157e+308, true
		}
		return nil, false
	}
	max := func(t tokens.TokenType) (any, bool) {
		switch t {
		case tokens.TokenI8:
			return int64(127), true
		case tokens.TokenI16:
			return int64(32767), true
		case tokens.TokenI32:
			return int64(2147483647), true
		case tokens.TokenI64:
			return int64(9223372036854775807), true
		case tokens.TokenU8, tokens.TokenByte:
			return int64(255), true
		case tokens.TokenU16:
			return int64(65535), true
		case tokens.TokenU32:
			return int64(4294967295), true
		case tokens.TokenU64:
			return 1.8446744073709552e+19, true
		case tokens.TokenF32:
			return 3.4028234663852886e+38, true
		case tokens.TokenF64:
			return 1.7976931348623157e+308, true
		}
		return nil, false
	}
	switch name {
	case "BITS":
		if b, ok := bits(tt); ok {
			return b, true
		}
	case "BYTES":
		if by, ok := bytes(tt); ok {
			return by, true
		}
	case "MIN":
		if v, ok := min(tt); ok {
			return v, true
		}
	case "MAX":
		if v, ok := max(tt); ok {
			return v, true
		}
	}
	return nil, false
}
