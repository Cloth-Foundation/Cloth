package semantic

import (
	"compiler/src/ast"
	"compiler/src/tokens"
	"fmt"
	"math"
	"strconv"
)

type CheckDiag struct {
	Message string
	Span    tokens.TokenSpan
	Hint    string
}

type typeScope struct {
	parent   *typeScope
	types    map[string]string
	selfType string
}

func newTypeScope(parent *typeScope) *typeScope {
	ts := &typeScope{parent: parent, types: map[string]string{}}
	if parent != nil {
		ts.selfType = parent.selfType
	}
	return ts
}
func (s *typeScope) define(name, t string) { s.types[name] = t }
func (s *typeScope) resolve(name string) (string, bool) {
	if t, ok := s.types[name]; ok {
		return t, true
	}
	if s.parent != nil {
		return s.parent.resolve(name)
	}
	return "", false
}

func formatAssign(target, value string) string {
	return fmt.Sprintf("cannot assign %s to %s", value, target)
}

// CheckExpressions performs type inference/checking for basic rules.
func CheckExpressions(file *ast.File, types *TypeTable, module *Scope) []CheckDiag {
	var diags []CheckDiag
	for _, d := range file.Decls {
		switch n := d.(type) {
		case *ast.GlobalVarDecl:
			ts := newTypeScope(nil)
			checkGlobal(n, ts, types, module, &diags)
		case *ast.FuncDecl:
			ts := newTypeScope(nil)
			for _, p := range n.Params {
				ts.define(p.Name, p.Type)
			}
			checkBlock(n.Body, ts, n.ReturnType, types, module, &diags)
		case *ast.ClassDecl:
			for _, m := range n.Methods {
				ts := newTypeScope(nil)
				ts.selfType = n.Name
				for _, p := range m.Params {
					ts.define(p.Name, p.Type)
				}
				checkBlock(m.Body, ts, m.ReturnType, types, module, &diags)
			}
			for _, b := range n.Builders {
				ts := newTypeScope(nil)
				ts.selfType = n.Name
				for _, p := range b.Params {
					ts.define(p.Name, p.Type)
				}
				checkBlock(b.Body, ts, "void", types, module, &diags)
			}
			// Note: instantiation of abstract classes is prevented at constructor-call time
		case *ast.StructDecl:
			for _, m := range n.Methods {
				ts := newTypeScope(nil)
				for _, p := range m.Params {
					ts.define(p.Name, p.Type)
				}
				checkBlock(m.Body, ts, m.ReturnType, types, module, &diags)
			}
		}
	}
	return diags
}

func checkGlobal(g *ast.GlobalVarDecl, ts *typeScope, table *TypeTable, module *Scope, diags *[]CheckDiag) {
	if g.Value != nil {
		vt := inferExprType(g.Value, ts, table, module, diags)
		if g.Type != "" && !assignable(g.Type, vt) {
			*diags = append(*diags, CheckDiag{Message: formatAssign(g.Type, vt), Span: g.Tok.Span})
		}
		if g.Type != "" && (IsIntegerType(g.Type) || IsFloatType(g.Type) || g.Type == TokenTypeName(tokens.TokenBit)) {
			if ok, msg := literalFitsType(g.Type, g.Value); !ok {
				*diags = append(*diags, CheckDiag{Message: msg, Span: g.Value.Span()})
			}
		}
		if g.Type == "" {
			ts.define(g.Name, vt)
		} else {
			ts.define(g.Name, g.Type)
		}
	} else if g.Type != "" {
		ts.define(g.Name, g.Type)
	}
}

