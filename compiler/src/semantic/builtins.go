package semantic

import (
	"bufio"
	"compiler/src/ast"
	"fmt"
	"os"
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
}

// CallBuiltin executes a builtin if name matches, returning whether it handled the call.
// It evaluates arguments using evalExpr to support expressions as arguments.
func CallBuiltin(name string, args []ast.Expr, env map[string]any, globals map[string]any, module *Scope) (bool, any, error) {
	switch name {
	case "print":
		for _, a := range args {
			v, err := evalExpr(a, env, globals, module)
			if err != nil {
				return true, nil, err
			}
			fmt.Print(toString(v))
		}
		return true, nil, nil
	case "println":
		for _, a := range args {
			v, err := evalExpr(a, env, globals, module)
			if err != nil {
				return true, nil, err
			}
			fmt.Println(toString(v))
		}
		return true, nil, nil
	case "printf":
		if len(args) == 0 {
			return true, nil, nil
		}
		// first arg is the format string
		fmtVal, err := evalExpr(args[0], env, globals, module)
		if err != nil {
			return true, nil, err
		}
		fmtStr := toString(fmtVal)
		// collect values from second argument if provided
		var values []any
		if len(args) >= 2 {
			// If second arg is an array literal, evaluate its elements individually
			if arrLit, ok := args[1].(*ast.ArrayLiteralExpr); ok {
				for _, el := range arrLit.Elements {
					v, err := evalExpr(el, env, globals, module)
					if err != nil {
						return true, nil, err
					}
					values = append(values, v)
				}
			} else {
				// Otherwise evaluate the second arg; if it's a slice, spread it; else use single value fallback
				v, err := evalExpr(args[1], env, globals, module)
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
		// also collect any additional args after the second parameter (variadic style)
		if len(args) > 2 {
			for _, a := range args[2:] {
				v, err := evalExpr(a, env, globals, module)
				if err != nil {
					return true, nil, err
				}
				// spread if evaluated to a slice
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
		// Replace occurrences of {} or %s (and allow %% to escape) with successive values
		out := make([]rune, 0, len(fmtStr)+len(values)*4)
		runes := []rune(fmtStr)
		vi := 0
		for i := 0; i < len(runes); i++ {
			// {} placeholder
			if runes[i] == '{' && i+1 < len(runes) && runes[i+1] == '}' {
				if vi < len(values) {
					out = append(out, []rune(toString(values[vi]))...)
					vi++
					i++
					continue
				}
				// no value left: leave {}
			}
			// % sequence
			if runes[i] == '%' && i+1 < len(runes) {
				if runes[i+1] == '%' {
					out = append(out, '%')
					i++
					continue
				}
				if runes[i+1] == 's' {
					if vi < len(values) {
						out = append(out, []rune(toString(values[vi]))...)
						vi++
						i++
						continue
					}
					// no value left: leave %s
				}
			}
			out = append(out, runes[i])
		}
		fmt.Print(string(out))
		return true, nil, nil
	case "input":
		// optional prompt already type-checked as string; print if provided
		if len(args) >= 1 {
			v, err := evalExpr(args[0], env, globals, module)
			if err != nil {
				return true, nil, err
			}
			fmt.Print(toString(v))
		}
		scanner := bufio.NewScanner(os.Stdin)
		if scanner.Scan() {
			return true, scanner.Text(), nil
		}
		if err := scanner.Err(); err != nil {
			return true, nil, err
		}
		return true, "", nil
	case "exit":
		if len(args) == 0 {
			os.Exit(0)
		}
		if len(args) == 1 {
			v, err := evalExpr(args[0], env, globals, module)
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
