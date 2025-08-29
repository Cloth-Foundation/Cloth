package parser

import (
	"compiler/src/ast"
	"compiler/src/tokens"
	"fmt"
)

func (p *Parser) parseImport() *ast.ImportDecl {
	importTok := p.curr
	p.advance()
	var segs []string
	id := p.expect(tokens.TokenIdentifier, "expected module path segment")
	if p.fatal {
		return nil
	}
	segs = append(segs, id.Text)
	for p.curr.Type == tokens.TokenDot {
		p.advance()
		id = p.expect(tokens.TokenIdentifier, "expected module path segment")
		if p.fatal {
			return nil
		}
		segs = append(segs, id.Text)
	}
	var items []ast.ImportItem
	if p.curr.Type == tokens.TokenDoubleColon {
		p.advance()
		p.expect(tokens.TokenLBrace, "expected '{'")
		if p.fatal {
			return nil
		}
		for p.curr.Type != tokens.TokenRBrace && p.curr.Type != tokens.TokenEndOfFile {
			nameTok := p.expect(tokens.TokenIdentifier, "expected import item name")
			if p.fatal {
				return nil
			}
			alias := ""
			if p.curr.Type == tokens.TokenAs {
				p.advance()
				aliasTok := p.expect(tokens.TokenIdentifier, "expected alias identifier")
				if p.fatal {
					return nil
				}
				alias = aliasTok.Text
			}
			items = append(items, ast.ImportItem{Name: nameTok.Text, Alias: alias, Tok: nameTok})
			if p.curr.Type == tokens.TokenComma {
				p.advance()
				continue
			}
			break
		}
		p.expect(tokens.TokenRBrace, "expected '}' after import list")
		if p.fatal {
			return nil
		}
	}
	_ = p.match(tokens.TokenSemicolon)
	return &ast.ImportDecl{PathSegments: segs, Items: items, Tok: importTok}
}

