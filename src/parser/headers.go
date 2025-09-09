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

// helpers
func (p *Parser) parseParamList() []ast.Parameter {
	var params []ast.Parameter
	if p.curr.Type != tokens.TokenRParen {
		for {
			paramName := p.expect(tokens.TokenIdentifier, "expected parameter name")
			if p.fatal {
				return params
			}
			p.expect(tokens.TokenColon, "expected ':'")
			if p.fatal {
				return params
			}
			typStr := p.parseTypeName()
			if p.fatal {
				return params
			}
			params = append(params, ast.Parameter{Name: paramName.Text, Type: typStr, Tok: paramName})
			if p.curr.Type == tokens.TokenComma {
				p.advance()
				continue
			}
			break
		}
	}
	return params
}

func (p *Parser) parseOptionalReturnType(defaultType string) string {
	retType := defaultType
	if p.curr.Type == tokens.TokenColon {
		p.advance()
		retType = p.parseTypeName()
	}
	return retType
}

func (p *Parser) parseOptionalBodyOrSemicolon() []ast.Stmt {
	var body []ast.Stmt
	if p.curr.Type == tokens.TokenLBrace {
		blk := p.parseBlock()
		if p.fatal {
			return nil
		}
		body = blk.Stmts
	} else {
		_ = p.match(tokens.TokenSemicolon)
	}
	return body
}

// body parsers
func (p *Parser) parseClassBody(cd *ast.ClassDecl) bool {
	// assumes current token is '{'
	p.advance()
	for p.curr.Type != tokens.TokenRBrace && p.curr.Type != tokens.TokenEndOfFile {
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
		// constructor
		if p.curr.Type == tokens.TokenIdentifier && p.curr.Text == cd.Name && p.peek.Type == tokens.TokenLParen {
			_ = p.expect(tokens.TokenIdentifier, "expected constructor name")
			p.expect(tokens.TokenLParen, "expected '('")
			if p.fatal {
				return false
			}
			params := p.parseParamList()
			p.expect(tokens.TokenRParen, "expected ')'")
			if p.fatal {
				return false
			}
			body := p.parseOptionalBodyOrSemicolon()
			cd.Builders = append(cd.Builders, ast.MethodDecl{Visibility: innerVis, Name: cd.Name, Params: params, ReturnType: "", Body: body})
			continue
		}
		// method modifiers and signatures
		isTemplate := false
		isOverride := false
		for p.curr.Type == tokens.TokenTemplate || p.curr.Type == tokens.TokenOverride {
			if p.curr.Type == tokens.TokenTemplate {
				isTemplate = true
			}
			if p.curr.Type == tokens.TokenOverride {
				isOverride = true
			}
			p.advance()
		}
		_ = p.match(tokens.TokenVar)
		if p.curr.Type == tokens.TokenFunc {
			p.advance()
		}
		if p.curr.Type == tokens.TokenIdentifier && p.peek.Type == tokens.TokenLParen {
			nameTok := p.expect(tokens.TokenIdentifier, "expected method name")
			if p.fatal {
				return false
			}
			p.expect(tokens.TokenLParen, "expected '('")
			if p.fatal {
				return false
			}
			params := p.parseParamList()
			p.expect(tokens.TokenRParen, "expected ')'")
			if p.fatal {
				return false
			}
			retType := p.parseOptionalReturnType("void")
			body := p.parseOptionalBodyOrSemicolon()
			cd.Methods = append(cd.Methods, ast.MethodDecl{Visibility: innerVis, Name: nameTok.Text, Params: params, ReturnType: retType, Body: body, IsTemplate: isTemplate, IsOverride: isOverride})
			continue
		}
		// fields
		isFinal := false
		for p.curr.Type == tokens.TokenFin {
			isFinal = true
			p.advance()
		}
		_ = p.match(tokens.TokenVar)
		if isFinal || p.curr.Type == tokens.TokenIdentifier {
			fname := p.expect(tokens.TokenIdentifier, "expected field name")
			if p.fatal {
				return false
			}
			p.expect(tokens.TokenColon, "expected ':' after field name")
			if p.fatal {
				return false
			}
			ft := p.parseTypeName()
			if p.fatal {
				return false
			}
			if p.curr.Type == tokens.TokenEqual {
				p.advance()
				_ = p.parseExpression(precLowest)
				if p.fatal {
					return false
				}
			}
			cd.Fields = append(cd.Fields, ast.FieldDecl{Name: fname.Text, Type: ft, IsFinal: isFinal})
			_ = p.match(tokens.TokenSemicolon)
			continue
		}
		p.report(p.curr, fmt.Sprintf("unexpected token %s in class body", tokens.TokenTypeName(p.curr.Type)))
		return false
	}
	p.expect(tokens.TokenRBrace, "expected '}' to end class body")
	return !p.fatal
}

