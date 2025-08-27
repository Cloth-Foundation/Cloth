package semantic

import "compiler/src/ast"

// InjectBuiltins adds core builtin symbols to the provided scope so that
// parsing/binding/checking can reference them.
// 'print' accepts any single argument (empty type means unconstrained) and returns void.
func InjectBuiltins(scope *Scope) {
	_ = scope.Define(Symbol{
		Name: "print",
		Kind: SymFunc,
		Node: &ast.FuncDecl{
			Name:       "print",
			Params:     []ast.Parameter{{Name: "value", Type: ""}},
			ReturnType: "void",
		},
	})
}
