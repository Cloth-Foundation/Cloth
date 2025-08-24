package main

import (
	"compiler/src/ast"
	"compiler/src/lexer"
	"compiler/src/parser"
	"compiler/src/semantic"
	"compiler/src/tokens"
	"fmt"
	"os"
	"path/filepath"
)

func tokenOpString(tt tokens.TokenType) string {
	switch tt {
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
		return tokens.TokenTypeName(tt)
	}
}

func main() {
	if len(os.Args) < 2 {
		fmt.Println("Usage: loom <file>")
		return
	}
	path := os.Args[1]
	data, err := os.ReadFile(path)
	if err != nil {
		fmt.Printf("failed to read %s: %v\n", path, err)
		return
	}
	lx := lexer.New(string(data), path)
	p := parser.New(lx)
	file, errs := p.ParseFile()
	if len(errs) > 0 {
		fmt.Println("Parse errors:")
		for _, e := range errs {
			fmt.Println(" -", e)
		}
	}
	if file != nil {
		if file.Module != nil {
			fmt.Println("module:", file.Module.Name)
		}
		// Semantic: collect and resolve imports
		scope, semErrs := semantic.CollectTopLevel(file)
		for _, e := range semErrs {
			fmt.Println(" -", e)
		}
		root := filepath.Dir(filepath.Dir(path)) // project root above src/tests, adjust if needed
		loader := &semantic.FSLoader{Root: root}
		impErrs := semantic.ResolveImports(file, loader, scope)
		for _, e := range impErrs {
			fmt.Println(" -", e)
		}
		// Bind names in bodies
		bindDiags := semantic.Bind(file, scope)
		for _, d := range bindDiags {
			fmt.Println(" -", d.Message)
		}
		// Type resolution
		env := semantic.NewTypeEnv()
		ttab := semantic.NewTypeTable()
		tdiags := semantic.ResolveTypes(file, env, ttab)
		for _, td := range tdiags {
			fmt.Println(" -", td.Message)
		}
		// Type checking (stub for now)
		cdiags := semantic.CheckExpressions(file, ttab)
		for _, cd := range cdiags {
			fmt.Println(" -", cd.Message)
		}
		for _, im := range file.Imports {
			fmt.Println("import:", im.PathSegments)
		}
		for _, d := range file.Decls {
			switch dd := d.(type) {
			case *ast.GlobalVarDecl:
				kind := "var"
				if dd.IsLet {
					kind = "let"
				}
				if dd.Type != "" && dd.Value != nil {
					fmt.Println(kind, dd.Name+":"+dd.Type, "=", exprString(dd.Value))
				} else if dd.Type != "" {
					fmt.Println(kind, dd.Name+":"+dd.Type)
				} else if dd.Value != nil {
					fmt.Println(kind, dd.Name, "=", exprString(dd.Value))
				} else {
					fmt.Println(kind, dd.Name)
				}
			case *ast.FuncDecl:
				fmt.Println("func:", dd.Name)
				printParams(dd.Params)
				fmt.Println("  returns:", dd.ReturnType)
				if len(dd.Body) > 0 {
					fmt.Println("  body:")
					for _, st := range dd.Body {
						printStmt("    ", st)
					}
				}
			case *ast.ClassDecl:
				fmt.Println("class:", dd.Name)
				if len(dd.Fields) > 0 {
					fmt.Println("  fields:")
					for _, f := range dd.Fields {
						fmt.Printf("    - %s: %s\n", f.Name, f.Type)
					}
				}
				if len(dd.Builders) > 0 {
					fmt.Println("  builders:")
					for _, m := range dd.Builders {
						printMethod("    ", m)
					}
				}
				if len(dd.Methods) > 0 {
					fmt.Println("  methods:")
					for _, m := range dd.Methods {
						printMethod("    ", m)
					}
				}
			case *ast.StructDecl:
				fmt.Println("struct:", dd.Name)
				if len(dd.Fields) > 0 {
					fmt.Println("  fields:")
					for _, f := range dd.Fields {
						fmt.Printf("    - %s: %s\n", f.Name, f.Type)
					}
				}
				if len(dd.Methods) > 0 {
					fmt.Println("  methods:")
					for _, m := range dd.Methods {
						printMethod("    ", m)
					}
				}
			case *ast.EnumDecl:
				fmt.Println("enum:", dd.Name)
				if len(dd.Cases) > 0 {
					fmt.Println("  cases:")
					for _, c := range dd.Cases {
						args := ""
						for i, a := range c.Params {
							if i > 0 {
								args += ", "
							}
							args += exprString(a)
						}
						if args != "" {
							fmt.Printf("    - %s(%s)\n", c.Name, args)
						} else {
							fmt.Printf("    - %s\n", c.Name)
						}
					}
				}
			default:
				fmt.Printf("decl: %T\n", dd)
			}
		}
	}
	_ = tokens.TokenEndOfFile
}

