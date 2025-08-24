package ast

import (
	"compiler/src/tokens"
)

// Node is the base interface for all AST nodes.
type Node interface {
	Span() tokens.TokenSpan
}

// Expr represents an expression node.
type Expr interface {
	Node
	isExpr()
}

// Stmt represents a statement node.
type Stmt interface {
	Node
	isStmt()
}

// Decl represents a top-level declaration node.
type Decl interface {
	Node
	isDecl()
}

// Visibility of declarations
type Visibility int

const (
	VisDefault Visibility = iota
	VisPublic
	VisPrivate
	VisProtected
)

// File represents a parsed file (phase-1 headers collected)
type File struct {
	Module  *ModDecl
	Imports []*ImportDecl
	Decls   []Decl
}

// Module declaration: mod example;
type ModDecl struct {
	Name string
	Tok  tokens.Token
}

func (d *ModDecl) Span() tokens.TokenSpan { return d.Tok.Span }
func (d *ModDecl) isDecl()                {}

// Import declaration: import std.system.io::{print as p, printf}
type ImportItem struct {
	Name  string
	Alias string // optional
	Tok   tokens.Token
}

type ImportDecl struct {
	PathSegments []string
	Items        []ImportItem // empty => import entire module
	Tok          tokens.Token
}

func (d *ImportDecl) Span() tokens.TokenSpan { return d.Tok.Span }
func (d *ImportDecl) isDecl()                {}

// Function declaration header (phase-1). Body is skipped span.
type Parameter struct {
	Name string
	Type string
	Tok  tokens.Token
}

type FuncDecl struct {
	Visibility  Visibility
	Name        string
	Params      []Parameter
	ReturnType  string
	HeaderTok   tokens.Token
	BodySpan    tokens.TokenSpan // span covering the { ... }
	BodySkipped bool             // true if body was skipped in phase 1
	Body        []Stmt           // filled in phase 2
}

func (d *FuncDecl) Span() tokens.TokenSpan { return d.HeaderTok.Span }
func (d *FuncDecl) isDecl()                {}

// Class declaration header (phase-1)
type ClassDecl struct {
	Visibility  Visibility
	Name        string
	SuperTypes  []string // optional extends list (parsed as simple path segments)
	HeaderTok   tokens.Token
	BodySpan    tokens.TokenSpan
	BodySkipped bool
	Fields      []FieldDecl
	Methods     []MethodDecl
	Builders    []MethodDecl
}

func (d *ClassDecl) Span() tokens.TokenSpan { return d.HeaderTok.Span }
func (d *ClassDecl) isDecl()                {}

// Struct declaration header (phase-1)
type StructDecl struct {
	Visibility  Visibility
	Name        string
	HeaderTok   tokens.Token
	BodySpan    tokens.TokenSpan
	BodySkipped bool
	Fields      []FieldDecl
	Methods     []MethodDecl
	Builders    []MethodDecl
}

func (d *StructDecl) Span() tokens.TokenSpan { return d.HeaderTok.Span }
func (d *StructDecl) isDecl()                {}

// Enum declaration header (phase-1)
type EnumDecl struct {
	Visibility  Visibility
	Name        string
	HeaderTok   tokens.Token
	BodySpan    tokens.TokenSpan
	BodySkipped bool
	Cases       []EnumCase
	Fields      []FieldDecl
	Methods     []MethodDecl
	Builders    []MethodDecl
}

func (d *EnumDecl) Span() tokens.TokenSpan { return d.HeaderTok.Span }
func (d *EnumDecl) isDecl()                {}

// ---------------- Expressions ----------------

type IdentifierExpr struct {
	Name string
	Tok  tokens.Token
}

func (e *IdentifierExpr) Span() tokens.TokenSpan { return e.Tok.Span }
func (e *IdentifierExpr) isExpr()                {}

type NumberLiteralExpr struct {
	Value tokens.NumericLiteral
	Tok   tokens.Token
}

