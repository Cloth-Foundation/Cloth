// Copyright (c) 2026. The Cloth contributors.
//
// StatementParser.cs is part of the Cloth Frontend.
//
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

namespace FrontEnd.Parser;

using Token;
using AST.Expressions;
using AST.Statements;
using AST.Type;
using FrontEnd.Error.Parser;

internal sealed class StatementParser(Parser parser) {

	/// <summary>
	/// Parses a single statement from the source code. This method handles
	/// various types of statements, including control flow statements,
	/// variable declarations, block statements, and expressions.
	/// </summary>
	/// <returns>
	/// A parsed statement represented as an instance of <see cref="Stmt"/>.
	/// The specific type of statement returned depends on the source code
	/// being parsed.
	/// </returns>
	internal Stmt ParseStatement() {
		// super(args) — super-constructor delegation
		if (parser.CheckKeyword(Keyword.Super) && parser.PeekAt(1).Operator == Operator.LParen)
			return ParseSuperCall();

		// this(args) — constructor self-delegation
		if (parser.CheckKeyword(Keyword.This) && parser.PeekAt(1).Operator == Operator.LParen)
			return ParseThisCall();

		// Control flow
		if (parser.CheckKeyword(Keyword.If))      return ParseIfStmt();
		if (parser.CheckKeyword(Keyword.Switch))  return ParseSwitchStmt();
		if (parser.CheckKeyword(Keyword.While))   return ParseWhileStmt();
		if (parser.CheckKeyword(Keyword.Do))      return ParseDoWhileStmt();
		if (parser.CheckKeyword(Keyword.For))     return ParseForStmt();
		if (parser.CheckKeyword(Keyword.Return))  return ParseReturnStmt();
		if (parser.CheckKeyword(Keyword.Throw))   return ParseThrowStmt();

		if (parser.CheckKeyword(Keyword.Break)) {
			var span = parser.Current.Span;
			parser.Advance();
			parser.ExpectSemiColon();
			return new Stmt.Break(span);
		}

		if (parser.CheckKeyword(Keyword.Continue)) {
			var span = parser.Current.Span;
			parser.Advance();
			parser.ExpectSemiColon();
			return new Stmt.Continue(span);
		}

		// Inline block
		if (parser.CheckOperator(Operator.LBrace))
			return new Stmt.BlockStmt(parser.ParseBlock());

		// let declaration (name-first: let name: type = expr;)
		if (parser.CheckKeyword(Keyword.Let))
			return ParseVarDecl([]);

		// var modifiers (static, atomic) must precede a declaration
		var mods = ParseVarModifiers();
		if (mods.Count > 0)
			return ParseVarDecl(mods);

		// Primitive type keyword → unambiguously a variable declaration
		if (IsTypeKeyword())
			return ParseVarDecl([]);

		// Identifier: may be a user-defined type name starting a declaration,
		// or the beginning of an expression. Try parsing a type; if the token
		// immediately following is also an identifier (the variable name), treat
		// this as a declaration. Otherwise, restore and fall through to expression.
		if (parser.Current.Type == TokenType.Identifier) {
			var saved = parser.SaveCursor();
			var ty = parser.TryParseTypeExpression();
			if (ty != null && parser.Current.Type == TokenType.Identifier)
				return ParseVarDeclWithType(ty.Value);
			parser.RestoreTo(saved);
		}

		return ParseExprStmt();
	}

	/// <summary>
	/// Parses and collects variable modifiers from the current token stream.
	/// This method identifies and processes specific modifiers such as
	/// <c>static</c> and <c>atomic</c>.
	/// </summary>
	/// <returns>
	/// A list of parsed variable modifiers represented as instances of
	/// <see cref="VarModifier"/>. If no modifiers are found, an empty list is returned.
	/// </returns>
	private List<VarModifier> ParseVarModifiers() {
		var mods = new List<VarModifier>();
		if (parser.CheckKeyword(Keyword.Static)) { mods.Add(VarModifier.Static); parser.Advance(); }
		if (parser.CheckKeyword(Keyword.Atomic)) { mods.Add(VarModifier.Atomic); parser.Advance(); }
		return mods;
	}

