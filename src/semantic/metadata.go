package semantic

import (
	"compiler/src/ast"
	"compiler/src/tokens"
	"fmt"
)

// MetadataAccess handles all metadata access operations using pre-computed type information
func MetadataAccess(object ast.Expr, memberName string, env map[string]any, globals map[string]any, module *Scope) (any, error) {
	// Convert member name to metadata token
	memberToken := tokens.NameToMetadataToken(memberName)
	if memberToken == tokens.TokenInvalid {
		return nil, rt(object.Span(), fmt.Sprintf("unknown metadata %s", memberName), fmt.Sprintf("metadata accessor requires a valid metadata token. Unknown: %s", memberName))
	}

	// Use pre-computed type information from the type checker
	if currentTypes == nil {
		return nil, rt(object.Span(), "type table is null - type checker may not have been called", "ensure type checking is enabled")
	}
	// Debug: log what we're looking for
	if currentTypes != nil {
		// For metadata access, we need to look up the type info of the object, not the entire expression
		if memberAccess, ok := object.(*ast.MemberAccessExpr); ok {
			if typeInfo, ok := currentTypes.NodeToTypeInfo[memberAccess.Object]; ok {
				return getMetadataFromTypeInfo(typeInfo, memberToken, object, env, globals, module)
			}
		}
		// For direct object access (like variable references)
		if typeInfo, ok := currentTypes.NodeToTypeInfo[object]; ok {
			return getMetadataFromTypeInfo(typeInfo, memberToken, object, env, globals, module)
		}
		// Debug: check what's in NodeToType
		if typeName, ok := currentTypes.NodeToType[object]; ok {
			return nil, rt(object.Span(), fmt.Sprintf("found type '%s' but no type info for metadata %s", typeName, memberName), "type checker may not have populated type information")
		}
		return nil, rt(object.Span(), "object not found in type table", "type checker may not have processed this variable")
	}

	// Fallback: try to get type information from builtin type name
	if objId, ok := object.(*ast.IdentifierExpr); ok {
		// If left is a builtin type token, use token-based resolution
		tt := NameToTokenType(objId.Name)
		if tt != tokens.TokenInvalid {
			typeInfo := Type{Base: tt, ArrayDepth: 0, Nullable: false}
			return getMetadataFromTypeInfo(typeInfo, memberToken, object, env, globals, module)
		}
		return nil, rt(object.Span(), fmt.Sprintf("unknown metadata %s for type %s", memberName, objId.Name), "available type metadata: TYPE, KIND, MIN, MAX, BITS, BYTES, NULLABLE, HASHABLE, COMPARABLE")
	}

	return nil, rt(object.Span(), "left of '::' must be a type name or expression with known type", "metadata access requires type information from the type checker")
}

// getMetadataFromTypeInfo resolves metadata using pre-computed type information
func getMetadataFromTypeInfo(typeInfo Type, memberToken tokens.TokenType, object ast.Expr, env map[string]any, globals map[string]any, module *Scope) (any, error) {
	switch memberToken {
	case tokens.TokenTypeOf:
		if v, ok := getTypeName(typeInfo.Base); ok {
			return v, nil
		}
	case tokens.TokenKind:
		if v, ok := getTypeKind(typeInfo.Base); ok {
			return v, nil
		}
	case tokens.TokenNullable:
		if v, ok := getTypeNullable(typeInfo.Base); ok {
			return v, nil
		}
	case tokens.TokenHashable:
		if v, ok := getTypeHashable(typeInfo.Base); ok {
			return v, nil
		}
	case tokens.TokenComparable:
		if v, ok := getTypeComparable(typeInfo.Base); ok {
			return v, nil
		}
	case tokens.TokenBits:
		if v, ok := getTypeBits(typeInfo.Base); ok {
			return v, nil
		}
	case tokens.TokenBytes:
		if v, ok := getTypeBytes(typeInfo.Base); ok {
			return v, nil
		}
	case tokens.TokenMin:
		if v, ok := getTypeMin(typeInfo.Base); ok {
			return v, nil
		}
	case tokens.TokenMax:
		if v, ok := getTypeMax(typeInfo.Base); ok {
			return v, nil
		}
	case tokens.TokenLength:
		// For arrays, we need to evaluate the runtime value to get length
		if typeInfo.IsArray() {
			obj, err := EvalExpr(object, env, globals, module)
			if err != nil {
				return nil, err
			}
			if arr, ok := derefIfPtr(obj).([]any); ok {
				return int64(len(arr)), nil
			}
			return nil, rt(object.Span(), "expected array instance for LENGTH metadata", "LENGTH is only available for array instances")
		}
		// For strings, we need to evaluate the runtime value to get length
		if typeInfo.Base == tokens.TokenString {
			obj, err := EvalExpr(object, env, globals, module)
			if err != nil {
				return nil, err
			}
			if str, ok := derefIfPtr(obj).(string); ok {
				return int64(len([]rune(str))), nil
			}
			return nil, rt(object.Span(), "expected string instance for LENGTH metadata", "LENGTH is only available for string instances")
		}
		return nil, rt(object.Span(), "LENGTH metadata not available for this type", "LENGTH is only available for arrays and strings")
	default:
		return nil, rt(object.Span(), fmt.Sprintf("unknown metadata for type"), "metadata not supported for this type")
	}

	// If we get here, the metadata was not found
	return nil, rt(object.Span(), fmt.Sprintf("metadata %s not available for this type", tokens.TokenTypeName(memberToken)), "check available metadata for this type")
}

