package parser

import (
	"compiler/src/ast"
	"compiler/src/lexer"
	"compiler/src/tokens"
	"fmt"
)

// Core parser type and shared helpers

type Parser struct {
	lx    *lexer.Lexer
	curr  tokens.Token
	peek  tokens.Token
	errs  []error
	fatal bool
}

func New(lx *lexer.Lexer) *Parser {
	p := &Parser{lx: lx}
	p.advance()
	return p
}

func (p *Parser) Errors() []error { return p.errs }

func (p *Parser) advance() {
	p.curr = p.lx.Next()
	p.peek = p.lx.Peek()
}

func (p *Parser) match(tt tokens.TokenType) bool {
	if p.curr.Type == tt {
		p.advance()
		return true
	}
	return false
}

func (p *Parser) report(tok tokens.Token, msg string) {
	p.errs = append(p.errs, ParseError{Message: msg, Span: tok.Span})
	p.fatal = true
}

func (p *Parser) expect(tt tokens.TokenType, msg string) tokens.Token {
	if p.curr.Type != tt {
		p.report(p.curr, fmt.Sprintf("%s: got %s", msg, tokens.TokenTypeName(p.curr.Type)))
		return p.curr
	}
	t := p.curr
	p.advance()
	return t
}

// ParseFile orchestrates the high-level parse. Subroutines live in headers.go, statements.go, expressions.go, types.go
func (p *Parser) ParseFile() (*ast.File, []error) {
	f := &ast.File{}
	if p.curr.Type == tokens.TokenMod {
		modTok := p.curr
		p.advance()
		nameTok := p.expect(tokens.TokenIdentifier, "expected module identifier")
		if p.fatal {
			return nil, p.errs
		}
		f.Module = &ast.ModDecl{Name: nameTok.Text, Tok: modTok}
		_ = p.match(tokens.TokenSemicolon)
		if p.fatal {
			return nil, p.errs
		}
	}
	for p.curr.Type == tokens.TokenImport && !p.fatal {
		f.Imports = append(f.Imports, p.parseImport())
		if p.fatal {
			return nil, p.errs
		}
	}
	for p.curr.Type != tokens.TokenEndOfFile && !p.fatal {
		prevLine, prevCol := p.curr.Span.StartLine, p.curr.Span.StartColumn
		if p.curr.Type == tokens.TokenLet || p.curr.Type == tokens.TokenVar || p.curr.Type == tokens.TokenPub || p.curr.Type == tokens.TokenPriv || p.curr.Type == tokens.TokenProt {
			if gv := p.parseGlobalVarDecl(); gv != nil {
				if p.fatal {
					return nil, p.errs
				}
				f.Decls = append(f.Decls, gv)
				continue
			}
		}
		if decl := p.parseTopLevelDeclHeader(); decl != nil {
			if p.fatal {
				return nil, p.errs
			}
			if fd, ok := decl.(*ast.FuncDecl); ok {
				if p.curr.Type == tokens.TokenLBrace {
					fd.Body = p.parseBlock().Stmts
					if p.fatal {
						return nil, p.errs
					}
				}
			}
			f.Decls = append(f.Decls, decl)
		} else {
			p.report(p.curr, fmt.Sprintf("unexpected token %s at top-level", tokens.TokenTypeName(p.curr.Type)))
			return nil, p.errs
		}
		if p.curr.Span.StartLine == prevLine && p.curr.Span.StartColumn == prevCol {
			p.advance()
		}
	}
	if len(p.errs) > 0 {
		return nil, p.errs
	}
	return f, p.errs
}
