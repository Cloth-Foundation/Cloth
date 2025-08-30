package parser

import (
	"compiler/src/ast"
	"compiler/src/tokens"
	"fmt"
)

func (p *Parser) parseExpression(rbp int) ast.Expr {
	left := p.parsePrefix()
	if p.fatal {
		return left
	}
	for {
		op := p.curr.Type
		if rbp <= precOr && op == tokens.TokenQuestion {
			q := p.curr
			p.advance()
			thenExpr := p.parseExpression(precLowest)
			if p.fatal {
				return left
			}
			p.expect(tokens.TokenColon, "expected ':' in ternary")
			if p.fatal {
				return left
			}
			elseExpr := p.parseExpression(precLowest)
			if p.fatal {
				return left
			}
			left = &ast.TernaryExpr{Cond: left, ThenExpr: thenExpr, ElseExpr: elseExpr, QTok: q, CTok: p.curr}
			continue
		}
		lbp := precedenceFor(op)
		if lbp == 0 || lbp < rbp {
			if p.curr.Type == tokens.TokenLParen {
				lpar := p.curr
				p.advance()
				var args []ast.Expr
				if p.curr.Type != tokens.TokenRParen {
					for {
						arg := p.parseExpression(precLowest)
						if p.fatal {
							return left
						}
						args = append(args, arg)
						if p.curr.Type == tokens.TokenComma {
							p.advance()
							continue
						}
						break
					}
				}
				rpar := p.expect(tokens.TokenRParen, "expected ')' after arguments")
				if p.fatal {
					return left
				}
				left = &ast.CallExpr{Callee: left, Args: args, LParen: lpar, RParen: rpar}
				continue
			}
			if p.curr.Type == tokens.TokenDot {
				dot := p.curr
				p.advance()
				name := p.expect(tokens.TokenIdentifier, "expected member name after '.'")
				if p.fatal {
					return left
				}
				left = &ast.MemberAccessExpr{Object: left, Member: name.Text, DotTok: dot, MemberTok: name}
				continue
			}
			if p.curr.Type == tokens.TokenLBracket {
				lbr := p.curr
				p.advance()
				idx := p.parseExpression(precLowest)
				if p.fatal {
					return left
				}
				rbr := p.expect(tokens.TokenRBracket, "expected ']' after index")
				if p.fatal {
					return left
				}
				left = &ast.IndexExpr{Base: left, Index: idx, LBrack: lbr, RBrack: rbr}
				continue
			}
			if p.curr.Type == tokens.TokenAs {
				asTok := p.curr
				p.advance()
				typ := p.parseTypeName()
				if p.fatal {
					return left
				}
				left = &ast.CastExpr{Expr: left, TargetType: typ, AsTok: asTok}
				continue
			}
			break
		}
		if op == tokens.TokenPlusPlus || op == tokens.TokenMinusMinus {
			tok := p.curr
			p.advance()
			left = &ast.UnaryExpr{Operator: op, Operand: left, IsPostfix: true, OpTok: tok}
			continue
		}
		if op == tokens.TokenEqual || op == tokens.TokenPlusEqual || op == tokens.TokenMinusEqual || op == tokens.TokenStarEqual || op == tokens.TokenSlashEqual || op == tokens.TokenPercentEqual {
			opTok := p.curr
			p.advance()
			right := p.parseExpression(lbp)
			if p.fatal {
				return left
			}
			left = &ast.AssignExpr{Target: left, Operator: op, Value: right, OpTok: opTok}
			continue
		}
		opTok := p.curr
		p.advance()
		right := p.parseExpression(lbp + 1)
		if p.fatal {
			return left
		}
		left = &ast.BinaryExpr{Left: left, Operator: op, Right: right, OpTok: opTok}
	}
	return left
}

