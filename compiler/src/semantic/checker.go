package semantic

import (
	"compiler/src/ast"
	"compiler/src/tokens"
	"fmt"
)

type CheckDiag struct{ Message string }

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
			*diags = append(*diags, CheckDiag{Message: formatAssign(g.Type, vt)})
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
					*diags = append(*diags, CheckDiag{Message: formatAssign(n.Type, vt)})
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
					*diags = append(*diags, CheckDiag{Message: formatAssign(n.Type, vt)})
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
				if retType != "" && retType != "void" && !assignable(retType, vt) {
					*diags = append(*diags, CheckDiag{Message: formatAssign(retType, vt)})
				}
			} else if retType != "" && retType != "void" {
				*diags = append(*diags, CheckDiag{Message: "missing return value"})
			}
		case *ast.IfStmt:
			ct := inferExprType(n.Cond, ts, table, module, diags)
			if ct != "bool" {
				*diags = append(*diags, CheckDiag{Message: "if condition must be bool"})
			}
			checkBlock(n.Then.Stmts, newTypeScope(ts), retType, table, module, diags)
			for _, ei := range n.Elifs {
				ct2 := inferExprType(ei.Cond, ts, table, module, diags)
				if ct2 != "bool" {
					*diags = append(*diags, CheckDiag{Message: "elif condition must be bool"})
				}
				checkBlock(ei.Then.Stmts, newTypeScope(ts), retType, table, module, diags)
			}
			if n.Else != nil {
				checkBlock(n.Else.Stmts, newTypeScope(ts), retType, table, module, diags)
			}
		case *ast.WhileStmt:
			ct := inferExprType(n.Cond, ts, table, module, diags)
			if ct != "bool" {
				*diags = append(*diags, CheckDiag{Message: "while condition must be bool"})
			}
			checkBlock(n.Body.Stmts, newTypeScope(ts), retType, table, module, diags)
		case *ast.DoWhileStmt:
			checkBlock(n.Body.Stmts, newTypeScope(ts), retType, table, module, diags)
			ct := inferExprType(n.Cond, ts, table, module, diags)
			if ct != "bool" {
				*diags = append(*diags, CheckDiag{Message: "do-while condition must be bool"})
			}
		case *ast.LoopStmt:
			ft := inferExprType(n.From, ts, table, module, diags)
			_ = inferExprType(n.To, ts, table, module, diags)
			if n.Step != nil {
				_ = inferExprType(n.Step, ts, table, module, diags)
			}
			ls := newTypeScope(ts)
			if ft == "" {
				ft = "i32"
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
	for i, a := range args {
		at := inferExprType(a, ts, table, module, diags)
		if i < len(sigParams) {
			pt := sigParams[i].Type
			if pt != "" && !assignable(pt, at) {
				*diags = append(*diags, CheckDiag{Message: fmt.Sprintf("argument %d to %s: %s", i+1, callName, formatAssign(pt, at))})
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
		if x.Name == "self" && ts.selfType != "" {
			table.NodeToType[e] = ts.selfType
			return ts.selfType
		}
		if t, ok := ts.resolve(x.Name); ok {
			table.NodeToType[e] = t
			return t
		}
		return ""
	case *ast.NumberLiteralExpr:
		if x.Value.Suffix != "" {
			table.NodeToType[e] = x.Value.Suffix
			return x.Value.Suffix
		}
		if x.Value.IsFloat {
			table.NodeToType[e] = "f64"
			return "f64"
		}
		table.NodeToType[e] = "i32"
		return "i32"
	case *ast.StringLiteralExpr:
		table.NodeToType[e] = "string"
		return "string"
	case *ast.CharLiteralExpr:
		table.NodeToType[e] = "char"
		return "char"
	case *ast.BoolLiteralExpr:
		table.NodeToType[e] = "bool"
		return "bool"
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
				*diags = append(*diags, CheckDiag{Message: "++/-- require numeric operand"})
			}
		case tokens.TokenNot:
			if ot != "bool" {
				*diags = append(*diags, CheckDiag{Message: "! operand must be bool"})
			}
		case tokens.TokenBitNot:
			if !IsIntegerType(ot) {
				*diags = append(*diags, CheckDiag{Message: "~ operand must be integer"})
			}
		}
		table.NodeToType[e] = ot
		return ot
	case *ast.BinaryExpr:
		lt := inferExprType(x.Left, ts, table, module, diags)
		rt := inferExprType(x.Right, ts, table, module, diags)
		op := tokensToSymbol(x.Operator)
		if op == "&&" || op == "||" {
			if lt != "bool" || rt != "bool" {
				*diags = append(*diags, CheckDiag{Message: "logical operands must be bool"})
			}
			table.NodeToType[e] = "bool"
			return "bool"
		}
		if op == "==" || op == "!=" || op == "<" || op == "<=" || op == ">" || op == ">=" {
			if !(IsNumericType(lt) && IsNumericType(rt)) {
				*diags = append(*diags, CheckDiag{Message: "comparison operands must be numeric"})
			}
			table.NodeToType[e] = "bool"
			return "bool"
		}
		if op == "&" || op == "|" || op == "^" || op == "<<" || op == ">>" {
			if !(IsIntegerType(lt) && IsIntegerType(rt)) {
				*diags = append(*diags, CheckDiag{Message: "bitwise operands must be integers"})
			}
			table.NodeToType[e] = lt
			return lt
		}
		// Allow string concatenation
		if op == "+" {
			if (lt == "string" && rt == "string") || (lt == "string" && IsNumericType(rt)) || (rt == "string" && IsNumericType(lt)) {
				table.NodeToType[e] = "string"
				return "string"
			}
		}
		if IsNumericType(lt) && IsNumericType(rt) {
			if IsFloatType(lt) || IsFloatType(rt) {
				table.NodeToType[e] = "f64"
				return "f64"
			}
			table.NodeToType[e] = lt
			return lt
		}
		return ""
	case *ast.AssignExpr:
		var tt string
		if id, ok := x.Target.(*ast.IdentifierExpr); ok {
			if t, ok2 := ts.resolve(id.Name); ok2 {
				tt = t
			}
		}
		// self.field assignment: treat lhs type from field if known
		if macc, ok := x.Target.(*ast.MemberAccessExpr); ok && ts.selfType != "" {
			if _, isSelf := macc.Object.(*ast.IdentifierExpr); isSelf {
				if ts.selfType != "" {
					// best-effort: lookup field type on the class
					if sym, ok := module.Resolve(ts.selfType); ok {
						if cd, ok2 := sym.Node.(*ast.ClassDecl); ok2 {
							for _, f := range cd.Fields {
								if f.Name == macc.Member {
									tt = f.Type
									break
								}
							}
						}
					}
				}
			}
		}
		vt := inferExprType(x.Value, ts, table, module, diags)
		switch x.Operator {
		case tokens.TokenEqual:
			if tt != "" && !assignable(tt, vt) {
				*diags = append(*diags, CheckDiag{Message: formatAssign(tt, vt)})
			}
		case tokens.TokenPlusEqual, tokens.TokenMinusEqual, tokens.TokenStarEqual, tokens.TokenSlashEqual:
			if x.Operator == tokens.TokenPlusEqual {
				// allow string concatenation with +=
				if !(tt == "string" && (vt == "string" || IsNumericType(vt))) {
					if !(IsNumericType(tt) && IsNumericType(vt)) {
						*diags = append(*diags, CheckDiag{Message: "compound arithmetic assignment requires numeric types"})
					}
				}
			} else if !(IsNumericType(tt) && IsNumericType(vt)) {
				*diags = append(*diags, CheckDiag{Message: "compound arithmetic assignment requires numeric types"})
			}
		case tokens.TokenPercentEqual:
			if !(IsIntegerType(tt) && IsIntegerType(vt)) {
				*diags = append(*diags, CheckDiag{Message: "compound modulus assignment requires integer types"})
			}
		default:
			if tt != "" && !assignable(tt, vt) {
				*diags = append(*diags, CheckDiag{Message: formatAssign(tt, vt)})
			}
		}
		table.NodeToType[e] = tt
		return tt
	case *ast.CastExpr:
		_ = inferExprType(x.Expr, ts, table, module, diags)
		return x.TargetType
	case *ast.CallExpr:
		// Instance method call: obj.method(args)
		if macc, ok := x.Callee.(*ast.MemberAccessExpr); ok {
			objType := inferExprType(macc.Object, ts, table, module, diags)
			if params, ret, ok2 := findMethodOnType(objType, macc.Member, module); ok2 {
				retType := checkCallAgainst(params, ret, objType+"."+macc.Member, x.Args, ts, table, module, diags)
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
				}
			}
		}
		return ""
	case *ast.TernaryExpr:
		ct := inferExprType(x.Cond, ts, table, module, diags)
		if ct != "bool" {
			*diags = append(*diags, CheckDiag{Message: "ternary condition must be bool"})
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
	if target == value {
		return true
	}
	if IsNumericType(target) && IsNumericType(value) {
		return true
	}
	return false
}
