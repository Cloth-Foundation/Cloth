package semantic

import (
	"compiler/src/ast"
	"compiler/src/tokens"
	"fmt"
)

// MetadataAccess handles all metadata access operations (Type::NAME or instance::NAME)
func MetadataAccess(object ast.Expr, memberName string, env map[string]any, globals map[string]any, module *Scope) (any, error) {
	// Convert member name to metadata token
	memberToken := tokens.NameToMetadataToken(memberName)
	if memberToken == tokens.TokenInvalid {
		return nil, fmt.Errorf("unknown metadata %s", memberName)
	}

	// Try to evaluate the object as an expression first (for instance metadata)
	obj, err := EvalExpr(object, env, globals, module)
	if err == nil {
		// Check if it's an array instance
		if arr, ok := derefIfPtr(obj).([]any); ok {
			if v, ok := evalArrayInstanceMetadata(arr, memberToken); ok {
				return v, nil
			}
			return nil, fmt.Errorf("unknown metadata %s for array", memberName)
		}
		// Check if it's a string instance
		if str, ok := derefIfPtr(obj).(string); ok {
			if v, ok := evalStringInstanceMetadata(str, memberToken); ok {
				return v, nil
			}
			return nil, fmt.Errorf("unknown metadata %s for string", memberName)
		}
	}

	// Fallback to type metadata (for builtin types)
	if objId, ok := object.(*ast.IdentifierExpr); ok {
		// If left is a builtin type token, use token-based resolution
		tt := NameToTokenType(objId.Name)
		if tt != tokens.TokenInvalid {
			if v, ok := evalTypeMetadata(tt, memberToken); ok {
				return v, nil
			}
		}
		return nil, fmt.Errorf("unknown metadata %s for type %s", memberName, objId.Name)
	}

	return nil, fmt.Errorf("left of '::' must be a type name or array instance")
}

// evalTypeMetadata resolves metadata for builtin types using token types
func evalTypeMetadata(tt tokens.TokenType, memberToken tokens.TokenType) (any, bool) {
	switch memberToken {
	case tokens.TokenBits:
		return getTypeBits(tt)
	case tokens.TokenBytes:
		return getTypeBytes(tt)
	case tokens.TokenMin:
		return getTypeMin(tt)
	case tokens.TokenMax:
		return getTypeMax(tt)
	default:
		return nil, false
	}
}

// evalArrayInstanceMetadata handles metadata access for runtime array instances
func evalArrayInstanceMetadata(arr []any, memberToken tokens.TokenType) (any, bool) {
	switch memberToken {
	case tokens.TokenLength:
		return int64(len(arr)), true
	default:
		return nil, false
	}
}

// Helper functions for type metadata
func getTypeBits(tt tokens.TokenType) (int64, bool) {
	switch tt {
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

func getTypeBytes(tt tokens.TokenType) (int64, bool) {
	if b, ok := getTypeBits(tt); ok {
		return b / 8, true
	}
	return 0, false
}

func getTypeMin(tt tokens.TokenType) (any, bool) {
	switch tt {
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

func getTypeMax(tt tokens.TokenType) (any, bool) {
	switch tt {
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

// evalStringInstanceMetadata resolves metadata for string instances
func evalStringInstanceMetadata(str string, memberToken tokens.TokenType) (any, bool) {
	switch memberToken {
	case tokens.TokenLength:
		// Return the number of runes (Unicode code points) in the string
		return int64(len([]rune(str))), true
	case tokens.TokenBits:
		// Return the number of bits needed to represent the string
		return int64(len(str) * 8), true
	case tokens.TokenBytes:
		// Return the number of bytes in the string
		return int64(len(str)), true
	}
	return nil, false
}