func (p *Parser) parseStructBody(sd *ast.StructDecl) bool {
	p.advance()
	for p.curr.Type != tokens.TokenRBrace && p.curr.Type != tokens.TokenEndOfFile {
		if p.curr.Type == tokens.TokenLet {
			p.report(p.curr, "'let' is only allowed inside functions; use 'var' for fields")
			return false
		}
		isFinal := false
		for p.curr.Type == tokens.TokenFin {
			isFinal = true
			p.advance()
		}
		if p.curr.Type == tokens.TokenIdentifier && p.curr.Text == sd.Name && p.peek.Type == tokens.TokenLParen {
			_ = p.expect(tokens.TokenIdentifier, "expected constructor name")
			p.expect(tokens.TokenLParen, "expected '('")
			if p.fatal {
				return false
			}
			params := p.parseParamList()
			p.expect(tokens.TokenRParen, "expected ')'")
			if p.fatal {
				return false
			}
			body := p.parseOptionalBodyOrSemicolon()
			sd.Builders = append(sd.Builders, ast.MethodDecl{Name: sd.Name, Params: params, ReturnType: "", Body: body})
			continue
		}
		if isFinal || p.curr.Type == tokens.TokenIdentifier || p.curr.Type == tokens.TokenVar {
			_ = p.match(tokens.TokenVar)
			fname := p.expect(tokens.TokenIdentifier, "expected field name")
			if p.fatal {
				return false
			}
			p.expect(tokens.TokenColon, "expected ':' after field name")
			if p.fatal {
				return false
			}
			ft := p.parseTypeName()
			if p.fatal {
				return false
			}
			if p.curr.Type == tokens.TokenEqual {
				p.advance()
				_ = p.parseExpression(precLowest)
				if p.fatal {
					return false
				}
			}
			sd.Fields = append(sd.Fields, ast.FieldDecl{Name: fname.Text, Type: ft, IsFinal: isFinal})
			_ = p.match(tokens.TokenSemicolon)
			continue
		}
		if p.curr.Type == tokens.TokenFunc {
			p.advance()
			mname := p.expect(tokens.TokenIdentifier, "expected method name")
			if p.fatal {
				return false
			}
			p.expect(tokens.TokenLParen, "expected '('")
			if p.fatal {
				return false
			}
			params := p.parseParamList()
			p.expect(tokens.TokenRParen, "expected ')'")
			if p.fatal {
				return false
			}
			retType := p.parseOptionalReturnType("void")
			body := p.parseOptionalBodyOrSemicolon()
			sd.Methods = append(sd.Methods, ast.MethodDecl{Name: mname.Text, Params: params, ReturnType: retType, Body: body})
			continue
		}
		p.report(p.curr, fmt.Sprintf("unexpected token %s in struct body", tokens.TokenTypeName(p.curr.Type)))
		return false
	}
	p.expect(tokens.TokenRBrace, "expected '}' to end struct body")
	return !p.fatal
}