func checkBlock(stmts []ast.Stmt, ts *typeScope, retType string, table *TypeTable, module *Scope, diags *[]CheckDiag) {
	for _, s := range stmts {
		switch n := s.(type) {
		case *ast.BlockStmt:
			checkBlock(n.Stmts, newTypeScope(ts), retType, table, module, diags)
		case *ast.LetStmt:
			if n.Value != nil {
				vt := inferExprType(n.Value, ts, table, module, diags)
				if n.Type != "" && !assignable(n.Type, vt) {
					*diags = append(*diags, CheckDiag{Message: formatAssign(n.Type, vt), Span: n.NameTok.Span})
				}
				if n.Type != "" && (IsIntegerType(n.Type) || IsFloatType(n.Type) || n.Type == TokenTypeName(tokens.TokenBit)) {
					if ok, msg := literalFitsType(n.Type, n.Value); !ok {
						*diags = append(*diags, CheckDiag{Message: msg, Span: n.Value.Span()})
					}
				}
				if n.Type == "" {
					ts.define(n.Name, vt)
				} else {
					ts.define(n.Name, n.Type)
				}
			} else if n.Type != "" {
				ts.define(n.Name, n.Type)
			}
		case *ast.VarStmt:
			if n.Value != nil {
				vt := inferExprType(n.Value, ts, table, module, diags)
				if n.Type != "" && !assignable(n.Type, vt) {
					*diags = append(*diags, CheckDiag{Message: formatAssign(n.Type, vt), Span: n.NameTok.Span})
				}
				if n.Type != "" && (IsIntegerType(n.Type) || IsFloatType(n.Type) || n.Type == TokenTypeName(tokens.TokenBit)) {
					if ok, msg := literalFitsType(n.Type, n.Value); !ok {
						*diags = append(*diags, CheckDiag{Message: msg, Span: n.Value.Span()})
					}
				}
				if n.Type == "" {
					ts.define(n.Name, vt)
				} else {
					ts.define(n.Name, n.Type)
				}
			} else if n.Type != "" {
				ts.define(n.Name, n.Type)
			}
		case *ast.ExpressionStmt:
			_ = inferExprType(n.E, ts, table, module, diags)
		case *ast.ReturnStmt:
			if n.Value != nil {
				vt := inferExprType(n.Value, ts, table, module, diags)
				// Normalize 'self' in return type context
				normRet := retType
				if normRet == "self" && ts.selfType != "" {
					normRet = ts.selfType
				}
				if normRet != "" && normRet != TokenTypeName(tokens.TokenVoid) {
					if normRet == TokenTypeName(tokens.TokenBit) && vt == "null" {
						*diags = append(*diags, CheckDiag{Message: "bit cannot be null", Span: n.Tok.Span, Hint: "return 0 or 1 instead of null"})
					} else if !assignable(normRet, vt) {
						*diags = append(*diags, CheckDiag{Message: formatAssign(normRet, vt), Span: n.Tok.Span})
					}
				}
			} else if retType != "" && retType != TokenTypeName(tokens.TokenVoid) {
				*diags = append(*diags, CheckDiag{Message: "missing return value", Span: n.Tok.Span})
			}
		case *ast.IfStmt:
			ct := inferExprType(n.Cond, ts, table, module, diags)
			if ct != TokenTypeName(tokens.TokenBool) {
				*diags = append(*diags, CheckDiag{Message: "if condition must be bool", Span: n.Cond.Span()})
			}
			checkBlock(n.Then.Stmts, newTypeScope(ts), retType, table, module, diags)
			for _, ei := range n.Elifs {
				ct2 := inferExprType(ei.Cond, ts, table, module, diags)
				if ct2 != TokenTypeName(tokens.TokenBool) {
					*diags = append(*diags, CheckDiag{Message: "elif condition must be bool", Span: ei.Cond.Span()})
				}
				checkBlock(ei.Then.Stmts, newTypeScope(ts), retType, table, module, diags)
			}
			if n.Else != nil {
				checkBlock(n.Else.Stmts, newTypeScope(ts), retType, table, module, diags)
			}
		case *ast.WhileStmt:
			ct := inferExprType(n.Cond, ts, table, module, diags)
			if ct != TokenTypeName(tokens.TokenBool) {
				*diags = append(*diags, CheckDiag{Message: "while condition must be bool", Span: n.Cond.Span()})
			}
			checkBlock(n.Body.Stmts, newTypeScope(ts), retType, table, module, diags)
		case *ast.DoWhileStmt:
			checkBlock(n.Body.Stmts, newTypeScope(ts), retType, table, module, diags)
			ct := inferExprType(n.Cond, ts, table, module, diags)
			if ct != TokenTypeName(tokens.TokenBool) {
				*diags = append(*diags, CheckDiag{Message: "do-while condition must be bool", Span: n.Cond.Span()})
			}
		case *ast.LoopStmt:
			ft := inferExprType(n.From, ts, table, module, diags)
			_ = inferExprType(n.To, ts, table, module, diags)
			if n.Step != nil {
				_ = inferExprType(n.Step, ts, table, module, diags)
			}
			ls := newTypeScope(ts)
			if ft == "" {
				ft = TokenTypeName(tokens.TokenI32)
			}
			ls.define(n.VarName, ft)
			checkBlock(n.Body.Stmts, ls, retType, table, module, diags)
		}
	}
}

