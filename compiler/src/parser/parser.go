package parser

import (
	"compiler/src/ast"
	"compiler/src/lexer"
	"compiler/src/tokens"
	"fmt"
)

type Parser struct {
	lx   *lexer.Lexer
	curr tokens.Token
	peek tokens.Token
	errs []error
}

func New(lx *lexer.Lexer) *Parser {
	p := &Parser{lx: lx}
	p.advance()
	return p
}

func (p *Parser) Errors() []error { return p.errs }

func (p *Parser) advance() {
	// Always consume one token and refresh lookahead
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

func (p *Parser) expect(tt tokens.TokenType, msg string) tokens.Token {
	if p.curr.Type != tt {
		err := fmt.Errorf("%s: got %s", msg, tokens.TokenTypeName(p.curr.Type))
		p.errs = append(p.errs, err)
		return p.curr
	}
	t := p.curr
	p.advance()
	return t
}

// ParseFile performs phase-1 header parsing: module, imports, and function headers (bodies skipped).
func (p *Parser) ParseFile() (*ast.File, []error) {
	f := &ast.File{}
	// Module (optional)
	if p.curr.Type == tokens.TokenMod {
		modTok := p.curr
		p.advance()
		nameTok := p.expect(tokens.TokenIdentifier, "expected module identifier")
		f.Module = &ast.ModDecl{Name: nameTok.Text, Tok: modTok}
		_ = p.match(tokens.TokenSemicolon)
	}
	// Imports (zero or more)
	for p.curr.Type == tokens.TokenImport {
		f.Imports = append(f.Imports, p.parseImport())
	}
	// Top-level headers (functions/classes/structs/enums and global vars)
	for p.curr.Type != tokens.TokenEndOfFile {
		prevLine, prevCol := p.curr.Span.StartLine, p.curr.Span.StartColumn
		if p.curr.Type == tokens.TokenLet || p.curr.Type == tokens.TokenVar || p.curr.Type == tokens.TokenPub || p.curr.Type == tokens.TokenPriv || p.curr.Type == tokens.TokenProt {
			if gv := p.parseGlobalVarDecl(); gv != nil {
				f.Decls = append(f.Decls, gv)
				continue
			}
		}
		if decl := p.parseTopLevelDeclHeader(); decl != nil {
			// Phase 2: parse function bodies after header when next token is '{'
			if fd, ok := decl.(*ast.FuncDecl); ok {
				if p.curr.Type == tokens.TokenLBrace {
					fd.Body = p.parseBlock().Stmts
				}
			}
			f.Decls = append(f.Decls, decl)
		} else {
			// Fallback to avoid infinite loop: advance at least one token
			p.advance()
		}
		// Progress guard
		if p.curr.Span.StartLine == prevLine && p.curr.Span.StartColumn == prevCol {
			// force advance to break potential stall
			p.advance()
		}
	}
	return f, p.errs
}

func (p *Parser) parseImport() *ast.ImportDecl {
	importTok := p.curr
	p.advance()
	var segs []string
	id := p.expect(tokens.TokenIdentifier, "expected module path segment")
	segs = append(segs, id.Text)
	for p.curr.Type == tokens.TokenDot {
		p.advance()
		id = p.expect(tokens.TokenIdentifier, "expected module path segment")
		segs = append(segs, id.Text)
	}
	var items []ast.ImportItem
	if p.curr.Type == tokens.TokenDoubleColon {
		p.advance()
		p.expect(tokens.TokenLBrace, "expected '{'")
		for p.curr.Type != tokens.TokenRBrace && p.curr.Type != tokens.TokenEndOfFile {
			nameTok := p.expect(tokens.TokenIdentifier, "expected import item name")
			alias := ""
			if p.curr.Type == tokens.TokenAs {
				p.advance()
				aliasTok := p.expect(tokens.TokenIdentifier, "expected alias identifier")
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
		p.expect(tokens.TokenLParen, "expected '('")
		var params []ast.Parameter
		if p.curr.Type != tokens.TokenRParen {
			for {
				paramName := p.expect(tokens.TokenIdentifier, "expected parameter name")
				p.expect(tokens.TokenColon, "expected ':' after parameter name")
				typStr := p.parseTypeName()
				params = append(params, ast.Parameter{Name: paramName.Text, Type: typStr, Tok: paramName})
				if p.curr.Type == tokens.TokenComma {
					p.advance()
					continue
				}
				break
			}
		}
		p.expect(tokens.TokenRParen, "expected ')'")
		retType := "void"
		if p.curr.Type == tokens.TokenColon {
			p.advance()
			retType = p.parseTypeName()
		}
		return &ast.FuncDecl{Visibility: vis, Name: nameTok.Text, Params: params, ReturnType: retType, HeaderTok: funcTok, BodySkipped: false}
	case tokens.TokenClass:
		classTok := p.curr
		p.advance()
		nameTok := p.expect(tokens.TokenIdentifier, "expected class name")
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
				if p.curr.Type == tokens.TokenFunc || p.curr.Type == tokens.TokenBuilder {
					// method or builder
					isBuilder := p.curr.Type == tokens.TokenBuilder
					p.advance()
					name := "builder"
					if !isBuilder {
						nameTok2 := p.expect(tokens.TokenIdentifier, "expected method name")
						name = nameTok2.Text
					}
					p.expect(tokens.TokenLParen, "expected '('")
					var params []ast.Parameter
					if p.curr.Type != tokens.TokenRParen {
						for {
							paramName := p.expect(tokens.TokenIdentifier, "expected parameter name")
							p.expect(tokens.TokenColon, "expected ':'")
							typStr := p.parseTypeName()
							params = append(params, ast.Parameter{Name: paramName.Text, Type: typStr, Tok: paramName})
							if p.curr.Type == tokens.TokenComma {
								p.advance()
								continue
							}
							break
						}
					}
					p.expect(tokens.TokenRParen, "expected ')'")
					retType := "void"
					if !isBuilder && p.curr.Type == tokens.TokenColon {
						p.advance()
						retType = p.parseTypeName()
					}
					var body []ast.Stmt
					if p.curr.Type == tokens.TokenLBrace {
						blk := p.parseBlock()
						body = blk.Stmts
					}
					md := ast.MethodDecl{Visibility: innerVis, Name: name, Params: params, ReturnType: retType, Body: body}
					if isBuilder {
						cd.Builders = append(cd.Builders, md)
					} else {
						cd.Methods = append(cd.Methods, md)
					}
					continue
				}
				// field: name : Type
				if p.curr.Type == tokens.TokenIdentifier {
					fname := p.expect(tokens.TokenIdentifier, "expected field name")
					p.expect(tokens.TokenColon, "expected ':' after field name")
					ft := p.parseTypeName()
					cd.Fields = append(cd.Fields, ast.FieldDecl{Name: fname.Text, Type: ft})
					_ = p.match(tokens.TokenSemicolon)
					continue
				}
				// if unknown, advance to avoid stall
				p.advance()
			}
			p.expect(tokens.TokenRBrace, "expected '}' to end class body")
		}
		return cd
	case tokens.TokenStruct:
		structTok := p.curr
		p.advance()
		nameTok := p.expect(tokens.TokenIdentifier, "expected struct name")
		sd := &ast.StructDecl{Visibility: vis, Name: nameTok.Text, HeaderTok: structTok}
		if p.curr.Type == tokens.TokenLBrace {
			p.advance()
			for p.curr.Type != tokens.TokenRBrace && p.curr.Type != tokens.TokenEndOfFile {
				// fields or methods similar to class
				if p.curr.Type == tokens.TokenIdentifier {
					fname := p.expect(tokens.TokenIdentifier, "expected field name")
					p.expect(tokens.TokenColon, "expected ':' after field name")
					ft := p.parseTypeName()
					sd.Fields = append(sd.Fields, ast.FieldDecl{Name: fname.Text, Type: ft})
					_ = p.match(tokens.TokenSemicolon)
					continue
				}
				// simple method header (optional)
				if p.curr.Type == tokens.TokenFunc {
					p.advance()
					mname := p.expect(tokens.TokenIdentifier, "expected method name")
					p.expect(tokens.TokenLParen, "expected '('")
					var params []ast.Parameter
					if p.curr.Type != tokens.TokenRParen {
						for {
							paramName := p.expect(tokens.TokenIdentifier, "expected parameter name")
							p.expect(tokens.TokenColon, "expected ':'")
							typStr := p.parseTypeName()
							params = append(params, ast.Parameter{Name: paramName.Text, Type: typStr, Tok: paramName})
							if p.curr.Type == tokens.TokenComma {
								p.advance()
								continue
							}
							break
						}
					}
					p.expect(tokens.TokenRParen, "expected ')'")
					retType := "void"
					if p.curr.Type == tokens.TokenColon {
						p.advance()
						retType = p.parseTypeName()
					}
					var body []ast.Stmt
					if p.curr.Type == tokens.TokenLBrace {
						blk := p.parseBlock()
						body = blk.Stmts
					}
					sd.Methods = append(sd.Methods, ast.MethodDecl{Name: mname.Text, Params: params, ReturnType: retType, Body: body})
					continue
				}
				p.advance()
			}
			p.expect(tokens.TokenRBrace, "expected '}' to end struct body")
		}
		return sd
	case tokens.TokenEnum:
		enumTok := p.curr
		p.advance()
		nameTok := p.expect(tokens.TokenIdentifier, "expected enum name")
		ed := &ast.EnumDecl{Visibility: vis, Name: nameTok.Text, HeaderTok: enumTok}
		if p.curr.Type == tokens.TokenLBrace {
			p.advance()
			for p.curr.Type != tokens.TokenRBrace && p.curr.Type != tokens.TokenEndOfFile {
				if p.curr.Type == tokens.TokenIdentifier {
					cname := p.expect(tokens.TokenIdentifier, "expected case name")
					// optional payload in parens
					var args []ast.Expr
					if p.curr.Type == tokens.TokenLParen {
						p.advance()
						if p.curr.Type != tokens.TokenRParen {
							for {
								args = append(args, p.parseExpression(precLowest))
								if p.curr.Type == tokens.TokenComma {
									p.advance()
									continue
								}
								break
							}
						}
						p.expect(tokens.TokenRParen, "expected ')' after enum payload")
					}
					ed.Cases = append(ed.Cases, ast.EnumCase{Name: cname.Text, Params: args})
					// optional comma or newline-semicolon
					_ = p.match(tokens.TokenComma)
					continue
				}
				p.advance()
			}
			p.expect(tokens.TokenRBrace, "expected '}' to end enum body")
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
	if p.curr.Type != tokens.TokenLet && p.curr.Type != tokens.TokenVar {
		return nil
	}
	isLet := p.curr.Type == tokens.TokenLet
	startTok := p.curr
	p.advance()
	name := p.expect(tokens.TokenIdentifier, "expected identifier")
	var typ string
	if p.curr.Type == tokens.TokenColon {
		p.advance()
		typ = p.parseTypeName()
	}
	var init ast.Expr
	if p.curr.Type == tokens.TokenEqual {
		p.advance()
		init = p.parseExpression(precLowest)
	}
	_ = p.match(tokens.TokenSemicolon)
	return &ast.GlobalVarDecl{Visibility: vis, IsLet: isLet, Name: name.Text, Type: typ, Value: init, Tok: startTok}
}

// Pratt parser precedence levels
const (
	precLowest   = 1
	precAssign   = 2  // =, +=, -=, *=, /=, %=
	precOr       = 3  // ||
	precAnd      = 4  // &&
	precBitOr    = 5  // |
	precBitXor   = 6  // ^
	precBitAnd   = 7  // &
	precEquality = 8  // ==, !=
	precCompare  = 9  // <, <=, >, >=
	precRange    = 10 // .., ..=
	precShift    = 11 // <<, >>
	precAdd      = 12 // +, -
	precMul      = 13 // *, /, %
	precPrefix   = 14
	precPostfix  = 15 // ++, --
)

func precedenceFor(tt tokens.TokenType) int {
	switch tt {
	case tokens.TokenEqual, tokens.TokenPlusEqual, tokens.TokenMinusEqual, tokens.TokenStarEqual, tokens.TokenSlashEqual, tokens.TokenPercentEqual:
		return precAssign
	case tokens.TokenOr:
		return precOr
	case tokens.TokenAnd:
		return precAnd
	case tokens.TokenBitOr:
		return precBitOr
	case tokens.TokenBitXor:
		return precBitXor
	case tokens.TokenBitAnd:
		return precBitAnd
	case tokens.TokenDoubleEqual, tokens.TokenNotEqual:
		return precEquality
	case tokens.TokenLess, tokens.TokenLessEqual, tokens.TokenGreater, tokens.TokenGreaterEqual:
		return precCompare
	case tokens.TokenRange, tokens.TokenRangeInclusive:
		return precRange
	case tokens.TokenShiftLeft, tokens.TokenShiftRight:
		return precShift
	case tokens.TokenPlus, tokens.TokenMinus:
		return precAdd
	case tokens.TokenStar, tokens.TokenSlash, tokens.TokenPercent:
		return precMul
	case tokens.TokenPlusPlus, tokens.TokenMinusMinus:
		return precPostfix
	}
	return 0
}

// ---------------- Statements / Blocks ----------------

func (p *Parser) parseBlock() *ast.BlockStmt {
	lbrace := p.expect(tokens.TokenLBrace, "expected '{' to start block")
	var stmts []ast.Stmt
	for p.curr.Type != tokens.TokenRBrace && p.curr.Type != tokens.TokenEndOfFile {
		prevLine, prevCol := p.curr.Span.StartLine, p.curr.Span.StartColumn
		stmt := p.parseStatement()
		if stmt != nil {
			stmts = append(stmts, stmt)
		}
		// consume optional semicolon to prevent stalling
		if p.curr.Type == tokens.TokenSemicolon {
			p.advance()
		}
		// Progress guard inside blocks
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
		// Expression statement
		tok := p.curr
		ex := p.parseExpression(precLowest)
		return &ast.ExpressionStmt{E: ex, Tok: tok}
	}
}

func (p *Parser) parseLet() ast.Stmt {
	tok := p.expect(tokens.TokenLet, "expected 'let'")
	name := p.expect(tokens.TokenIdentifier, "expected identifier after let")
	var typ string
	if p.curr.Type == tokens.TokenColon {
		p.advance()
		typ = p.parseTypeName()
	}
	var init ast.Expr
	if p.curr.Type == tokens.TokenEqual {
		p.advance()
		init = p.parseExpression(precLowest)
	}
	return &ast.LetStmt{Name: name.Text, Type: typ, Value: init, NameTok: tok}
}

func (p *Parser) parseVar() ast.Stmt {
	tok := p.expect(tokens.TokenVar, "expected 'var'")
	name := p.expect(tokens.TokenIdentifier, "expected identifier after var")
	var typ string
	if p.curr.Type == tokens.TokenColon {
		p.advance()
		typ = p.parseTypeName()
	}
	var init ast.Expr
	if p.curr.Type == tokens.TokenEqual {
		p.advance()
		init = p.parseExpression(precLowest)
	}
	return &ast.VarStmt{Name: name.Text, Type: typ, Value: init, NameTok: tok}
}

func (p *Parser) parseIf() ast.Stmt {
	t := p.expect(tokens.TokenIf, "expected 'if'")
	p.expect(tokens.TokenLParen, "expected '('")
	cond := p.parseExpression(precLowest)
	p.expect(tokens.TokenRParen, "expected ')'")
	thenBlk := p.parseBlock()
	var elifs []ast.ElseIf
	for p.curr.Type == tokens.TokenElif {
		p.advance()
		p.expect(tokens.TokenLParen, "expected '('")
		c := p.parseExpression(precLowest)
		p.expect(tokens.TokenRParen, "expected ')'")
		b := p.parseBlock()
		elifs = append(elifs, ast.ElseIf{Cond: c, Then: b})
	}
	var elseBlk *ast.BlockStmt
	if p.curr.Type == tokens.TokenElse {
		p.advance()
		elseBlk = p.parseBlock()
	}
	return &ast.IfStmt{Cond: cond, Then: thenBlk, Elifs: elifs, Else: elseBlk, Tok: t}
}

func (p *Parser) parseWhile() ast.Stmt {
	t := p.expect(tokens.TokenWhile, "expected 'while'")
	p.expect(tokens.TokenLParen, "expected '('")
	cond := p.parseExpression(precLowest)
	p.expect(tokens.TokenRParen, "expected ')'")
	body := p.parseBlock()
	return &ast.WhileStmt{Cond: cond, Body: body, Tok: t}
}

func (p *Parser) parseDoWhile() ast.Stmt {
	t := p.expect(tokens.TokenDo, "expected 'do'")
	body := p.parseBlock()
	p.expect(tokens.TokenWhile, "expected 'while' after do-block")
	p.expect(tokens.TokenLParen, "expected '('")
	cond := p.parseExpression(precLowest)
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
	p.expect(tokens.TokenLParen, "expected '('")
	name := p.expect(tokens.TokenIdentifier, "expected loop variable")
	p.expect(tokens.TokenColon, "expected ':' after loop variable")
	// Range: a..b or a..=b with optional 'step N'
	from := p.parseExpression(precLowest)
	// expect range operator
	if p.curr.Type != tokens.TokenRange && p.curr.Type != tokens.TokenRangeInclusive {
		p.errs = append(p.errs, fmt.Errorf("expected range operator '..' or '..='"))
	}
	inclusive := p.curr.Type == tokens.TokenRangeInclusive
	p.advance()
	to := p.parseExpression(precLowest)
	var step ast.Expr
	if p.curr.Type == tokens.TokenStep {
		p.advance()
		step = p.parseExpression(precLowest)
	}
	p.expect(tokens.TokenRParen, "expected ')'")
	body := p.parseBlock()
	return &ast.LoopStmt{Reverse: reverse, VarName: name.Text, From: from, To: to, Inclusive: inclusive, Step: step, Body: body, Tok: name}
}

func (p *Parser) parseReturn() ast.Stmt {
	t := p.expect(tokens.TokenRet, "expected 'ret'")
	var v ast.Expr
	if p.curr.Type != tokens.TokenSemicolon && p.curr.Type != tokens.TokenRBrace {
		v = p.parseExpression(precLowest)
	}
	return &ast.ReturnStmt{Value: v, Tok: t}
}

// ---------------- Expressions ----------------

func (p *Parser) parseExpression(rbp int) ast.Expr {
	left := p.parsePrefix()
	for {
		op := p.curr.Type
		// Ternary
		if rbp <= precOr && op == tokens.TokenQuestion {
			q := p.curr
			p.advance()
			thenExpr := p.parseExpression(precLowest)
			p.expect(tokens.TokenColon, "expected ':' in ternary")
			elseExpr := p.parseExpression(precLowest)
			left = &ast.TernaryExpr{Cond: left, ThenExpr: thenExpr, ElseExpr: elseExpr, QTok: q, CTok: p.curr}
			continue
		}
		lbp := precedenceFor(op)
		if lbp == 0 || lbp < rbp {
			// Postfix: call, member, index, cast
			if p.curr.Type == tokens.TokenLParen {
				lpar := p.curr
				p.advance()
				var args []ast.Expr
				if p.curr.Type != tokens.TokenRParen {
					for {
						arg := p.parseExpression(precLowest)
						args = append(args, arg)
						if p.curr.Type == tokens.TokenComma {
							p.advance()
							continue
						}
						break
					}
				}
				rpar := p.expect(tokens.TokenRParen, "expected ')' after arguments")
				left = &ast.CallExpr{Callee: left, Args: args, LParen: lpar, RParen: rpar}
				continue
			}
			if p.curr.Type == tokens.TokenDot {
				dot := p.curr
				p.advance()
				name := p.expect(tokens.TokenIdentifier, "expected member name after '.'")
				left = &ast.MemberAccessExpr{Object: left, Member: name.Text, DotTok: dot, MemberTok: name}
				continue
			}
			if p.curr.Type == tokens.TokenLBracket {
				lbr := p.curr
				p.advance()
				idx := p.parseExpression(precLowest)
				rbr := p.expect(tokens.TokenRBracket, "expected ']' after index")
				left = &ast.IndexExpr{Base: left, Index: idx, LBrack: lbr, RBrack: rbr}
				continue
			}
			if p.curr.Type == tokens.TokenAs {
				asTok := p.curr
				p.advance()
				typ := p.parseTypeName()
				left = &ast.CastExpr{Expr: left, TargetType: typ, AsTok: asTok}
				continue
			}
			break
		}
		// Postfix ++/--
		if op == tokens.TokenPlusPlus || op == tokens.TokenMinusMinus {
			tok := p.curr
			p.advance()
			left = &ast.UnaryExpr{Operator: op, Operand: left, IsPostfix: true, OpTok: tok}
			continue
		}
		// Assignment
		if op == tokens.TokenEqual || op == tokens.TokenPlusEqual || op == tokens.TokenMinusEqual || op == tokens.TokenStarEqual || op == tokens.TokenSlashEqual || op == tokens.TokenPercentEqual {
			opTok := p.curr
			p.advance()
			right := p.parseExpression(lbp)
			left = &ast.AssignExpr{Target: left, Operator: op, Value: right, OpTok: opTok}
			continue
		}
		// Infix
		opTok := p.curr
		p.advance()
		right := p.parseExpression(lbp + 1)
		left = &ast.BinaryExpr{Left: left, Operator: op, Right: right, OpTok: opTok}
	}
	return left
}

func (p *Parser) parsePrefix() ast.Expr {
	switch p.curr.Type {
	case tokens.TokenPlusPlus, tokens.TokenMinusMinus, tokens.TokenNot, tokens.TokenMinus, tokens.TokenBitNot:
		opTok := p.curr
		op := p.curr.Type
		p.advance()
		rhs := p.parseExpression(precPrefix)
		return &ast.UnaryExpr{Operator: op, Operand: rhs, IsPostfix: false, OpTok: opTok}
	case tokens.TokenLParen:
		p.advance()
		e := p.parseExpression(precLowest)
		p.expect(tokens.TokenRParen, "expected ')'")
		return e
	case tokens.TokenLBracket:
		lbr := p.curr
		p.advance()
		var elems []ast.Expr
		if p.curr.Type != tokens.TokenRBracket {
			for {
				elems = append(elems, p.parseExpression(precLowest))
				if p.curr.Type == tokens.TokenComma {
					p.advance()
					continue
				}
				break
			}
		}
		rbr := p.expect(tokens.TokenRBracket, "expected ']' in array literal")
		return &ast.ArrayLiteralExpr{Elements: elems, LBrack: lbr, RBrack: rbr}
	case tokens.TokenIdentifier:
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
	// Fallback
	tok := p.curr
	p.advance()
	return &ast.IdentifierExpr{Name: tok.Text, Tok: tok}
}

// ---------- Types ----------

func (p *Parser) isBuiltinTypeToken(tt tokens.TokenType) bool {
	switch tt {
	case tokens.TokenByte, tokens.TokenF16, tokens.TokenF32, tokens.TokenF64,
		tokens.TokenI8, tokens.TokenI16, tokens.TokenI32, tokens.TokenI64,
		tokens.TokenU8, tokens.TokenU16, tokens.TokenU32, tokens.TokenU64,
		tokens.TokenBool, tokens.TokenBit:
		return true
	}
	return false
}

func (p *Parser) parseTypeName() string {
	prefix := ""
	for p.curr.Type == tokens.TokenLBracket && p.peek.Type == tokens.TokenRBracket {
		p.advance() // [
		_ = p.expect(tokens.TokenRBracket, "expected ']' in array type")
		prefix += "[]"
	}
	// base name
	if p.curr.Type == tokens.TokenIdentifier || p.isBuiltinTypeToken(p.curr.Type) {
		name := p.curr.Text
		p.advance()
		// nullable suffix
		if p.curr.Type == tokens.TokenQuestion {
			p.advance()
			name += "?"
		}
		return prefix + name
	}
	p.errs = append(p.errs, fmt.Errorf("expected type name"))
	return prefix + "<error>"
}
