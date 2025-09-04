package semantic

import (
	"bufio"
	"compiler/src/ast"
	"compiler/src/runtime/arc"
	"compiler/src/tokens"
	"fmt"
	"os"
	"strconv"
	"strings"
)

// InjectBuiltins adds core builtin symbols to the provided scope so that
// parsing/binding/checking can reference them.
// 'print' accepts any single argument (empty type means unconstrained) and returns void.
func InjectBuiltins(scope *Scope) {
	_ = scope.Define(Symbol{
		Name: "print",
		Kind: SymFunc,
		Node: &ast.FuncDecl{
			Name:       "print",
			Params:     []ast.Parameter{{Name: "value", Type: ""}},
			ReturnType: "void",
		},
	})

	_ = scope.Define(Symbol{
		Name: "println",
		Kind: SymFunc,
		Node: &ast.FuncDecl{
			Name:       "println",
			Params:     []ast.Parameter{{Name: "value", Type: ""}},
			ReturnType: "void",
		},
	})

	_ = scope.Define(Symbol{
		Name: "printf",
		Kind: SymFunc,
		Node: &ast.FuncDecl{
			Name:       "printf",
			Params:     []ast.Parameter{{Name: "value", Type: ""}, {Name: "args", Type: "[]any"}},
			ReturnType: "void",
		},
	})

	_ = scope.Define(Symbol{
		Name: "exit",
		Kind: SymFunc,
		Node: &ast.FuncDecl{
			Name:   "exit",
			Params: []ast.Parameter{{Name: "code", Type: "i32"}},
		},
	})

	_ = scope.Define(Symbol{
		Name: "input",
		Kind: SymFunc,
		Node: &ast.FuncDecl{
			Name:       "input",
			Params:     []ast.Parameter{{Name: "prompt", Type: "string"}},
			ReturnType: "string",
		},
	})

	// range built-in
	_ = scope.Define(Symbol{
		Name: "range",
		Kind: SymFunc,
		Node: &ast.FuncDecl{
			Name:       "range",
			Params:     []ast.Parameter{{Name: "a", Type: "i32"}, {Name: "b", Type: "i32"}, {Name: "c", Type: "i32"}},
			ReturnType: "[]i32",
		},
	})

	// array constructor: array(type, size[, init]) -> []type (runtime uses []any)
	_ = scope.Define(Symbol{
		Name: "array",
		Kind: SymFunc,
		Node: &ast.FuncDecl{
			Name:       "array",
			Params:     []ast.Parameter{{Name: "type", Type: ""}, {Name: "size", Type: "i32"}, {Name: "init", Type: ""}},
			ReturnType: "[]any",
		},
	})

	// ARC helpers: weak, upgrade, refcount
	_ = scope.Define(Symbol{
		Name: "weak",
		Kind: SymFunc,
		Node: &ast.FuncDecl{
			Name:       "weak",
			Params:     []ast.Parameter{{Name: "x", Type: ""}},
			ReturnType: "any",
		},
	})
	_ = scope.Define(Symbol{
		Name: "upgrade",
		Kind: SymFunc,
		Node: &ast.FuncDecl{
			Name:       "upgrade",
			Params:     []ast.Parameter{{Name: "w", Type: ""}},
			ReturnType: "any",
		},
	})
	_ = scope.Define(Symbol{
		Name: "refcount",
		Kind: SymFunc,
		Node: &ast.FuncDecl{
			Name:       "refcount",
			Params:     []ast.Parameter{{Name: "x", Type: ""}},
			ReturnType: "i32",
		},
	})
}

// CallBuiltin executes a builtin if name matches, returning whether it handled the call.
// It evaluates arguments using evalExpr to support expressions as arguments.
func CallBuiltin(name string, args []ast.Expr, env map[string]any, globals map[string]any, module *Scope) (bool, any, error) {
	switch name {
	case "print":
		for _, a := range args {
			v, err := EvalExpr(a, env, globals, module)
			if err != nil {
				return true, nil, err
			}
			fmt.Print(ToString(v))
		}
		return true, nil, nil
	case "println":
		for _, a := range args {
			v, err := EvalExpr(a, env, globals, module)
			if err != nil {
				return true, nil, err
			}
			fmt.Println(ToString(v))
		}
		return true, nil, nil
	case "printf":
		return callPrintf(args, env, globals, module)
	case "input":
		return callInput(args, env, globals, module)
	case "range":
		return callRange(args, env, globals, module)
	case "array":
		return callArray(args, env, globals, module)
	case "weak":
		return callWeak(args, env, globals, module)
	case "upgrade":
		return callUpgrade(args, env, globals, module)
	case "refcount":
		return callRefcount(args, env, globals, module)
	case "exit":
		if len(args) == 0 {
			os.Exit(0)
		}
		if len(args) == 1 {
			v, err := EvalExpr(args[0], env, globals, module)
			if err != nil {
				return true, nil, err
			}
			if i, ok := v.(int64); ok {
				os.Exit(int(i))
			}
		}
		return true, nil, nil
	default:
		return false, nil, nil
	}
}