	/// <summary>
	/// Determines whether the current token in the parser represents a type keyword.
	/// Type keywords include primitive types (e.g., int, float, char) and other predefined
	/// type-specifying keywords in the language.
	/// </summary>
	/// <returns>
	/// A boolean value indicating whether the current token is recognized as a type keyword.
	/// Returns <c>true</c> if the current token matches a predefined type keyword; otherwise, <c>false</c>.
	/// </returns>
	private bool IsTypeKeyword() => parser.Current.Keyword is
		Keyword.Bool    or Keyword.Char     or Keyword.Byte    or
		Keyword.I8      or Keyword.I16      or Keyword.I32     or Keyword.I64     or
		Keyword.U8      or Keyword.U16      or Keyword.U32     or Keyword.U64     or
		Keyword.F32     or Keyword.F64      or Keyword.Float   or Keyword.Double  or Keyword.Real or
		Keyword.Long    or Keyword.Short    or Keyword.Int     or Keyword.Uint    or Keyword.Unsigned or
		Keyword.String  or Keyword.Bit      or Keyword.Any;

	/// <summary>
	/// Parses a variable declaration statement. This method processes declarations with optional
	/// modifiers, a type annotation, an initializer expression, and ensures the declaration ends with
	/// a semicolon. It supports both explicit "let" declarations and declarations inferred from types.
	/// </summary>
	/// <param name="modifiers">
	/// A list of <see cref="VarModifier"/> applied to the variable declaration, such as static or atomic.
	/// These modifiers define the behavior and scope of the declared variable.
	/// </param>
	/// <returns>
	/// An instance of <see cref="Stmt"/> representing the parsed variable declaration. This includes
	/// details of the variable's type, name, optional initializer, and any modifiers applied.
	/// </returns>
	private Stmt.VarDecl ParseVarDecl(List<VarModifier> modifiers) {
		var start = parser.Current.Span;

		if (parser.CheckKeyword(Keyword.Let)) {
			parser.Advance();
			var name = parser.ExpectIdentifier();

			TypeExpression? type = null; // just infer
			/*if (parser.CheckOperator(Operator.Colon)) {
				parser.Advance();
				type = parser.ParseTypeExpression();
			}*/

			Expression? init = null;
			if (parser.ConsumeOp(Operator.Equal)) {
				init = ParseExpression();
			}

			parser.ExpectSemiColon();
			var span = TokenSpan.Merge(start, parser.Previous().Span);
			return new Stmt.VarDecl(new VarDeclStmt(modifiers, type, name, init, span));
		}
		else {
			var type = parser.ParseTypeExpression();
			var name = parser.ExpectIdentifier();

			Expression? init = null;
			if (parser.ConsumeOp(Operator.Equal)) {
				init = ParseExpression();
			}

			parser.ExpectSemiColon();
			var span = TokenSpan.Merge(start, parser.Previous().Span);
			return new Stmt.VarDecl(new VarDeclStmt(modifiers, type, name, init, span));
		}
	}

	/// <summary>
	/// Parses a variable declaration with a specified type. This method
	/// expects an identifier after the type expression and may parse an optional
	/// initializer expression and a required semicolon.
	/// </summary>
	/// <param name="type">The type expression associated with the variable being declared.</param>
	/// <returns>
	/// A variable declaration statement represented as an instance of <see cref="Stmt.VarDecl"/>.
	/// The statement includes the type, name, optional initializer, and associated span information.
	/// </returns>
	private Stmt.VarDecl ParseVarDeclWithType(TypeExpression type) {
		var start = type.Span;
		var name = parser.ExpectIdentifier();

		Expression? init = null;
		if (parser.ConsumeOp(Operator.Equal)) {
			init = ParseExpression();
		}

		parser.ExpectSemiColon();
		var span = TokenSpan.Merge(start, parser.Previous().Span);
		return new Stmt.VarDecl(new VarDeclStmt([], type, name, init, span));
	}

	/// <summary>
	/// Parses an expression from the source code. This method is responsible for
	/// handling various types of expressions, including literals, binary operations,
	/// unary operations, function calls, and more, depending on the current context
	/// in the parsing process.
	/// </summary>
	/// <returns>
	/// An instance of <see cref="Expression"/> that represents the parsed expression.
	/// The specific type of expression returned is determined by the syntax being parsed.
	/// </returns>
	private Expression ParseExpression() => parser.ExpressionParser.ParseExpression();

