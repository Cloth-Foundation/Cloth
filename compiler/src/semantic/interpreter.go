package semantic

import (
	"compiler/src/ast"
	"compiler/src/tokens"
	"fmt"
)

// Execute runs a compiled file by finding main() and interpreting its body.
// Supports: literals, identifiers (locals), binary +,-,*,/,%, assignment, calls, member access via simple self with fields map.
// For now, only builtin print is supported for side effects.
func Execute(file *ast.File, module *Scope) error {
	var mainFn *ast.FuncDecl
	for _, d := range file.Decls {
		if fn, ok := d.(*ast.FuncDecl); ok && fn.Name == "main" {
			mainFn = fn
			break
		}
	}
	if mainFn == nil {
		return fmt.Errorf("no main() function found")
	}
	// simple env for locals
	env := map[string]any{}
	_, err := execBlock(mainFn.Body, env, module)
	return err
}

type returnValue struct{ v any }

func execBlock(stmts []ast.Stmt, env map[string]any, module *Scope) (any, error) {
	for _, s := range stmts {
		switch n := s.(type) {
		case *ast.BlockStmt:
			if v, err := execBlock(n.Stmts, env, module); err != nil || v != nil {
				return v, err
			}
		case *ast.LetStmt:
			var val any
			if n.Value != nil {
				v, err := evalExpr(n.Value, env, module)
				if err != nil {
					return nil, err
				}
				val = v
			}
			env[n.Name] = val
		case *ast.VarStmt:
			var val any
			if n.Value != nil {
				v, err := evalExpr(n.Value, env, module)
				if err != nil {
					return nil, err
				}
				val = v
			}
			env[n.Name] = val
		case *ast.ExpressionStmt:
			if _, err := evalExpr(n.E, env, module); err != nil {
				return nil, err
			}
		case *ast.ReturnStmt:
			if n.Value == nil {
				return returnValue{v: nil}, nil
			}
			v, err := evalExpr(n.Value, env, module)
			if err != nil {
				return nil, err
			}
			return returnValue{v: v}, nil
		}
	}
	return nil, nil
}

func evalExpr(e ast.Expr, env map[string]any, module *Scope) (any, error) {
	switch x := e.(type) {
	case *ast.NumberLiteralExpr:
		// parse to float64 or int based on isFloat
		if x.Value.IsFloat {
			var f float64
			fmt.Sscanf(x.Value.Digits, "%f", &f)
			return f, nil
		}
		var n int64
		fmt.Sscanf(x.Value.Digits, "%d", &n)
		return n, nil
	case *ast.StringLiteralExpr:
		return x.Value, nil
	case *ast.CharLiteralExpr:
		if len(x.Value) > 0 {
			return x.Value[0], nil
		}
		return byte(0), nil
	case *ast.BoolLiteralExpr:
		return x.Value, nil
	case *ast.NullLiteralExpr:
		return nil, nil
	case *ast.IdentifierExpr:
		if v, ok := env[x.Name]; ok {
			return v, nil
		}
		return nil, fmt.Errorf("undefined variable %s", x.Name)
	case *ast.UnaryExpr:
		v, err := evalExpr(x.Operand, env, module)
		if err != nil {
			return nil, err
		}
		switch x.Operator {
		case tokens.TokenMinus:
			switch n := v.(type) {
			case int64:
				return -n, nil
			case float64:
				return -n, nil
			}
		}
		return v, nil
	case *ast.BinaryExpr:
		l, err := evalExpr(x.Left, env, module)
		if err != nil {
			return nil, err
		}
		r, err := evalExpr(x.Right, env, module)
		if err != nil {
			return nil, err
		}
		switch x.Operator {
		case tokens.TokenPlus:
			// string concat or numeric add
			if ls, ok := l.(string); ok {
				return ls + toString(r), nil
			}
			if rs, ok := r.(string); ok {
				return toString(l) + rs, nil
			}
			return addNums(l, r)
		case tokens.TokenMinus:
			return subNums(l, r)
		case tokens.TokenStar:
			return mulNums(l, r)
		case tokens.TokenSlash:
			return divNums(l, r)
		case tokens.TokenPercent:
			return modNums(l, r)
		}
		return nil, fmt.Errorf("unsupported binary op")
	case *ast.AssignExpr:
		v, err := evalExpr(x.Value, env, module)
		if err != nil {
			return nil, err
		}
		if id, ok := x.Target.(*ast.IdentifierExpr); ok {
			switch x.Operator {
			case tokens.TokenEqual:
				env[id.Name] = v
				return v, nil
			case tokens.TokenPlusEqual:
				res, err := evalExpr(&ast.BinaryExpr{Left: &ast.IdentifierExpr{Name: id.Name, Tok: id.Tok}, Operator: tokens.TokenPlus, Right: x.Value, OpTok: x.OpTok}, env, module)
				if err != nil {
					return nil, err
				}
				env[id.Name] = res
				return res, nil
			}
		}
		return nil, fmt.Errorf("unsupported assignment target")
	case *ast.CastExpr:
		v, err := evalExpr(x.Expr, env, module)
		if err != nil {
			return nil, err
		}
		// Minimal casts: i32<->f64, and to string
		switch x.TargetType {
		case "i32", "i64":
			switch n := v.(type) {
			case int64:
				return n, nil
			case float64:
				return int64(n), nil
			}
			return nil, fmt.Errorf("cannot cast to int")
		case "f64", "f32":
			switch n := v.(type) {
			case int64:
				return float64(n), nil
			case float64:
				return n, nil
			}
			return nil, fmt.Errorf("cannot cast to float")
		case "string":
			return toString(v), nil
		default:
			// pass-through for unknown types
			return v, nil
		}
	case *ast.CallExpr:
		// Builtin print
		if id, ok := x.Callee.(*ast.IdentifierExpr); ok && id.Name == "print" {
			for _, a := range x.Args {
				v, err := evalExpr(a, env, module)
				if err != nil {
					return nil, err
				}
				fmt.Println(toString(v))
			}
			return nil, nil
		}
		return nil, fmt.Errorf("unknown function call")
	default:
		return nil, fmt.Errorf("unsupported expression kind")
	}
}

