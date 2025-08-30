package semantic

import (
	"compiler/src/ast"
	"compiler/src/tokens"
	"fmt"
	"strconv"
)

var currentTypes *TypeTable

// Execute runs a compiled file by finding main() and interpreting its body.
// Supports: literals, identifiers (locals and globals), binary +,-,*,/,%, assignment, calls.
// For now, only builtin print is supported for side effects.
func Execute(file *ast.File, module *Scope, types *TypeTable) error {
	currentTypes = types
	defer func() { currentTypes = nil }()
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
	// Initialize globals
	globals := map[string]any{}
	for _, d := range file.Decls {
		if g, ok := d.(*ast.GlobalVarDecl); ok {
			var val any
			if g.Value != nil {
				v, err := evalExpr(g.Value, nil, globals, module)
				if err != nil {
					return err
				}
				val = v
			}
			globals[g.Name] = val
		}
	}
	// simple env for locals
	locals := map[string]any{}
	_, err := execBlock(mainFn.Body, locals, globals, module)
	return err
}

type returnValue struct{ v any }

func execBlock(stmts []ast.Stmt, env map[string]any, globals map[string]any, module *Scope) (any, error) {
	for _, s := range stmts {
		switch n := s.(type) {
		case *ast.BlockStmt:
			if v, err := execBlock(n.Stmts, env, globals, module); err != nil || v != nil {
				return v, err
			}
		case *ast.LetStmt:
			var val any
			if n.Value != nil {
				v, err := evalExpr(n.Value, env, globals, module)
				if err != nil {
					return nil, err
				}
				val = v
			}
			env[n.Name] = val
		case *ast.VarStmt:
			var val any
			if n.Value != nil {
				v, err := evalExpr(n.Value, env, globals, module)
				if err != nil {
					return nil, err
				}
				val = v
			}
			env[n.Name] = val
		case *ast.ExpressionStmt:
			if _, err := evalExpr(n.E, env, globals, module); err != nil {
				return nil, err
			}
		case *ast.ReturnStmt:
			if n.Value == nil {
				return returnValue{v: nil}, nil
			}
			v, err := evalExpr(n.Value, env, globals, module)
			if err != nil {
				return nil, err
			}
			return returnValue{v: v}, nil
		case *ast.IfStmt:
			cond, err := evalExpr(n.Cond, env, globals, module)
			if err != nil {
				return nil, err
			}
			if b, ok := cond.(bool); ok && b {
				if v, err := execBlock(n.Then.Stmts, env, globals, module); err != nil || v != nil {
					return v, err
				}
				break
			}
			handled := false
			for _, ei := range n.Elifs {
				c2, err := evalExpr(ei.Cond, env, globals, module)
				if err != nil {
					return nil, err
				}
				if b2, ok := c2.(bool); ok && b2 {
					if v, err := execBlock(ei.Then.Stmts, env, globals, module); err != nil || v != nil {
						return v, err
					}
					handled = true
					break
				}
			}
			if !handled && n.Else != nil {
				if v, err := execBlock(n.Else.Stmts, env, globals, module); err != nil || v != nil {
					return v, err
				}
			}
		case *ast.WhileStmt:
			for {
				c, err := evalExpr(n.Cond, env, globals, module)
				if err != nil {
					return nil, err
				}
				b, ok := c.(bool)
				if !ok || !b {
					break
				}
				if v, err := execBlock(n.Body.Stmts, env, globals, module); err != nil || v != nil {
					return v, err
				}
			}
		case *ast.DoWhileStmt:
			for {
				if v, err := execBlock(n.Body.Stmts, env, globals, module); err != nil || v != nil {
					return v, err
				}
				c, err := evalExpr(n.Cond, env, globals, module)
				if err != nil {
					return nil, err
				}
				b, ok := c.(bool)
				if !ok || !b {
					break
				}
			}
		case *ast.LoopStmt:
			// Two forms: range-style (From/To) or iterable (Iter)
			ls := env
			if n.Iter != nil {
				iterVal, err := evalExpr(n.Iter, env, globals, module)
				if err != nil {
					return nil, err
				}
				// Expect []any
				if arr, ok := iterVal.([]any); ok {
					for _, v := range arr {
						ls[n.VarName] = v
						if ret, err := execBlock(n.Body.Stmts, ls, globals, module); err != nil || ret != nil {
							return ret, err
						}
					}
				} else {
					return nil, fmt.Errorf("loop iterable is not a sequence")
				}
				break
			}
			// range-style
			fromVal, err := evalExpr(n.From, env, globals, module)
			if err != nil {
				return nil, err
			}
			toVal, err := evalExpr(n.To, env, globals, module)
			if err != nil {
				return nil, err
			}
			fi, fok := fromVal.(int64)
			ti, tok := toVal.(int64)
			if !(fok && tok) {
				return nil, fmt.Errorf("loop bounds must be integers")
			}
			step := int64(1)
			if n.Step != nil {
				st, err := evalExpr(n.Step, env, globals, module)
				if err != nil {
					return nil, err
				}
				if si, ok := st.(int64); ok {
					step = si
				} else {
					return nil, fmt.Errorf("loop step must be integer")
				}
			}
			if step == 0 {
				return nil, fmt.Errorf("loop step cannot be 0")
			}
			if !n.Reverse {
				if !n.Inclusive {
					for i := fi; i < ti; i += step {
						ls[n.VarName] = int64(i)
						if ret, err := execBlock(n.Body.Stmts, ls, globals, module); err != nil || ret != nil {
							return ret, err
						}
					}
				} else {
					for i := fi; i <= ti; i += step {
						ls[n.VarName] = int64(i)
						if ret, err := execBlock(n.Body.Stmts, ls, globals, module); err != nil || ret != nil {
							return ret, err
						}
					}
				}
			} else {
				if !n.Inclusive {
					for i := fi; i > ti; i += -step {
						ls[n.VarName] = int64(i)
						if ret, err := execBlock(n.Body.Stmts, ls, globals, module); err != nil || ret != nil {
							return ret, err
						}
					}
				} else {
					for i := fi; i >= ti; i += -step {
						ls[n.VarName] = int64(i)
						if ret, err := execBlock(n.Body.Stmts, ls, globals, module); err != nil || ret != nil {
							return ret, err
						}
					}
				}
			}
		}
	}
	return nil, nil
}

