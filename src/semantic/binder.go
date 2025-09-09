package semantic

import (
	"compiler/src/ast"
	"compiler/src/tokens"
	"fmt"
)

type Diagnostic struct {
	Message string
	Span    tokens.TokenSpan
	Hint    string
}

// Bind walks a file and binds names in function bodies.
func Bind(file *ast.File, module *Scope) []Diagnostic {
	var diags []Diagnostic
	// Bind top-level
	for _, d := range file.Decls {
		switch n := d.(type) {
		case *ast.FuncDecl:
			fnScope := NewScope(module)
			for _, p := range n.Params {
				if err := fnScope.Define(Symbol{Name: p.Name, Kind: SymVar, Node: p}); err != nil {
					diags = append(diags, Diagnostic{Message: err.Error(), Span: p.Tok.Span, Hint: "parameter name already defined in this scope"})
				}
			}
			bindStmts(n.Body, fnScope, &diags)
		case *ast.ClassDecl:
			bindTypeDecl(n.Name, n.Fields, n.Methods, n.Builders, module, &diags, true)
		case *ast.StructDecl:
			bindTypeDecl(n.Name, n.Fields, n.Methods, n.Builders, module, &diags, false)
		case *ast.EnumDecl:
			bindTypeDecl(n.Name, n.Fields, n.Methods, n.Builders, module, &diags, true)
		}
	}
	return diags
}

func bindTypeDecl(name string, fields []ast.FieldDecl, methods []ast.MethodDecl, builders []ast.MethodDecl, module *Scope, diags *[]Diagnostic, allowSelf bool) {
	tScope := NewScope(module)
	for _, f := range fields {
		if err := tScope.Define(Symbol{Name: f.Name, Kind: SymField, Node: f}); err != nil {
			*diags = append(*diags, Diagnostic{Message: fmt.Sprintf("%s.%s: %v", name, f.Name, err)})
		}
	}
	for _, b := range builders {
		if err := tScope.Define(Symbol{Name: "builder", Kind: SymFunc, Node: b}); err != nil {
			*diags = append(*diags, Diagnostic{Message: fmt.Sprintf("%s.builder: %v", name, err)})
		}
		mScope := NewScope(tScope)
		if allowSelf {
			_ = mScope.Define(Symbol{Name: "self", Kind: SymVar, Node: b})
		}
		for _, p := range b.Params {
			if err := mScope.Define(Symbol{Name: p.Name, Kind: SymVar, Node: p}); err != nil {
				*diags = append(*diags, Diagnostic{Message: err.Error(), Span: p.Tok.Span})
			}
		}
		bindStmts(b.Body, mScope, diags)
	}
	for _, m := range methods {
		if err := tScope.Define(Symbol{Name: m.Name, Kind: SymFunc, Node: m}); err != nil {
			*diags = append(*diags, Diagnostic{Message: fmt.Sprintf("%s.%s: %v", name, m.Name, err)})
		}
		mScope := NewScope(tScope)
		if allowSelf {
			_ = mScope.Define(Symbol{Name: "self", Kind: SymVar, Node: m})
		}
		for _, p := range m.Params {
			if err := mScope.Define(Symbol{Name: p.Name, Kind: SymVar, Node: p}); err != nil {
				*diags = append(*diags, Diagnostic{Message: err.Error(), Span: p.Tok.Span})
			}
		}
		bindStmts(m.Body, mScope, diags)
	}
}