func printParams(params []ast.Parameter) {
	if len(params) == 0 {
		fmt.Println("  params: []")
		return
	}
	fmt.Println("  params:")
	for _, p := range params {
		fmt.Printf("    - %s: %s\n", p.Name, p.Type)
	}
}

func printMethod(indent string, m ast.MethodDecl) {
	fmt.Printf(indent+"%s(", m.Name)
	for i, p := range m.Params {
		if i > 0 {
			fmt.Print(", ")
		}
		fmt.Printf("%s: %s", p.Name, p.Type)
	}
	fmt.Print(")")
	if m.ReturnType != "" && m.ReturnType != "void" {
		fmt.Printf(": %s", m.ReturnType)
	}
	fmt.Println()
	if len(m.Body) > 0 {
		fmt.Println(indent + "  body:")
		for _, st := range m.Body {
			printStmt(indent+"    ", st)
		}
	}
}

func printStmt(indent string, st ast.Stmt) {
	switch s := st.(type) {
	case *ast.BlockStmt:
		fmt.Println(indent + "block {")
		for _, inner := range s.Stmts {
			printStmt(indent+"  ", inner)
		}
		fmt.Println(indent + "}")
	case *ast.ExpressionStmt:
		fmt.Println(indent+"expr:", exprString(s.E))
	case *ast.ReturnStmt:
		if s.Value != nil {
			fmt.Println(indent+"ret:", exprString(s.Value))
		} else {
			fmt.Println(indent + "ret")
		}
	case *ast.BreakStmt:
		fmt.Println(indent + "break")
	case *ast.ContinueStmt:
		fmt.Println(indent + "continue")
	case *ast.LetStmt:
		if s.Type != "" && s.Value != nil {
			fmt.Println(indent+"let", s.Name+":"+s.Type, "=", exprString(s.Value))
		} else if s.Type != "" {
			fmt.Println(indent+"let", s.Name+":"+s.Type)
		} else if s.Value != nil {
			fmt.Println(indent+"let", s.Name, "=", exprString(s.Value))
		} else {
			fmt.Println(indent+"let", s.Name)
		}
	case *ast.VarStmt:
		if s.Type != "" && s.Value != nil {
			fmt.Println(indent+"var", s.Name+":"+s.Type, "=", exprString(s.Value))
		} else if s.Type != "" {
			fmt.Println(indent+"var", s.Name+":"+s.Type)
		} else if s.Value != nil {
			fmt.Println(indent+"var", s.Name, "=", exprString(s.Value))
		} else {
			fmt.Println(indent+"var", s.Name)
		}
	case *ast.IfStmt:
		fmt.Println(indent+"if (", exprString(s.Cond), ") {")
		for _, inner := range s.Then.Stmts {
			printStmt(indent+"  ", inner)
		}
		fmt.Println(indent + "}")
		for _, ei := range s.Elifs {
			fmt.Println(indent+"elif (", exprString(ei.Cond), ") {")
			for _, inner := range ei.Then.Stmts {
				printStmt(indent+"  ", inner)
			}
			fmt.Println(indent + "}")
		}
		if s.Else != nil {
			fmt.Println(indent + "else {")
			for _, inner := range s.Else.Stmts {
				printStmt(indent+"  ", inner)
			}
			fmt.Println(indent + "}")
		}
	case *ast.WhileStmt:
		fmt.Println(indent+"while (", exprString(s.Cond), ") {")
		for _, inner := range s.Body.Stmts {
			printStmt(indent+"  ", inner)
		}
		fmt.Println(indent + "}")
	case *ast.DoWhileStmt:
		fmt.Println(indent + "do {")
		for _, inner := range s.Body.Stmts {
			printStmt(indent+"  ", inner)
		}
		fmt.Println(indent+"} while (", exprString(s.Cond), ")")
	case *ast.LoopStmt:
		rangeOp := ".."
		if s.Inclusive {
			rangeOp = "..="
		}
		stepStr := ""
		if s.Step != nil {
			stepStr = ", step " + exprString(s.Step)
		}
		if s.Reverse {
			fmt.Println(indent+"rev loop (", s.VarName+": ", exprString(s.From), rangeOp, exprString(s.To), stepStr, ") {")
		} else {
			fmt.Println(indent+"loop (", s.VarName+": ", exprString(s.From), rangeOp, exprString(s.To), stepStr, ") {")
		}
		for _, inner := range s.Body.Stmts {
			printStmt(indent+"  ", inner)
		}
		fmt.Println(indent + "}")
	default:
		fmt.Printf(indent+"stmt: %T\n", s)
	}
}

