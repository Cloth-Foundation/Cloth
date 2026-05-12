// Copyright (c) 2026.The Cloth contributors.
// 
// ExpressionParser.cs is part of the Cloth Frontend.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

using FrontEnd.Error.Parser;
using FrontEnd.Parser.AST.Expressions;

namespace FrontEnd.Parser;

using Token;

/// <summary>
/// A class responsible for parsing expressions within the source code.
/// Provides methods to parse different types of expressions based on precedence
/// and context while interacting with the token stream. This uses the Pratt parsing
/// algorithm to parse expressions in a top-down fashion.
/// </summary>
internal sealed class ExpressionParser(Parser parser) {
	internal Expression ParseExpression() => ParseExprPrecedence(0);

	/// <summary>
	/// Parses an expression with a specified minimum binding power, enabling the interpretation of
	/// infix, assignment, and ternary operators within the expression hierarchy.
	/// The method processes primary expressions (prefixes) and progressively builds more complex
	/// expressions by checking operator precedence and associativity.
	/// </summary>
	/// <param name="minBp">
	/// The minimum binding power of operators to consider during parsing, which determines the precedence
	/// level at which the expression parsing begins.
	/// </param>
	/// <returns>
	/// An <see cref="Expression"/> representing the fully parsed expression, constructed by combining prefix,
	/// infix, and other operators based on precedence and associativity.
	/// </returns>
	private Expression ParseExprPrecedence(int minBp) {
		var left = ParsePrefix();

		while (true) {
			// Assignment operators (right-associative, bp 1)
			if (minBp <= 1) {
				var assignOp = PeekAssignOp();
				if (assignOp.HasValue) {
					parser.Advance();
					var right = ParseExprPrecedence(1);
					left = new Expression.Assign(left, assignOp.Value, right, TokenSpan.Merge(left.Span, right.Span));
					continue;
				}
			}

			// Ternary (right-associative, bp 3)
			if (minBp <= 3 && parser.CheckOperator(Operator.Question)) {
				parser.Advance();
				var thenE = ParseExprPrecedence(0);
				parser.ExpectOperator(Operator.Colon);
				var elseE = ParseExprPrecedence(3);
				left = new Expression.Ternary(left, thenE, elseE, TokenSpan.Merge(left.Span, elseE.Span));
				continue;
			}

			var bp = PeekInfixBp();
			if (bp == null || bp.Value.Left < minBp) break;
			left = ParseInfix(left, bp.Value.Right);
		}

		return left;
	}

