// Copyright (c) 2026. The Cloth contributors.
//
// ExpressionParser.cs is part of the Cloth Frontend.
//
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

using FrontEnd.Error.Parser;
using FrontEnd.Parser.AST.Expressions;
using FrontEnd.Parser.AST.Type;

namespace FrontEnd.Parser;

using Token;

internal sealed class ExpressionParser(Parser parser) {

	internal Expression ParseExpression() => ParseExprPrec(0);

	private Expression ParseExprPrec(int minBp) {
		var left = ParsePrefix();

		while (true) {
			// Assignment operators (right-associative, bp 1)
			if (minBp <= 1) {
				var assignOp = PeekAssignOp();
				if (assignOp.HasValue) {
					parser.Advance();
					var right = ParseExprPrec(1);
					left = new Expression.Assign(left, assignOp.Value, right, TokenSpan.Merge(left.Span, right.Span));
					continue;
				}
			}

			// Ternary (right-associative, bp 3)
			if (minBp <= 3 && parser.CheckOperator(Operator.Question)) {
				parser.Advance();
				var thenE = ParseExprPrec(0);
				parser.ExpectOperator(Operator.Colon);
				var elseE = ParseExprPrec(3);
				left = new Expression.Ternary(left, thenE, elseE, TokenSpan.Merge(left.Span, elseE.Span));
				continue;
			}

			var bp = PeekInfixBp();
			if (bp == null || bp.Value.Left < minBp) break;
			left = ParseInfix(left, bp.Value.Right);
		}

		return left;
	}

	private Expression ParsePrefix() {
		var tok = parser.Current;
		var start = tok.Span;

		if (tok.Type == TokenType.Literal) {
			parser.Advance();
			return new Expression.Literal(ParseLiteral(tok), start);
		}

		if (tok.Type == TokenType.Keyword) {
			switch (tok.Keyword) {
				case Keyword.True:
					parser.Advance();
					return new Expression.Literal(new Literal.Bool(true), start);
				case Keyword.False:
					parser.Advance();
					return new Expression.Literal(new Literal.Bool(false), start);
				case Keyword.Null:
					parser.Advance();
					return new Expression.Literal(new Literal.Null(), start);
				case Keyword.NaN:
					parser.Advance();
					return new Expression.Literal(new Literal.Nan(), start);
				case Keyword.This:
					parser.Advance();
					return new Expression.This(start);
				case Keyword.Super:
					parser.Advance();
					return new Expression.Super(start);
				case Keyword.New:
					return ParseNewExpr();
				case Keyword.Await: {
					parser.Advance();
					var inner = ParseExprPrec(24);
					return new Expression.Unary(UnOp.Await, inner, TokenSpan.Merge(start, inner.Span));
				}
				default:
					if (IsTypeKeyword(tok)) {
						var name = tok.Lexeme;
						parser.Advance();
						return new Expression.Identifier(name, start);
					}
					break;
			}
		}

		if (tok.Type == TokenType.Operator) {
			switch (tok.Operator) {
				case Operator.Minus: {
					parser.Advance();
					var inner = ParseExprPrec(24);
					return new Expression.Unary(UnOp.Neg, inner, TokenSpan.Merge(start, inner.Span));
				}
				case Operator.Not: {
					parser.Advance();
					var inner = ParseExprPrec(24);
					return new Expression.Unary(UnOp.Not, inner, TokenSpan.Merge(start, inner.Span));
				}
				case Operator.Tilde: {
					parser.Advance();
					var inner = ParseExprPrec(24);
					return new Expression.Unary(UnOp.BitNot, inner, TokenSpan.Merge(start, inner.Span));
				}
				case Operator.PlusPlus: {
					parser.Advance();
					var inner = ParseExprPrec(24);
					return new Expression.Unary(UnOp.PreInc, inner, TokenSpan.Merge(start, inner.Span));
				}
				case Operator.MinusMinus: {
					parser.Advance();
					var inner = ParseExprPrec(24);
					return new Expression.Unary(UnOp.PreDec, inner, TokenSpan.Merge(start, inner.Span));
				}
				case Operator.LParen:
					return ParseParenOrLambda();
			}
		}

		if (tok.Type == TokenType.Identifier) {
			parser.Advance();
			return new Expression.Identifier(tok.Lexeme, start);
		}

		throw ParserError.ExpectedIdentifier
			.WithMessage($"unexpected token '{tok.Lexeme}' in expression")
			.WithSpan(start)
			.Render();
	}

