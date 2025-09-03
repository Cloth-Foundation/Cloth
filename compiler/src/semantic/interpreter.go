package semantic

import (
	"compiler/src/ast"
	"compiler/src/runtime/arc"
	"compiler/src/tokens"
	"fmt"
	"strconv"
)

var currentTypes *TypeTable

type RuntimeError struct {
	Msg  string
	Loc  tokens.TokenSpan
	Hint string
}

func (e RuntimeError) Error() string          { return e.Msg }
func (e RuntimeError) Span() tokens.TokenSpan { return e.Loc }
func (e RuntimeError) HintText() string       { return e.Hint }
func rt(loc tokens.TokenSpan, msg, hint string) error {
	return RuntimeError{Msg: msg, Loc: loc, Hint: hint}
}

// helpers (new)
func selectBuilderByArity(builders []ast.MethodDecl, argc int) *ast.MethodDecl {
	for i := range builders {
		b := &builders[i]
		if len(b.Params) == argc {
			return b
		}
	}
	return nil
}

func bindArgs(params []ast.Parameter, args []ast.Expr, env map[string]any, globals map[string]any, module *Scope) (map[string]any, error) {
	bound := map[string]any{}
	for i, p := range params {
		if i < len(args) {
			v, err := evalExpr(args[i], env, globals, module)
			if err != nil {
				return nil, err
			}
			bound[p.Name] = v
		}
	}
	return bound, nil
}