func bindStmts(stmts []ast.Stmt, scope *Scope, diags *[]Diagnostic) {
	for _, s := range stmts {
		switch n := s.(type) {
		case *ast.BlockStmt:
			inner := NewScope(scope)
			bindStmts(n.Stmts, inner, diags)
		case *ast.LetStmt:
			if n.Value != nil {
				bindExpr(n.Value, scope, diags)
			}
			if err := scope.Define(Symbol{Name: n.Name, Kind: SymVar, Node: n}); err != nil {
				*diags = append(*diags, Diagnostic{Message: err.Error(), Span: n.NameTok.Span})
			}
		case *ast.VarStmt:
			if n.Value != nil {
				bindExpr(n.Value, scope, diags)
			}
			if err := scope.Define(Symbol{Name: n.Name, Kind: SymVar, Node: n}); err != nil {
				*diags = append(*diags, Diagnostic{Message: err.Error(), Span: n.NameTok.Span})
			}
		case *ast.ExpressionStmt:
			bindExpr(n.E, scope, diags)
		case *ast.ReturnStmt:
			if n.Value != nil {
				bindExpr(n.Value, scope, diags)
			}
		case *ast.IfStmt:
			bindExpr(n.Cond, scope, diags)
			bindStmts(n.Then.Stmts, NewScope(scope), diags)
			for _, ei := range n.Elifs {
				bindExpr(ei.Cond, scope, diags)
				bindStmts(ei.Then.Stmts, NewScope(scope), diags)
			}
			if n.Else != nil {
				bindStmts(n.Else.Stmts, NewScope(scope), diags)
			}
		case *ast.WhileStmt:
			bindExpr(n.Cond, scope, diags)
			bindStmts(n.Body.Stmts, NewScope(scope), diags)
		case *ast.DoWhileStmt:
			bindStmts(n.Body.Stmts, NewScope(scope), diags)
			bindExpr(n.Cond, scope, diags)
		case *ast.LoopStmt:
			ls := NewScope(scope)
			if err := ls.Define(Symbol{Name: n.VarName, Kind: SymVar, Node: n}); err != nil {
				*diags = append(*diags, Diagnostic{Message: err.Error()})
			}
			bindExpr(n.From, scope, diags)
			bindExpr(n.To, scope, diags)
			if n.Step != nil {
				bindExpr(n.Step, scope, diags)
			}
			bindStmts(n.Body.Stmts, ls, diags)
		}
	}
}

func bindExpr(e ast.Expr, scope *Scope, diags *[]Diagnostic) {
	switch x := e.(type) {
	case *ast.IdentifierExpr:
		if _, ok := scope.Resolve(x.Name); !ok {
			// Allow builtin type names to appear in expression position (e.g., to_float(f32))
			if NameToTokenType(x.Name) != tokens.TokenInvalid {
				return
			}
			// allow 'self' in class/enum methods
			if x.Name == "self" {
				*diags = append(*diags, Diagnostic{Message: "'self' is only valid inside class/enum instance methods", Span: x.Tok.Span, Hint: "use the type name for static access, or pass the instance explicitly"})
				return
			}
			// allow 'super' identifier where 'self' is available (class scopes)
			if x.Name == "super" {
				if _, ok2 := scope.Resolve("self"); ok2 {
					return
				}
				*diags = append(*diags, Diagnostic{Message: "'super' is only valid inside class constructors", Span: x.Tok.Span, Hint: "use inside a class constructor to call the base"})
				return
			}
			*diags = append(*diags, Diagnostic{Message: fmt.Sprintf("undefined identifier '%s'", x.Name), Span: x.Tok.Span, Hint: "define it earlier, import it, or check for typos"})
		} else if x.Name == "self" {
			// 'self' is permitted in class/enum methods
			// No extra const/static restrictions
		} else if sym, ok := scope.Resolve(x.Name); ok && sym.Kind == SymModule {
			// ok: allow module namespace symbol to be the left of member access; deeper resolution happens later
		}
	case *ast.UnaryExpr:
		bindExpr(x.Operand, scope, diags)
	case *ast.BinaryExpr:
		bindExpr(x.Left, scope, diags)
		bindExpr(x.Right, scope, diags)
	case *ast.AssignExpr:
		bindExpr(x.Target, scope, diags)
		bindExpr(x.Value, scope, diags)
	case *ast.CallExpr:
		bindExpr(x.Callee, scope, diags)
		for _, a := range x.Args {
			bindExpr(a, scope, diags)
		}
	case *ast.MemberAccessExpr:
		bindExpr(x.Object, scope, diags)
		if objId, ok := x.Object.(*ast.IdentifierExpr); ok {
			if sym, ok2 := scope.Resolve(objId.Name); ok2 && sym.Kind == SymModule {
				if modScope, ok3 := sym.Node.(*Scope); ok3 {
					if _, ok4 := modScope.Resolve(x.Member); !ok4 {
						*diags = append(*diags, Diagnostic{Message: fmt.Sprintf("undefined member '%s' in module '%s'", x.Member, objId.Name), Span: x.MemberTok.Span, Hint: "did you mean to import or alias a different symbol?"})
					}
				}
			}
		}
	case *ast.CastExpr:
		bindExpr(x.Expr, scope, diags)
	case *ast.TernaryExpr:
		bindExpr(x.Cond, scope, diags)
		bindExpr(x.ThenExpr, scope, diags)
		bindExpr(x.ElseExpr, scope, diags)
	case *ast.IndexExpr:
		bindExpr(x.Base, scope, diags)
		bindExpr(x.Index, scope, diags)
	case *ast.ArrayLiteralExpr:
		for _, el := range x.Elements {
			bindExpr(el, scope, diags)
		}
	default:
		// literals
	}
}