func getFuncSignature(sym Symbol) ([]ast.Parameter, string, bool) {
	switch n := sym.Node.(type) {
	case *ast.FuncDecl:
		return n.Params, n.ReturnType, true
	case ast.MethodDecl:
		return n.Params, n.ReturnType, true
	default:
		return nil, "", false
	}
}

func findMethodOnType(typeName, method string, module *Scope) ([]ast.Parameter, string, bool) {
	if typeName == "" {
		return nil, "", false
	}
	if sym, ok := module.Resolve(typeName); ok {
		switch n := sym.Node.(type) {
		case *ast.ClassDecl:
			for _, m := range n.Methods {
				if m.Name == method {
					return m.Params, m.ReturnType, true
				}
			}
		case *ast.StructDecl:
			for _, m := range n.Methods {
				if m.Name == method {
					return m.Params, m.ReturnType, true
				}
			}
		}
	}
	return nil, "", false
}

func checkCallAgainst(sigParams []ast.Parameter, ret string, callName string, args []ast.Expr, ts *typeScope, table *TypeTable, module *Scope, diags *[]CheckDiag) string {
	// Special-case builtin printf signature: (string, []any)
	if callName == "printf" {
		// Only ensure at least 1 arg and that first arg is string-like; skip strict checks for []any
		return ret
	}
	for i, a := range args {
		at := inferExprType(a, ts, table, module, diags)
		if i < len(sigParams) {
			pt := sigParams[i].Type
			if pt != "" {
				// Forbid null to bit parameters with a clearer message
				if pt == TokenTypeName(tokens.TokenBit) && at == "null" {
					*diags = append(*diags, CheckDiag{Message: fmt.Sprintf("argument %d to %s: bit cannot be null", i+1, callName), Span: a.Span(), Hint: "pass 0 or 1 instead of null"})
				} else if !assignable(pt, at) {
					*diags = append(*diags, CheckDiag{Message: fmt.Sprintf("argument %d to %s: %s", i+1, callName, formatAssign(pt, at)), Span: a.Span()})
				}
			}
		}
	}
	if len(args) < len(sigParams) {
		*diags = append(*diags, CheckDiag{Message: fmt.Sprintf("too few arguments to %s: expected %d, got %d", callName, len(sigParams), len(args))})
	}
	if len(args) > len(sigParams) {
		*diags = append(*diags, CheckDiag{Message: fmt.Sprintf("too many arguments to %s: expected %d, got %d", callName, len(sigParams), len(args))})
	}
	return ret
}