	/// <summary>
	/// Parses the arguments provided in a function or method call. This method is responsible
	/// for collecting and returning all expressions supplied as arguments within a call context.
	/// </summary>
	/// <returns>
	/// A list containing expressions representing the parsed arguments, each as an instance
	/// of <see cref="Expression"/>. If no arguments are provided, an empty list is returned.
	/// </returns>
	private List<Expression> ParseCallArgs() => parser.ExpressionParser.ParseCallArgs();

	/// <summary>
	/// Parses a `super` constructor call statement from the source code.
	/// </summary>
	/// <returns>
	/// A parsed super-constructor call represented as an instance of <see cref="Stmt.SuperCall"/>.
	/// The returned statement includes the arguments passed to the super constructor
	/// and the associated token span information.
	/// </returns>
	private Stmt ParseSuperCall() {
		var start = parser.Current.Span;
		parser.ExpectKeyword(Keyword.Super);
		parser.ExpectOperator(Operator.LParen);
		var args = ParseCallArgs();
		parser.ExpectOperator(Operator.RParen);
		parser.ExpectSemiColon();
		return new Stmt.SuperCall(args, TokenSpan.Merge(start, parser.Previous().Span));
	}

	/// <summary>
	/// Parses a 'this' constructor call statement from the source code. This method
	/// is used in scenarios where a constructor delegates to another constructor
	/// within the same class, using the 'this' keyword followed by argument parentheses.
	/// </summary>
	/// <returns>
	/// A parsed 'this' call statement represented as an instance of <see cref="Stmt.ThisCall"/>.
	/// This contains the arguments passed to the constructor call as well as the token span
	/// representing the location of the statement in the source code.
	/// </returns>
	private Stmt ParseThisCall() {
		var start = parser.Current.Span;
		parser.ExpectKeyword(Keyword.This);
		parser.ExpectOperator(Operator.LParen);
		var args = ParseCallArgs();
		parser.ExpectOperator(Operator.RParen);
		parser.ExpectSemiColon();
		return new Stmt.ThisCall(args, TokenSpan.Merge(start, parser.Previous().Span));
	}

	/// <summary>
	/// Parses an "if" statement from the source code, including its condition,
	/// "then" branch, optional "else if" branches, and optional "else" branch.
	/// This method handles complex conditional control flow structures by
	/// evaluating the provided expressions and associating them with their
	/// corresponding block statements.
	/// </summary>
	/// <returns>
	/// A parsed "if" statement represented as an instance of <see cref="Stmt.If"/>.
	/// This object contains details about the condition, "then" branch, "else if"
	/// branches (if any), and "else" branch (if present).
	/// </returns>
	private Stmt.If ParseIfStmt() {
		var start = parser.Current.Span;
		parser.ExpectKeyword(Keyword.If);
		parser.ExpectOperator(Operator.LParen);
		var cond = ParseExpression();
		parser.ExpectOperator(Operator.RParen);
		var thenBranch = parser.ParseBlock();

		var elseIfBranches = new List<ElseIfBranch>();
		Block? elseBranch = null;

		while (parser.CheckKeyword(Keyword.Else)) {
			parser.Advance();
			if (parser.CheckKeyword(Keyword.If)) {
				parser.Advance();
				parser.ExpectOperator(Operator.LParen);
				var eiCond = ParseExpression();
				parser.ExpectOperator(Operator.RParen);
				elseIfBranches.Add(new ElseIfBranch(eiCond, parser.ParseBlock()));
			} else {
				elseBranch = parser.ParseBlock();
				break;
			}
		}

		var span = TokenSpan.Merge(start, parser.Previous().Span);
		return new Stmt.If(new IfStmt(cond, thenBranch, elseIfBranches, elseBranch, span));
	}

