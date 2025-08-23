package main

import (
	"compiler/src/ast"
	"compiler/src/lexer"
	"compiler/src/parser"
	"compiler/src/tokens"
	"fmt"
	"os"
)

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
		for _, im := range file.Imports {
			fmt.Println("import:", im.PathSegments)
		}
		for _, d := range file.Decls {
			switch dd := d.(type) {
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
			case *ast.StructDecl:
				fmt.Println("struct:", dd.Name)
			case *ast.EnumDecl:
				fmt.Println("enum:", dd.Name)
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
			return fmt.Sprintf("%s%s", exprString(x.Operand), tokens.TokenTypeName(x.Operator))
		}
		return fmt.Sprintf("%s%s", tokens.TokenTypeName(x.Operator), exprString(x.Operand))
	case *ast.BinaryExpr:
		return fmt.Sprintf("(%s %s %s)", exprString(x.Left), tokens.TokenTypeName(x.Operator), exprString(x.Right))
	case *ast.AssignExpr:
		return fmt.Sprintf("%s %s %s", exprString(x.Target), tokens.TokenTypeName(x.Operator), exprString(x.Value))
	default:
		return fmt.Sprintf("<expr %T>", x)
	}
}