func (p *Parser) parsePrefix() ast.Expr {
	switch p.curr.Type {
	case tokens.TokenNew:
		// new Type(args...): parse a qualified type reference then arguments
		p.advance()
		first := p.expect(tokens.TokenIdentifier, "expected type name after 'new'")
		if p.fatal {
			return &ast.IdentifierExpr{Name: first.Text, Tok: first}
		}
		var callee ast.Expr = &ast.IdentifierExpr{Name: first.Text, Tok: first}
		for p.curr.Type == tokens.TokenDot {
			dot := p.curr
			p.advance()
			name := p.expect(tokens.TokenIdentifier, "expected member after '.'")
			if p.fatal {
				return callee
			}
			callee = &ast.MemberAccessExpr{Object: callee, Member: name.Text, DotTok: dot, MemberTok: name}
		}
		lpar := p.expect(tokens.TokenLParen, "expected '(' after type in 'new' expression")
		if p.fatal {
			return callee
		}
		var args []ast.Expr
		if p.curr.Type != tokens.TokenRParen {
			for {
				arg := p.parseExpression(precLowest)
				if p.fatal {
					return &ast.CallExpr{Callee: callee, Args: args, LParen: lpar}
				}
				args = append(args, arg)
				if p.curr.Type == tokens.TokenComma {
					p.advance()
					continue
				}
				break
			}
		}
		rpar := p.expect(tokens.TokenRParen, "expected ')' to close 'new' arguments")
		return &ast.CallExpr{Callee: callee, Args: args, LParen: lpar, RParen: rpar}
	case tokens.TokenPlusPlus, tokens.TokenMinusMinus, tokens.TokenNot, tokens.TokenMinus, tokens.TokenBitNot:
		opTok := p.curr
		op := p.curr.Type
		p.advance()
		rhs := p.parseExpression(precPrefix)
		if p.fatal {
			return &ast.UnaryExpr{Operator: op, Operand: rhs, IsPostfix: false, OpTok: opTok}
		}
		return &ast.UnaryExpr{Operator: op, Operand: rhs, IsPostfix: false, OpTok: opTok}
	case tokens.TokenLParen:
		p.advance()
		e := p.parseExpression(precLowest)
		if p.fatal {
			return e
		}
		p.expect(tokens.TokenRParen, "expected ')'")
		return e
	case tokens.TokenLBracket:
		lbr := p.curr
		p.advance()
		var elems []ast.Expr
		if p.curr.Type != tokens.TokenRBracket {
			for {
				elems = append(elems, p.parseExpression(precLowest))
				if p.fatal {
					return &ast.ArrayLiteralExpr{Elements: elems, LBrack: lbr}
				}
				if p.curr.Type == tokens.TokenComma {
					p.advance()
					continue
				}
				break
			}
		}
		rbr := p.expect(tokens.TokenRBracket, "expected ']' in array literal")
		return &ast.ArrayLiteralExpr{Elements: elems, LBrack: lbr, RBrack: rbr}
	case tokens.TokenSelf:
		// Treat 'self' keyword like an identifier in expressions
		tok := p.curr
		p.advance()
		return &ast.IdentifierExpr{Name: "self", Tok: tok}
	case tokens.TokenIdentifier:
		tok := p.curr
		p.advance()
		return &ast.IdentifierExpr{Name: tok.Text, Tok: tok}
	case tokens.TokenF16, tokens.TokenF32, tokens.TokenF64:
		// Allow builtin float type tokens to appear as identifiers in expressions (e.g., to_float(f32))
		tok := p.curr
		p.advance()
		return &ast.IdentifierExpr{Name: tok.Text, Tok: tok}
	case tokens.TokenNumber:
		tok := p.curr
		p.advance()
		if lit, ok := tok.Value.(tokens.NumericLiteral); ok {
			return &ast.NumberLiteralExpr{Value: lit, Tok: tok}
		}
		return &ast.NumberLiteralExpr{Value: tokens.NumericLiteral{Digits: tok.Text, Base: 10}, Tok: tok}
	case tokens.TokenString:
		tok := p.curr
		p.advance()
		if s, ok := tok.Value.(string); ok {
			return &ast.StringLiteralExpr{Value: s, Tok: tok}
		}
		return &ast.StringLiteralExpr{Value: tok.Text, Tok: tok}
	case tokens.TokenChar:
		tok := p.curr
		p.advance()
		if s, ok := tok.Value.(string); ok {
			return &ast.CharLiteralExpr{Value: s, Tok: tok}
		}
		return &ast.CharLiteralExpr{Value: tok.Text, Tok: tok}
	case tokens.TokenTrue:
		tok := p.curr
		p.advance()
		return &ast.BoolLiteralExpr{Value: true, Tok: tok}
	case tokens.TokenFalse:
		tok := p.curr
		p.advance()
		return &ast.BoolLiteralExpr{Value: false, Tok: tok}
	case tokens.TokenNull:
		tok := p.curr
		p.advance()
		return &ast.NullLiteralExpr{Tok: tok}
	}
	tok := p.curr
	p.report(tok, fmt.Sprintf("unexpected token %s", tokens.TokenTypeName(tok.Type)))
	p.advance()
	return &ast.IdentifierExpr{Name: tok.Text, Tok: tok}
}
