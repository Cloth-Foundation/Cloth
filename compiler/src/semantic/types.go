package semantic

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

func IsIntegerType(t string) bool {
	switch t {
	case "i8", "i16", "i32", "i64", "u8", "u16", "u32", "u64", "byte", "bit":
		return true
	}
	return false
}

func IsFloatType(t string) bool { return t == "f16" || t == "f32" || t == "f64" }

func IsNumericType(t string) bool { return IsIntegerType(t) || IsFloatType(t) }

// TypeTable maps AST nodes to resolved type names.
type TypeTable struct {
	NodeToType map[any]string
}

func NewTypeTable() *TypeTable { return &TypeTable{NodeToType: map[any]string{}} }