	/// <summary>
	/// Parses a prefix expression by interpreting the current token as a starting point
	/// and transitioning into the appropriate expression type.
	/// The method recognizes literals, keywords, operators, and identifiers, constructing
	/// the corresponding expressions based on the token type and other contextual clues.
	/// </summary>
	/// <returns>
	/// A new <see cref="Expression"/> representing the parsed prefix expression.
	/// </returns>
	/// <exception cref="ParserError">
	/// Thrown if the token cannot be interpreted as a valid start for an expression, or if
	/// an unexpected token type is encountered.
	/// </exception>
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
				case Keyword.Await:
				{
					parser.Advance();
					var inner = ParseExprPrecedence(24);
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
				case Operator.Minus:
				{
					parser.Advance();
					// Prefix bp 26 binds tighter than `^` (lbp 25) so `-10 ^ 2` groups as
					// `(-10) ^ 2`, matching calculator-style math conventions. The infix
					// loop continues when `lbp >= minBp`, so the operand parser must use
					// a threshold *strictly greater* than `^`'s lbp.
					var inner = ParseExprPrecedence(26);
					return new Expression.Unary(UnOp.Neg, inner, TokenSpan.Merge(start, inner.Span));
				}
				case Operator.Not:
				{
					parser.Advance();
					var inner = ParseExprPrecedence(26);
					return new Expression.Unary(UnOp.Not, inner, TokenSpan.Merge(start, inner.Span));
				}
				case Operator.Tilde:
				{
					parser.Advance();
					var inner = ParseExprPrecedence(26);
					return new Expression.Unary(UnOp.BitNot, inner, TokenSpan.Merge(start, inner.Span));
				}
				case Operator.PlusPlus:
				{
					parser.Advance();
					var inner = ParseExprPrecedence(24);
					return new Expression.Unary(UnOp.PreInc, inner, TokenSpan.Merge(start, inner.Span));
				}
				case Operator.MinusMinus:
				{
					parser.Advance();
					var inner = ParseExprPrecedence(24);
					return new Expression.Unary(UnOp.PreDec, inner, TokenSpan.Merge(start, inner.Span));
				}
				case Operator.LParen:
					return ParseParenOrLambda();
				case Operator.LBracket:
				{
					// Array literal: `[expr, expr, ...]`. Unambiguous at expression start
					// because `[` as a postfix (`arr[i]`) is only consumed by the infix loop.
					parser.Advance();
					var elements = new List<Expression>();
					if (!parser.CheckOperator(Operator.RBracket)) {
						elements.Add(ParseExpression());
						while (parser.ConsumeOp(Operator.Comma))
							elements.Add(ParseExpression());
					}
					parser.ExpectOperator(Operator.RBracket);
					return new Expression.ArrayLit(elements, TokenSpan.Merge(start, parser.Previous().Span));
				}
			}
		}

		if (tok.Type == TokenType.Identifier) {
			parser.Advance();
			return new Expression.Identifier(tok.Lexeme, start);
		}

		throw ParserError.ExpectedIdentifier.WithMessage($"unexpected token '{tok.Lexeme}' in expression").WithSpan(start).Render();
	}

	/// <summary>
	/// Parses an infix expression by combining the provided left-hand expression
	/// with an operator and a right-hand expression based on the specified precedence.
	/// The method identifies the operator and recursively parses the right-hand expression.
	/// </summary>
	/// <param name="left">The left-hand side expression of the infix operation.</param>
	/// <param name="rbp">The right-binding power (precedence) of the operator.</param>
	/// <returns>
	/// A new <see cref="Expression"/> representing the combined infix expression,
	/// or the original left-hand expression if no valid operator is found.
	/// </returns>
	/// <exception cref="ParserError">
	/// Thrown if the parsing fails due to an unexpected token or invalid syntax.
	/// </exception>
	private Expression ParseInfix(Expression left, int rbp) {
		var tok = parser.Current;
		var leftSpan = left.Span;

		if (tok.Type == TokenType.Operator) {
			switch (tok.Operator) {
				case Operator.Plus:
				{
					parser.Advance();
					var r = ParseExprPrecedence(rbp);
					return new Expression.Binary(left, BinOp.Add, r, TokenSpan.Merge(leftSpan, r.Span));
				}
				case Operator.Minus:
				{
					parser.Advance();
					var r = ParseExprPrecedence(rbp);
					return new Expression.Binary(left, BinOp.Sub, r, TokenSpan.Merge(leftSpan, r.Span));
				}
				case Operator.Star:
				{
					parser.Advance();
					var r = ParseExprPrecedence(rbp);
					return new Expression.Binary(left, BinOp.Mul, r, TokenSpan.Merge(leftSpan, r.Span));
				}
				case Operator.Slash:
				{
					parser.Advance();
					var r = ParseExprPrecedence(rbp);
					return new Expression.Binary(left, BinOp.Div, r, TokenSpan.Merge(leftSpan, r.Span));
				}
				case Operator.Percent:
				{
					parser.Advance();
					var r = ParseExprPrecedence(rbp);
					return new Expression.Binary(left, BinOp.Rem, r, TokenSpan.Merge(leftSpan, r.Span));
				}
				case Operator.And:
				{
					parser.Advance();
					var r = ParseExprPrecedence(rbp);
					return new Expression.Binary(left, BinOp.BitAnd, r, TokenSpan.Merge(leftSpan, r.Span));
				}
				case Operator.Or:
				{
					parser.Advance();
					var r = ParseExprPrecedence(rbp);
					return new Expression.Binary(left, BinOp.BitOr, r, TokenSpan.Merge(leftSpan, r.Span));
				}
				case Operator.Caret:
				{
					parser.Advance();
					var r = ParseExprPrecedence(rbp);
					return new Expression.Binary(left, BinOp.Pow, r, TokenSpan.Merge(leftSpan, r.Span));
				}
				case Operator.Less:
				{
					// < or << (shift left — two consecutive 'Less' tokens)
					if (parser.PeekAt(1).Operator == Operator.Less) {
						parser.Advance();
						parser.Advance();
						var r = ParseExprPrecedence(rbp);
						return new Expression.Binary(left, BinOp.Shl, r, TokenSpan.Merge(leftSpan, r.Span));
					}
					else {
						parser.Advance();
						var r = ParseExprPrecedence(rbp);
						return new Expression.Binary(left, BinOp.Lt, r, TokenSpan.Merge(leftSpan, r.Span));
					}
				}
				case Operator.LessEqual:
				{
					parser.Advance();
					var r = ParseExprPrecedence(rbp);
					return new Expression.Binary(left, BinOp.LtEq, r, TokenSpan.Merge(leftSpan, r.Span));
				}
				case Operator.Greater:
				{
					// > or >> (shift right — two consecutive Greater tokens)
					if (parser.PeekAt(1).Operator == Operator.Greater) {
						parser.Advance();
						parser.Advance();
						var r = ParseExprPrecedence(rbp);
						return new Expression.Binary(left, BinOp.Shr, r, TokenSpan.Merge(leftSpan, r.Span));
					}
					else {
						parser.Advance();
						var r = ParseExprPrecedence(rbp);
						return new Expression.Binary(left, BinOp.Gt, r, TokenSpan.Merge(leftSpan, r.Span));
					}
				}
				case Operator.GreaterEqual:
				{
					parser.Advance();
					var r = ParseExprPrecedence(rbp);
					return new Expression.Binary(left, BinOp.GtEq, r, TokenSpan.Merge(leftSpan, r.Span));
				}
				case Operator.EqualEqual:
				{
					parser.Advance();
					var r = ParseExprPrecedence(rbp);
					return new Expression.Binary(left, BinOp.Eq, r, TokenSpan.Merge(leftSpan, r.Span));
				}
				case Operator.NotEqual:
				{
					parser.Advance();
					var r = ParseExprPrecedence(rbp);
					return new Expression.Binary(left, BinOp.NotEq, r, TokenSpan.Merge(leftSpan, r.Span));
				}
				case Operator.Fallback:
				{
					parser.Advance();
					var r = ParseExprPrecedence(rbp);
					return new Expression.NullCoalesce(left, r, TokenSpan.Merge(leftSpan, r.Span));
				}
				case Operator.DotDot:
				{
					parser.Advance();
					var r = ParseExprPrecedence(rbp);
					return new Expression.Range(left, r, TokenSpan.Merge(leftSpan, r.Span));
				}
				case Operator.Dot:
				{
					parser.Advance();
					// `<TypeName>.this` — explicit outer-instance reference. The LHS must be
					// a bare Identifier (a type name); other expression shapes don't make sense.
					if (parser.CheckKeyword(Keyword.This)) {
						parser.Advance();
						if (left is not Expression.Identifier idForThis)
							throw ParserError.ExpectedIdentifier.WithMessage("'.this' must follow a type name").WithSpan(parser.Current.Span).Render();
						return new Expression.OuterThis(idForThis.Name, TokenSpan.Merge(leftSpan, parser.Previous().Span));
					}

					// `<expr>.new T(args)` — receiver-qualified construction (Java-style for
					// instantiating an inner class against a specific outer instance). The
					// receiver-qualified form only makes sense for class instantiation, not
					// array allocation, so a `new T[n]` after a `.` errors out at parse time.
					if (parser.CheckKeyword(Keyword.New)) {
						var bare = ParseNewExpr();
						if (bare is Expression.New bareNew)
							return bareNew with { Receiver = left, Span = TokenSpan.Merge(leftSpan, bareNew.Span) };
						throw ParserError.ExpectedKeyword.WithMessage("`.new T[n]` is not supported — array allocation does not take a receiver").WithSpan(bare.Span).Render();
					}

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
				case Operator.ColonColon:
				{
					parser.Advance();
					if (parser.Current.Type != TokenType.Meta)
						throw ParserError.ExpectedKeyword.WithMessage($"expected meta keyword (e.g. SIZE, TYPE), got '{parser.Current.Lexeme}'").WithSpan(parser.Current.Span).Render();
					var metaName = parser.Current.Lexeme;
					var metaSpan = parser.Current.Span;
					parser.Advance();
					return new Expression.MetaAccess(left, metaName, TokenSpan.Merge(leftSpan, metaSpan));
				}
				case Operator.LParen:
				{
					parser.Advance();
					var args = ParseCallArgs();
					parser.ExpectOperator(Operator.RParen);
					return new Expression.Call(left, args, TokenSpan.Merge(leftSpan, parser.Previous().Span));
				}
				case Operator.LBracket:
				{
					parser.Advance();
					var idx = ParseExpression();
					parser.ExpectOperator(Operator.RBracket);
					return new Expression.Index(left, idx, TokenSpan.Merge(leftSpan, parser.Previous().Span));
				}
				case Operator.PlusPlus:
				{
					var end = parser.Current.Span;
					parser.Advance();
					return new Expression.Postfix(left, PostOp.Inc, TokenSpan.Merge(leftSpan, end));
				}
				case Operator.MinusMinus:
				{
					var end = parser.Current.Span;
					parser.Advance();
					return new Expression.Postfix(left, PostOp.Dec, TokenSpan.Merge(leftSpan, end));
				}
			}
		}

		if (tok.Type == TokenType.Keyword) {
			switch (tok.Keyword) {
				case Keyword.And:
				{
					parser.Advance();
					var r = ParseExprPrecedence(rbp);
					return new Expression.Binary(left, BinOp.And, r, TokenSpan.Merge(leftSpan, r.Span));
				}
				case Keyword.Or:
				{
					parser.Advance();
					var r = ParseExprPrecedence(rbp);
					return new Expression.Binary(left, BinOp.Or, r, TokenSpan.Merge(leftSpan, r.Span));
				}
				case Keyword.As:
				{
					parser.Advance();
					var safe = parser.ConsumeOp(Operator.Question);
					var ty = parser.ParseTypeExpression();
					return new Expression.Cast(left, ty, safe, TokenSpan.Merge(leftSpan, ty.Span));
				}
				case Keyword.Is:
				{
					parser.Advance();
					var ty = parser.ParseTypeExpression();
					return new Expression.TypeCheck(left, ty, TokenSpan.Merge(leftSpan, ty.Span));
				}
				case Keyword.In:
				{
					parser.Advance();
					var r = ParseExprPrecedence(rbp);
					return new Expression.MembershipCheck(left, r, TokenSpan.Merge(leftSpan, r.Span));
				}
			}
		}

		return left;
	}

	/// <summary>
	/// Identifies the assignment operator at the current parsing position, if any, and maps it to a specific assignment operation type.
	/// These operators are used for right-associative assignment expressions, such as `=`, `+=`, or `-=`.
	/// </summary>
	/// <returns>
	/// The corresponding assignment operation as an <see cref="AssignOp"/> value, or null if the current token does not represent an assignment operator.
	/// </returns>
	private AssignOp? PeekAssignOp() => parser.Current.Operator switch {
		Operator.Equal => AssignOp.Assign,
		Operator.PlusEqual => AssignOp.AddAssign,
		Operator.MinusEqual => AssignOp.SubAssign,
		Operator.StarEqual => AssignOp.MulAssign,
		Operator.SlashEqual => AssignOp.DivAssign,
		Operator.PercentEqual => AssignOp.RemAssign,
		Operator.AndEqual => AssignOp.AndAssign,
		Operator.OrEqual => AssignOp.OrAssign,
		Operator.CaretEqual => AssignOp.PowAssign,
		_ => null
	};

	/// <summary>
	/// Determines the infix binding power of the current token in the parsing process.
	/// The binding power is represented as a tuple containing left and right precedence values.
	/// These values dictate how the current token should associate with others in an expression.
	/// </summary>
	/// <returns>
	/// A tuple containing the left and right binding powers of the current token.
	/// Returns null if the token does not represent an operator or keyword with defined binding power.
	/// </returns>
	private (int Left, int Right)? PeekInfixBp() => (parser.Current.Type, parser.Current.Operator, parser.Current.Keyword) switch {
		(TokenType.Operator, Operator.Dot, _) => (28, 29),
		(TokenType.Operator, Operator.ColonColon, _) => (28, 29),
		(TokenType.Operator, Operator.LParen, _) => (28, 29),
		(TokenType.Operator, Operator.LBracket, _) => (28, 29),
		(TokenType.Operator, Operator.PlusPlus, _) => (28, 0),
		(TokenType.Operator, Operator.MinusMinus, _) => (28, 0),
		(TokenType.Keyword, _, Keyword.As) => (26, 0),
		(TokenType.Keyword, _, Keyword.Is) => (12, 0),
		(TokenType.Keyword, _, Keyword.In) => (12, 13),
		(TokenType.Operator, Operator.Star, _) => (22, 23),
		(TokenType.Operator, Operator.Slash, _) => (22, 23),
		(TokenType.Operator, Operator.Percent, _) => (22, 23),
		(TokenType.Operator, Operator.Plus, _) => (20, 21),
		(TokenType.Operator, Operator.Minus, _) => (20, 21),
		(TokenType.Operator, Operator.Less, _) => (20, 21),
		(TokenType.Operator, Operator.Greater, _) => (20, 21),
		(TokenType.Operator, Operator.And, _) => (18, 19),
		// `^` is power, right-associative (lbp > rbp), and binds tighter than `*`/`/`/`%`
		// so `(-10) ^ 2 % 3` groups as `((-10) ^ 2) % 3`. Unary `-` (handled by the prefix
		// parser, not this infix table) still binds tighter, keeping `-10 ^ 2` as `(-10) ^ 2`.
		(TokenType.Operator, Operator.Caret, _) => (25, 24),
		(TokenType.Operator, Operator.Or, _) => (14, 15),
		(TokenType.Operator, Operator.LessEqual, _) => (12, 13),
		(TokenType.Operator, Operator.GreaterEqual, _) => (12, 13),
		(TokenType.Operator, Operator.EqualEqual, _) => (10, 11),
		(TokenType.Operator, Operator.NotEqual, _) => (10, 11),
		(TokenType.Keyword, _, Keyword.And) => (8, 9),
		(TokenType.Keyword, _, Keyword.Or) => (6, 7),
		(TokenType.Operator, Operator.Fallback, _) => (4, 5),
		(TokenType.Operator, Operator.DotDot, _) => (2, 3),
		_ => null
	};

	/// <summary>
	/// Parses a series of call arguments from the input and constructs a list of expressions.
	/// The method handles expressions separated by commas and terminates upon encountering
	/// a closing parenthesis.
	/// </summary>
	/// <returns>
	/// A list of parsed expressions representing the individual arguments of a function or method call.
	/// If no arguments are provided, an empty list is returned.
	/// </returns>
	internal List<Expression> ParseCallArgs() {
		var args = new List<Expression>();
		if (!parser.CheckOperator(Operator.RParen)) {
			args.Add(ParseExpression());
			while (parser.ConsumeOp(Operator.Comma))
				args.Add(ParseExpression());
		}

		return args;
	}

	/// <summary>
	/// Parses a "new" expression, constructing an instance of the specified type with optional arguments.
	/// This method handles the "new" keyword, the type being instantiated, and the provided argument list
	/// enclosed in parentheses.
	/// </summary>
	/// <returns>
	/// An <see cref="Expression.New"/> representing the instantiation expression.
	/// The resulting object includes the type being instantiated, the list of initialization arguments, and
	/// the associated source code span.
	/// </returns>
	/// <exception cref="ParserError">
	/// Thrown when the syntax for the "new" expression is incorrect, such as a missing type, parentheses,
	/// or improperly formatted arguments.
	/// </exception>
	private Expression ParseNewExpr() {
		var start = parser.Current.Span;
		parser.ExpectKeyword(Keyword.New);
		var ty = parser.ParseTypeExpression();
		// `new T[a]` / `new T[a][b]` — heap-allocate a nested array. `ParseTypeExpression`
		// stops before any non-empty `[]`, so any `[` immediately after the type starts an
		// allocation-size list. Multiple bracket pairs build a multi-dimensional array.
		if (parser.CheckOperator(Operator.LBracket)) {
			var sizes = new List<Expression>();
			while (parser.CheckOperator(Operator.LBracket)) {
				parser.Advance();
				sizes.Add(ParseExpression());
				parser.ExpectOperator(Operator.RBracket);
			}
			return new Expression.NewArray(ty, sizes, TokenSpan.Merge(start, parser.Previous().Span));
		}
		parser.ExpectOperator(Operator.LParen);
		var args = ParseCallArgs();
		parser.ExpectOperator(Operator.RParen);
		return new Expression.New(ty, args, TokenSpan.Merge(start, parser.Previous().Span));
	}

	/// <summary>
	/// Parses an expression that is either enclosed in parentheses or represents a lambda expression.
	/// If a lambda expression is detected, the method attempts to parse it using the current token span.
	/// If no valid lambda expression is found, the parser proceeds to parse a parenthesized expression.
	/// </summary>
	/// <returns>
	/// An <see cref="Expression"/> representing either a lambda expression,
	/// a parenthesized expression, or a tuple if multiple expressions are detected within parentheses.
	/// </returns>
	/// <exception cref="ParserError">
	/// Thrown when an invalid token sequence is encountered, such as missing a closing parenthesis or
	/// encountering an unexpected operator other than ',' or ')'.
	/// </exception>
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

		throw ParserError.ExpectedOperator.WithMessage($"expected ')' or ','").WithSpan(parser.Current.Span).Render();
	}

	/// <summary>
	/// Attempts to parse a lambda expression starting from the specified token span.
	/// The method expects a parameter list enclosed in parentheses, followed by an arrow (->),
	/// and the lambda body. If the pattern is matched, a lambda expression is returned; otherwise, null.
	/// </summary>
	/// <param name="start">The starting token span from which the parser begins attempting to read a lambda expression.</param>
	/// <returns>
	/// A <see cref="Expression.Lambda"/> instance if the tokens represent a valid lambda expression;
	/// otherwise, null.
	/// </returns>
	private Expression.Lambda? TryParseLambda(TokenSpan start) {
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
		var body = ParseExprPrecedence(0);
		return new Expression.Lambda(parms, new LambdaBody.ExpressionBody(body), TokenSpan.Merge(start, body.Span));
	}

	/// <summary>
	/// Determines whether the sequence of tokens starting from the current position
	/// represents the beginning of a lambda expression. The pattern checked is
	/// either an empty parameter list followed by an arrow (e.g., "() ->") or
	/// a parameter list with type annotations, followed by an arrow (e.g., "(T name, T2 name) ->").
	/// Non-consuming lookahead: does `(` begin a lambda? Pattern: `([T name [, T name]*]) ->`
	/// </summary>
	/// <returns>
	/// True if the token sequence matches the lambda expression pattern; otherwise, false.
	/// </returns>
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

			if (parser.PeekAt(i).Operator == Operator.Comma) {
				i++;
				continue;
			}

			if (parser.PeekAt(i).Operator == Operator.RParen) return parser.PeekAt(i + 1).Operator == Operator.Arrow;
			return false;
		}
	}

	/// <summary>
	/// Determines if the specified token is a type keyword recognized by the language.
	/// </summary>
	/// <param name="tok">The token to check, which is expected to have its type and keyword properties defined.</param>
	/// <returns>
	/// True if the token represents a type keyword (such as a primitive or predefined type);
	/// otherwise, false.
	/// </returns>
	private static bool IsTypeKeyword(Token tok) => tok.Type == TokenType.Keyword && tok.Keyword is Keyword.Bool or Keyword.Char or Keyword.Byte or Keyword.I8 or Keyword.I16 or Keyword.I32 or Keyword.I64 or Keyword.U8 or Keyword.U16 or Keyword.U32 or Keyword.U64 or Keyword.F32 or Keyword.F64 or Keyword.Float or Keyword.Double or Keyword.Real or Keyword.Long or Keyword.Short or Keyword.Int or Keyword.Uint or Keyword.Unsigned or Keyword.String or Keyword.Bit or Keyword.Any;

	/// <summary>
	/// Parses a token representing a literal and converts it into the corresponding literal expression.
	/// </summary>
	/// <param name="tok">The token to be parsed, which should contain the lexeme and type information identifying the literal.</param>
	/// <returns>
	/// A <see cref="Literal"/> instance that represents the parsed literal, such as an integer, float, string, character, or bit.
	/// </returns>
	/// <exception cref="ParserError">
	/// Thrown if the token contains an invalid or unsupported literal format.
	/// </exception>
	private static Literal ParseLiteral(Token tok) {
		var lexeme = tok.Lexeme;
		if (lexeme.StartsWith('"'))
			return new Literal.Str(lexeme[1..^1]);
		if (lexeme.StartsWith('\'')) {
			var content = lexeme[1..^1];
			char ch = content.Length == 1
				? content[0]
				: content[1] switch {
					'n' => '\n', 't' => '\t', 'r' => '\r',
					'\\' => '\\', '\'' => '\'', '0' => '\0',
					_ => content[1]
				};
			return new Literal.Char(ch);
		}

		if (lexeme is "0t" or "1t") return new Literal.Bit(lexeme[0] == '0' ? (byte)0 : (byte)1);
		if (lexeme.Contains('.') || lexeme.Contains('e') || lexeme.Contains('E'))
			return new Literal.Float(lexeme);
		return new Literal.Int(lexeme);
	}

	/// <summary>
	/// Retrieves and validates a member name from the current parser token.
	/// </summary>
	/// <returns>
	/// A tuple containing the member name as a string and its associated <see cref="TokenSpan"/>.
	/// </returns>
	/// <exception cref="ParserError">
	/// Thrown when the current token is not a valid identifier or keyword,
	/// with an associated error message and span information.
	/// </exception>
	private (string Name, TokenSpan Span) ExpectMemberName() {
		var span = parser.Current.Span;
		if (parser.Current.Type == TokenType.Identifier || parser.Current.Type == TokenType.Keyword) {
			var name = parser.Current.Lexeme;
			parser.Advance();
			return (name, span);
		}

		throw ParserError.ExpectedIdentifier.WithMessage($"expected member name, got '{parser.Current.Lexeme}'").WithSpan(span).Render();
	}
}