func inferExprType(e ast.Expr, ts *typeScope, table *TypeTable, module *Scope, diags *[]CheckDiag) string {
	switch x := e.(type) {
	case *ast.IdentifierExpr:
		// local scope first
		if t, ok := ts.resolve(x.Name); ok {
			table.NodeToType[e] = t
			return t
		}
		// special-case 'self' to current receiver type
		if x.Name == "self" && ts.selfType != "" {
			table.NodeToType[e] = ts.selfType
			return ts.selfType
		}
		// then module-level symbols
		if sym, ok := module.Resolve(x.Name); ok {
			switch sym.Kind {
			case SymVar:
				if gv, ok2 := sym.Node.(*ast.GlobalVarDecl); ok2 {
					if gv.Type != "" {
						table.NodeToType[e] = gv.Type
						return gv.Type
					}
					if gv.Value != nil {
						vt := inferExprType(gv.Value, ts, table, module, diags)
						table.NodeToType[e] = vt
						return vt
					}
				}
			case SymClass, SymStruct, SymEnum:
				// treat as a type reference; use the symbol name as the type
				table.NodeToType[e] = sym.Name
				return sym.Name
			}
		}
		return ""
	case *ast.NumberLiteralExpr:
		if x.Value.Suffix != "" {
			table.NodeToType[e] = x.Value.Suffix
			return x.Value.Suffix
		}
		if x.Value.IsFloat {
			t := TokenTypeName(tokens.TokenF64)
			table.NodeToType[e] = t
			return t
		}
		t := TokenTypeName(tokens.TokenI32)
		table.NodeToType[e] = t
		return t
	case *ast.StringLiteralExpr:
		t := TokenTypeName(tokens.TokenString)
		table.NodeToType[e] = t
		return t
	case *ast.CharLiteralExpr:
		t := TokenTypeName(tokens.TokenChar)
		table.NodeToType[e] = t
		return t
	case *ast.BoolLiteralExpr:
		t := TokenTypeName(tokens.TokenBool)
		table.NodeToType[e] = t
		return t
	case *ast.NullLiteralExpr:
		table.NodeToType[e] = "null"
		return "null"
	case *ast.ArrayLiteralExpr:
		et := ""
		for i, el := range x.Elements {
			vt := inferExprType(el, ts, table, module, diags)
			if i == 0 {
				et = vt
			} else if et != vt {
				et = "?"
			}
		}
		t := "[]" + et
		table.NodeToType[e] = t
		return t
	case *ast.IndexExpr:
		bt := inferExprType(x.Base, ts, table, module, diags)
		_ = inferExprType(x.Index, ts, table, module, diags)
		if len(bt) >= 2 && bt[:2] == "[]" {
			et := bt[2:]
			table.NodeToType[e] = et
			return et
		}
		return ""
	case *ast.UnaryExpr:
		ot := inferExprType(x.Operand, ts, table, module, diags)
		// Enforce unary operator typing
		switch x.Operator {
		case tokens.TokenPlusPlus, tokens.TokenMinusMinus:
			if !IsNumericType(ot) {
				*diags = append(*diags, CheckDiag{Message: "++/-- require numeric operand", Span: x.OpTok.Span})
			}
		case tokens.TokenNot:
			if ot != TokenTypeName(tokens.TokenBool) {
				*diags = append(*diags, CheckDiag{Message: "! operand must be bool", Span: x.OpTok.Span})
			}
		case tokens.TokenBitNot:
			if !IsIntegerType(ot) {
				*diags = append(*diags, CheckDiag{Message: "~ operand must be integer", Span: x.OpTok.Span})
			}
		}
		table.NodeToType[e] = ot
		return ot
	case *ast.BinaryExpr:
		lt := inferExprType(x.Left, ts, table, module, diags)
		rt := inferExprType(x.Right, ts, table, module, diags)
		op := tokensToSymbol(x.Operator)
		if op == "&&" || op == "||" {
			if lt != TokenTypeName(tokens.TokenBool) || rt != TokenTypeName(tokens.TokenBool) {
				*diags = append(*diags, CheckDiag{Message: "logical operands must be bool", Span: x.OpTok.Span})
			}
			t := TokenTypeName(tokens.TokenBool)
			table.NodeToType[e] = t
			return t
		}
		// Equality comparisons: allow numeric, string, bool, and any vs null
		if op == "==" || op == "!=" {
			if lt == "null" || rt == "null" {
				t := TokenTypeName(tokens.TokenBool)
				table.NodeToType[e] = t
				return t
			}
			isNum := IsNumericType(lt) && IsNumericType(rt)
			isStr := lt == TokenTypeName(tokens.TokenString) && rt == TokenTypeName(tokens.TokenString)
			isBool := lt == TokenTypeName(tokens.TokenBool) && rt == TokenTypeName(tokens.TokenBool)
			if !(isNum || isStr || isBool) {
				*diags = append(*diags, CheckDiag{Message: "equality operands must be comparable (numeric, string, or bool)", Span: x.OpTok.Span})
			}
			t := TokenTypeName(tokens.TokenBool)
			table.NodeToType[e] = t
			return t
		}
		// Relational comparisons: numeric only
		if op == "<" || op == "<=" || op == ">" || op == ">=" {
			if !(IsNumericType(lt) && IsNumericType(rt)) {
				*diags = append(*diags, CheckDiag{Message: "comparison operands must be numeric", Span: x.OpTok.Span})
			}
			t := TokenTypeName(tokens.TokenBool)
			table.NodeToType[e] = t
			return t
		}
		if op == "&" || op == "|" || op == "^" || op == "<<" || op == ">>" {
			if !(IsIntegerType(lt) && IsIntegerType(rt)) {
				*diags = append(*diags, CheckDiag{Message: "bitwise operands must be integers", Span: x.OpTok.Span})
			}
			table.NodeToType[e] = lt
			return lt
		}
		// Allow string concatenation
		if op == "+" {
			if (lt == TokenTypeName(tokens.TokenString) && rt == TokenTypeName(tokens.TokenString)) || (lt == TokenTypeName(tokens.TokenString) && IsNumericType(rt)) || (rt == TokenTypeName(tokens.TokenString) && IsNumericType(lt)) {
				t := TokenTypeName(tokens.TokenString)
				table.NodeToType[e] = t
				return t
			}
		}
		// Division always yields floating point in Loom
		if op == "/" {
			t := TokenTypeName(tokens.TokenF64)
			table.NodeToType[e] = t
			return t
		}
		if IsNumericType(lt) && IsNumericType(rt) {
			if IsFloatType(lt) || IsFloatType(rt) {
				t := TokenTypeName(tokens.TokenF64)
				table.NodeToType[e] = t
				return t
			}
			table.NodeToType[e] = lt
			return lt
		}
		return ""
	case *ast.AssignExpr:
		var tt string
		// Handle assignment to array element: arr[idx] = value
		if ix, ok := x.Target.(*ast.IndexExpr); ok {
			bt := inferExprType(ix.Base, ts, table, module, diags)
			vt := inferExprType(x.Value, ts, table, module, diags)
			el := ""
			if len(bt) >= 2 && bt[:2] == "[]" {
				el = bt[2:]
			}
			if el != "" && !assignable(el, vt) {
				*diags = append(*diags, CheckDiag{Message: formatAssign(el, vt), Span: x.OpTok.Span})
			}
			table.NodeToType[e] = el
			return el
		}
		if id, ok := x.Target.(*ast.IdentifierExpr); ok {
			if t, ok2 := ts.resolve(id.Name); ok2 {
				tt = t
			} else if sym, ok3 := module.Resolve(id.Name); ok3 {
				if sym.Kind == SymVar {
					if gv, ok4 := sym.Node.(*ast.GlobalVarDecl); ok4 {
						tt = gv.Type
					}
				}
			}
		}
		vt := inferExprType(x.Value, ts, table, module, diags)
		switch x.Operator {
		case tokens.TokenEqual:
			if tt != "" && !assignable(tt, vt) {
				*diags = append(*diags, CheckDiag{Message: formatAssign(tt, vt), Span: x.OpTok.Span})
			}
		case tokens.TokenPlusEqual, tokens.TokenMinusEqual, tokens.TokenStarEqual, tokens.TokenSlashEqual:
			if x.Operator == tokens.TokenPlusEqual {
				// allow string concatenation with +=
				if !(tt == TokenTypeName(tokens.TokenString) && (vt == TokenTypeName(tokens.TokenString) || IsNumericType(vt))) {
					if !(IsNumericType(tt) && IsNumericType(vt)) {
						*diags = append(*diags, CheckDiag{Message: "compound arithmetic assignment requires numeric types", Span: x.OpTok.Span})
					}
				}
			} else if !(IsNumericType(tt) && IsNumericType(vt)) {
				*diags = append(*diags, CheckDiag{Message: "compound arithmetic assignment requires numeric types", Span: x.OpTok.Span})
			}
		case tokens.TokenPercentEqual:
			if !(IsIntegerType(tt) && IsIntegerType(vt)) {
				*diags = append(*diags, CheckDiag{Message: "compound modulus assignment requires integer types", Span: x.OpTok.Span})
			}
		default:
			if tt != "" && !assignable(tt, vt) {
				*diags = append(*diags, CheckDiag{Message: formatAssign(tt, vt), Span: x.OpTok.Span})
			}
		}
		table.NodeToType[e] = tt
		return tt
	case *ast.CastExpr:
		_ = inferExprType(x.Expr, ts, table, module, diags)
		return x.TargetType
	case *ast.CallExpr:
		// Constructor or type-call: ClassName(args) or EnumName(args)
		if id, ok := x.Callee.(*ast.IdentifierExpr); ok {
			if sym, ok2 := module.Resolve(id.Name); ok2 {
				switch n := sym.Node.(type) {
				case *ast.ClassDecl:
					// select builder by arity, validate args
					for _, b := range n.Builders {
						if len(b.Params) == len(x.Args) {
							_ = checkCallAgainst(b.Params, id.Name, id.Name, x.Args, ts, table, module, diags)
							table.NodeToType[e] = id.Name
							return id.Name
						}
					}
					// fallback: still mark as this class type
					table.NodeToType[e] = id.Name
					return id.Name
				case *ast.EnumDecl:
					// constructing enum value; type is enum name
					for _, a := range x.Args {
						_ = inferExprType(a, ts, table, module, diags)
					}
					table.NodeToType[e] = id.Name
					return id.Name
				}
			}
		}
		// Instance method call: obj.method(args)
		if macc, ok := x.Callee.(*ast.MemberAccessExpr); ok {
			objType := inferExprType(macc.Object, ts, table, module, diags)
			// Universal type(): returns string
			if macc.Member == "type" && len(x.Args) == 0 {
				t := TokenTypeName(tokens.TokenString)
				table.NodeToType[e] = t
				return t
			}
			// Numeric intrinsic methods
			if objType != "" && IsNumericType(objType) {
				name := macc.Member
				switch name {
				case "to_dec", "to_hex", "to_bin", "to_oct", "to_base", "to_sci":
					// Return string
					// Validate simple arities
					if name == "to_base" {
						if len(x.Args) < 1 {
							*diags = append(*diags, CheckDiag{Message: "to_base expects 1 argument: base", Span: macc.MemberTok.Span, Hint: "usage: x.to_base(36)"})
						}
					} else if name == "to_sci" {
						if len(x.Args) > 1 {
							*diags = append(*diags, CheckDiag{Message: "to_sci takes at most 1 argument: precision", Span: macc.MemberTok.Span})
						}
					}
					t := TokenTypeName(tokens.TokenString)
					table.NodeToType[e] = t
					return t
				case "to_float":
					// 0 or 1 arg; if 1 arg must be float type token identifier
					if len(x.Args) == 0 {
						// default target based on source integer width
						ret := defaultFloatForInt(objType)
						table.NodeToType[e] = ret
						return ret
					}
					if len(x.Args) == 1 {
						if id, ok := x.Args[0].(*ast.IdentifierExpr); ok {
							if id.Name == TokenTypeName(tokens.TokenF16) || id.Name == TokenTypeName(tokens.TokenF32) || id.Name == TokenTypeName(tokens.TokenF64) {
								table.NodeToType[e] = id.Name
								return id.Name
							}
							*diags = append(*diags, CheckDiag{Message: "to_float expects a float type (f16|f32|f64)", Span: id.Tok.Span})
							return ""
						}
						*diags = append(*diags, CheckDiag{Message: "to_float expects a type identifier (f16|f32|f64)", Span: macc.MemberTok.Span})
						return ""
					}
					*diags = append(*diags, CheckDiag{Message: "to_float takes 0 or 1 arguments", Span: macc.MemberTok.Span})
					return ""
				}
			}
			if params, ret, ok2 := findMethodOnType(objType, macc.Member, module); ok2 {
				retType := checkCallAgainst(params, ret, objType+"."+macc.Member, x.Args, ts, table, module, diags)
				if retType == "self" && objType != "" {
					retType = objType
				}
				table.NodeToType[e] = retType
				return retType
			}
			// Module namespaced function: io.print
			if objId, ok3 := macc.Object.(*ast.IdentifierExpr); ok3 {
				if modSym, ok4 := module.Resolve(objId.Name); ok4 && modSym.Kind == SymModule {
					if modScope, ok5 := modSym.Node.(*Scope); ok5 {
						if sym, ok6 := modScope.Resolve(macc.Member); ok6 && sym.Kind == SymFunc {
							if params, ret, ok7 := getFuncSignature(sym); ok7 {
								retType := checkCallAgainst(params, ret, objId.Name+"::"+macc.Member, x.Args, ts, table, modScope, diags)
								table.NodeToType[e] = retType
								return retType
							}
						}
					}
				}
			}
		}
		// Top-level function: foo(args)
		if id, ok := x.Callee.(*ast.IdentifierExpr); ok {
			if sym, ok2 := module.Resolve(id.Name); ok2 && sym.Kind == SymFunc {
				if params, ret, ok3 := getFuncSignature(sym); ok3 {
					retType := checkCallAgainst(params, ret, id.Name, x.Args, ts, table, module, diags)
					table.NodeToType[e] = retType
					return retType
				}
			}
		}
		// Fallback: still infer argument expressions
		for _, a := range x.Args {
			_ = inferExprType(a, ts, table, module, diags)
		}
		return ""
	case *ast.MemberAccessExpr:
		ot := inferExprType(x.Object, ts, table, module, diags)
		// self.field resolution for known class fields
		if ot != "" {
			if sym, ok := module.Resolve(ot); ok {
				if cd, ok2 := sym.Node.(*ast.ClassDecl); ok2 {
					for _, f := range cd.Fields {
						if f.Name == x.Member {
							table.NodeToType[e] = f.Type
							return f.Type
						}
					}
				} else if sd, ok2 := sym.Node.(*ast.StructDecl); ok2 {
					for _, f := range sd.Fields {
						if f.Name == x.Member {
							table.NodeToType[e] = f.Type
							return f.Type
						}
					}
				} else if ed, ok2 := sym.Node.(*ast.EnumDecl); ok2 {
					// Enum case reference has type of the enum
					for _, c := range ed.Cases {
						if c.Name == x.Member {
							table.NodeToType[e] = ot
							return ot
						}
					}
				}
			}
		}
		return ""
	case *ast.TernaryExpr:
		ct := inferExprType(x.Cond, ts, table, module, diags)
		if ct != TokenTypeName(tokens.TokenBool) {
			*diags = append(*diags, CheckDiag{Message: "ternary condition must be bool", Span: x.CTok.Span})
		}
		lt := inferExprType(x.ThenExpr, ts, table, module, diags)
		rt := inferExprType(x.ElseExpr, ts, table, module, diags)
		if lt == rt {
			table.NodeToType[e] = lt
			return lt
		}
		return ""
	default:
		return ""
	}
}