func callMethodBody(m *ast.MethodDecl, recv map[string]any, callArgs []ast.Expr, env map[string]any, globals map[string]any, module *Scope) (any, error) {
	argEnv, err := bindArgs(m.Params, callArgs, env, globals, module)
	if err != nil {
		return nil, err
	}
	newEnv := map[string]any{"self": recv}
	for k, v := range argEnv {
		newEnv[k] = v
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

func callStaticMethod(m *ast.MethodDecl, callArgs []ast.Expr, env map[string]any, globals map[string]any, module *Scope) (any, error) {
	newEnv, err := bindArgs(m.Params, callArgs, env, globals, module)
	if err != nil {
		return nil, err
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

func findMethodInClassChain(classDecl *ast.ClassDecl, name string, module *Scope) *ast.MethodDecl {
	for i := range classDecl.Methods {
		if classDecl.Methods[i].Name == name {
			return &classDecl.Methods[i]
		}
	}
	base := classDecl
	for {
		if len(base.SuperTypes) == 0 {
			return nil
		}
		bsym, ok := module.Resolve(base.SuperTypes[0])
		if !ok {
			return nil
		}
		bcd, ok := bsym.Node.(*ast.ClassDecl)
		if !ok {
			return nil
		}
		for i := range bcd.Methods {
			if bcd.Methods[i].Name == name {
				return &bcd.Methods[i]
			}
		}
		base = bcd
	}
}

// Execute runs a compiled file by finding main() and interpreting its body.
// Validates main signature: main(argc: []i32, argv: []string): i32
// Returns the exit code from main's return value (default 0 if no explicit return).
func Execute(file *ast.File, module *Scope, types *TypeTable, progArgs []string) (int, error) {
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
		return 1, fmt.Errorf("no main() function found")
	}
	if len(mainFn.Params) != 2 {
		return 1, rt(mainFn.Span(), "invalid main signature", "expected: pub func main(argc: []i32, argv: []string): i32")
	}
	if mainFn.Params[0].Type != "[]i32" || mainFn.Params[1].Type != "[]string" || mainFn.ReturnType != "i32" {
		return 1, rt(mainFn.Span(), "invalid main signature", "expected: pub func main(argc: []i32, argv: []string): i32")
	}
	globals := map[string]any{}
	for _, d := range file.Decls {
		if g, ok := d.(*ast.GlobalVarDecl); ok {
			if g.Value != nil {
				v, err := evalExpr(g.Value, nil, globals, module)
				if err != nil {
					return 1, err
				}
				globals[g.Name] = retainWrapIfObject(v)
			} else {
				globals[g.Name] = nil
			}
		}
	}
	locals := map[string]any{}
	argcVals := []any{int64(len(progArgs))}
	argvVals := make([]any, 0, len(progArgs))
	for _, a := range progArgs {
		argvVals = append(argvVals, a)
	}
	locals[mainFn.Params[0].Name] = argcVals
	locals[mainFn.Params[1].Name] = argvVals
	ret, err := execBlock(mainFn.Body, locals, globals, module)
	if err != nil {
		return 1, err
	}
	if rv, ok := ret.(returnValue); ok {
		if rv.v == nil {
			return 0, nil
		}
		if n, ok2 := rv.v.(int64); ok2 {
			return int(n), nil
		}
		if f, ok2 := rv.v.(float64); ok2 {
			return int(int64(f)), nil
		}
		return 1, rt(mainFn.Span(), "main must return i32", "change return type/value to i32")
	}
	return 0, nil
}

type returnValue struct{ v any }

// makeInstance creates a map object for a class or struct type, initializing nested struct fields.
func makeInstance(typeName string, module *Scope) map[string]any {
	inst := map[string]any{"__type": typeName}
	// helper to populate fields from a class and its base chain
	var populateClassFields func(cd *ast.ClassDecl)
	populateClassFields = func(cd *ast.ClassDecl) {
		// populate base first
		if len(cd.SuperTypes) > 0 {
			if sym, ok := module.Resolve(cd.SuperTypes[0]); ok {
				if bcd, ok2 := sym.Node.(*ast.ClassDecl); ok2 {
					populateClassFields(bcd)
				}
			}
		}
		for _, f := range cd.Fields {
			// Initialize struct-typed fields to empty struct instance; otherwise nil
			if fsym, ok2 := module.Resolve(f.Type); ok2 {
				if _, isStruct := fsym.Node.(*ast.StructDecl); isStruct {
					inst[f.Name] = arc.NewObject(makeInstance(f.Type, module))
					continue
				}
			}
			if _, exists := inst[f.Name]; !exists {
				inst[f.Name] = nil
			}
		}
	}
	if sym, ok := module.Resolve(typeName); ok {
		switch n := sym.Node.(type) {
		case *ast.ClassDecl:
			populateClassFields(n)
		case *ast.StructDecl:
			for _, f := range n.Fields {
				inst[f.Name] = nil
			}
		}
	}
	return inst
}

func getObjectMap(obj any) (map[string]any, bool) {
	if m, ok := obj.(map[string]any); ok {
		return m, true
	}
	if sp, ok := obj.(arc.StrongPtr); ok {
		if v := sp.Get(); v != nil {
			if m, ok2 := v.(map[string]any); ok2 {
				return m, true
			}
		}
	}
	return nil, false
}

func derefIfPtr(v any) any {
	if sp, ok := v.(arc.StrongPtr); ok {
		return sp.Get()
	}
	return v
}

func wrapIfObject(v any) any {
	switch v.(type) {
	case map[string]any, []any:
		return arc.NewObject(v)
	default:
		return v
	}
}

func retainIfPtr(v any) any {
	if sp, ok := v.(arc.StrongPtr); ok {
		return sp.Clone()
	}
	return v
}

func retainWrapIfObject(v any) any {
	// If it's already a StrongPtr, clone. If it's an object/array, wrap into StrongPtr.
	if sp, ok := v.(arc.StrongPtr); ok {
		return sp.Clone()
	}
	return wrapIfObject(v)
}

func releaseIfPtr(v any) {
	if sp, ok := v.(arc.StrongPtr); ok {
		sp2 := sp
		sp2.Release()
	}
}

func execBlock(stmts []ast.Stmt, env map[string]any, globals map[string]any, module *Scope) (any, error) {
	// Track locals introduced in this block for release on exit
	var blockLocals []string
	releaseBlockLocals := func() {
		for _, name := range blockLocals {
			if val, ok := env[name]; ok {
				releaseIfPtr(val)
			}
		}
	}
	for _, s := range stmts {
		switch n := s.(type) {
		case *ast.BlockStmt:
			if v, err := execBlock(n.Stmts, env, globals, module); err != nil || v != nil {
				releaseBlockLocals()
				return v, err
			}
		case *ast.LetStmt:
			if n.Value != nil {
				v, err := evalExpr(n.Value, env, globals, module)
				if err != nil {
					releaseBlockLocals()
					return nil, err
				}
				env[n.Name] = retainWrapIfObject(v)
			} else {
				env[n.Name] = nil
			}
			blockLocals = append(blockLocals, n.Name)
		case *ast.VarStmt:
			if n.Value != nil {
				v, err := evalExpr(n.Value, env, globals, module)
				if err != nil {
					releaseBlockLocals()
					return nil, err
				}
				env[n.Name] = retainWrapIfObject(v)
			} else {
				env[n.Name] = nil
			}
			blockLocals = append(blockLocals, n.Name)
		case *ast.ExpressionStmt:
			if _, err := evalExpr(n.E, env, globals, module); err != nil {
				releaseBlockLocals()
				return nil, err
			}
		case *ast.ReturnStmt:
			if n.Value == nil {
				releaseBlockLocals()
				return returnValue{v: nil}, nil
			}
			v, err := evalExpr(n.Value, env, globals, module)
			if err != nil {
				releaseBlockLocals()
				return nil, err
			}
			releaseBlockLocals()
			return returnValue{v: v}, nil
		case *ast.IfStmt:
			cond, err := evalExpr(n.Cond, env, globals, module)
			if err != nil {
				releaseBlockLocals()
				return nil, err
			}
			if b, ok := cond.(bool); ok && b {
				if v, err := execBlock(n.Then.Stmts, env, globals, module); err != nil || v != nil {
					releaseBlockLocals()
					return v, err
				}
				break
			}
			handled := false
			for _, ei := range n.Elifs {
				c2, err := evalExpr(ei.Cond, env, globals, module)
				if err != nil {
					releaseBlockLocals()
					return nil, err
				}
				if b2, ok := c2.(bool); ok && b2 {
					if v, err := execBlock(ei.Then.Stmts, env, globals, module); err != nil || v != nil {
						releaseBlockLocals()
						return v, err
					}
					handled = true
					break
				}
			}
			if !handled && n.Else != nil {
				if v, err := execBlock(n.Else.Stmts, env, globals, module); err != nil || v != nil {
					releaseBlockLocals()
					return v, err
				}
			}
		case *ast.WhileStmt:
			for {
				c, err := evalExpr(n.Cond, env, globals, module)
				if err != nil {
					releaseBlockLocals()
					return nil, err
				}
				b, ok := c.(bool)
				if !ok || !b {
					break
				}
				if v, err := execBlock(n.Body.Stmts, env, globals, module); err != nil || v != nil {
					releaseBlockLocals()
					return v, err
				}
			}
		case *ast.DoWhileStmt:
			for {
				if v, err := execBlock(n.Body.Stmts, env, globals, module); err != nil || v != nil {
					releaseBlockLocals()
					return v, err
				}
				c, err := evalExpr(n.Cond, env, globals, module)
				if err != nil {
					releaseBlockLocals()
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
					releaseBlockLocals()
					return nil, err
				}
				// Expect []any
				if arr, ok := derefIfPtr(iterVal).([]any); ok {
					for _, v := range arr {
						ls[n.VarName] = v
						if ret, err := execBlock(n.Body.Stmts, ls, globals, module); err != nil || ret != nil {
							releaseBlockLocals()
							return ret, err
						}
					}
				} else {
					releaseBlockLocals()
					return nil, fmt.Errorf("loop iterable is not a sequence")
				}
				break
			}
			// range-style
			fromVal, err := evalExpr(n.From, env, globals, module)
			if err != nil {
				releaseBlockLocals()
				return nil, err
			}
			toVal, err := evalExpr(n.To, env, globals, module)
			if err != nil {
				releaseBlockLocals()
				return nil, err
			}
			fi, fok := fromVal.(int64)
			ti, tok := toVal.(int64)
			if !(fok && tok) {
				releaseBlockLocals()
				return nil, fmt.Errorf("loop bounds must be integers")
			}
			step := int64(1)
			if n.Step != nil {
				st, err := evalExpr(n.Step, env, globals, module)
				if err != nil {
					releaseBlockLocals()
					return nil, err
				}
				if si, ok := st.(int64); ok {
					step = si
				} else {
					releaseBlockLocals()
					return nil, fmt.Errorf("loop step must be integer")
				}
			}
			if step == 0 {
				releaseBlockLocals()
				return nil, fmt.Errorf("loop step cannot be 0")
			}
			if !n.Reverse {
				if !n.Inclusive {
					for i := fi; i < ti; i += step {
						ls[n.VarName] = int64(i)
						if ret, err := execBlock(n.Body.Stmts, ls, globals, module); err != nil || ret != nil {
							releaseBlockLocals()
							return ret, err
						}
					}
				} else {
					for i := fi; i <= ti; i += step {
						ls[n.VarName] = int64(i)
						if ret, err := execBlock(n.Body.Stmts, ls, globals, module); err != nil || ret != nil {
							releaseBlockLocals()
							return ret, err
						}
					}
				}
			} else {
				if !n.Inclusive {
					for i := fi; i > ti; i += -step {
						ls[n.VarName] = int64(i)
						if ret, err := execBlock(n.Body.Stmts, ls, globals, module); err != nil || ret != nil {
							releaseBlockLocals()
							return ret, err
						}
					}
				} else {
					for i := fi; i >= ti; i += -step {
						ls[n.VarName] = int64(i)
						if ret, err := execBlock(n.Body.Stmts, ls, globals, module); err != nil || ret != nil {
							releaseBlockLocals()
							return ret, err
						}
					}
				}
			}
		}
	}
	releaseBlockLocals()
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
			// fallback to self field
			if self, ok := env["self"]; ok {
				if obj, ok2 := self.(map[string]any); ok2 {
					if typeName, ok3 := obj["__type"].(string); ok3 {
						if sym, ok4 := module.Resolve(typeName); ok4 {
							switch d := sym.Node.(type) {
							case *ast.ClassDecl:
								for _, f := range d.Fields {
									if f.Name == x.Name {
										return obj[x.Name], nil
									}
								}
							case *ast.StructDecl:
								for _, f := range d.Fields {
									if f.Name == x.Name {
										return obj[x.Name], nil
									}
								}
							}
						}
					}
				}
			}
		}
		if globals != nil {
			if v, ok := globals[x.Name]; ok {
				return v, nil
			}
		}
		return nil, fmt.Errorf("undefined variable %s", x.Name)
	case *ast.MemberAccessExpr:
		// Enum case materialization: Type.CASE
		if objId, ok := x.Object.(*ast.IdentifierExpr); ok {
			if sym, ok2 := module.Resolve(objId.Name); ok2 {
				switch d := sym.Node.(type) {
				case *ast.EnumDecl:
					for _, c := range d.Cases {
						if c.Name == x.Member {
							// Evaluate payload args stored in case and run matching constructor
							args := make([]any, 0, len(c.Params))
							for _, a := range c.Params {
								v, err := evalExpr(a, env, globals, module)
								if err != nil {
									return nil, err
								}
								args = append(args, v)
							}
							inst := makeInstance(d.Name, module)
							// pick constructor by arity
							for _, b := range d.Builders {
								if len(b.Params) == len(args) {
									newEnv := map[string]any{"self": inst}
									for i, p := range b.Params {
										newEnv[p.Name] = args[i]
									}
									if _, err := execBlock(b.Body, newEnv, globals, module); err != nil {
										return nil, err
									}
									break
								}
							}
							return inst, nil
						}
					}
				}
			}
		}
		// Otherwise evaluate object and try field access
		obj, err := evalExpr(x.Object, env, globals, module)
		if err != nil {
			return nil, err
		}
		if m, ok := getObjectMap(obj); ok {
			if v, ok2 := m[x.Member]; ok2 {
				return v, nil
			}
			return nil, fmt.Errorf("unknown field %s", x.Member)
		}
		return nil, fmt.Errorf("member access on non-object")
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
					// self field fallback
					if self, ok := env["self"]; ok {
						if obj, ok2 := self.(map[string]any); ok2 {
							if cur, exists := obj[id.Name]; exists {
								old := cur
								obj[id.Name] = addOne(old, delta)
								if x.IsPostfix {
									return old, nil
								}
								return obj[id.Name], nil
							}
						}
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
		// Assignment to object field: self.name = v
		if macc, ok := x.Target.(*ast.MemberAccessExpr); ok {
			baseVal, err := evalExpr(macc.Object, env, globals, module)
			if err != nil {
				return nil, err
			}
			obj := baseVal
			// Auto-initialize nil self fields when their declared type is struct/class
			if obj == nil {
				if id, ok := macc.Object.(*ast.IdentifierExpr); ok {
					if self, ok2 := env["self"]; ok2 {
						if sobj, ok3 := self.(map[string]any); ok3 {
							if typeName, ok4 := sobj["__type"].(string); ok4 {
								if sym, ok5 := module.Resolve(typeName); ok5 {
									if cd, isClass := sym.Node.(*ast.ClassDecl); isClass {
										for _, f := range cd.Fields {
											if f.Name == id.Name {
												sobj[id.Name] = arc.NewObject(makeInstance(f.Type, module))
												obj = sobj[id.Name]
												break
											}
										}
									}
								}
							}
						}
					}
				}
			}
			if m, ok := getObjectMap(obj); ok {
				// release old field if it was a ptr
				if old, exists := m[macc.Member]; exists {
					releaseIfPtr(old)
				}
				m[macc.Member] = retainWrapIfObject(v)
				return v, nil
			}
			return nil, fmt.Errorf("assignment to non-object member")
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
			if arr, ok := derefIfPtr(base).([]any); ok {
				if i < 0 || int(i) >= len(arr) {
					return nil, fmt.Errorf("index out of bounds")
				}
				// release old element if StrongPtr
				releaseIfPtr(arr[i])
				arr[i] = retainWrapIfObject(v)
				return v, nil
			}
			return nil, fmt.Errorf("indexing non-array")
		}
		if id, ok := x.Target.(*ast.IdentifierExpr); ok {
			switch x.Operator {
			case tokens.TokenEqual:
				// prefer local assignment if variable exists, else global
				if env != nil {
					if old, exists := env[id.Name]; exists {
						releaseIfPtr(old)
						env[id.Name] = retainWrapIfObject(v)
						return v, nil
					}
					// self field fallback: assign to self.<field>
					if self, ok := env["self"]; ok {
						if obj, ok2 := self.(map[string]any); ok2 {
							if old, ex := obj[id.Name]; ex {
								releaseIfPtr(old)
							}
							obj[id.Name] = retainWrapIfObject(v)
							return v, nil
						}
					}
				}
				if globals != nil {
					if old, exists := globals[id.Name]; exists {
						releaseIfPtr(old)
						globals[id.Name] = retainWrapIfObject(v)
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
					if old, exists := env[id.Name]; exists {
						releaseIfPtr(old)
						env[id.Name] = retainWrapIfObject(res)
						return res, nil
					}
					if self, ok := env["self"]; ok {
						if obj, ok2 := self.(map[string]any); ok2 {
							if old, ex := obj[id.Name]; ex {
								releaseIfPtr(old)
							}
							obj[id.Name] = retainWrapIfObject(res)
							return res, nil
						}
					}
				}
				if globals != nil {
					if old, exists := globals[id.Name]; exists {
						releaseIfPtr(old)
						globals[id.Name] = retainWrapIfObject(res)
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
			// Instance method on class/enum
			if obj, ok := getObjectMap(recv); ok {
				if typeName, ok2 := obj["__type"].(string); ok2 {
					if sym, ok3 := module.Resolve(typeName); ok3 {
						switch d := sym.Node.(type) {
						case *ast.ClassDecl:
							if m := findMethodInClassChain(d, macc.Member, module); m != nil {
								return callMethodBody(m, obj, x.Args, env, globals, module)
							}
						case *ast.EnumDecl:
							for i := range d.Methods {
								if d.Methods[i].Name == macc.Member {
									return callMethodBody(&d.Methods[i], obj, x.Args, env, globals, module)
								}
							}
						}
					}
				}
			}
		}
		// Static method call: Type.method(...)
		if macc, ok := x.Callee.(*ast.MemberAccessExpr); ok {
			if objId, ok2 := macc.Object.(*ast.IdentifierExpr); ok2 {
				if sym, ok3 := module.Resolve(objId.Name); ok3 {
					switch n := sym.Node.(type) {
					case *ast.ClassDecl:
						for i := range n.Methods {
							if n.Methods[i].Name == macc.Member {
								return callStaticMethod(&n.Methods[i], x.Args, env, globals, module)
							}
						}
					}
				}
			}
		}
		// Constructor calls: Type(...)
		if id, ok := x.Callee.(*ast.IdentifierExpr); ok {
			// super(...) inside a constructor: call base-class builder on same self
			if id.Name == "super" {
				if env == nil {
					return nil, fmt.Errorf("super() outside of constructor")
				}
				self, ok := env["self"].(map[string]any)
				if !ok {
					return nil, fmt.Errorf("super() requires 'self' in scope")
				}
				typeName, ok := self["__type"].(string)
				if !ok {
					return nil, fmt.Errorf("invalid receiver for super()")
				}
				sym, ok := module.Resolve(typeName)
				if !ok {
					return nil, fmt.Errorf("unknown type %s", typeName)
				}
				cd, ok := sym.Node.(*ast.ClassDecl)
				if !ok {
					return nil, fmt.Errorf("super() only valid in classes")
				}
				if len(cd.SuperTypes) == 0 {
					return nil, fmt.Errorf("class %s has no base to call super()", typeName)
				}
				bsym, ok := module.Resolve(cd.SuperTypes[0])
				if !ok {
					return nil, fmt.Errorf("unknown base class %s", cd.SuperTypes[0])
				}
				bcd, ok := bsym.Node.(*ast.ClassDecl)
				if !ok {
					return nil, fmt.Errorf("super target is not a class")
				}
				if b := selectBuilderByArity(bcd.Builders, len(x.Args)); b != nil {
					newEnv := map[string]any{"self": self}
					for i, p := range b.Params {
						v, err := evalExpr(x.Args[i], env, globals, module)
						if err != nil {
							return nil, err
						}
						newEnv[p.Name] = v
					}
					_, err := execBlock(b.Body, newEnv, globals, module)
					return nil, err
				}
				return nil, fmt.Errorf("no matching super constructor")
			}
			if sym, ok2 := module.Resolve(id.Name); ok2 {
				switch d := sym.Node.(type) {
				case *ast.ClassDecl:
					if d.IsTemplate {
						return nil, fmt.Errorf("cannot instantiate template class %s", d.Name)
					}
					if b := selectBuilderByArity(d.Builders, len(x.Args)); b != nil {
						newEnv := map[string]any{}
						instMap := makeInstance(d.Name, module)
						for i, p := range b.Params {
							v, err := evalExpr(x.Args[i], env, globals, module)
							if err != nil {
								return nil, err
							}
							newEnv[p.Name] = v
						}
						newEnv["self"] = instMap
						if _, err := execBlock(b.Body, newEnv, globals, module); err != nil {
							return nil, err
						}
						return instMap, nil
					}
					instMap := makeInstance(d.Name, module)
					return instMap, nil
				case *ast.EnumDecl:
					vals := make([]any, 0, len(x.Args))
					for _, a := range x.Args {
						v, err := evalExpr(a, env, globals, module)
						if err != nil {
							return nil, err
						}
						vals = append(vals, v)
					}
					return map[string]any{"__type": d.Name, "__payload": vals}, nil
				}
			}
		}
		// Top-level function calls
		if id, ok := x.Callee.(*ast.IdentifierExpr); ok {
			if sym, ok2 := module.Resolve(id.Name); ok2 {
				if fn, ok3 := sym.Node.(*ast.FuncDecl); ok3 {
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
		return nil, rt(x.Callee.Span(), "unknown function call", "define it earlier, import it, or check for typos")
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
		if arr, ok := derefIfPtr(base).([]any); ok {
			if i < 0 || int(i) >= len(arr) {
				return nil, fmt.Errorf("index out of bounds")
			}
			return arr[i], nil
		}
		return nil, fmt.Errorf("indexing non-array")
	case *ast.TernaryExpr:
		cv, err := evalExpr(x.Cond, env, globals, module)
		if err != nil {
			return nil, err
		}
		b, ok := cv.(bool)
		if !ok {
			return nil, rt(x.Cond.Span(), "ternary condition must be bool", "use a boolean expression before '?' ")
		}
		if b {
			return evalExpr(x.ThenExpr, env, globals, module)
		}
		return evalExpr(x.ElseExpr, env, globals, module)
	default:
		return nil, rt(e.Span(), "unsupported expression kind", "this expression form is not yet supported")
	}
}

// toString converts a value of any type into its string representation, handling various types including nil and smart pointers.
func toString(v any) string {
	// Deref smart pointers for display
	if sp, ok := v.(arc.StrongPtr); ok {
		return toString(sp.Get())
	}
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

// addOne increments the input value by the specified delta if it's of type int64 or float64; returns unchanged value otherwise.
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

// addNums adds two numeric values of type int64 or float64 and returns the result. Returns an error if types are unsupported.
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

// subNums subtracts two values of type int64 or float64 and returns the result.
// If the types are incompatible or unsupported, it returns an error.
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

// mulNums multiplies two values of type int64 or float64 and returns the result.
// If the types are incompatible or unsupported, it returns an error.
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

// divNums performs a division operation on two values if both are int64, returning the result or an error
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

// modNums performs a modulus operation on two values if both are int64, returning the result or an error otherwise.
func modNums(a, b any) (any, error) {
	xi, aok := a.(int64)
	yi, bok := b.(int64)
	if aok && bok {
		return xi % yi, nil
	}
	return nil, fmt.Errorf("non-integer modulus")
}

// toFloat converts an input of type `any` to a `float64`. Returns a boolean indicating success or failure.
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