func (p *Parser) parseTopLevelDeclHeader() ast.Decl {
	vis := ast.VisDefault
	switch p.curr.Type {
	case tokens.TokenPub:
		vis = ast.VisPublic
		p.advance()
	case tokens.TokenPriv:
		vis = ast.VisPrivate
		p.advance()
	case tokens.TokenProt:
		vis = ast.VisProtected
		p.advance()
	}
	switch p.curr.Type {
	case tokens.TokenFunc:
		funcTok := p.curr
		p.advance()
		nameTok := p.expect(tokens.TokenIdentifier, "expected function name")
		if p.fatal {
			return nil
		}
		p.expect(tokens.TokenLParen, "expected '('")
		if p.fatal {
			return nil
		}
		var params []ast.Parameter
		if p.curr.Type != tokens.TokenRParen {
			for {
				paramName := p.expect(tokens.TokenIdentifier, "expected parameter name")
				if p.fatal {
					return nil
				}
				p.expect(tokens.TokenColon, "expected ':' after parameter name")
				if p.fatal {
					return nil
				}
				typStr := p.parseTypeName()
				if p.fatal {
					return nil
				}
				params = append(params, ast.Parameter{Name: paramName.Text, Type: typStr, Tok: paramName})
				if p.curr.Type == tokens.TokenComma {
					p.advance()
					continue
				}
				break
			}
		}
		p.expect(tokens.TokenRParen, "expected ')'")
		if p.fatal {
			return nil
		}
		retType := "void"
		if p.curr.Type == tokens.TokenColon {
			p.advance()
			retType = p.parseTypeName()
			if p.fatal {
				return nil
			}
		}
		return &ast.FuncDecl{Visibility: vis, Name: nameTok.Text, Params: params, ReturnType: retType, HeaderTok: funcTok, BodySkipped: false}
	case tokens.TokenClass:
		classTok := p.curr
		p.advance()
		nameTok := p.expect(tokens.TokenIdentifier, "expected class name")
		if p.fatal {
			return nil
		}
		cd := &ast.ClassDecl{Visibility: vis, Name: nameTok.Text, HeaderTok: classTok}
		// Optional class body
		if p.curr.Type == tokens.TokenLBrace {
			p.advance()
			for p.curr.Type != tokens.TokenRBrace && p.curr.Type != tokens.TokenEndOfFile {
				// Parse simple field or method header
				innerVis := ast.VisDefault
				switch p.curr.Type {
				case tokens.TokenPub:
					innerVis = ast.VisPublic
					p.advance()
				case tokens.TokenPriv:
					innerVis = ast.VisPrivate
					p.advance()
				case tokens.TokenProt:
					innerVis = ast.VisProtected
					p.advance()
				}
				// Methods
				if p.curr.Type == tokens.TokenFunc || p.curr.Type == tokens.TokenBuilder {
					isBuilder := p.curr.Type == tokens.TokenBuilder
					p.advance()
					name := "builder"
					if !isBuilder {
						nameTok2 := p.expect(tokens.TokenIdentifier, "expected method name")
						if p.fatal {
							return nil
						}
						name = nameTok2.Text
					}
					p.expect(tokens.TokenLParen, "expected '('")
					if p.fatal {
						return nil
					}
					var params []ast.Parameter
					if p.curr.Type != tokens.TokenRParen {
						for {
							paramName := p.expect(tokens.TokenIdentifier, "expected parameter name")
							if p.fatal {
								return nil
							}
							p.expect(tokens.TokenColon, "expected ':'")
							if p.fatal {
								return nil
							}
							typStr := p.parseTypeName()
							if p.fatal {
								return nil
							}
							params = append(params, ast.Parameter{Name: paramName.Text, Type: typStr, Tok: paramName})
							if p.curr.Type == tokens.TokenComma {
								p.advance()
								continue
							}
							break
						}
					}
					p.expect(tokens.TokenRParen, "expected ')'")
					if p.fatal {
						return nil
					}
					retType := "void"
					if !isBuilder && p.curr.Type == tokens.TokenColon {
						p.advance()
						retType = p.parseTypeName()
						if p.fatal {
							return nil
						}
					}
					var body []ast.Stmt
					if p.curr.Type == tokens.TokenLBrace {
						blk := p.parseBlock()
						if p.fatal {
							return nil
						}
						body = blk.Stmts
					}
					md := ast.MethodDecl{Visibility: innerVis, Name: name, Params: params, ReturnType: retType, Body: body}
					cd.Methods = append(cd.Methods, md)
					continue
				}
				// Forbid let at type body level
				if p.curr.Type == tokens.TokenLet {
					p.report(p.curr, "'let' is only allowed inside functions; use 'var' for fields")
					return nil
				}
				// Field modifiers: allow fin only
				isFinal := false
				for p.curr.Type == tokens.TokenFin {
					isFinal = true
					p.advance()
				}
				// Optional 'var'
				_ = p.match(tokens.TokenVar)
				// field: name : Type [= initializer]
				if isFinal || p.curr.Type == tokens.TokenIdentifier {
					fname := p.expect(tokens.TokenIdentifier, "expected field name")
					if p.fatal {
						return nil
					}
					p.expect(tokens.TokenColon, "expected ':' after field name")
					if p.fatal {
						return nil
					}
					ft := p.parseTypeName()
					if p.fatal {
						return nil
					}
					// optional initializer (ignored for now)
					if p.curr.Type == tokens.TokenEqual {
						p.advance()
						_ = p.parseExpression(precLowest)
						if p.fatal {
							return nil
						}
					}
					cd.Fields = append(cd.Fields, ast.FieldDecl{Name: fname.Text, Type: ft, IsFinal: isFinal})
					_ = p.match(tokens.TokenSemicolon)
					continue
				}
				// if unknown, report and fail
				p.report(p.curr, fmt.Sprintf("unexpected token %s in class body", tokens.TokenTypeName(p.curr.Type)))
				return nil
			}
			p.expect(tokens.TokenRBrace, "expected '}' to end class body")
			if p.fatal {
				return nil
			}
		}
		return cd
	case tokens.TokenStruct:
		structTok := p.curr
		p.advance()
		nameTok := p.expect(tokens.TokenIdentifier, "expected struct name")
		if p.fatal {
			return nil
		}
		sd := &ast.StructDecl{Visibility: vis, Name: nameTok.Text, HeaderTok: structTok}
		if p.curr.Type == tokens.TokenLBrace {
			p.advance()
			for p.curr.Type != tokens.TokenRBrace && p.curr.Type != tokens.TokenEndOfFile {
				// Forbid let at type body level
				if p.curr.Type == tokens.TokenLet {
					p.report(p.curr, "'let' is only allowed inside functions; use 'var' for fields")
					return nil
				}
				// field modifiers: allow fin only
				isFinal := false
				for p.curr.Type == tokens.TokenFin {
					isFinal = true
					p.advance()
				}
				// fields
				if isFinal || p.curr.Type == tokens.TokenIdentifier || p.curr.Type == tokens.TokenVar {
					_ = p.match(tokens.TokenVar)
					fname := p.expect(tokens.TokenIdentifier, "expected field name")
					if p.fatal {
						return nil
					}
					p.expect(tokens.TokenColon, "expected ':' after field name")
					if p.fatal {
						return nil
					}
					ft := p.parseTypeName()
					if p.fatal {
						return nil
					}
					if p.curr.Type == tokens.TokenEqual {
						p.advance()
						_ = p.parseExpression(precLowest)
						if p.fatal {
							return nil
						}
					}
					sd.Fields = append(sd.Fields, ast.FieldDecl{Name: fname.Text, Type: ft, IsFinal: isFinal})
					_ = p.match(tokens.TokenSemicolon)
					continue
				}
				// simple method header
				if p.curr.Type == tokens.TokenFunc {
					p.advance()
					mname := p.expect(tokens.TokenIdentifier, "expected method name")
					if p.fatal {
						return nil
					}
					p.expect(tokens.TokenLParen, "expected '('")
					if p.fatal {
						return nil
					}
					var params []ast.Parameter
					if p.curr.Type != tokens.TokenRParen {
						for {
							paramName := p.expect(tokens.TokenIdentifier, "expected parameter name")
							if p.fatal {
								return nil
							}
							p.expect(tokens.TokenColon, "expected ':'")
							if p.fatal {
								return nil
							}
							typStr := p.parseTypeName()
							if p.fatal {
								return nil
							}
							params = append(params, ast.Parameter{Name: paramName.Text, Type: typStr, Tok: paramName})
							if p.curr.Type == tokens.TokenComma {
								p.advance()
								continue
							}
							break
						}
					}
					p.expect(tokens.TokenRParen, "expected ')'")
					if p.fatal {
						return nil
					}
					retType := "void"
					if p.curr.Type == tokens.TokenColon {
						p.advance()
						retType = p.parseTypeName()
						if p.fatal {
							return nil
						}
					}
					var body []ast.Stmt
					if p.curr.Type == tokens.TokenLBrace {
						blk := p.parseBlock()
						if p.fatal {
							return nil
						}
						body = blk.Stmts
					}
					sd.Methods = append(sd.Methods, ast.MethodDecl{Name: mname.Text, Params: params, ReturnType: retType, Body: body})
					continue
				}
				// unknown
				p.report(p.curr, fmt.Sprintf("unexpected token %s in struct body", tokens.TokenTypeName(p.curr.Type)))
				return nil
			}
			p.expect(tokens.TokenRBrace, "expected '}' to end struct body")
			if p.fatal {
				return nil
			}
		}
		return sd
	case tokens.TokenEnum:
		enumTok := p.curr
		p.advance()
		nameTok := p.expect(tokens.TokenIdentifier, "expected enum name")
		if p.fatal {
			return nil
		}
		ed := &ast.EnumDecl{Visibility: vis, Name: nameTok.Text, HeaderTok: enumTok}
		if p.curr.Type == tokens.TokenLBrace {
			p.advance()
			for p.curr.Type != tokens.TokenRBrace && p.curr.Type != tokens.TokenEndOfFile {
				if p.curr.Type == tokens.TokenLet {
					p.report(p.curr, "'let' is only allowed inside functions; use 'var' or case payloads in enums")
					return nil
				}
				if p.curr.Type == tokens.TokenIdentifier {
					cname := p.expect(tokens.TokenIdentifier, "expected case name")
					if p.fatal {
						return nil
					}
					var args []ast.Expr
					if p.curr.Type == tokens.TokenLParen {
						p.advance()
						if p.curr.Type != tokens.TokenRParen {
							for {
								args = append(args, p.parseExpression(precLowest))
								if p.fatal {
									return nil
								}
								if p.curr.Type == tokens.TokenComma {
									p.advance()
									continue
								}
								break
							}
						}
						p.expect(tokens.TokenRParen, "expected ')' after enum payload")
						if p.fatal {
							return nil
						}
					}
					ed.Cases = append(ed.Cases, ast.EnumCase{Name: cname.Text, Params: args})
					_ = p.match(tokens.TokenComma)
					continue
				}
				p.report(p.curr, fmt.Sprintf("unexpected token %s in enum body", tokens.TokenTypeName(p.curr.Type)))
				return nil
			}
			p.expect(tokens.TokenRBrace, "expected '}' to end enum body")
			if p.fatal {
				return nil
			}
		}
		return ed
	}
	return nil
}