// Universal instance builtins (apply to any value), e.g., x.type()
func CallUniversalMethod(name string, recv any, args []ast.Expr, env map[string]any, globals map[string]any, module *Scope) (bool, any, error) {
	switch name {
	case "type":
		if len(args) != 0 {
			return true, nil, fmt.Errorf("type() takes no arguments")
		}
		return true, runtimeTypeName(recv), nil
	default:
		return false, nil, nil
	}
}

func runtimeTypeName(v any) string {
	switch v.(type) {
	case int64:
		return "i32" // default logical int width
	case float64:
		return "f64"
	case string:
		return "string"
	case bool:
		return "bool"
	case nil:
		return "null"
	default:
		return "object"
	}
}

func callPrintf(args []ast.Expr, env map[string]any, globals map[string]any, module *Scope) (bool, any, error) {
	if len(args) == 0 {
		return true, nil, nil
	}
	fmtVal, err := EvalExpr(args[0], env, globals, module)
	if err != nil {
		return true, nil, err
	}
	fmtStr := ToString(fmtVal)
	var values []any
	if len(args) >= 2 {
		if arrLit, ok := args[1].(*ast.ArrayLiteralExpr); ok {
			for _, el := range arrLit.Elements {
				v, err := EvalExpr(el, env, globals, module)
				if err != nil {
					return true, nil, err
				}
				values = append(values, v)
			}
		} else {
			v, err := EvalExpr(args[1], env, globals, module)
			if err != nil {
				return true, nil, err
			}
			if vs, ok := v.([]any); ok {
				values = append(values, vs...)
			} else if vsi, ok := v.([]interface{}); ok {
				for _, it := range vsi {
					values = append(values, it)
				}
			} else {
				values = append(values, v)
			}
		}
	}
	if len(args) > 2 {
		for _, a := range args[2:] {
			v, err := EvalExpr(a, env, globals, module)
			if err != nil {
				return true, nil, err
			}
			if vs, ok := v.([]any); ok {
				values = append(values, vs...)
			} else if vsi, ok := v.([]interface{}); ok {
				for _, it := range vsi {
					values = append(values, it)
				}
			} else {
				values = append(values, v)
			}
		}
	}
	// Format placeholders
	out := make([]rune, 0, len(fmtStr)+len(values)*4)
	runes := []rune(fmtStr)
	vi := 0
	for i := 0; i < len(runes); i++ {
		if runes[i] == '{' && i+1 < len(runes) && runes[i+1] == '}' {
			if vi < len(values) {
				out = append(out, []rune(ToString(values[vi]))...)
				vi++
				i++
				continue
			}
		}
		if runes[i] == '%' && i+1 < len(runes) {
			if runes[i+1] == '%' {
				out = append(out, '%')
				i++
				continue
			}
			if runes[i+1] == 's' {
				if vi < len(values) {
					out = append(out, []rune(ToString(values[vi]))...)
					vi++
					i++
					continue
				}
			}
		}
		out = append(out, runes[i])
	}
	fmt.Print(string(out))
	return true, nil, nil
}

func callInput(args []ast.Expr, env map[string]any, globals map[string]any, module *Scope) (bool, any, error) {
	if len(args) >= 1 {
		v, err := EvalExpr(args[0], env, globals, module)
		if err != nil {
			return true, nil, err
		}
		fmt.Print(ToString(v))
	}
	scanner := bufio.NewScanner(os.Stdin)
	if scanner.Scan() {
		return true, scanner.Text(), nil
	}
	if err := scanner.Err(); err != nil {
		return true, nil, err
	}
	return true, "", nil
}

// -------- Numeric builtins dispatcher used by interpreter --------