func toString(v any) string {
	switch t := v.(type) {
	case nil:
		return "null"
	case string:
		return t
	case int64:
		return fmt.Sprintf("%d", t)
	case float64:
		return fmt.Sprintf("%g", t)
	case bool:
		if t {
			return "true"
		}
		return "false"
	default:
		return fmt.Sprintf("%v", t)
	}
}

func addNums(a, b any) (any, error) {
	switch x := a.(type) {
	case int64:
		switch y := b.(type) {
		case int64:
			return x + y, nil
		case float64:
			return float64(x) + y, nil
		}
	case float64:
		switch y := b.(type) {
		case int64:
			return x + float64(y), nil
		case float64:
			return x + y, nil
		}
	}
	return nil, fmt.Errorf("non-numeric addition")
}
func subNums(a, b any) (any, error) {
	switch x := a.(type) {
	case int64:
		switch y := b.(type) {
		case int64:
			return x - y, nil
		case float64:
			return float64(x) - y, nil
		}
	case float64:
		switch y := b.(type) {
		case int64:
			return x - float64(y), nil
		case float64:
			return x - y, nil
		}
	}
	return nil, fmt.Errorf("non-numeric subtraction")
}
func mulNums(a, b any) (any, error) {
	switch x := a.(type) {
	case int64:
		switch y := b.(type) {
		case int64:
			return x * y, nil
		case float64:
			return float64(x) * y, nil
		}
	case float64:
		switch y := b.(type) {
		case int64:
			return x * float64(y), nil
		case float64:
			return x * y, nil
		}
	}
	return nil, fmt.Errorf("non-numeric multiplication")
}
func divNums(a, b any) (any, error) {
	switch x := a.(type) {
	case int64:
		switch y := b.(type) {
		case int64:
			return x / y, nil
		case float64:
			return float64(x) / y, nil
		}
	case float64:
		switch y := b.(type) {
		case int64:
			return x / float64(y), nil
		case float64:
			return x / y, nil
		}
	}
	return nil, fmt.Errorf("non-numeric division")
}
func modNums(a, b any) (any, error) {
	xi, aok := a.(int64)
	yi, bok := b.(int64)
	if aok && bok {
		return xi % yi, nil
	}
	return nil, fmt.Errorf("non-integer modulus")
}