func tokensToSymbol(t tokens.TokenType) string {
	switch t {
	case tokens.TokenAnd:
		return "&&"
	case tokens.TokenOr:
		return "||"
	case tokens.TokenDoubleEqual:
		return "=="
	case tokens.TokenNotEqual:
		return "!="
	case tokens.TokenLess:
		return "<"
	case tokens.TokenLessEqual:
		return "<="
	case tokens.TokenGreater:
		return ">"
	case tokens.TokenGreaterEqual:
		return ">="
	case tokens.TokenPlus:
		return "+"
	case tokens.TokenMinus:
		return "-"
	case tokens.TokenStar:
		return "*"
	case tokens.TokenSlash:
		return "/"
	case tokens.TokenPercent:
		return "%"
	case tokens.TokenBitAnd:
		return "&"
	case tokens.TokenBitOr:
		return "|"
	case tokens.TokenBitXor:
		return "^"
	case tokens.TokenBitNot:
		return "~"
	case tokens.TokenShiftLeft:
		return "<<"
	case tokens.TokenShiftRight:
		return ">>"
	default:
		return tokens.TokenTypeName(t)
	}
}

func assignable(target, value string) bool {
	// Allow assigning null to any type except bit
	if value == "null" {
		if target == TokenTypeName(tokens.TokenBit) {
			return false
		}
		return true
	}
	// bit is very strict: only assignable from bit
	if target == TokenTypeName(tokens.TokenBit) {
		return value == TokenTypeName(tokens.TokenBit)
	}
	if target == value {
		return true
	}
	if IsNumericType(target) && IsNumericType(value) {
		return true
	}
	// arrays: []any accepts any []T
	if target == "[]any" && len(value) >= 2 && value[:2] == "[]" {
		return true
	}
	// identical array types must match exactly
	if len(target) >= 2 && target[:2] == "[]" && len(value) >= 2 && value[:2] == "[]" {
		return target == value
	}
	return false
}