func CallNumericMethod(name string, recv any, args []ast.Expr, env map[string]any, globals map[string]any, module *Scope) (bool, any, error) {
	switch name {
	case "to_dec", "to_hex", "to_bin", "to_oct", "to_base", "to_sci":
		v, ok := numericFormat(name, recv, args, env, globals, module)
		if !ok {
			return true, nil, fmt.Errorf("invalid receiver for %s", name)
		}
		return true, v, nil
	case "to_float":
		v, ok := numericToFloat(recv, args, env, globals, module)
		if !ok {
			return true, nil, fmt.Errorf("invalid receiver/args for to_float")
		}
		return true, v, nil
	default:
		return false, nil, nil
	}
}

func numericFormat(kind string, recv any, args []ast.Expr, env map[string]any, globals map[string]any, module *Scope) (any, bool) {
	var u uint64
	var f float64
	switch n := recv.(type) {
	case int64:
		u = uint64(n)
		f = float64(n)
	case float64:
		f = n
	default:
		return nil, false
	}
	group := "_"
	uppercase := true
	prefix := true
	switch kind {
	case "to_dec":
		if _, ok := recv.(int64); ok {
			return formatDec(u, group), true
		}
		return fmt.Sprintf("%g", f), true
	case "to_hex":
		if _, ok := recv.(int64); ok {
			return groupHex(u, uppercase, group, prefix), true
		}
		return nil, false
	case "to_bin":
		if _, ok := recv.(int64); ok {
			return groupBin(u, group, prefix), true
		}
		return nil, false
	case "to_oct":
		if _, ok := recv.(int64); ok {
			return groupOct(u, group, prefix), true
		}
		return nil, false
	case "to_base":
		if _, ok := recv.(int64); !ok {
			return nil, false
		}
		base := 10
		if len(args) >= 1 {
			v, err := EvalExpr(args[0], env, globals, module)
			if err == nil {
				if b, ok := v.(int64); ok {
					base = int(b)
				}
			}
		}
		return formatBase(u, base, uppercase), true
	case "to_sci":
		precision := 6
		if len(args) >= 1 {
			v, err := EvalExpr(args[0], env, globals, module)
			if err == nil {
				if p, ok := v.(int64); ok {
					precision = int(p)
				}
			}
		}
		return formatSci(f, precision, true), true
	}
	return nil, false
}

func numericToFloat(recv any, args []ast.Expr, env map[string]any, globals map[string]any, module *Scope) (any, bool) {
	var f float64
	switch n := recv.(type) {
	case int64:
		f = float64(n)
	case float64:
		f = n
	default:
		return nil, false
	}
	if len(args) == 0 {
		return f, true
	}
	if len(args) == 1 {
		if id, ok := args[0].(*ast.IdentifierExpr); ok {
			tt := NameToTokenType(id.Name)
			if tt == tokens.TokenF64 {
				return f, true
			}
			if tt == tokens.TokenF32 || tt == tokens.TokenF16 {
				return float64(float32(f)), true
			}
		}
	}
	return nil, false
}

func formatDec(u uint64, group string) string {
	s := strconv.FormatUint(u, 10)
	return groupDigits(s, 3, group)
}

func groupHex(u uint64, upper bool, group string, prefix bool) string {
	var s string
	s = strconv.FormatUint(u, 16)
	s = strings.ToUpper(s)
	s = groupDigits(s, 2, group)
	if prefix {
		s = "0x" + s
	}
	return s
}

func groupBin(u uint64, group string, prefix bool) string {
	s := strconv.FormatUint(u, 2)
	s = groupDigits(s, 4, group)
	if prefix {
		s = "0b" + s
	}
	return s
}

func groupOct(u uint64, group string, prefix bool) string {
	s := strconv.FormatUint(u, 8)
	s = groupDigits(s, 3, group)
	if prefix {
		s = "0o" + s
	}
	return s
}

func formatBase(u uint64, base int, upper bool) string {
	if base < 2 || base > 36 {
		base = 10
	}
	s := strconv.FormatUint(u, base)
	if upper {
		s = strings.ToUpper(s)
	}
	return s
}

func formatSci(f float64, precision int, upper bool) string {
	if precision < 0 {
		precision = 0
	}
	fmtStr := "%.*E"
	if !upper {
		fmtStr = "%.*e"
	}
	return fmt.Sprintf(fmtStr, precision, f)
}

func groupDigits(s string, n int, sep string) string {
	neg := strings.HasPrefix(s, "-")
	if neg {
		s = s[1:]
	}
	var out []byte
	count := 0
	for i := len(s) - 1; i >= 0; i-- {
		ch := s[i]
		out = append(out, ch)
		count++
		if i > 0 && count%n == 0 {
			out = append(out, sep...)
		}
	}
	for i, j := 0, len(out)-1; i < j; i, j = i+1, j-1 {
		out[i], out[j] = out[j], out[i]
	}
	res := string(out)
	if neg {
		res = "-" + res
	}
	return res
}