	private Expression ParseInfix(Expression left, int rbp) {
		var tok = parser.Current;
		var leftSpan = left.Span;

		if (tok.Type == TokenType.Operator) {
			switch (tok.Operator) {
				case Operator.Plus: {
					parser.Advance();
					var r = ParseExprPrec(rbp);
					return new Expression.Binary(left, BinOp.Add, r, TokenSpan.Merge(leftSpan, r.Span));
				}
				case Operator.Minus: {
					parser.Advance();
					var r = ParseExprPrec(rbp);
					return new Expression.Binary(left, BinOp.Sub, r, TokenSpan.Merge(leftSpan, r.Span));
				}
				case Operator.Star: {
					parser.Advance();
					var r = ParseExprPrec(rbp);
					return new Expression.Binary(left, BinOp.Mul, r, TokenSpan.Merge(leftSpan, r.Span));
				}
				case Operator.Slash: {
					parser.Advance();
					var r = ParseExprPrec(rbp);
					return new Expression.Binary(left, BinOp.Div, r, TokenSpan.Merge(leftSpan, r.Span));
				}
				case Operator.Percent: {
					parser.Advance();
					var r = ParseExprPrec(rbp);
					return new Expression.Binary(left, BinOp.Rem, r, TokenSpan.Merge(leftSpan, r.Span));
				}
				case Operator.And: {
					parser.Advance();
					var r = ParseExprPrec(rbp);
					return new Expression.Binary(left, BinOp.BitAnd, r, TokenSpan.Merge(leftSpan, r.Span));
				}
				case Operator.Or: {
					parser.Advance();
					var r = ParseExprPrec(rbp);
					return new Expression.Binary(left, BinOp.BitOr, r, TokenSpan.Merge(leftSpan, r.Span));
				}
				case Operator.Caret: {
					parser.Advance();
					var r = ParseExprPrec(rbp);
					return new Expression.Binary(left, BinOp.BitXor, r, TokenSpan.Merge(leftSpan, r.Span));
				}
				case Operator.Less: {
					// < or << (shift left — two consecutive Less tokens)
					if (parser.PeekAt(1).Operator == Operator.Less) {
						parser.Advance(); parser.Advance();
						var r = ParseExprPrec(rbp);
						return new Expression.Binary(left, BinOp.Shl, r, TokenSpan.Merge(leftSpan, r.Span));
					} else {
						parser.Advance();
						var r = ParseExprPrec(rbp);
						return new Expression.Binary(left, BinOp.Lt, r, TokenSpan.Merge(leftSpan, r.Span));
					}
				}
				case Operator.LessEqual: {
					parser.Advance();
					var r = ParseExprPrec(rbp);
					return new Expression.Binary(left, BinOp.LtEq, r, TokenSpan.Merge(leftSpan, r.Span));
				}
				case Operator.Greater: {
					// > or >> (shift right — two consecutive Greater tokens)
					if (parser.PeekAt(1).Operator == Operator.Greater) {
						parser.Advance(); parser.Advance();
						var r = ParseExprPrec(rbp);
						return new Expression.Binary(left, BinOp.Shr, r, TokenSpan.Merge(leftSpan, r.Span));
					} else {
						parser.Advance();
						var r = ParseExprPrec(rbp);
						return new Expression.Binary(left, BinOp.Gt, r, TokenSpan.Merge(leftSpan, r.Span));
					}
				}
				case Operator.GreaterEqual: {
					parser.Advance();
					var r = ParseExprPrec(rbp);
					return new Expression.Binary(left, BinOp.GtEq, r, TokenSpan.Merge(leftSpan, r.Span));
				}
				case Operator.EqualEqual: {
					parser.Advance();
					var r = ParseExprPrec(rbp);
					return new Expression.Binary(left, BinOp.Eq, r, TokenSpan.Merge(leftSpan, r.Span));
				}
				case Operator.NotEqual: {
					parser.Advance();
					var r = ParseExprPrec(rbp);
					return new Expression.Binary(left, BinOp.NotEq, r, TokenSpan.Merge(leftSpan, r.Span));
				}
				case Operator.Fallback: {
					parser.Advance();
					var r = ParseExprPrec(rbp);
					return new Expression.NullCoalesce(left, r, TokenSpan.Merge(leftSpan, r.Span));
				}
				case Operator.DotDot: {
					parser.Advance();
					var r = ParseExprPrec(rbp);
					return new Expression.Range(left, r, TokenSpan.Merge(leftSpan, r.Span));
				}
				case Operator.Dot: {
					parser.Advance();
					var (member, memberSpan) = ExpectMemberName();
					if (parser.CheckOperator(Operator.LParen)) {
						parser.Advance();
						var args = ParseCallArgs();
						parser.ExpectOperator(Operator.RParen);
						var merged = TokenSpan.Merge(leftSpan, parser.Previous().Span);
						return new Expression.Call(new Expression.MemberAccess(left, member, memberSpan), args, merged);
					}
					return new Expression.MemberAccess(left, member, TokenSpan.Merge(leftSpan, memberSpan));
				}
				case Operator.ColonColon: {
					parser.Advance();
					if (parser.Current.Type != TokenType.Meta)
						throw ParserError.ExpectedKeyword
							.WithMessage($"expected meta keyword (e.g. SIZE, TYPE), got '{parser.Current.Lexeme}'")
							.WithSpan(parser.Current.Span)
							.Render();
					var metaName = parser.Current.Lexeme;
					var metaSpan = parser.Current.Span;
					parser.Advance();
					return new Expression.MetaAccess(left, metaName, TokenSpan.Merge(leftSpan, metaSpan));
				}
				case Operator.LParen: {
					parser.Advance();
					var args = ParseCallArgs();
					parser.ExpectOperator(Operator.RParen);
					return new Expression.Call(left, args, TokenSpan.Merge(leftSpan, parser.Previous().Span));
				}
				case Operator.LBracket: {
					parser.Advance();
					var idx = ParseExpression();
					parser.ExpectOperator(Operator.RBracket);
					return new Expression.Index(left, idx, TokenSpan.Merge(leftSpan, parser.Previous().Span));
				}
				case Operator.PlusPlus: {
					var end = parser.Current.Span;
					parser.Advance();
					return new Expression.Postfix(left, PostOp.Inc, TokenSpan.Merge(leftSpan, end));
				}
				case Operator.MinusMinus: {
					var end = parser.Current.Span;
					parser.Advance();
					return new Expression.Postfix(left, PostOp.Dec, TokenSpan.Merge(leftSpan, end));
				}
			}
		}

		if (tok.Type == TokenType.Keyword) {
			switch (tok.Keyword) {
				case Keyword.And: {
					parser.Advance();
					var r = ParseExprPrec(rbp);
					return new Expression.Binary(left, BinOp.And, r, TokenSpan.Merge(leftSpan, r.Span));
				}
				case Keyword.Or: {
					parser.Advance();
					var r = ParseExprPrec(rbp);
					return new Expression.Binary(left, BinOp.Or, r, TokenSpan.Merge(leftSpan, r.Span));
				}
				case Keyword.As: {
					parser.Advance();
					var safe = parser.ConsumeOp(Operator.Question);
					var ty = parser.ParseTypeExpression();
					return new Expression.Cast(left, ty, safe, TokenSpan.Merge(leftSpan, ty.Span));
				}
				case Keyword.Is: {
					parser.Advance();
					var ty = parser.ParseTypeExpression();
					return new Expression.TypeCheck(left, ty, TokenSpan.Merge(leftSpan, ty.Span));
				}
				case Keyword.In: {
					parser.Advance();
					var r = ParseExprPrec(rbp);
					return new Expression.MembershipCheck(left, r, TokenSpan.Merge(leftSpan, r.Span));
				}
			}
		}

		return left;
	}