	/// <summary>
	/// Parses a switch statement from the source code. This method handles the
	/// parsing of the expression to be evaluated, the cases defined within the
	/// switch, and the statements belonging to each case or the default case.
	/// </summary>
	/// <returns>
	/// A parsed switch statement represented as an instance of
	/// <see cref="Stmt.Switch"/>. The returned statement contains
	/// information about the controlling expression, the defined cases, and
	/// their respective bodies.
	/// </returns>
	/// <exception cref="ParserError">
	/// Thrown if the switch statement is improperly formatted, such as missing
	/// expected keywords, delimiters, or other structural components.
	/// </exception>
	private Stmt.Switch ParseSwitchStmt() {
		var start = parser.Current.Span;
		parser.ExpectKeyword(Keyword.Switch);
		parser.ExpectOperator(Operator.LParen);
		var expr = ParseExpression();
		parser.ExpectOperator(Operator.RParen);
		parser.ExpectOperator(Operator.LBrace);

		var cases = new List<SwitchCase>();
		while (!parser.CheckOperator(Operator.RBrace) && !parser.AtEof()) {
			var caseStart = parser.Current.Span;
			SwitchPattern pattern;
			if (parser.ConsumeKeyword(Keyword.Case)) {
				pattern = new SwitchPattern.Case(ParseExpression());
			} else if (parser.ConsumeKeyword(Keyword.Default)) {
				pattern = new SwitchPattern.Default();
			} else {
				throw ParserError.ExpectedKeyword
					.WithMessage($"expected 'case' or 'default', got '{parser.Current.Lexeme}'")
					.WithSpan(parser.Current.Span)
					.Render();
			}
			parser.ExpectOperator(Operator.Colon);

			var body = new List<Stmt>();
			while (!parser.CheckKeyword(Keyword.Case)
				&& !parser.CheckKeyword(Keyword.Default)
				&& !parser.CheckOperator(Operator.RBrace)
				&& !parser.AtEof()) {
				body.Add(parser.ParseStatement());
			}

			cases.Add(new SwitchCase(pattern, body, TokenSpan.Merge(caseStart, parser.Previous().Span)));
		}

		parser.ExpectOperator(Operator.RBrace);
		return new Stmt.Switch(new SwitchStmt(expr, cases, TokenSpan.Merge(start, parser.Previous().Span)));
	}

	/// <summary>
	/// Parses a while loop statement from the source code. This method processes
	/// the `while` keyword, the loop condition within parentheses, and the loop body
	/// as a block statement.
	/// </summary>
	/// <returns>
	/// A parsed while statement represented as an instance of <see cref="Stmt.While"/>.
	/// This encapsulates the loop condition, body, and its corresponding source code span.
	/// </returns>
	private Stmt.While ParseWhileStmt() {
		var start = parser.Current.Span;
		parser.ExpectKeyword(Keyword.While);
		parser.ExpectOperator(Operator.LParen);
		var cond = ParseExpression();
		parser.ExpectOperator(Operator.RParen);
		var body = parser.ParseBlock();
		return new Stmt.While(new WhileStmt(cond, body, TokenSpan.Merge(start, parser.Previous().Span)));
	}

	/// <summary>
	/// Parses a do-while statement from the source code. This method handles
	/// the `do` keyword, the statement body, the `while` keyword, and the
	/// conditional expression wrapped in parentheses. It ensures correct syntax
	/// by validating the presence and order of these elements.
	/// </summary>
	/// <returns>
	/// A parsed do-while statement represented as an instance of
	/// <see cref="Stmt.DoWhile"/>. This includes the loop's body statement
	/// and its associated conditional expression.
	/// </returns>
	private Stmt.DoWhile ParseDoWhileStmt() {
		var start = parser.Current.Span;
		parser.ExpectKeyword(Keyword.Do);
		var body = parser.ParseBlock();
		parser.ExpectKeyword(Keyword.While);
		parser.ExpectOperator(Operator.LParen);
		var condition = ParseExpression();
		parser.ExpectOperator(Operator.RParen);
		return new Stmt.DoWhile(new DoWhileStmt(body, condition, TokenSpan.Merge(start, parser.Previous().Span)));
	}