func (e *NumberLiteralExpr) Span() tokens.TokenSpan { return e.Tok.Span }
func (e *NumberLiteralExpr) isExpr()                {}

type StringLiteralExpr struct {
	Value string
	Tok   tokens.Token
}

func (e *StringLiteralExpr) Span() tokens.TokenSpan { return e.Tok.Span }
func (e *StringLiteralExpr) isExpr()                {}

type CharLiteralExpr struct {
	Value string
	Tok   tokens.Token
}

func (e *CharLiteralExpr) Span() tokens.TokenSpan { return e.Tok.Span }
func (e *CharLiteralExpr) isExpr()                {}

type BoolLiteralExpr struct {
	Value bool
	Tok   tokens.Token
}

func (e *BoolLiteralExpr) Span() tokens.TokenSpan { return e.Tok.Span }
func (e *BoolLiteralExpr) isExpr()                {}

type NullLiteralExpr struct{ Tok tokens.Token }

func (e *NullLiteralExpr) Span() tokens.TokenSpan { return e.Tok.Span }
func (e *NullLiteralExpr) isExpr()                {}

type UnaryExpr struct {
	Operator  tokens.TokenType
	Operand   Expr
	IsPostfix bool
	OpTok     tokens.Token
}

func (e *UnaryExpr) Span() tokens.TokenSpan {
	if e.IsPostfix {
		return mergeSpans(e.Operand.Span(), e.OpTok.Span)
	}
	return mergeSpans(e.OpTok.Span, e.Operand.Span())
}
func (e *UnaryExpr) isExpr() {}

type BinaryExpr struct {
	Left     Expr
	Operator tokens.TokenType
	Right    Expr
	OpTok    tokens.Token
}

func (e *BinaryExpr) Span() tokens.TokenSpan { return mergeSpans(e.Left.Span(), e.Right.Span()) }
func (e *BinaryExpr) isExpr()                {}

type AssignExpr struct {
	Target   Expr
	Operator tokens.TokenType
	Value    Expr
	OpTok    tokens.Token
}

func (e *AssignExpr) Span() tokens.TokenSpan { return mergeSpans(e.Target.Span(), e.Value.Span()) }
func (e *AssignExpr) isExpr()                {}

// Indexing: base[index]
type IndexExpr struct {
	Base   Expr
	Index  Expr
	LBrack tokens.Token
	RBrack tokens.Token
}

func (e *IndexExpr) Span() tokens.TokenSpan { return mergeSpans(e.Base.Span(), e.RBrack.Span) }
func (e *IndexExpr) isExpr()                {}

// Array literal: [a, b, c]
type ArrayLiteralExpr struct {
	Elements []Expr
	LBrack   tokens.Token
	RBrack   tokens.Token
}

func (e *ArrayLiteralExpr) Span() tokens.TokenSpan { return mergeSpans(e.LBrack.Span, e.RBrack.Span) }
func (e *ArrayLiteralExpr) isExpr()                {}

// Function or method call
type CallExpr struct {
	Callee Expr
	Args   []Expr
	LParen tokens.Token
	RParen tokens.Token
}

func (e *CallExpr) Span() tokens.TokenSpan { return mergeSpans(e.Callee.Span(), e.RParen.Span) }
func (e *CallExpr) isExpr()                {}

// Member access: obj.member
type MemberAccessExpr struct {
	Object    Expr
	Member    string
	DotTok    tokens.Token
	MemberTok tokens.Token
}

func (e *MemberAccessExpr) Span() tokens.TokenSpan {
	return mergeSpans(e.Object.Span(), e.MemberTok.Span)
}
func (e *MemberAccessExpr) isExpr() {}

// Cast using 'as'
type CastExpr struct {
	Expr       Expr
	TargetType string
	AsTok      tokens.Token
}

func (e *CastExpr) Span() tokens.TokenSpan { return mergeSpans(e.Expr.Span(), e.AsTok.Span) }
func (e *CastExpr) isExpr()                {}