	private AssignOp? PeekAssignOp() => parser.Current.Operator switch {
		Operator.Equal        => AssignOp.Assign,
		Operator.PlusEqual    => AssignOp.AddAssign,
		Operator.MinusEqual   => AssignOp.SubAssign,
		Operator.StarEqual    => AssignOp.MulAssign,
		Operator.SlashEqual   => AssignOp.DivAssign,
		Operator.PercentEqual => AssignOp.RemAssign,
		Operator.AndEqual     => AssignOp.AndAssign,
		Operator.OrEqual      => AssignOp.OrAssign,
		Operator.CaretEqual   => AssignOp.XorAssign,
		_ => null
	};

	private (int Left, int Right)? PeekInfixBp() => (parser.Current.Type, parser.Current.Operator, parser.Current.Keyword) switch {
		(TokenType.Operator, Operator.Dot,          _)           => (28, 29),
		(TokenType.Operator, Operator.ColonColon,   _)           => (28, 29),
		(TokenType.Operator, Operator.LParen,       _)           => (28, 29),
		(TokenType.Operator, Operator.LBracket,     _)           => (28, 29),
		(TokenType.Operator, Operator.PlusPlus,     _)           => (28, 0),
		(TokenType.Operator, Operator.MinusMinus,   _)           => (28, 0),
		(TokenType.Keyword,  _,                     Keyword.As)  => (26, 0),
		(TokenType.Keyword,  _,                     Keyword.Is)  => (12, 0),
		(TokenType.Keyword,  _,                     Keyword.In)  => (12, 13),
		(TokenType.Operator, Operator.Star,         _)           => (22, 23),
		(TokenType.Operator, Operator.Slash,        _)           => (22, 23),
		(TokenType.Operator, Operator.Percent,      _)           => (22, 23),
		(TokenType.Operator, Operator.Plus,         _)           => (20, 21),
		(TokenType.Operator, Operator.Minus,        _)           => (20, 21),
		(TokenType.Operator, Operator.Less,         _)           => (20, 21),
		(TokenType.Operator, Operator.Greater,      _)           => (20, 21),
		(TokenType.Operator, Operator.And,          _)           => (18, 19),
		(TokenType.Operator, Operator.Caret,        _)           => (16, 17),
		(TokenType.Operator, Operator.Or,           _)           => (14, 15),
		(TokenType.Operator, Operator.LessEqual,    _)           => (12, 13),
		(TokenType.Operator, Operator.GreaterEqual, _)           => (12, 13),
		(TokenType.Operator, Operator.EqualEqual,   _)           => (10, 11),
		(TokenType.Operator, Operator.NotEqual,     _)           => (10, 11),
		(TokenType.Keyword,  _,                     Keyword.And) => (8, 9),
		(TokenType.Keyword,  _,                     Keyword.Or)  => (6, 7),
		(TokenType.Operator, Operator.Fallback,     _)           => (4, 5),
		(TokenType.Operator, Operator.DotDot,       _)           => (2, 3),
		_ => null
	};

