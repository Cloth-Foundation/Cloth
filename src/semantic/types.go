package semantic

import (
	"compiler/src/tokens"
	"strings"
)

// TypeEnv holds builtin types and user-defined type registry (future).
type TypeEnv struct {
	builtins map[string]bool
}

func NewTypeEnv() *TypeEnv {
	b := map[string]bool{
		"void":   true,
		"byte":   true,
		"bool":   true,
		"bit":    true,
		"char":   true,
		"string": true,
		"f16":    true, "f32": true, "f64": true,
		"i8": true, "i16": true, "i32": true, "i64": true,
		"u8": true, "u16": true, "u32": true, "u64": true,
	}
	return &TypeEnv{builtins: b}
}

func (e *TypeEnv) IsBuiltin(name string) bool { return e.builtins[name] }

// NameToTokenType maps a builtin type name to its token type; non-builtin returns TokenInvalid.
func NameToTokenType(name string) tokens.TokenType {
	switch name {
	case "void":
		return tokens.TokenVoid
	case "byte":
		return tokens.TokenByte
	case "bool":
		return tokens.TokenBool
	case "bit":
		return tokens.TokenBit
	case "char":
		return tokens.TokenChar
	case "string":
		return tokens.TokenString
	case "f16":
		return tokens.TokenF16
	case "f32":
		return tokens.TokenF32
	case "f64":
		return tokens.TokenF64
	case "i8":
		return tokens.TokenI8
	case "i16":
		return tokens.TokenI16
	case "i32":
		return tokens.TokenI32
	case "i64":
		return tokens.TokenI64
	case "u8":
		return tokens.TokenU8
	case "u16":
		return tokens.TokenU16
	case "u32":
		return tokens.TokenU32
	case "u64":
		return tokens.TokenU64
	case "NaN":
		return tokens.TokenNaN
	default:
		return tokens.TokenInvalid
	}
}

// TokenTypeName returns the canonical builtin type name for a token; non-builtin returns empty string.
func TokenTypeName(tt tokens.TokenType) string {
	switch tt {
	case tokens.TokenVoid:
		return "void"
	case tokens.TokenByte:
		return "byte"
	case tokens.TokenBool:
		return "bool"
	case tokens.TokenBit:
		return "bit"
	case tokens.TokenChar:
		return "char"
	case tokens.TokenString:
		return "string"
	case tokens.TokenF16:
		return "f16"
	case tokens.TokenF32:
		return "f32"
	case tokens.TokenF64:
		return "f64"
	case tokens.TokenI8:
		return "i8"
	case tokens.TokenI16:
		return "i16"
	case tokens.TokenI32:
		return "i32"
	case tokens.TokenI64:
		return "i64"
	case tokens.TokenU8:
		return "u8"
	case tokens.TokenU16:
		return "u16"
	case tokens.TokenU32:
		return "u32"
	case tokens.TokenU64:
		return "u64"
	case tokens.TokenNaN:
		return "NaN"
	default:
		return ""
	}
}

func IsIntegerToken(tt tokens.TokenType) bool {
	switch tt {
	case tokens.TokenI8, tokens.TokenI16, tokens.TokenI32, tokens.TokenI64,
		tokens.TokenU8, tokens.TokenU16, tokens.TokenU32, tokens.TokenU64,
		tokens.TokenByte, tokens.TokenBit:
		return true
	default:
		return false
	}
}

func IsFloatToken(tt tokens.TokenType) bool {
	return tt == tokens.TokenF16 || tt == tokens.TokenF32 || tt == tokens.TokenF64 || tt == tokens.TokenNaN
}

func IsNumericToken(tt tokens.TokenType) bool { return IsIntegerToken(tt) || IsFloatToken(tt) }

// IsIntegerType Backwards-compat string helpers during migration
func IsIntegerType(name string) bool { return IsIntegerToken(NameToTokenType(name)) }
func IsFloatType(name string) bool   { return IsFloatToken(NameToTokenType(name)) }
func IsNumericType(name string) bool { return IsNumericToken(NameToTokenType(name)) }

// defaultFloatForInt returns the default float type name for a given integer type name.
func defaultFloatForInt(intType string) string {
	tt := NameToTokenType(intType)
	switch tt {
	case tokens.TokenI8, tokens.TokenU8, tokens.TokenI16, tokens.TokenU16:
		return TokenTypeName(tokens.TokenF16)
	case tokens.TokenI32, tokens.TokenU32:
		return TokenTypeName(tokens.TokenF32)
	case tokens.TokenI64, tokens.TokenU64:
		return TokenTypeName(tokens.TokenF64)
	default:
		// already float or unknown -> choose f64 as safest default
		return TokenTypeName(tokens.TokenF64)
	}
}

// TypeTable maps AST nodes to resolved type names.
// Type represents a parsed type with metadata
type Type struct {
	Base       tokens.TokenType // Base token type (TokenI32, TokenString, etc.)
	ArrayDepth int              // Number of [] prefixes (0 = not array, 1 = [], 2 = [][], etc.)
	Nullable   bool             // Whether type is nullable (has ? suffix)
}

// TypeTable maps AST nodes to their resolved types
type TypeTable struct {
	NodeToType     map[any]string // Keep for backward compatibility
	NodeToTypeInfo map[any]Type   // New type info mapping
}

func NewTypeTable() *TypeTable {
	return &TypeTable{
		NodeToType:     map[any]string{},
		NodeToTypeInfo: map[any]Type{},
	}
}

// ParseType parses a type string into a Type struct
func ParseType(typeName string) Type {
	depth, base, nullable := ParseTypeString(typeName)
	return Type{
		Base:       base,
		ArrayDepth: depth,
		Nullable:   nullable,
	}
}

// TypeToString converts a Type back to string representation
func (t Type) String() string {
	result := ""
	for i := 0; i < t.ArrayDepth; i++ {
		result += "[]"
	}
	result += TokenTypeName(t.Base)
	if t.Nullable {
		result += "?"
	}
	return result
}

// IsArray returns true if this type is an array type
func (t Type) IsArray() bool {
	return t.ArrayDepth > 0
}

// GetElementType returns the element type of an array (reduces array depth by 1)
func (t Type) GetElementType() Type {
	if t.ArrayDepth > 0 {
		return Type{
			Base:       t.Base,
			ArrayDepth: t.ArrayDepth - 1,
			Nullable:   t.Nullable,
		}
	}
	return t
}

// ParseTypeString parses a simple type string like "[]i32", "[]string", "i32?" into components.
// Returns (arrayDepth, baseToken, isNullable).
// If base is not a builtin token, returns TokenInvalid as base.
func ParseTypeString(typeName string) (int, tokens.TokenType, bool) {
	s := typeName
	depth := 0
	for strings.HasPrefix(s, "[]") {
		depth++
		s = s[2:]
	}
	nullable := false
	if strings.HasSuffix(s, "?") {
		nullable = true
		s = strings.TrimSuffix(s, "?")
	}
	base := NameToTokenType(s)
	return depth, base, nullable
}
