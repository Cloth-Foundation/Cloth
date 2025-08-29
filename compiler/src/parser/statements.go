package parser

import (
	"compiler/src/ast"
	"compiler/src/tokens"
)

func (p *Parser) parseBlock() *ast.BlockStmt {
	lbrace := p.expect(tokens.TokenLBrace, "expected '{' to start block")
	if p.fatal {
		return &ast.BlockStmt{LBrace: lbrace}
	}
	var stmts []ast.Stmt
	for p.curr.Type != tokens.TokenRBrace && p.curr.Type != tokens.TokenEndOfFile {
		prevLine, prevCol := p.curr.Span.StartLine, p.curr.Span.StartColumn
		stmt := p.parseStatement()
		if p.fatal {
			return &ast.BlockStmt{LBrace: lbrace}
		}
		if stmt != nil {
			stmts = append(stmts, stmt)
		}
		if p.curr.Type == tokens.TokenSemicolon {
			p.advance()
		}
		if p.curr.Span.StartLine == prevLine && p.curr.Span.StartColumn == prevCol {
			p.advance()
		}
	}
	rbrace := p.expect(tokens.TokenRBrace, "expected '}' to end block")
	return &ast.BlockStmt{LBrace: lbrace, Stmts: stmts, RBrace: rbrace}
}

func (p *Parser) parseStatement() ast.Stmt {
	switch p.curr.Type {
	case tokens.TokenLBrace:
		return p.parseBlock()
	case tokens.TokenLet:
		return p.parseLet()
	case tokens.TokenVar:
		return p.parseVar()
	case tokens.TokenIf:
		return p.parseIf()
	case tokens.TokenWhile:
		return p.parseWhile()
	case tokens.TokenDo:
		return p.parseDoWhile()
	case tokens.TokenLoop, tokens.TokenRev:
		return p.parseLoop()
	case tokens.TokenRet:
		return p.parseReturn()
	case tokens.TokenBreak:
		t := p.curr
		p.advance()
		return &ast.BreakStmt{Tok: t}
	case tokens.TokenContinue:
		t := p.curr
		p.advance()
		return &ast.ContinueStmt{Tok: t}
	default:
		tok := p.curr
		ex := p.parseExpression(precLowest)
		return &ast.ExpressionStmt{E: ex, Tok: tok}
	}
}

func (p *Parser) parseLet() ast.Stmt {
	tok := p.expect(tokens.TokenLet, "expected 'let'")
	if p.fatal {
		return &ast.LetStmt{NameTok: tok}
	}
	name := p.expect(tokens.TokenIdentifier, "expected identifier after let")
	if p.fatal {
		return &ast.LetStmt{NameTok: tok}
	}
	var typ string
	if p.curr.Type == tokens.TokenColon {
		p.advance()
		typ = p.parseTypeName()
		if p.fatal {
			return &ast.LetStmt{NameTok: tok}
		}
	}
	var init ast.Expr
	if p.curr.Type == tokens.TokenEqual {
		p.advance()
		init = p.parseExpression(precLowest)
		if p.fatal {
			return &ast.LetStmt{NameTok: tok}
		}
	}
	return &ast.LetStmt{Name: name.Text, Type: typ, Value: init, NameTok: tok}
}

func (p *Parser) parseVar() ast.Stmt {
	tok := p.expect(tokens.TokenVar, "expected 'var'")
	if p.fatal {
		return &ast.VarStmt{NameTok: tok}
	}
	name := p.expect(tokens.TokenIdentifier, "expected identifier after var")
	if p.fatal {
		return &ast.VarStmt{NameTok: tok}
	}
	var typ string
	if p.curr.Type == tokens.TokenColon {
		p.advance()
		typ = p.parseTypeName()
		if p.fatal {
			return &ast.VarStmt{NameTok: tok}
		}
	}
	var init ast.Expr
	if p.curr.Type == tokens.TokenEqual {
		p.advance()
		init = p.parseExpression(precLowest)
		if p.fatal {
			return &ast.VarStmt{NameTok: tok}
		}
	}
	return &ast.VarStmt{Name: name.Text, Type: typ, Value: init, NameTok: tok}
}

func (p *Parser) parseIf() ast.Stmt {
	t := p.expect(tokens.TokenIf, "expected 'if'")
	if p.fatal {
		return &ast.IfStmt{Tok: t}
	}
	p.expect(tokens.TokenLParen, "expected '('")
	if p.fatal {
		return &ast.IfStmt{Tok: t}
	}
	cond := p.parseExpression(precLowest)
	if p.fatal {
		return &ast.IfStmt{Tok: t}
	}
	p.expect(tokens.TokenRParen, "expected ')'")
	if p.fatal {
		return &ast.IfStmt{Tok: t}
	}
	thenBlk := p.parseBlock()
	if p.fatal {
		return &ast.IfStmt{Tok: t}
	}
	var elifs []ast.ElseIf
	for p.curr.Type == tokens.TokenElif {
		p.advance()
		p.expect(tokens.TokenLParen, "expected '('")
		if p.fatal {
			return &ast.IfStmt{Tok: t}
		}
		c := p.parseExpression(precLowest)
		if p.fatal {
			return &ast.IfStmt{Tok: t}
		}
		p.expect(tokens.TokenRParen, "expected ')'")
		if p.fatal {
			return &ast.IfStmt{Tok: t}
		}
		b := p.parseBlock()
		if p.fatal {
			return &ast.IfStmt{Tok: t}
		}
		elifs = append(elifs, ast.ElseIf{Cond: c, Then: b})
	}
	var elseBlk *ast.BlockStmt
	if p.curr.Type == tokens.TokenElse {
		p.advance()
		elseBlk = p.parseBlock()
		if p.fatal {
			return &ast.IfStmt{Tok: t}
		}
	}
	return &ast.IfStmt{Cond: cond, Then: thenBlk, Elifs: elifs, Else: elseBlk, Tok: t}
}