	internal List<Expression> ParseCallArgs() {
		var args = new List<Expression>();
		if (!parser.CheckOperator(Operator.RParen)) {
			args.Add(ParseExpression());
			while (parser.ConsumeOp(Operator.Comma))
				args.Add(ParseExpression());
		}
		return args;
	}

	private Expression ParseNewExpr() {
		var start = parser.Current.Span;
		parser.ExpectKeyword(Keyword.New);
		var ty = parser.ParseTypeExpression();
		parser.ExpectOperator(Operator.LParen);
		var args = ParseCallArgs();
		parser.ExpectOperator(Operator.RParen);
		return new Expression.New(ty, args, TokenSpan.Merge(start, parser.Previous().Span));
	}

	private Expression ParseParenOrLambda() {
		var start = parser.Current.Span;
		var saved = parser.SaveCursor();

		if (LookaheadIsLambda()) {
			var lambda = TryParseLambda(start);
			if (lambda != null) return lambda;
			parser.RestoreTo(saved);
		}

		parser.ExpectOperator(Operator.LParen);
		var first = ParseExpression();
		if (parser.CheckOperator(Operator.RParen)) {
			parser.Advance();
			return first;
		}
		if (parser.CheckOperator(Operator.Comma)) {
			var items = new List<Expression> { first };
			while (parser.ConsumeOp(Operator.Comma))
				items.Add(ParseExpression());
			parser.ExpectOperator(Operator.RParen);
			return new Expression.Tuple(items, TokenSpan.Merge(start, parser.Previous().Span));
		}
		throw ParserError.ExpectedOperator
			.WithMessage($"expected ')' or ','")
			.WithSpan(parser.Current.Span)
			.Render();
	}