	/// <summary>
	/// Parses a 'for' statement from the source code. This method supports two
	/// distinct types of 'for' statements: traditional 'for' loops with an
	/// initializer, condition, and iterator, and 'for-in' loops where an
	/// identifier iterates over an iterable collection.
	/// </summary>
	/// <returns>
	/// A parsed 'for' statement represented as an instance of <see cref="Stmt.For"/>
	/// or <see cref="Stmt.ForIn"/>. The specific type depends on the syntax of
	/// the 'for' statement being parsed.
	/// </returns>
	private Stmt ParseForStmt() {
		var start = parser.Current.Span;
		parser.ExpectKeyword(Keyword.For);
		parser.ExpectOperator(Operator.LParen);

		// Disambiguate: for (Type name in ...) vs for (init; cond; iter)
		var isForIn = false;
		if (IsTypeKeyword() || parser.Current.Type == TokenType.Identifier) {
			var saved = parser.SaveCursor();
			_ = parser.TryParseTypeExpression(); // speculatively consume type
			if (parser.Current.Type == TokenType.Identifier) {
				parser.Advance(); // speculatively consume name
				isForIn = parser.CheckKeyword(Keyword.In);
			}
			parser.RestoreTo(saved);
		}

		if (isForIn) {
			var type = parser.ParseTypeExpression();
			var name = parser.ExpectIdentifier();
			parser.ExpectKeyword(Keyword.In);
			var iterable = ParseExpression();
			parser.ExpectOperator(Operator.RParen);
			var body = parser.ParseBlock();
			var span = TokenSpan.Merge(start, parser.Previous().Span);
			return new Stmt.ForIn(new ForInStmt(type, name, iterable, body, span));
		} else {
			var init = ParseForInit();
			var cond = ParseExpression();
			parser.ExpectSemiColon();
			var iter = ParseExpression();
			parser.ExpectOperator(Operator.RParen);
			var body = parser.ParseBlock();
			var span = TokenSpan.Merge(start, parser.Previous().Span);
			return new Stmt.For(new ForStmt(init, cond, iter, body, span));
		}
	}

	/// <summary>
	/// Parses the initializer section of a 'for' loop statement. This method determines
	/// whether the initialization is a variable declaration, a type-annotated variable
	/// declaration, or an expression, and subsequently parses it accordingly.
	/// </summary>
	/// <returns>
	/// An instance of <see cref="Stmt"/> representing the parsed initializer,
	/// which could either be a variable declaration, an expression statement,
	/// or null if no initialization is present.
	/// </returns>
	private Stmt ParseForInit() {
		if (IsTypeKeyword())
			return ParseVarDecl([]);
		if (parser.Current.Type == TokenType.Identifier) {
			var saved = parser.SaveCursor();
			var ty = parser.TryParseTypeExpression();
			if (ty != null && parser.Current.Type == TokenType.Identifier)
				return ParseVarDeclWithType(ty.Value);
			parser.RestoreTo(saved);
		}
		var expr = ParseExpression();
		parser.ExpectSemiColon();
		return new Stmt.ExprStmt(expr, expr.Span);
	}

	/// <summary>
	/// Parses a return statement from the source code. This method identifies
	/// the `return` keyword and optionally parses the accompanying expression
	/// if one is provided.
	/// </summary>
	/// <returns>
	/// A return statement represented as an instance of <see cref="Stmt.Return"/>.
	/// If no expression accompanies the `return` keyword, the `Value` property
	/// of the resulting statement will be null.
	/// </returns>
	private Stmt.Return ParseReturnStmt() {
		var start = parser.Current.Span;
		parser.ExpectKeyword(Keyword.Return);
		Expression? expression = null;

		if (!parser.CheckOperator(Operator.Semicolon)) {
			expression = ParseExpression();
		}

		parser.ExpectSemiColon();
		return new Stmt.Return(expression, TokenSpan.Merge(start, parser.Previous().Span));
	}

	/// <summary>
	/// Parses a throw statement from the source code. This method processes
	/// the 'throw' keyword, followed by an expression, and expects a semicolon
	/// to terminate the statement.
	/// </summary>
	/// <returns>
	/// A parsed throw statement represented as an instance of <see cref="Stmt.Throw"/>.
	/// The expression contained within the throw statement specifies the value to be thrown.
	/// </returns>
	private Stmt.Throw ParseThrowStmt() {
		var start = parser.Current.Span;
		parser.ExpectKeyword(Keyword.Throw);
		Expression expression = ParseExpression();
		parser.ExpectSemiColon();
		return new Stmt.Throw(expression, TokenSpan.Merge(start, parser.Previous().Span));
	}

	/// <summary>
	/// Parses an expression statement from the source code. An expression statement
	/// consists of an expression followed by a semicolon. This method ensures the
	/// semicolon is present and validates the expression structure.
	/// </summary>
	/// <returns>
	/// A parsed expression statement represented as an instance of <see cref="Stmt.ExprStmt"/>.
	/// The statement contains the expression being parsed and its corresponding token span.
	/// </returns>
	private Stmt.ExprStmt ParseExprStmt() {
		var expr = ParseExpression();
		parser.ExpectSemiColon();
		return new Stmt.ExprStmt(expr, expr.Span);
	}
}