// Helper functions for type metadata (kept for backward compatibility)

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
	default:
		return 0, false
	}
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
		return int8(-128), true
	case tokens.TokenI16:
		return int16(-32768), true
	case tokens.TokenI32:
		return int32(-2147483648), true
	case tokens.TokenI64:
		return int64(-9223372036854775808), true
	case tokens.TokenU8:
		return uint8(0), true
	case tokens.TokenU16:
		return uint16(0), true
	case tokens.TokenU32:
		return uint32(0), true
	case tokens.TokenU64:
		return uint64(0), true
	case tokens.TokenF32:
		return float32(-3.4028234663852886e+38), true
	case tokens.TokenF64:
		return -1.7976931348623157e+308, true
	case tokens.TokenByte:
		return byte(0), true
	}
	return nil, false
}

func getTypeMax(tt tokens.TokenType) (any, bool) {
	switch tt {
	case tokens.TokenI8:
		return int8(127), true
	case tokens.TokenI16:
		return int16(32767), true
	case tokens.TokenI32:
		return int32(2147483647), true
	case tokens.TokenI64:
		return int64(9223372036854775807), true
	case tokens.TokenU8:
		return uint8(255), true
	case tokens.TokenU16:
		return uint16(65535), true
	case tokens.TokenU32:
		return uint32(4294967295), true
	case tokens.TokenU64:
		return uint64(18446744073709551615), true
	case tokens.TokenF32:
		return float32(3.4028234663852886e+38), true
	case tokens.TokenF64:
		return 1.7976931348623157e+308, true
	case tokens.TokenByte:
		return byte(1), true

	}
	return nil, false
}

// Helper functions for new type metadata

// getTypeName returns the name of a type as a string
func getTypeName(tt tokens.TokenType) (string, bool) {
	return tokens.TokenTypeName(tt), true
}

// getTypeKind returns the category/kind of a type
func getTypeKind(tt tokens.TokenType) (string, bool) {
	switch tt {
	case tokens.TokenI8, tokens.TokenI16, tokens.TokenI32, tokens.TokenI64,
		tokens.TokenU8, tokens.TokenU16, tokens.TokenU32, tokens.TokenU64,
		tokens.TokenF32, tokens.TokenF64, tokens.TokenBool, tokens.TokenBit,
		tokens.TokenByte, tokens.TokenString:
		return "primitive", true
	case tokens.TokenVoid:
		return "void", true
	default:
		return "unknown", true
	}
}

// getTypeNullable returns whether a type supports null values
func getTypeNullable(tt tokens.TokenType) (bool, bool) {
	return true, true
}

// getTypeHashable returns whether a type can be used as a hash key
func getTypeHashable(tt tokens.TokenType) (bool, bool) {
	switch tt {
	case tokens.TokenI8, tokens.TokenI16, tokens.TokenI32, tokens.TokenI64,
		tokens.TokenU8, tokens.TokenU16, tokens.TokenU32, tokens.TokenU64,
		tokens.TokenBool, tokens.TokenBit, tokens.TokenByte, tokens.TokenString:
		return true, true
	case tokens.TokenF32, tokens.TokenF64:
		return false, true
	case tokens.TokenVoid:
		return false, true
	default:
		return false, true
	}
}

// getTypeComparable returns whether a type supports comparison operations
func getTypeComparable(tt tokens.TokenType) (bool, bool) {
	switch tt {
	case tokens.TokenI8, tokens.TokenI16, tokens.TokenI32, tokens.TokenI64,
		tokens.TokenU8, tokens.TokenU16, tokens.TokenU32, tokens.TokenU64,
		tokens.TokenF32, tokens.TokenF64, tokens.TokenBool, tokens.TokenBit,
		tokens.TokenByte, tokens.TokenString:
		return true, true
	case tokens.TokenVoid:
		return false, true
	default:
		return false, true
	}
}