// ----- Numeric literal range checks -----

func literalArgFits(target string, e ast.Expr) bool {
	ok, _ := literalFitsType(target, e)
	return ok
}

func literalFitsType(target string, e ast.Expr) (bool, string) {
	isNum, isInt, signed, i64, u64, isFloat, f64 := evalNumericLiteral(e)
	if !isNum {
		return true, ""
	}
	if target == TokenTypeName(tokens.TokenBit) {
		if !isInt {
			return false, "literal out of range for bit"
		}
		if signed {
			if i64 == 0 || i64 == 1 {
				return true, ""
			}
			return false, "literal out of range for bit"
		}
		if u64 == 0 || u64 == 1 {
			return true, ""
		}
		return false, "literal out of range for bit"
	}
	if IsIntegerType(target) {
		min, maxSigned, umax, isUnsigned, have := intLimits(target)
		if !have {
			return true, ""
		}
		if isUnsigned {
			if !isInt {
				return true, ""
			}
			if signed {
				return false, fmt.Sprintf("negative literal not allowed for %s", target)
			}
			if u64 <= umax {
				return true, ""
			}
			return false, fmt.Sprintf("literal exceeds max of %s", target)
		}
		if !isInt {
			return true, ""
		}
		if i64 >= min && i64 <= maxSigned {
			return true, ""
		}
		return false, fmt.Sprintf("literal out of range for %s", target)
	}
	if IsFloatType(target) {
		val := 0.0
		if isFloat {
			val = f64
		} else if isInt {
			if signed {
				val = float64(i64)
			} else {
				val = float64(u64)
			}
		}
		switch target {
		case TokenTypeName(tokens.TokenF16):
			if math.Abs(val) <= 65504.0 {
				return true, ""
			}
			return false, "literal out of range for f16"
		case TokenTypeName(tokens.TokenF32):
			if math.Abs(val) <= math.MaxFloat32 {
				return true, ""
			}
			return false, "literal out of range for f32"
		case TokenTypeName(tokens.TokenF64):
			return true, ""
		}
	}
	return true, ""
}