	private Expression? TryParseLambda(TokenSpan start) {
		if (!parser.CheckOperator(Operator.LParen)) return null;
		parser.Advance(); // consume (

		var parms = new List<Parameter>();
		if (!parser.CheckOperator(Operator.RParen)) {
			do {
				parms.Add(parser.ParseParameter());
			} while (parser.ConsumeOp(Operator.Comma));
		}
		parser.ExpectOperator(Operator.RParen);
		parser.ExpectOperator(Operator.Arrow);
		var body = ParseExprPrec(0);
		return new Expression.Lambda(parms, new LambdaBody.ExpressionBody(body), TokenSpan.Merge(start, body.Span));
	}

	// Non-consuming lookahead: does `(` begin a lambda? Pattern: `( [T name [, T name]*] ) ->`
	private bool LookaheadIsLambda() {
		int i = 1; // skip opening (

		// Empty params: () ->
		if (parser.PeekAt(i).Operator == Operator.RParen)
			return parser.PeekAt(i + 1).Operator == Operator.Arrow;

		while (true) {
			var typeTok = parser.PeekAt(i);
			if (typeTok.Type != TokenType.Identifier && !IsTypeKeyword(typeTok)) return false;
			i++;

			// Optional array suffix []
			if (parser.PeekAt(i).Operator == Operator.LBracket && parser.PeekAt(i + 1).Operator == Operator.RBracket)
				i += 2;

			// Optional type qualifiers ? ! &
			while (parser.PeekAt(i).Operator is Operator.Question or Operator.Not or Operator.And)
				i++;

			// Param name
			if (parser.PeekAt(i).Type != TokenType.Identifier) return false;
			i++;

			if (parser.PeekAt(i).Operator == Operator.Comma) { i++; continue; }
			if (parser.PeekAt(i).Operator == Operator.RParen) return parser.PeekAt(i + 1).Operator == Operator.Arrow;
			return false;
		}
	}

	private static bool IsTypeKeyword(Token tok) => tok.Type == TokenType.Keyword && tok.Keyword is
		Keyword.Bool    or Keyword.Char     or Keyword.Byte    or
		Keyword.I8      or Keyword.I16      or Keyword.I32     or Keyword.I64     or
		Keyword.U8      or Keyword.U16      or Keyword.U32     or Keyword.U64     or
		Keyword.F32     or Keyword.F64      or Keyword.Float   or Keyword.Double  or Keyword.Real or
		Keyword.Long    or Keyword.Short    or Keyword.Int     or Keyword.Uint    or Keyword.Unsigned or
		Keyword.String  or Keyword.Bit      or Keyword.Any;

	private static Literal ParseLiteral(Token tok) {
		var lexeme = tok.Lexeme;
		if (lexeme.StartsWith('"'))
			return new Literal.Str(lexeme[1..^1]);
		if (lexeme.StartsWith('\'')) {
			var content = lexeme[1..^1];
			char ch = content.Length == 1 ? content[0] : content[1] switch {
				'n'  => '\n', 't' => '\t', 'r' => '\r',
				'\\' => '\\', '\'' => '\'', '0' => '\0',
				_    => content[1]
			};
			return new Literal.Char(ch);
		}
		if (lexeme is "0t" or "1t") return new Literal.Bit(lexeme[0] == '0' ? (byte)0 : (byte)1);
		if (lexeme.Contains('.') || lexeme.Contains('e') || lexeme.Contains('E'))
			return new Literal.Float(lexeme);
		return new Literal.Int(lexeme);
	}

	private (string Name, TokenSpan Span) ExpectMemberName() {
		var span = parser.Current.Span;
		if (parser.Current.Type == TokenType.Identifier || parser.Current.Type == TokenType.Keyword) {
			var name = parser.Current.Lexeme;
			parser.Advance();
			return (name, span);
		}
		throw ParserError.ExpectedIdentifier
			.WithMessage($"expected member name, got '{parser.Current.Lexeme}'")
			.WithSpan(span)
			.Render();
	}
}
