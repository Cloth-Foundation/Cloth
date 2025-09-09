package parser

import (
	"compiler/src/tokens"
)

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
		if p.fatal {
			return prefix + "<error>"
		}
		prefix += "[]"
	}
	// base name
	if p.curr.Type == tokens.TokenIdentifier || p.isBuiltinTypeToken(p.curr.Type) || p.curr.Type == tokens.TokenSelf {
		name := p.curr.Text
		p.advance()
		// nullable suffix
		if p.curr.Type == tokens.TokenQuestion {
			p.advance()
			name += "?"
		}
		return prefix + name
	}
	p.report(p.curr, "expected type name")
	return prefix + "<error>"
}