func evalExpr(e ast.Expr, env map[string]any, globals map[string]any, module *Scope) (any, error) {
	switch x := e.(type) {
	case *ast.NumberLiteralExpr:
		// parse to float64 or int based on IsFloat and numeric base
		if x.Value.IsFloat {
			f, err := strconv.ParseFloat(x.Value.Digits, 64)
			if err != nil {
				return nil, fmt.Errorf("invalid float literal")
			}
			return f, nil
		}
		iv, err := strconv.ParseInt(x.Value.Digits, x.Value.Base, 64)
		if err != nil {
			return nil, fmt.Errorf("invalid integer literal")
		}
		return int64(iv), nil
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
		if env != nil {
			if v, ok := env[x.Name]; ok {
				return v, nil
			}
		}
		if globals != nil {
			if v, ok := globals[x.Name]; ok {
				return v, nil
			}
		}
		return nil, fmt.Errorf("undefined variable %s", x.Name)
	case *ast.UnaryExpr:
		v, err := evalExpr(x.Operand, env, globals, module)
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
		case tokens.TokenNot:
			if b, ok := v.(bool); ok {
				return !b, nil
			}
		case tokens.TokenPlusPlus, tokens.TokenMinusMinus:
			// handle ++/-- for identifiers; mutate env/globals; prefix/postfix semantics
			delta := int64(1)
			if x.Operator == tokens.TokenMinusMinus {
				delta = -1
			}
			// Only assignable for identifiers; otherwise just return adjusted value
			if id, ok := x.Operand.(*ast.IdentifierExpr); ok {
				// locate storage
				if env != nil {
					if cur, ok := env[id.Name]; ok {
						old := cur
						env[id.Name] = addOne(old, delta)
						if x.IsPostfix {
							return old, nil
						}
						return env[id.Name], nil
					}
				}
				if globals != nil {
					if cur, ok := globals[id.Name]; ok {
						old := cur
						globals[id.Name] = addOne(old, delta)
						if x.IsPostfix {
							return old, nil
						}
						return globals[id.Name], nil
					}
				}
				return nil, fmt.Errorf("assignment to undefined variable %s", id.Name)
			}
			// Non-identifier: compute adjusted value without storing
			return addOne(v, delta), nil
		}
		return v, nil
	case *ast.BinaryExpr:
		l, err := evalExpr(x.Left, env, globals, module)
		if err != nil {
			return nil, err
		}
		r, err := evalExpr(x.Right, env, globals, module)
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
		case tokens.TokenLess, tokens.TokenLessEqual, tokens.TokenGreater, tokens.TokenGreaterEqual:
			lf, lok := toFloat(l)
			rf, rok := toFloat(r)
			if !(lok && rok) {
				return nil, fmt.Errorf("non-numeric comparison")
			}
			switch x.Operator {
			case tokens.TokenLess:
				return lf < rf, nil
			case tokens.TokenLessEqual:
				return lf <= rf, nil
			case tokens.TokenGreater:
				return lf > rf, nil
			case tokens.TokenGreaterEqual:
				return lf >= rf, nil
			}
		case tokens.TokenDoubleEqual, tokens.TokenNotEqual:
			// numeric equality if both numeric, else stringified equality
			if lf, lok := toFloat(l); lok {
				if rf, rok := toFloat(r); rok {
					if x.Operator == tokens.TokenDoubleEqual {
						return lf == rf, nil
					}
					return lf != rf, nil
				}
			}
			le := toString(l)
			re := toString(r)
			if x.Operator == tokens.TokenDoubleEqual {
				return le == re, nil
			}
			return le != re, nil
		case tokens.TokenAnd:
			lb, lok := l.(bool)
			rb, rok := r.(bool)
			if !(lok && rok) {
				return nil, fmt.Errorf("logical 'and' requires booleans")
			}
			return lb && rb, nil
		case tokens.TokenOr:
			lb, lok := l.(bool)
			rb, rok := r.(bool)
			if !(lok && rok) {
				return nil, fmt.Errorf("logical 'or requires booleans")
			}
			return lb || rb, nil
		}
		return nil, fmt.Errorf("unsupported binary op")
	case *ast.AssignExpr:
		v, err := evalExpr(x.Value, env, globals, module)
		if err != nil {
			return nil, err
		}
		if ix, ok := x.Target.(*ast.IndexExpr); ok {
			base, err := evalExpr(ix.Base, env, globals, module)
			if err != nil {
				return nil, err
			}
			idxVal, err := evalExpr(ix.Index, env, globals, module)
			if err != nil {
				return nil, err
			}
			i, ok := idxVal.(int64)
			if !ok {
				return nil, fmt.Errorf("index must be integer")
			}
			if arr, ok := base.([]any); ok {
				if i < 0 || int(i) >= len(arr) {
					return nil, fmt.Errorf("index out of bounds")
				}
				arr[i] = v
				return v, nil
			}
			return nil, fmt.Errorf("indexing non-array")
		}
		if id, ok := x.Target.(*ast.IdentifierExpr); ok {
			switch x.Operator {
			case tokens.TokenEqual:
				// prefer local assignment if variable exists, else global
				if env != nil {
					if _, exists := env[id.Name]; exists {
						env[id.Name] = v
						return v, nil
					}
				}
				if globals != nil {
					if _, exists := globals[id.Name]; exists {
						globals[id.Name] = v
						return v, nil
					}
				}
				return nil, fmt.Errorf("assignment to undefined variable %s", id.Name)
			case tokens.TokenPlusEqual:
				res, err := evalExpr(&ast.BinaryExpr{Left: &ast.IdentifierExpr{Name: id.Name, Tok: id.Tok}, Operator: tokens.TokenPlus, Right: x.Value, OpTok: x.OpTok}, env, globals, module)
				if err != nil {
					return nil, err
				}
				if env != nil {
					if _, exists := env[id.Name]; exists {
						env[id.Name] = res
						return res, nil
					}
				}
				if globals != nil {
					if _, exists := globals[id.Name]; exists {
						globals[id.Name] = res
						return res, nil
					}
				}
				return nil, fmt.Errorf("assignment to undefined variable %s", id.Name)
			}
		}
		return nil, fmt.Errorf("unsupported assignment target")
	case *ast.CastExpr:
		v, err := evalExpr(x.Expr, env, globals, module)
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
		// delegate to builtin dispatcher first
		if id, ok := x.Callee.(*ast.IdentifierExpr); ok {
			if handled, ret, err := CallBuiltin(id.Name, x.Args, env, globals, module); handled {
				return ret, err
			}
		}
		// Instance builtins: universal first (e.g., type()), then numeric
		if macc, ok := x.Callee.(*ast.MemberAccessExpr); ok {
			recv, err := evalExpr(macc.Object, env, globals, module)
			if err != nil {
				return nil, err
			}
			// Special-case type(): prefer static type table if available
			if macc.Member == "type" && len(x.Args) == 0 && currentTypes != nil {
				if t, ok := currentTypes.NodeToType[macc.Object]; ok && t != "" {
					return t, nil
				}
			}
			if handled, ret, err := CallUniversalMethod(macc.Member, recv, x.Args, env, globals, module); handled {
				return ret, err
			}
			if handled, ret, err := CallNumericMethod(macc.Member, recv, x.Args, env, globals, module); handled {
				return ret, err
			}
		}
		// Static method call: Type.method(...)
		if macc, ok := x.Callee.(*ast.MemberAccessExpr); ok {
			if objId, ok2 := macc.Object.(*ast.IdentifierExpr); ok2 {
				if sym, ok3 := module.Resolve(objId.Name); ok3 {
					switch n := sym.Node.(type) {
					case *ast.ClassDecl:
						for _, m := range n.Methods {
							if m.Name == macc.Member {
								newEnv := map[string]any{}
								for i, p := range m.Params {
									if i < len(x.Args) {
										v, err := evalExpr(x.Args[i], env, globals, module)
										if err != nil {
											return nil, err
										}
										newEnv[p.Name] = v
									}
								}
								ret, err := execBlock(m.Body, newEnv, globals, module)
								if err != nil {
									return nil, err
								}
								if rv, ok := ret.(returnValue); ok {
									return rv.v, nil
								}
								return nil, nil
							}
						}
					}
				}
			}
		}
		// Top-level function calls
		if id, ok := x.Callee.(*ast.IdentifierExpr); ok {
			if sym, ok2 := module.Resolve(id.Name); ok2 {
				if fn, ok3 := sym.Node.(*ast.FuncDecl); ok3 {
					// bind parameters
					newEnv := map[string]any{}
					for i, p := range fn.Params {
						if i < len(x.Args) {
							v, err := evalExpr(x.Args[i], env, globals, module)
							if err != nil {
								return nil, err
							}
							newEnv[p.Name] = v
						}
					}
					ret, err := execBlock(fn.Body, newEnv, globals, module)
					if err != nil {
						return nil, err
					}
					if rv, ok := ret.(returnValue); ok {
						return rv.v, nil
					}
					return nil, nil
				}
			}
		}
		return nil, fmt.Errorf("unknown function call")
	case *ast.ArrayLiteralExpr:
		vals := make([]any, 0, len(x.Elements))
		for _, el := range x.Elements {
			v, err := evalExpr(el, env, globals, module)
			if err != nil {
				return nil, err
			}
			vals = append(vals, v)
		}
		return vals, nil
	case *ast.IndexExpr:
		base, err := evalExpr(x.Base, env, globals, module)
		if err != nil {
			return nil, err
		}
		idxVal, err := evalExpr(x.Index, env, globals, module)
		if err != nil {
			return nil, err
		}
		i, ok := idxVal.(int64)
		if !ok {
			return nil, fmt.Errorf("index must be integer")
		}
		if arr, ok := base.([]any); ok {
			if i < 0 || int(i) >= len(arr) {
				return nil, fmt.Errorf("index out of bounds")
			}
			return arr[i], nil
		}
		return nil, fmt.Errorf("indexing non-array")
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

func addOne(v any, delta int64) any {
	switch n := v.(type) {
	case int64:
		return n + delta
	case float64:
		return n + float64(delta)
	default:
		return v
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
			return float64(x) / float64(y), nil
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

func toFloat(v any) (float64, bool) {
	switch n := v.(type) {
	case int64:
		return float64(n), true
	case float64:
		return n, true
	default:
		return 0, false
	}
}

// Removed local numeric formatting helpers; moved to builtins.