func exprString(e ast.Expr) string {
	switch x := e.(type) {
	case *ast.IdentifierExpr:
		return x.Name
	case *ast.NumberLiteralExpr:
		return fmt.Sprintf("%s", x.Value.Digits)
	case *ast.StringLiteralExpr:
		return fmt.Sprintf("\"%s\"", x.Value)
	case *ast.CharLiteralExpr:
		return fmt.Sprintf("'%s'", x.Value)
	case *ast.BoolLiteralExpr:
		if x.Value {
			return "true"
		}
		return "false"
	case *ast.NullLiteralExpr:
		return "null"
	case *ast.UnaryExpr:
		if x.IsPostfix {
			return fmt.Sprintf("%s%s", exprString(x.Operand), tokenOpString(x.Operator))
		}
		return fmt.Sprintf("%s%s", tokenOpString(x.Operator), exprString(x.Operand))
	case *ast.BinaryExpr:
		return fmt.Sprintf("(%s %s %s)", exprString(x.Left), tokenOpString(x.Operator), exprString(x.Right))
	case *ast.AssignExpr:
		return fmt.Sprintf("%s %s %s", exprString(x.Target), tokenOpString(x.Operator), exprString(x.Value))
	case *ast.CallExpr:
		args := ""
		for i, a := range x.Args {
			if i > 0 {
				args += ", "
			}
			args += exprString(a)
		}
		return fmt.Sprintf("%s(%s)", exprString(x.Callee), args)
	case *ast.MemberAccessExpr:
		return fmt.Sprintf("%s.%s", exprString(x.Object), x.Member)
	case *ast.CastExpr:
		return fmt.Sprintf("(%s as %s)", exprString(x.Expr), x.TargetType)
	case *ast.TernaryExpr:
		return fmt.Sprintf("(%s ? %s : %s)", exprString(x.Cond), exprString(x.ThenExpr), exprString(x.ElseExpr))
	case *ast.IndexExpr:
		return fmt.Sprintf("%s[%s]", exprString(x.Base), exprString(x.Index))
	case *ast.ArrayLiteralExpr:
		parts := ""
		for i, el := range x.Elements {
			if i > 0 {
				parts += ", "
			}
			parts += exprString(el)
		}
		return fmt.Sprintf("[%s]", parts)
	default:
		return fmt.Sprintf("<expr %T>", x)
	}
}
