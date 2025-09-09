package ast

import "compiler/src/tokens"

type FieldDecl struct {
	Name    string
	Type    string
	IsFinal bool
}

type MethodDecl struct {
	Visibility Visibility
	Name       string
	Params     []Parameter
	ReturnType string
	Body       []Stmt
	IsTemplate bool
	IsOverride bool
}

type EnumCase struct {
	Name   string
	Params []Expr // optional payload
}

type GlobalVarDecl struct {
	Visibility Visibility
	IsLet      bool
	Name       string
	Type       string
	Value      Expr // optional
	Tok        tokens.Token
}

func (d *GlobalVarDecl) Span() tokens.TokenSpan { return d.Tok.Span }
func (d *GlobalVarDecl) isDecl()                {}
