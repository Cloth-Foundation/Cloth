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

	private Expression ParseExpression() => parser.ExpressionParser.ParseExpression();

	private Stmt ParseSuperCall()    => throw new NotImplementedException("ParseSuperCall requires expression parsing.");
	private Stmt ParseThisCall()     => throw new NotImplementedException("ParseThisCall requires expression parsing.");

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

	private Stmt.While ParseWhileStmt() {
		var start = parser.Current.Span;
		parser.ExpectKeyword(Keyword.While);
		parser.ExpectOperator(Operator.LParen);
		var cond = ParseExpression();
		parser.ExpectOperator(Operator.RParen);
		var body = parser.ParseBlock();
		return new Stmt.While(new WhileStmt(cond, body, TokenSpan.Merge(start, parser.Previous().Span)));
	}

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

	private Stmt.For ParseForStmt()      => throw new NotImplementedException("ParseForStmt");

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

	private Stmt.Throw ParseThrowStmt() {
		var start = parser.Current.Span;
		parser.ExpectKeyword(Keyword.Throw);
		Expression expression = ParseExpression();
		parser.ExpectSemiColon();
		return new Stmt.Throw(expression, TokenSpan.Merge(start, parser.Previous().Span));
	}
	private Stmt ParseExprStmt()     => throw new NotImplementedException("ParseExprStmt requires expression parsing.");
}
