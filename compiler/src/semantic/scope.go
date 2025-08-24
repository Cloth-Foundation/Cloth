package semantic

import (
	"fmt"
)

type SymbolKind int

const (
	SymVar SymbolKind = iota
	SymFunc
	SymClass
	SymStruct
	SymEnum
	SymField
	SymModule
)

type Symbol struct {
	Name string
	Kind SymbolKind
	Node any
}

type Scope struct {
	parent *Scope
	table  map[string]Symbol
}

func NewScope(parent *Scope) *Scope { return &Scope{parent: parent, table: map[string]Symbol{}} }

func (s *Scope) Define(sym Symbol) error {
	if _, exists := s.table[sym.Name]; exists {
		return fmt.Errorf("symbol '%s' already defined", sym.Name)
	}
	s.table[sym.Name] = sym
	return nil
}

func (s *Scope) Resolve(name string) (Symbol, bool) {
	if sym, ok := s.table[name]; ok {
		return sym, true
	}
	if s.parent != nil {
		return s.parent.Resolve(name)
	}
	return Symbol{}, false
}
