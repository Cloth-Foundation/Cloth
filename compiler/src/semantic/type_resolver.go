package semantic

import (
	"compiler/src/ast"
	"compiler/src/tokens"
	"fmt"
)

type TypeDiag struct {
	Message string
	Span    tokens.TokenSpan
	Hint    string
}

// ResolveTypes validates type names on declarations (params, returns, fields, globals).
func ResolveTypes(file *ast.File, env *TypeEnv, types *TypeTable, module *Scope) []TypeDiag {
	var diags []TypeDiag
	isKnown := func(name string) bool {
		base := stripArrayNullable(name)
		if base == "" {
			return true
		}
		if env.IsBuiltin(base) {
			return true
		}
		if sym, ok := module.Resolve(base); ok && (sym.Kind == SymClass || sym.Kind == SymStruct || sym.Kind == SymEnum) {
			return true
		}
		return false
	}
	for _, d := range file.Decls {
		switch n := d.(type) {
		case *ast.GlobalVarDecl:
			if n.Type != "" && !isKnown(n.Type) {
				diags = append(diags, TypeDiag{Message: "unknown type: " + n.Type, Span: n.Tok.Span, Hint: fmt.Sprintf("did you mean a built-in or a declared type? '%s'", n.Type)})
			}
		case *ast.FuncDecl:
			if n.ReturnType != "" && !isKnown(n.ReturnType) {
				diags = append(diags, TypeDiag{Message: "unknown return type: " + n.ReturnType, Span: n.HeaderTok.Span})
			}
			for _, p := range n.Params {
				if p.Type != "" && !isKnown(p.Type) {
					diags = append(diags, TypeDiag{Message: "unknown param type: " + p.Type, Span: p.Tok.Span})
				}
			}
		case *ast.ClassDecl:
			for _, f := range n.Fields {
				if f.Type != "" && !isKnown(f.Type) {
					diags = append(diags, TypeDiag{Message: "unknown field type: " + f.Type, Span: n.HeaderTok.Span})
				}
			}
			for _, m := range n.Methods {
				if m.ReturnType != "" && !isKnown(m.ReturnType) {
					diags = append(diags, TypeDiag{Message: "unknown return type: " + m.ReturnType})
				}
				for _, p := range m.Params {
					if p.Type != "" && !isKnown(p.Type) {
						diags = append(diags, TypeDiag{Message: "unknown param type: " + p.Type, Span: p.Tok.Span})
					}
				}
			}
		case *ast.StructDecl:
			for _, f := range n.Fields {
				if f.Type != "" && !isKnown(f.Type) {
					diags = append(diags, TypeDiag{Message: "unknown field type: " + f.Type, Span: n.HeaderTok.Span})
				}
			}
			for _, m := range n.Methods {
				if m.ReturnType != "" && !isKnown(m.ReturnType) {
					diags = append(diags, TypeDiag{Message: "unknown return type: " + m.ReturnType})
				}
				for _, p := range m.Params {
					if p.Type != "" && !isKnown(p.Type) {
						diags = append(diags, TypeDiag{Message: "unknown param type: " + p.Type, Span: p.Tok.Span})
					}
				}
			}
		}
	}
	return diags
}

func stripArrayNullable(t string) string {
	base := t
	for len(base) >= 2 && base[:2] == "[]" {
		base = base[2:]
	}
	if len(base) > 0 && base[len(base)-1] == '?' {
		base = base[:len(base)-1]
	}
	return base
}
