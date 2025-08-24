package semantic

import (
	"compiler/src/ast"
)

// Collects top-level symbols into a module scope
func CollectTopLevel(file *ast.File) (*Scope, []error) {
	scope := NewScope(nil)
	var errs []error
	for _, d := range file.Decls {
		switch n := d.(type) {
		case *ast.FuncDecl:
			if err := scope.Define(Symbol{Name: n.Name, Kind: SymFunc, Node: n}); err != nil {
				errs = append(errs, err)
			}
		case *ast.ClassDecl:
			if err := scope.Define(Symbol{Name: n.Name, Kind: SymClass, Node: n}); err != nil {
				errs = append(errs, err)
			}
		case *ast.StructDecl:
			if err := scope.Define(Symbol{Name: n.Name, Kind: SymStruct, Node: n}); err != nil {
				errs = append(errs, err)
			}
		case *ast.EnumDecl:
			if err := scope.Define(Symbol{Name: n.Name, Kind: SymEnum, Node: n}); err != nil {
				errs = append(errs, err)
			}
		case *ast.GlobalVarDecl:
			if err := scope.Define(Symbol{Name: n.Name, Kind: SymVar, Node: n}); err != nil {
				errs = append(errs, err)
			}
		}
	}
	return scope, errs
}