// Ternary conditional: cond ? thenExpr : elseExpr
type TernaryExpr struct {
	Cond     Expr
	ThenExpr Expr
	ElseExpr Expr
	QTok     tokens.Token
	CTok     tokens.Token
}

func (e *TernaryExpr) Span() tokens.TokenSpan { return mergeSpans(e.Cond.Span(), e.ElseExpr.Span()) }
func (e *TernaryExpr) isExpr()                {}

// ---------------- Statements ----------------

type BlockStmt struct {
	LBrace tokens.Token
	Stmts  []Stmt
	RBrace tokens.Token
}

func (s *BlockStmt) Span() tokens.TokenSpan { return mergeSpans(s.LBrace.Span, s.RBrace.Span) }
func (s *BlockStmt) isStmt()                {}

type ExpressionStmt struct {
	E   Expr
	Tok tokens.Token // first token of expr
}

func (s *ExpressionStmt) Span() tokens.TokenSpan { return s.E.Span() }
func (s *ExpressionStmt) isStmt()                {}

type ReturnStmt struct {
	Value Expr // optional
	Tok   tokens.Token
}

func (s *ReturnStmt) Span() tokens.TokenSpan { return s.Tok.Span }
func (s *ReturnStmt) isStmt()                {}

type BreakStmt struct{ Tok tokens.Token }

func (s *BreakStmt) Span() tokens.TokenSpan { return s.Tok.Span }
func (s *BreakStmt) isStmt()                {}

type ContinueStmt struct{ Tok tokens.Token }

func (s *ContinueStmt) Span() tokens.TokenSpan { return s.Tok.Span }
func (s *ContinueStmt) isStmt()                {}

type LetStmt struct {
	Name    string
	Type    string // optional
	Value   Expr   // optional
	NameTok tokens.Token
}

func (s *LetStmt) Span() tokens.TokenSpan { return s.NameTok.Span }
func (s *LetStmt) isStmt()                {}

type VarStmt struct {
	Name    string
	Type    string // optional
	Value   Expr   // required in loom examples, but allow optional for now
	NameTok tokens.Token
}

func (s *VarStmt) Span() tokens.TokenSpan { return s.NameTok.Span }
func (s *VarStmt) isStmt()                {}

type IfStmt struct {
	Cond  Expr
	Then  *BlockStmt
	Elifs []ElseIf
	Else  *BlockStmt // optional
	Tok   tokens.Token
}

func (s *IfStmt) Span() tokens.TokenSpan { return s.Tok.Span }
func (s *IfStmt) isStmt()                {}

type ElseIf struct {
	Cond Expr
	Then *BlockStmt
}

type WhileStmt struct {
	Cond Expr
	Body *BlockStmt
	Tok  tokens.Token
}

func (s *WhileStmt) Span() tokens.TokenSpan { return s.Tok.Span }
func (s *WhileStmt) isStmt()                {}

type DoWhileStmt struct {
	Body *BlockStmt
	Cond Expr
	Tok  tokens.Token
}

func (s *DoWhileStmt) Span() tokens.TokenSpan { return s.Tok.Span }
func (s *DoWhileStmt) isStmt()                {}

type LoopStmt struct {
	Reverse   bool
	VarName   string
	From      Expr
	To        Expr
	Inclusive bool
	Step      Expr // optional (nil means default 1)
	Body      *BlockStmt
	Tok       tokens.Token
}

func (s *LoopStmt) Span() tokens.TokenSpan { return s.Tok.Span }
func (s *LoopStmt) isStmt()                {}

// ---------- Utility ----------

func mergeSpans(a, b tokens.TokenSpan) tokens.TokenSpan {
	// Assumes a occurs before b in source. If not, returns a conservative span.
	sp := a
	sp.EndLine = b.EndLine
	sp.EndColumn = b.EndColumn
	return sp
}