func (p *Parser) parseWhile() ast.Stmt {
	t := p.expect(tokens.TokenWhile, "expected 'while'")
	if p.fatal {
		return &ast.WhileStmt{Tok: t}
	}
	p.expect(tokens.TokenLParen, "expected '('")
	if p.fatal {
		return &ast.WhileStmt{Tok: t}
	}
	cond := p.parseExpression(precLowest)
	if p.fatal {
		return &ast.WhileStmt{Tok: t}
	}
	p.expect(tokens.TokenRParen, "expected ')'")
	if p.fatal {
		return &ast.WhileStmt{Tok: t}
	}
	body := p.parseBlock()
	return &ast.WhileStmt{Cond: cond, Body: body, Tok: t}
}

func (p *Parser) parseDoWhile() ast.Stmt {
	t := p.expect(tokens.TokenDo, "expected 'do'")
	if p.fatal {
		return &ast.DoWhileStmt{Tok: t}
	}
	body := p.parseBlock()
	if p.fatal {
		return &ast.DoWhileStmt{Tok: t}
	}
	p.expect(tokens.TokenWhile, "expected 'while' after do-block")
	if p.fatal {
		return &ast.DoWhileStmt{Tok: t}
	}
	p.expect(tokens.TokenLParen, "expected '('")
	if p.fatal {
		return &ast.DoWhileStmt{Tok: t}
	}
	cond := p.parseExpression(precLowest)
	if p.fatal {
		return &ast.DoWhileStmt{Tok: t}
	}
	p.expect(tokens.TokenRParen, "expected ')'")
	return &ast.DoWhileStmt{Body: body, Cond: cond, Tok: t}
}

func (p *Parser) parseLoop() ast.Stmt {
	reverse := false
	if p.curr.Type == tokens.TokenRev {
		reverse = true
		p.advance()
	}
	p.expect(tokens.TokenLoop, "expected 'loop'")
	if p.fatal {
		return &ast.LoopStmt{}
	}
	// Two forms:
	// loop (i: a..b [step s]) { ... }
	// loop i in expr { ... }
	if p.curr.Type == tokens.TokenLParen {
		p.advance()
		name := p.expect(tokens.TokenIdentifier, "expected loop variable")
		if p.fatal {
			return &ast.LoopStmt{}
		}
		p.expect(tokens.TokenColon, "expected ':' after loop variable")
		if p.fatal {
			return &ast.LoopStmt{}
		}
		from := p.parseExpression(precLowest)
		if p.fatal {
			return &ast.LoopStmt{}
		}
		if p.curr.Type != tokens.TokenRange && p.curr.Type != tokens.TokenRangeInclusive {
			p.report(p.curr, "expected range operator '..' or '..='")
			return &ast.LoopStmt{}
		}
		inclusive := p.curr.Type == tokens.TokenRangeInclusive
		p.advance()
		to := p.parseExpression(precLowest)
		if p.fatal {
			return &ast.LoopStmt{}
		}
		var step ast.Expr
		// 'step' removed; default only
		p.expect(tokens.TokenRParen, "expected ')'")
		if p.fatal {
			return &ast.LoopStmt{}
		}
		body := p.parseBlock()
		return &ast.LoopStmt{Reverse: reverse, VarName: name.Text, From: from, To: to, Inclusive: inclusive, Step: step, Body: body, Tok: name}
	}
	// loop i in expr { ... }
	name := p.expect(tokens.TokenIdentifier, "expected loop variable")
	if p.fatal {
		return &ast.LoopStmt{}
	}
	p.expect(tokens.TokenIn, "expected 'in' after loop variable")
	if p.fatal {
		return &ast.LoopStmt{}
	}
	iter := p.parseExpression(precLowest)
	if p.fatal {
		return &ast.LoopStmt{}
	}
	body := p.parseBlock()
	return &ast.LoopStmt{Reverse: reverse, VarName: name.Text, Iter: iter, Body: body, Tok: name}
}

func (p *Parser) parseReturn() ast.Stmt {
	t := p.expect(tokens.TokenRet, "expected 'ret'")
	if p.fatal {
		return &ast.ReturnStmt{Tok: t}
	}
	var v ast.Expr
	if p.curr.Type != tokens.TokenSemicolon && p.curr.Type != tokens.TokenRBrace {
		v = p.parseExpression(precLowest)
		if p.fatal {
			return &ast.ReturnStmt{Tok: t}
		}
	}
	return &ast.ReturnStmt{Value: v, Tok: t}
}
