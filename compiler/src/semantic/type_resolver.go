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
	isKnown := func(name string, enclosing string) bool {
		base := stripArrayNullable(name)
		if base == "" {
			return true
		}
		if base == "self" && enclosing != "" {
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
			if n.Type != "" && !isKnown(n.Type, "") {
				diags = append(diags, TypeDiag{Message: "unknown type: " + n.Type, Span: n.Tok.Span, Hint: fmt.Sprintf("did you mean a built-in or a declared type? '%s'", n.Type)})
			}
		case *ast.FuncDecl:
			if n.ReturnType != "" && !isKnown(n.ReturnType, "") {
				diags = append(diags, TypeDiag{Message: "unknown return type: " + n.ReturnType, Span: n.HeaderTok.Span})
			}
			for _, p := range n.Params {
				if p.Type != "" && !isKnown(p.Type, "") {
					diags = append(diags, TypeDiag{Message: "unknown param type: " + p.Type, Span: p.Tok.Span})
				}
			}
		case *ast.ClassDecl:
			for _, f := range n.Fields {
				if f.Type != "" && !isKnown(f.Type, n.Name) {
					diags = append(diags, TypeDiag{Message: "unknown field type: " + f.Type, Span: n.HeaderTok.Span})
				}
			}
			for _, m := range n.Methods {
				if m.ReturnType != "" && !isKnown(m.ReturnType, n.Name) {
					diags = append(diags, TypeDiag{Message: "unknown return type: " + m.ReturnType})
				}
				for _, p := range m.Params {
					if p.Type != "" && !isKnown(p.Type, n.Name) {
						diags = append(diags, TypeDiag{Message: "unknown param type: " + p.Type, Span: p.Tok.Span})
					}
				}
			}
			// Inheritance checks: validate overrides against base template methods
			if len(n.SuperTypes) > 0 {
				if sym, ok := module.Resolve(n.SuperTypes[0]); ok {
					if base, ok2 := sym.Node.(*ast.ClassDecl); ok2 {
						// collect base template methods
						baseTemplates := map[string]*ast.MethodDecl{}
						for i := range base.Methods {
							bm := &base.Methods[i]
							if bm.IsTemplate {
								baseTemplates[bm.Name] = bm
							}
						}
						// ensure overrides match and clear from required set
						for i := range n.Methods {
							m := &n.Methods[i]
							if m.IsOverride {
								bm, ok := baseTemplates[m.Name]
								if !ok {
									diags = append(diags, TypeDiag{Message: fmt.Sprintf("override '%s' has no matching template in base '%s'", m.Name, base.Name), Span: n.HeaderTok.Span})
									continue
								}
								// simple signature check: arity and return type string match
								if len(m.Params) != len(bm.Params) || m.ReturnType != bm.ReturnType {
									diags = append(diags, TypeDiag{Message: fmt.Sprintf("override '%s' signature mismatch base template", m.Name), Span: n.HeaderTok.Span})
								}
								delete(baseTemplates, m.Name)
							}
						}
						// any remaining base templates mean class is abstract
						if len(baseTemplates) > 0 {
							n.IsTemplate = true
						}
					}
				}
			}
		case *ast.StructDecl:
			for _, f := range n.Fields {
				if f.Type != "" && !isKnown(f.Type, n.Name) {
					diags = append(diags, TypeDiag{Message: "unknown field type: " + f.Type, Span: n.HeaderTok.Span})
				}
			}
			for _, m := range n.Methods {
				if m.ReturnType != "" && !isKnown(m.ReturnType, n.Name) {
					diags = append(diags, TypeDiag{Message: "unknown return type: " + m.ReturnType})
				}
				for _, p := range m.Params {
					if p.Type != "" && !isKnown(p.Type, n.Name) {
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
