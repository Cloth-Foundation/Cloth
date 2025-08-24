package main

import (
	"compiler/src/diagnostics"
	"compiler/src/lexer"
	"compiler/src/parser"
	"compiler/src/semantic"
	"fmt"
	"os"
	"path/filepath"
)

func main() {
	if len(os.Args) < 2 {
		fmt.Println("Usage: loom [--no-color] <file>")
		return
	}
	// Parse flags
	noColor := false
	var path string
	for _, a := range os.Args[1:] {
		if a == "--no-color" {
			noColor = true
			continue
		}
		if path == "" {
			path = a
		} else {
			// ignore extra args for now
		}
	}
	if path == "" {
		fmt.Println("Usage: loom [--no-color] <file>")
		return
	}
	diagnostics.SetColorsEnabled(!noColor)

	data, err := os.ReadFile(path)
	if err != nil {
		fmt.Printf("failed to read %s: %v\n", path, err)
		os.Exit(1)
	}
	lx := lexer.New(string(data), path)
	p := parser.New(lx)
	file, errs := p.ParseFile()
	if len(errs) > 0 || file == nil {
		diagnostics.RenderParseErrors(path, string(data), errs)
		os.Exit(1)
	}
	// Semantic pipeline
	scope, semErrs := semantic.CollectTopLevel(file)
	root := filepath.Dir(filepath.Dir(path))
	loader := &semantic.FSLoader{Root: root}
	impErrs := semantic.ResolveImports(file, loader, scope)
	bindDiags := semantic.Bind(file, scope)
	env := semantic.NewTypeEnv()
	ttab := semantic.NewTypeTable()
	typeDiags := semantic.ResolveTypes(file, env, ttab)
	checkDiags := semantic.CheckExpressions(file, ttab, scope)
	// Render
	if len(semErrs) > 0 {
		msgs := make([]string, 0, len(semErrs))
		for _, e := range semErrs {
			msgs = append(msgs, e.Error())
		}
		diagnostics.RenderMessages("Symbol collection errors", msgs)
	}
	if len(impErrs) > 0 {
		msgs := make([]string, 0, len(impErrs))
		for _, e := range impErrs {
			msgs = append(msgs, e.Error())
		}
		diagnostics.RenderMessages("Import errors", msgs)
	}
	diagnostics.RenderBindDiagnostics(bindDiags)
	diagnostics.RenderTypeResolveDiagnostics(typeDiags)
	diagnostics.RenderTypeCheckDiagnostics(checkDiags)
	if len(semErrs) > 0 || len(impErrs) > 0 || len(bindDiags) > 0 || len(typeDiags) > 0 || len(checkDiags) > 0 {
		os.Exit(1)
	}
}
