package semantic

import (
	"compiler/src/ast"
	"fmt"
)

type CheckDiag struct{ Message string }

type typeScope struct {
	parent *typeScope
	types  map[string]string
}

func newTypeScope(parent *typeScope) *typeScope {
	return &typeScope{parent: parent, types: map[string]string{}}
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

func opStr(tt ast.Expr) string { return "" }

// opSymbol maps token names used in main to C-style symbols here.
func opSymbol(op string) string { return op }

func formatAssign(target, value string) string {
	return fmt.Sprintf("cannot assign %s to %s", value, target)
}

// CheckExpressions performs type inference/checking for basic rules.
func CheckExpressions(file *ast.File, types *TypeTable) []CheckDiag {
	var diags []CheckDiag
	for _, d := range file.Decls {
		switch n := d.(type) {
		case *ast.GlobalVarDecl:
			ts := newTypeScope(nil)
			checkGlobal(n, ts, types, &diags)
		case *ast.FuncDecl:
			ts := newTypeScope(nil)
			for _, p := range n.Params {
				ts.define(p.Name, p.Type)
			}
			checkBlock(n.Body, ts, n.ReturnType, types, &diags)
		case *ast.ClassDecl:
			for _, m := range n.Methods {
				ts := newTypeScope(nil)
				for _, p := range m.Params {
					ts.define(p.Name, p.Type)
				}
				checkBlock(m.Body, ts, m.ReturnType, types, &diags)
			}
			for _, b := range n.Builders {
				ts := newTypeScope(nil)
				for _, p := range b.Params {
					ts.define(p.Name, p.Type)
				}
				checkBlock(b.Body, ts, "void", types, &diags)
			}
		case *ast.StructDecl:
			for _, m := range n.Methods {
				ts := newTypeScope(nil)
				for _, p := range m.Params {
					ts.define(p.Name, p.Type)
				}
				checkBlock(m.Body, ts, m.ReturnType, types, &diags)
			}
		}
	}
	return diags
}

func checkGlobal(g *ast.GlobalVarDecl, ts *typeScope, table *TypeTable, diags *[]CheckDiag) {
	if g.Value != nil {
		vt := inferExprType(g.Value, ts, table, diags)
		if g.Type != "" && !assignable(g.Type, vt) {
			*diags = append(*diags, CheckDiag{Message: fmt.Sprintf("%s", formatAssign(g.Type, vt))})
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

func checkBlock(stmts []ast.Stmt, ts *typeScope, retType string, table *TypeTable, diags *[]CheckDiag) {
	for _, s := range stmts {
		switch n := s.(type) {
		case *ast.BlockStmt:
			checkBlock(n.Stmts, newTypeScope(ts), retType, table, diags)
		case *ast.LetStmt:
			if n.Value != nil {
				vt := inferExprType(n.Value, ts, table, diags)
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
				vt := inferExprType(n.Value, ts, table, diags)
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
			_ = inferExprType(n.E, ts, table, diags)
		case *ast.ReturnStmt:
			if n.Value != nil {
				vt := inferExprType(n.Value, ts, table, diags)
				if retType != "" && retType != "void" && !assignable(retType, vt) {
					*diags = append(*diags, CheckDiag{Message: formatAssign(retType, vt)})
				}
			} else if retType != "" && retType != "void" {
				*diags = append(*diags, CheckDiag{Message: "missing return value"})
			}
		case *ast.IfStmt:
			ct := inferExprType(n.Cond, ts, table, diags)
			if ct != "bool" {
				*diags = append(*diags, CheckDiag{Message: "if condition must be bool"})
			}
			checkBlock(n.Then.Stmts, newTypeScope(ts), retType, table, diags)
			for _, ei := range n.Elifs {
				ct2 := inferExprType(ei.Cond, ts, table, diags)
				if ct2 != "bool" {
					*diags = append(*diags, CheckDiag{Message: "elif condition must be bool"})
				}
				checkBlock(ei.Then.Stmts, newTypeScope(ts), retType, table, diags)
			}
			if n.Else != nil {
				checkBlock(n.Else.Stmts, newTypeScope(ts), retType, table, diags)
			}
		case *ast.WhileStmt:
			ct := inferExprType(n.Cond, ts, table, diags)
			if ct != "bool" {
				*diags = append(*diags, CheckDiag{Message: "while condition must be bool"})
			}
			checkBlock(n.Body.Stmts, newTypeScope(ts), retType, table, diags)
		case *ast.DoWhileStmt:
			checkBlock(n.Body.Stmts, newTypeScope(ts), retType, table, diags)
			ct := inferExprType(n.Cond, ts, table, diags)
			if ct != "bool" {
				*diags = append(*diags, CheckDiag{Message: "do-while condition must be bool"})
			}
		case *ast.LoopStmt:
			ft := inferExprType(n.From, ts, table, diags)
			_ = inferExprType(n.To, ts, table, diags)
			if n.Step != nil {
				_ = inferExprType(n.Step, ts, table, diags)
			}
			ls := newTypeScope(ts)
			if ft == "" {
				ft = "i32"
			}
			ls.define(n.VarName, ft)
			checkBlock(n.Body.Stmts, ls, retType, table, diags)
		}
	}
}

func inferExprType(e ast.Expr, ts *typeScope, table *TypeTable, diags *[]CheckDiag) string {
	switch x := e.(type) {
	case *ast.IdentifierExpr:
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
			vt := inferExprType(el, ts, table, diags)
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
		bt := inferExprType(x.Base, ts, table, diags)
		_ = inferExprType(x.Index, ts, table, diags)
		if len(bt) >= 2 && bt[:2] == "[]" {
			et := bt[2:]
			table.NodeToType[e] = et
			return et
		}
		return ""
	case *ast.UnaryExpr:
		ot := inferExprType(x.Operand, ts, table, diags)
		table.NodeToType[e] = ot
		return ot
	case *ast.BinaryExpr:
		lt := inferExprType(x.Left, ts, table, diags)
		rt := inferExprType(x.Right, ts, table, diags)
		// hardcode operator strings (mirror printer)
		long := x.Operator
		var op string
		switch long {
		case 0:
			op = ""
		default:
			op = tokensToSymbol(long)
		}
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
		vt := inferExprType(x.Value, ts, table, diags)
		if tt != "" && !assignable(tt, vt) {
			*diags = append(*diags, CheckDiag{Message: formatAssign(tt, vt)})
		}
		table.NodeToType[e] = tt
		return tt
	case *ast.CastExpr:
		_ = inferExprType(x.Expr, ts, table, diags)
		return x.TargetType
	case *ast.CallExpr:
		for _, a := range x.Args {
			_ = inferExprType(a, ts, table, diags)
		}
		return ""
	case *ast.MemberAccessExpr:
		_ = inferExprType(x.Object, ts, table, diags)
		return ""
	case *ast.TernaryExpr:
		ct := inferExprType(x.Cond, ts, table, diags)
		if ct != "bool" {
			*diags = append(*diags, CheckDiag{Message: "ternary condition must be bool"})
		}
		lt := inferExprType(x.ThenExpr, ts, table, diags)
		rt := inferExprType(x.ElseExpr, ts, table, diags)
		if lt == rt {
			table.NodeToType[e] = lt
			return lt
		}
		return ""
	default:
		return ""
	}
}

func tokensToSymbol(op interface{}) string { return "" }

func assignable(target, value string) bool {
	if target == value {
		return true
	}
	if IsNumericType(target) && IsNumericType(value) {
		return true
	}
	return false
}
