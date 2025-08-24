package semantic

import (
	"compiler/src/ast"
)

type TypeDiag struct{ Message string }

// ResolveTypes validates type names on declarations (params, returns, fields, globals).
func ResolveTypes(file *ast.File, env *TypeEnv, types *TypeTable) []TypeDiag {
	var diags []TypeDiag
	// Globals
	for _, d := range file.Decls {
		switch n := d.(type) {
		case *ast.GlobalVarDecl:
			if n.Type != "" && !env.IsBuiltin(stripArrayNullable(n.Type)) {
				diags = append(diags, TypeDiag{Message: "unknown type: " + n.Type})
			}
		case *ast.FuncDecl:
			if n.ReturnType != "" && !env.IsBuiltin(stripArrayNullable(n.ReturnType)) {
				diags = append(diags, TypeDiag{Message: "unknown return type: " + n.ReturnType})
			}
			for _, p := range n.Params {
				if p.Type != "" && !env.IsBuiltin(stripArrayNullable(p.Type)) {
					diags = append(diags, TypeDiag{Message: "unknown param type: " + p.Type})
				}
			}
		case *ast.ClassDecl:
			for _, f := range n.Fields {
				if f.Type != "" && !env.IsBuiltin(stripArrayNullable(f.Type)) {
					diags = append(diags, TypeDiag{Message: "unknown field type: " + f.Type})
				}
			}
			for _, m := range n.Methods {
				if m.ReturnType != "" && !env.IsBuiltin(stripArrayNullable(m.ReturnType)) {
					diags = append(diags, TypeDiag{Message: "unknown return type: " + m.ReturnType})
				}
				for _, p := range m.Params {
					if p.Type != "" && !env.IsBuiltin(stripArrayNullable(p.Type)) {
						diags = append(diags, TypeDiag{Message: "unknown param type: " + p.Type})
					}
				}
			}
		case *ast.StructDecl:
			for _, f := range n.Fields {
				if f.Type != "" && !env.IsBuiltin(stripArrayNullable(f.Type)) {
					diags = append(diags, TypeDiag{Message: "unknown field type: " + f.Type})
				}
			}
			for _, m := range n.Methods {
				if m.ReturnType != "" && !env.IsBuiltin(stripArrayNullable(m.ReturnType)) {
					diags = append(diags, TypeDiag{Message: "unknown return type: " + m.ReturnType})
				}
				for _, p := range m.Params {
					if p.Type != "" && !env.IsBuiltin(stripArrayNullable(p.Type)) {
						diags = append(diags, TypeDiag{Message: "unknown param type: " + p.Type})
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