func (p *Parser) parseEnumBody(ed *ast.EnumDecl) bool {
	p.advance()
	parsingCases := true
	for p.curr.Type != tokens.TokenRBrace && p.curr.Type != tokens.TokenEndOfFile {
		innerVis := ast.VisDefault
		switch p.curr.Type {
		case tokens.TokenPub:
			innerVis = ast.VisPublic
			p.advance()
			parsingCases = false
		case tokens.TokenPriv:
			innerVis = ast.VisPrivate
			p.advance()
			parsingCases = false
		case tokens.TokenProt:
			innerVis = ast.VisProtected
			p.advance()
			parsingCases = false
		}
		if parsingCases && p.curr.Type == tokens.TokenIdentifier {
			cname := p.expect(tokens.TokenIdentifier, "expected case name")
			if p.fatal {
				return false
			}
			var args []ast.Expr
			if p.curr.Type == tokens.TokenLParen {
				p.advance()
				if p.curr.Type != tokens.TokenRParen {
					for {
						args = append(args, p.parseExpression(precLowest))
						if p.fatal {
							return false
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
					return false
				}
			}
			ed.Cases = append(ed.Cases, ast.EnumCase{Name: cname.Text, Params: args})
			if p.curr.Type == tokens.TokenComma {
				p.advance()
			}
			continue
		}
		if p.curr.Type == tokens.TokenIdentifier && p.curr.Text == ed.Name && p.peek.Type == tokens.TokenLParen {
			_ = p.expect(tokens.TokenIdentifier, "expected constructor name")
			p.expect(tokens.TokenLParen, "expected '('")
			if p.fatal {
				return false
			}
			params := p.parseParamList()
			p.expect(tokens.TokenRParen, "expected ')'")
			if p.fatal {
				return false
			}
			body := p.parseOptionalBodyOrSemicolon()
			ed.Builders = append(ed.Builders, ast.MethodDecl{Visibility: innerVis, Name: ed.Name, Params: params, ReturnType: "", Body: body})
			continue
		}
		if p.curr.Type == tokens.TokenFunc {
			parsingCases = false
			p.advance()
			mname := p.expect(tokens.TokenIdentifier, "expected method name")
			if p.fatal {
				return false
			}
			p.expect(tokens.TokenLParen, "expected '('")
			if p.fatal {
				return false
			}
			params := p.parseParamList()
			p.expect(tokens.TokenRParen, "expected ')'")
			if p.fatal {
				return false
			}
			retType := p.parseOptionalReturnType("void")
			body := p.parseOptionalBodyOrSemicolon()
			ed.Methods = append(ed.Methods, ast.MethodDecl{Visibility: innerVis, Name: mname.Text, Params: params, ReturnType: retType, Body: body})
			continue
		}
		// fields
		isFinal := false
		for p.curr.Type == tokens.TokenFin {
			isFinal = true
			p.advance()
			parsingCases = false
		}
		if p.curr.Type == tokens.TokenVar {
			p.advance()
			parsingCases = false
		}
		if p.curr.Type == tokens.TokenIdentifier && !parsingCases {
			fname := p.expect(tokens.TokenIdentifier, "expected field name")
			if p.fatal {
				return false
			}
			p.expect(tokens.TokenColon, "expected ':' after field name")
			if p.fatal {
				return false
			}
			ft := p.parseTypeName()
			if p.fatal {
				return false
			}
			if p.curr.Type == tokens.TokenEqual {
				p.advance()
				_ = p.parseExpression(precLowest)
				if p.fatal {
					return false
				}
			}
			ed.Fields = append(ed.Fields, ast.FieldDecl{Name: fname.Text, Type: ft, IsFinal: isFinal})
			_ = p.match(tokens.TokenSemicolon)
			continue
		}
		p.report(p.curr, fmt.Sprintf("unexpected token %s in enum body", tokens.TokenTypeName(p.curr.Type)))
		return false
	}
	p.expect(tokens.TokenRBrace, "expected '}' to end enum body")
	return !p.fatal
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
	// Optional 'template' modifier before class at top-level
	topIsTemplate := false
	if p.curr.Type == tokens.TokenTemplate {
		topIsTemplate = true
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
		params := p.parseParamList()
		p.expect(tokens.TokenRParen, "expected ')'")
		if p.fatal {
			return nil
		}
		retType := p.parseOptionalReturnType("void")
		return &ast.FuncDecl{Visibility: vis, Name: nameTok.Text, Params: params, ReturnType: retType, HeaderTok: funcTok, BodySkipped: false}
	case tokens.TokenClass:
		classTok := p.curr
		p.advance()
		nameTok := p.expect(tokens.TokenIdentifier, "expected class name")
		if p.fatal {
			return nil
		}
		cd := &ast.ClassDecl{Visibility: vis, Name: nameTok.Text, HeaderTok: classTok, IsTemplate: topIsTemplate}
		if p.curr.Type == tokens.TokenColon {
			p.advance()
			baseTok := p.expect(tokens.TokenIdentifier, "expected base class name after ':'")
			if p.fatal {
				return nil
			}
			cd.SuperTypes = append(cd.SuperTypes, baseTok.Text)
		}
		if p.curr.Type == tokens.TokenLBrace {
			if !p.parseClassBody(cd) {
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
			if !p.parseStructBody(sd) {
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
			if !p.parseEnumBody(ed) {
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
