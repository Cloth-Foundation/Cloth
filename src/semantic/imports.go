package semantic

import (
	"compiler/src/ast"
	"fmt"
)

type ModuleLoader interface {
	Load(modulePath []string) (*ast.File, error)
}

// ResolveImports loads imported modules and adds selected symbols into the provided scope.
func ResolveImports(file *ast.File, loader ModuleLoader, scope *Scope) []error {
	var errs []error
	for _, im := range file.Imports {
		modFile, err := loader.Load(im.PathSegments)
		if err != nil {
			errs = append(errs, fmt.Errorf("import %v: %w", im.PathSegments, err))
			continue
		}
		importedScope, collErrs := CollectTopLevel(modFile)
		errs = append(errs, collErrs...)
		// helper to check visibility
		isVisible := func(node any, importingModule *ast.ModDecl) bool {
			sameModule := modFile.Module != nil && importingModule != nil && modFile.Module.Name == importingModule.Name
			switch n := node.(type) {
			case *ast.FuncDecl:
				switch n.Visibility {
				case ast.VisPublic:
					return true
				case ast.VisPrivate:
					return false
				case ast.VisProtected:
					return sameModule
				}
			case *ast.ClassDecl:
				switch n.Visibility {
				case ast.VisPublic:
					return true
				case ast.VisPrivate:
					return false
				case ast.VisProtected:
					return sameModule
				}
			case *ast.StructDecl:
				switch n.Visibility {
				case ast.VisPublic:
					return true
				case ast.VisPrivate:
					return false
				case ast.VisProtected:
					return sameModule
				}
			case *ast.EnumDecl:
				switch n.Visibility {
				case ast.VisPublic:
					return true
				case ast.VisPrivate:
					return false
				case ast.VisProtected:
					return sameModule
				}
			case *ast.GlobalVarDecl:
				switch n.Visibility {
				case ast.VisPublic:
					return true
				case ast.VisPrivate:
					return false
				case ast.VisProtected:
					return sameModule
				}
			}
			return true
		}
		if len(im.Items) > 0 {
			for _, it := range im.Items {
				sym, ok := importedScope.Resolve(it.Name)
				if !ok {
					errs = append(errs, fmt.Errorf("import: symbol '%s' not found in %v", it.Name, im.PathSegments))
					continue
				}
				if !isVisible(sym.Node, file.Module) {
					errs = append(errs, fmt.Errorf("import: symbol '%s' is not visible due to its access modifier", it.Name))
					continue
				}
				name := it.Name
				if it.Alias != "" {
					name = it.Alias
				}
				if defErr := scope.Define(Symbol{Name: name, Kind: sym.Kind, Node: sym.Node}); defErr != nil {
					errs = append(errs, defErr)
				}
			}
		} else {
			// Expose module namespace under its last segment
			if len(im.PathSegments) > 0 {
				modName := im.PathSegments[len(im.PathSegments)-1]
				// filtered child scope honoring visibility
				filtered := NewScope(nil)
				for _, sym := range importedScope.Symbols() {
					if isVisible(sym.Node, file.Module) {
						_ = filtered.Define(sym)
					}
				}
				_ = scope.Define(Symbol{Name: modName, Kind: SymModule, Node: filtered})
			}
		}
	}
	return errs
}