func (p *Parser) parseGlobalVarDecl() ast.Decl {
	vis := ast.VisDefault
	if p.curr.Type == tokens.TokenPub {
		vis = ast.VisPublic
		p.advance()
	}
	if p.curr.Type == tokens.TokenPriv {
		vis = ast.VisPrivate
		p.advance()
	}
	if p.curr.Type == tokens.TokenProt {
		vis = ast.VisProtected
		p.advance()
	}
	if p.curr.Type == tokens.TokenFin {
		p.advance()
	}
	if p.curr.Type == tokens.TokenLet {
		p.report(p.curr, "'let' is only allowed inside functions; use 'var' here")
		return nil
	}
	if p.curr.Type != tokens.TokenVar {
		return nil
	}
	isLet := false
	startTok := p.curr
	p.advance()
	name := p.expect(tokens.TokenIdentifier, "expected identifier")
	if p.fatal {
		return nil
	}
	var typ string
	if p.curr.Type == tokens.TokenColon {
		p.advance()
		typ = p.parseTypeName()
		if p.fatal {
			return nil
		}
	}
	var init ast.Expr
	if p.curr.Type == tokens.TokenEqual {
		p.advance()
		init = p.parseExpression(precLowest)
		if p.fatal {
			return nil
		}
	}
	_ = p.match(tokens.TokenSemicolon)
	return &ast.GlobalVarDecl{Visibility: vis, IsLet: isLet, Name: name.Text, Type: typ, Value: init, Tok: startTok}
}