func evalNumericLiteral(e ast.Expr) (isNum bool, isInt bool, signed bool, i64 int64, u64 uint64, isFloat bool, f64 float64) {
	switch x := e.(type) {
	case *ast.NumberLiteralExpr:
		if x.Value.IsFloat {
			v, err := strconv.ParseFloat(x.Value.Digits, 64)
			if err == nil {
				return true, false, false, 0, 0, true, v
			}
			return false, false, false, 0, 0, false, 0
		}
		v, err := strconv.ParseInt(x.Value.Digits, x.Value.Base, 64)
		if err == nil {
			return true, true, v < 0, v, uint64(v), false, 0
		}
		uv, err2 := strconv.ParseUint(x.Value.Digits, x.Value.Base, 64)
		if err2 == nil {
			return true, true, false, int64(uv), uv, false, 0
		}
		return false, false, false, 0, 0, false, 0
	case *ast.UnaryExpr:
		if x.Operator == tokens.TokenMinus {
			isNum2, isInt2, _, i642, _, isFloat2, f642 := evalNumericLiteral(x.Operand)
			if !isNum2 {
				return false, false, false, 0, 0, false, 0
			}
			if isFloat2 {
				return true, false, true, 0, 0, true, -f642
			}
			if isInt2 {
				return true, true, true, -i642, uint64(-i642), false, 0
			}
		}
		return false, false, false, 0, 0, false, 0
	default:
		return false, false, false, 0, 0, false, 0
	}
}

func intLimits(target string) (min int64, maxSigned int64, umax uint64, isUnsigned bool, ok bool) {
	switch target {
	case TokenTypeName(tokens.TokenI8):
		return -128, 127, 0, false, true
	case TokenTypeName(tokens.TokenI16):
		return -32768, 32767, 0, false, true
	case TokenTypeName(tokens.TokenI32):
		return -2147483648, 2147483647, 0, false, true
	case TokenTypeName(tokens.TokenI64):
		return -9223372036854775808, 9223372036854775807, 0, false, true
	case TokenTypeName(tokens.TokenU8):
		return 0, 0, 255, true, true
	case TokenTypeName(tokens.TokenU16):
		return 0, 0, 65535, true, true
	case TokenTypeName(tokens.TokenU32):
		return 0, 0, 4294967295, true, true
	case TokenTypeName(tokens.TokenU64):
		return 0, 0, 18446744073709551615, true, true
	default:
		return 0, 0, 0, false, false
	}
}