func callRange(args []ast.Expr, env map[string]any, globals map[string]any, module *Scope) (bool, any, error) {
	// range(n) or range(start, end[, step])
	getInt := func(e ast.Expr) (int64, error) {
		v, err := EvalExpr(e, env, globals, module)
		if err != nil {
			return 0, err
		}
		i, ok := v.(int64)
		if !ok {
			return 0, fmt.Errorf("range expects integer arguments")
		}
		return i, nil
	}
	var start, end, step int64
	if len(args) == 1 {
		s, err := getInt(args[0])
		if err != nil {
			return true, nil, err
		}
		start, end, step = 0, s, 1
	} else if len(args) >= 2 {
		s0, err := getInt(args[0])
		if err != nil {
			return true, nil, err
		}
		s1, err := getInt(args[1])
		if err != nil {
			return true, nil, err
		}
		start, end, step = s0, s1, 1
		if len(args) >= 3 {
			s2, err := getInt(args[2])
			if err != nil {
				return true, nil, err
			}
			step = s2
		}
	} else {
		return true, nil, fmt.Errorf("range requires 1 to 3 integer arguments")
	}
	if step == 0 {
		return true, nil, fmt.Errorf("range step cannot be 0")
	}
	var out []any
	if step > 0 {
		for i := start; i < end; i += step {
			out = append(out, int64(i))
		}
	} else {
		for i := start; i > end; i += step {
			out = append(out, int64(i))
		}
	}
	return true, out, nil
}

func callArray(args []ast.Expr, env map[string]any, globals map[string]any, module *Scope) (bool, any, error) {
	if len(args) < 2 {
		return true, nil, fmt.Errorf("array requires at least type and size")
	}
	// type arg is ignored at runtime (dynamic), but kept for checker
	// evaluate size
	szv, err := EvalExpr(args[1], env, globals, module)
	if err != nil {
		return true, nil, err
	}
	sz, ok := szv.(int64)
	if !ok || sz < 0 {
		return true, nil, fmt.Errorf("array size must be non-negative integer")
	}
	var init any
	if len(args) >= 3 {
		v, err := EvalExpr(args[2], env, globals, module)
		if err != nil {
			return true, nil, err
		}
		init = v
	}
	arr := make([]any, int(sz))
	for i := 0; i < int(sz); i++ {
		arr[i] = init
	}
	return true, arr, nil
}

// ARC builtins
func callWeak(args []ast.Expr, env map[string]any, globals map[string]any, module *Scope) (bool, any, error) {
	if len(args) != 1 {
		return true, nil, fmt.Errorf("weak(x) takes 1 argument")
	}
	v, err := EvalExpr(args[0], env, globals, module)
	if err != nil {
		return true, nil, err
	}
	if sp, ok := v.(arc.StrongPtr); ok {
		return true, sp.Downgrade(), nil
	}
	return true, nil, fmt.Errorf("weak() expects a strong pointer")
}

func callUpgrade(args []ast.Expr, env map[string]any, globals map[string]any, module *Scope) (bool, any, error) {
	if len(args) != 1 {
		return true, nil, fmt.Errorf("upgrade(w) takes 1 argument")
	}
	v, err := EvalExpr(args[0], env, globals, module)
	if err != nil {
		return true, nil, err
	}
	if w, ok := v.(arc.WeakPtr); ok {
		if sp, ok2 := w.Upgrade(); ok2 {
			// Immediately release the temporary strong ref and return the underlying value.
			val := sp.Get()
			sp.Release()
			return true, val, nil
		}
		return true, nil, nil
	}
	return true, nil, fmt.Errorf("upgrade() expects a weak pointer")
}

func callRefcount(args []ast.Expr, env map[string]any, globals map[string]any, module *Scope) (bool, any, error) {
	if len(args) != 1 {
		return true, nil, fmt.Errorf("refcount(x) takes 1 argument")
	}
	v, err := EvalExpr(args[0], env, globals, module)
	if err != nil {
		return true, nil, err
	}
	if sp, ok := v.(arc.StrongPtr); ok {
		sp2 := sp.Clone()
		sp2.Release()
		return true, int64(0), nil
	}
	return true, int64(0), nil
}
