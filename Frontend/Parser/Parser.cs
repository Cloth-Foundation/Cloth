// Copyright (c) 2026.The Cloth contributors.
// 
// Parser.cs is part of the Cloth Frontend.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

using FrontEnd.Error.Parser;
using FrontEnd.Parser.AST;
using FrontEnd.Parser.AST.Declarations;
using FrontEnd.Parser.AST.Entries;
using FrontEnd.Parser.AST.Expressions;
using FrontEnd.Parser.AST.Statements;
using FrontEnd.Parser.AST.Type;

namespace FrontEnd.Parser;

using Lexer;
using Token;

public class Parser {
	private readonly List<Token> _tokens;
	private Token _current;
	private bool _moduleDeclared;
	private int _cursor;
	private readonly string _currentFileName;
	// Tracks the name of the class/struct/etc currently being parsed so constructor
	// detection inside member-bodies can disambiguate between an outer class and a
	// nested one. Pushed/popped around nested-type parsing.
	private string _currentClassName = "";

	internal StatementParser StatementParser;
	internal ExpressionParser ExpressionParser;

	public Parser(Lexer lexer) {
		_tokens = lexer.LexAll();
		_current = _tokens.First();
		_moduleDeclared = false;
		_cursor = 0;
		_currentFileName = lexer.GetSourceFile().NameWithoutExtension;
		StatementParser = new(this);
		ExpressionParser = new(this);
	}

	public CompilationUnit Parse() {
		var start = _current.Span;

		_current = _tokens.First();
		var module = ParseModuleDeclaration();
		var imports = ParseImports();
		var types = new List<TypeDeclaration>();

		while (!AtEof()) {
			types.Add(ParseTypeDeclaration());
		}

		ExpectEof();
		var end = Previous().Span;
		return new CompilationUnit(module, imports, types, TokenSpan.Merge(start, end));
	}

	/// <summary>
	/// Parses a module declaration, including its identifier path and token span, from the current token stream.
	/// Ensures that only one module declaration exists per parsing context and validates its syntax.
	/// </summary>
	/// <returns>
	/// A <see cref="ModuleDeclaration"/> object containing the path and token span of the parsed module declaration.
	/// </returns>
	/// <exception cref="ParserError">
	/// Thrown if a module has already been declared, if the 'module' keyword is missing,
	/// or if the module path is not defined.
	/// </exception>
	private ModuleDeclaration ParseModuleDeclaration() {
		if (_moduleDeclared) throw ParserError.ModuleAlreadyDefined.WithSpan(_current.Span).Render();
		if (_current.Type != TokenType.Keyword || _current.Keyword != Keyword.Module)
			throw ParserError.ModuleExpected.WithMessage($"expected 'module', got {_current.Type.ToString().ToLower()} '{_current.Lexeme}'").Render();

		var startSpan = _current.Span;
		Advance(); // eat "module", _current = first path segment
		var path = ParseModulePath();
		if (path.Count == 0) throw ParserError.ModulePathNotDefined.WithMessage("module path not defined").Render();
		var endSpan = ExpectSemiColon();

		_moduleDeclared = true;
		return new ModuleDeclaration(path, TokenSpan.Merge(startSpan, endSpan));
	}

	/// <summary>
	/// Parses the module path consisting of one or more identifiers separated by dots from the current token stream.
	/// Handles validation for special cases such as `_src` and ensures correct syntax for the module path.
	/// </summary>
	/// <returns>
	/// A list of strings representing the segments of the parsed module path.
	/// </returns>
	/// <exception cref="ParserError">
	/// Thrown if the module path is invalid, such as when using `_src` inappropriately or failing to provide an expected identifier.
	/// </exception>
	private List<string> ParseModulePath() {
		var path = new List<string>();

		// _current is the first identifier of the path
		var first = ExpectIdentifier();
		path.Add(first);

		if (first.ToLower() == "_src") {
			if (CheckOperator(Operator.Dot))
				throw ParserError.ModuleSrcInvalid.WithMessage("'_src' is only valid as its own identifier").WithSpan(Previous().Span).Render();
			return path;
		}

		while (CheckOperator(Operator.Dot)) {
			Advance(); // consume '.', _current = next segment
			path.Add(ExpectIdentifier());
		}

		return path;
	}

	/// <summary>
	/// Parses a collection of import declarations from the current token stream.
	/// Continues parsing imports until no more import declarations are found.
	/// </summary>
	/// <returns>
	/// A list of <see cref="ImportDeclaration"/> objects representing the parsed import declarations.
	/// </returns>
	private List<ImportDeclaration> ParseImports() {
		var imports = new List<ImportDeclaration>();
		while (CheckKeyword(Keyword.Import)) {
			imports.Add(ParseImport());
		}

		return imports;
	}

	/// <summary>
	/// Parses an import declaration, including the module path and import entries if specified,
	/// from the current token stream. Supports both module and selective imports with optional aliases.
	/// </summary>
	/// <returns>
	/// An <see cref="ImportDeclaration"/> object representing the parsed import declaration,
	/// which includes the module path, any selective import items with aliases, and the token span.
	/// </returns>
	/// <exception cref="ParserError">
	/// Thrown if the 'import' keyword is missing, if the expected module path is invalid,
	/// or if the import statement ends incorrectly with missing delimiters or syntax errors.
	/// </exception>
	private ImportDeclaration ParseImport() {
		var start = _current.Span;

		ExpectKeyword(Keyword.Import); // consume 'import', _current = first path segment
		var path = new List<string>();
		path.Add(ExpectImportIdentifier());

		while (CheckOperator(Operator.Dot) && (PeekAt(1).Type == TokenType.Identifier || PeekAt(1).Operator == Operator.Star)) {
			if (PeekAt(1).Operator == Operator.Star) {
				path.Add(PeekAt(1).Literal);
				Advance(); // eat .
				Advance(); // eat *
				break;
			}

			Advance(); // consume '.', _current = next segment
			path.Add(ExpectIdentifier());
		}

		ImportDeclaration.ImportItems items;
		if (CheckOperator(Operator.ColonColon)) {
			Advance(); // consume '::', _current = '{'
			ExpectOperator(Operator.LBrace); // consume '{', _current = first entry

			var entries = new List<ImportEntry>();
			do {
				var entryStart = _current.Span;
				var name = ExpectIdentifier();

				string? alias = null;
				if (CheckKeyword(Keyword.As)) {
					Advance(); // consume 'as', _current = alias identifier
					alias = ExpectIdentifier();
				}

				entries.Add(new ImportEntry(name, alias, TokenSpan.Merge(entryStart, Previous().Span)));

				if (!ConsumeOp(Operator.Comma)) break;
			} while (true);

			ExpectOperator(Operator.RBrace); // consume '}'
			items = new ImportDeclaration.ImportItems.Selective(entries);
		}
		else {
			items = new ImportDeclaration.ImportItems.Module();
		}

		var end = ExpectSemiColon();
		return new ImportDeclaration(path, items, TokenSpan.Merge(start, end));
	}

	private TypeDeclaration ParseTypeDeclaration() {
		var visibility = ParseVisibility();
		var modifiers = ParseTopModifiers();
		return _current.Keyword switch {
			Keyword.Class => new TypeDeclaration.Class(ParseClassDeclaration(visibility, modifiers)),
			Keyword.Struct => new TypeDeclaration.Struct(ParseStructDeclaration(visibility)),
			Keyword.Enum => new TypeDeclaration.Enum(ParseEnumDeclaration(visibility)),
			Keyword.Interface => new TypeDeclaration.Interface(ParseInterfaceDeclaration(visibility)),
			Keyword.Trait => new TypeDeclaration.Trait(ParseTraitDeclaration(visibility)),
			_ => throw ParserError.InvalidTopLevelDecl.WithMessage($"invalid top-level declaration '{_current.Lexeme}'").WithSpan(_current.Span).Render()
		};
	}

	private ClassDeclaration ParseClassDeclaration(Visibility visibility, List<ClassModifiers> modifiers, bool nested = false) {
		var start = _current.Span;
		ExpectKeyword(Keyword.Class); // consume 'class'

		// Identifier resolution:
		//   - nested: identifier is REQUIRED in source (no filename fallback).
		//   - top-level: optional identifier; if present must match _currentFileName (case-sensitive).
		string name;
		if (nested) {
			name = ExpectIdentifier();
		}
		else if (_current.Type == TokenType.Identifier) {
			var explicitName = _current.Lexeme;
			if (explicitName != _currentFileName)
				throw ParserError.ClassNameMismatch.WithMessage($"top-level class identifier '{explicitName}' does not match filename '{_currentFileName}' (case sensitive)").WithSpan(_current.Span).Render();
			Advance();
			name = explicitName;
		}
		else {
			name = _currentFileName;
		}

		// Track the class being parsed so member bodies (constructor detection) can match
		// `name == _currentClassName` instead of relying on the filename.
		var previousClassName = _currentClassName;
		_currentClassName = name;
		try {
			var parameters = new List<Parameter>();
			// Nested classes REQUIRE the primary-ctor parens; top-level may omit them.
			if (nested) {
				ExpectOperator(Operator.LParen);
				parameters = ParseParameters();
				ExpectOperator(Operator.RParen);
			}
			else if (CheckOperator(Operator.LParen)) {
				Advance();
				parameters = ParseParameters();
				ExpectOperator(Operator.RParen);
			}

			var extends = CheckExtensions();
			var implementors = CheckImplementors();

			ExpectOperator(Operator.LBrace);
			var members = ParseMembers();
			ExpectOperator(Operator.RBrace);

			return new ClassDeclaration(visibility, modifiers, name, parameters, extends, implementors, members, TokenSpan.Merge(start, Previous().Span));
		}
		finally {
			_currentClassName = previousClassName;
		}
	}

	private StructDeclaration ParseStructDeclaration(Visibility visibility, bool nested = false) {
		var start = _current.Span;
		ExpectKeyword(Keyword.Struct);
		var name = ResolveTypeDeclName(nested);
		var previousClassName = _currentClassName;
		_currentClassName = name;
		try {
			ExpectOperator(Operator.LBrace);
			var members = ParseMembers();
			ExpectOperator(Operator.RBrace);
			return new StructDeclaration(visibility, name, members, TokenSpan.Merge(start, Previous().Span));
		}
		finally {
			_currentClassName = previousClassName;
		}
	}

	private EnumDeclaration ParseEnumDeclaration(Visibility visibility, bool nested = false) {
		var start = _current.Span;
		ExpectKeyword(Keyword.Enum);
		var name = ResolveTypeDeclName(nested);
		ExpectOperator(Operator.LBrace);
		var cases = new List<EnumDeclaration.EnumCase>();
		while (!CheckOperator(Operator.RBrace) && !AtEof()) {
			var caseStart = _current.Span;
			var caseName = ExpectIdentifier();
			Expression? discriminant = null;
			var payload = new List<TypeExpression>();
			if (ConsumeOp(Operator.Equal)) {
				// TODO: parse discriminant expression
				while (!CheckOperator(Operator.Comma) && !CheckOperator(Operator.RBrace) && !AtEof()) Advance();
			}
			else if (CheckOperator(Operator.LParen)) {
				Advance(); // consume '('
				payload.Add(ParseTypeExpression());
				while (ConsumeOp(Operator.Comma)) payload.Add(ParseTypeExpression());
				ExpectOperator(Operator.RParen);
			}

			cases.Add(new EnumDeclaration.EnumCase(caseName, discriminant, payload, TokenSpan.Merge(caseStart, Previous().Span)));
			if (!ConsumeOp(Operator.Comma)) break;
		}

		ExpectOperator(Operator.RBrace);
		return new EnumDeclaration(visibility, name, cases, TokenSpan.Merge(start, Previous().Span));
	}

	private InterfaceDeclaration ParseInterfaceDeclaration(Visibility visibility, bool nested = false) {
		var start = _current.Span;
		ExpectKeyword(Keyword.Interface);
		var name = ResolveTypeDeclName(nested);
		var previousClassName = _currentClassName;
		_currentClassName = name;
		try {
			ExpectOperator(Operator.LBrace);
			var members = ParseMembers();
			ExpectOperator(Operator.RBrace);
			return new InterfaceDeclaration(visibility, name, members, TokenSpan.Merge(start, Previous().Span));
		}
		finally {
			_currentClassName = previousClassName;
		}
	}

	private TraitDeclaration ParseTraitDeclaration(Visibility visibility, bool nested = false) {
		var start = _current.Span;
		ExpectKeyword(Keyword.Trait);
		var name = ResolveTypeDeclName(nested);
		var previousClassName = _currentClassName;
		_currentClassName = name;
		try {
			ExpectOperator(Operator.LBrace);
			var members = ParseMembers();
			ExpectOperator(Operator.RBrace);
			return new TraitDeclaration(visibility, name, members, TokenSpan.Merge(start, Previous().Span));
		}
		finally {
			_currentClassName = previousClassName;
		}
	}

	// Resolve the name of a struct/enum/interface/trait declaration. For nested declarations
	// the identifier is REQUIRED in source. For top-level declarations the identifier is
	// optional; if present it must match `_currentFileName` (case sensitive); when omitted,
	// the filename is used.
	private string ResolveTypeDeclName(bool nested) {
		if (nested) return ExpectIdentifier();
		if (_current.Type == TokenType.Identifier) {
			var explicitName = _current.Lexeme;
			if (explicitName != _currentFileName)
				throw ParserError.ClassNameMismatch.WithMessage($"top-level type identifier '{explicitName}' does not match filename '{_currentFileName}' (case sensitive)").WithSpan(_current.Span).Render();
			Advance();
			return explicitName;
		}
		return _currentFileName;
	}

	/// <summary>
	/// Checks if the current class declaration extends another class by verifying the presence of a colon operator.
	/// If the colon operator is found, it advances the token stream and captures the identifier of the parent class.
	/// </summary>
	/// <returns>
	/// A string containing the identifier of the parent class being extended, or null if the current class does not extend any other class.
	/// </returns>
	/// <exception cref="ParserError">
	/// Thrown if an identifier is expected after the colon operator but is not found.
	/// </exception>
	private string? CheckExtensions() {
		if (CheckOperator(Operator.Colon)) {
			Advance(); // consume 'extends', _current = parent name
			return ExpectIdentifier();
		}

		return null;
	}

	/// <summary>
	/// Identifies and collects the list of implemented traits or interfaces specified
	/// for a class declaration. If no implementors are defined, an empty list is returned.
	/// </summary>
	/// <returns>
	/// A list of strings representing the names of the implemented traits or interfaces.
	/// Returns an empty list if no implementors are present.
	/// </returns>
	private List<string> CheckImplementors() {
		var implementors = new List<string>();
		if (!CheckOperator(Operator.Arrow)) return implementors;

		Advance(); // consume 'is', _current = first implementor
		implementors.Add(ExpectIdentifier());
		while (ConsumeOp(Operator.Comma)) {
			implementors.Add(ExpectIdentifier());
		}

		return implementors;
	}

	/// <summary>
	/// Parses member declarations within a class, struct, interface, or trait context.
	/// Handles multiple member types such as constructors, constants, methods, and fields,
	/// along with their annotations and visibility settings.
	/// </summary>
	/// <returns>
	/// A list of <see cref="MemberDeclaration"/> objects representing the parsed members.
	/// </returns>
	/// <exception cref="ParserError">
	/// Thrown if a syntax error occurs while parsing member declarations or if an unexpected token
	/// is encountered before the closing brace of the declaration context.
	/// </exception>
	private List<MemberDeclaration> ParseMembers() {
		var members = new List<MemberDeclaration>();
		while (!CheckOperator(Operator.RBrace) && !AtEof()) {
			var annotations = ParseAnnotations();
			var visibility = ParseMemberVisibility();
			var modifiers = ParseFunctionModifiers();

			if (CheckKeyword(Keyword.Const)) {
				var isStatic = modifiers.Contains(FunctionModifiers.Static);
				members.Add(new MemberDeclaration.Const(ParseConstDecl(visibility, isStatic)));
			}
			else if (CheckKeyword(Keyword.Func)) {
				members.Add(new MemberDeclaration.Method(ParseMethodDecl(annotations, visibility, modifiers)));
			}
			else if (CheckKeyword(Keyword.Fragment)) {
				members.Add(new MemberDeclaration.Fragment(ParseFragmentDecl(annotations, visibility, modifiers)));
			}
			else if (CheckOperator(Operator.Tilde)) {
				// DESTRUCTOR
				Advance(); // consume '~'
				members.Add(new MemberDeclaration.Destructor(ParseDestructorDecl(annotations, visibility)));
			}
			else if (CheckKeyword(Keyword.Class) || CheckKeyword(Keyword.Struct)
			         || CheckKeyword(Keyword.Enum) || CheckKeyword(Keyword.Interface)
			         || CheckKeyword(Keyword.Trait)) {
				// NESTED TYPE: visibility/modifiers already parsed; the nested-type helper
				// handles the kind dispatch and identifier-required path.
				members.Add(new MemberDeclaration.NestedType(ParseNestedTypeDeclaration(visibility, modifiers)));
			}
			else if (_current.Type == TokenType.Identifier && _current.Literal == _currentClassName) {
				// CONSTRUCTOR — name matches the class currently being parsed (filename for
				// top-level, explicit identifier for nested).
				Advance(); // consume constructor name; ParseConstructorDecl starts at '(' or '{'
				members.Add(new MemberDeclaration.Constructor(ParseConstructorDecl(annotations, visibility)));
			}
			else {
				members.Add(new MemberDeclaration.Field(ParseFieldDecl(annotations, visibility)));
			}
		}

		return members;
	}

	// Parse a nested type declaration. The kind keyword (class/struct/enum/interface/trait)
	// is _current; the dispatcher peeks but does NOT consume it — each kind's parser
	// consumes its own keyword. Visibility / modifiers are already parsed by ParseMembers.
	// The kind's parser is invoked with a sentinel non-null `nestedName` (the literal
	// "<nested>") which puts it into nested mode: identifier becomes required, filename
	// comparison is skipped, primary-ctor parens become required where applicable.
	// The actual identifier is then read via the parser's own identifier-acquisition path.
	private TypeDeclaration ParseNestedTypeDeclaration(Visibility visibility, List<FunctionModifiers> _) {
		// Dispatch on the kind keyword. Each parser consumes its own keyword and identifier.
		// We pass a SENTINEL (empty string) as `nestedName` to flag nested-mode without
		// substituting a name — the parser still reads the explicit identifier from source.
		var kind = _current.Keyword;
		var modifiers = new List<ClassModifiers>(); // top-level modifiers not honored on nested in v1
		return kind switch {
			Keyword.Class => new TypeDeclaration.Class(ParseClassDeclaration(visibility, modifiers, nested: true)),
			Keyword.Struct => new TypeDeclaration.Struct(ParseStructDeclaration(visibility, nested: true)),
			Keyword.Enum => new TypeDeclaration.Enum(ParseEnumDeclaration(visibility, nested: true)),
			Keyword.Interface => new TypeDeclaration.Interface(ParseInterfaceDeclaration(visibility, nested: true)),
			Keyword.Trait => new TypeDeclaration.Trait(ParseTraitDeclaration(visibility, nested: true)),
			_ => throw ParserError.InvalidTopLevelDecl.WithMessage($"unexpected nested-type keyword '{kind}'").WithSpan(_current.Span).Render()
		};
	}

	/// <summary>
	/// Parses a list of annotations from the current token stream.
	/// An annotation is identified by the '@' operator, followed by an identifier or meta token.
	/// Each parsed annotation is stored as a <see cref="TraitAnnotation"/> with its name, optional arguments, and token span.
	/// </summary>
	/// <returns>
	/// A list of <see cref="TraitAnnotation"/> objects representing the annotations parsed from the token stream.
	/// </returns>
	/// <exception cref="ParserError">
	/// Thrown if an invalid token is encountered after the '@' operator, such as a non-identifier or non-meta token.
	/// </exception>
	private List<TraitAnnotation> ParseAnnotations() {
		var annotations = new List<TraitAnnotation>();
		while (CheckOperator(Operator.At)) {
			var start = _current.Span;
			Advance(); // consume '@', _current = annotation name
			if (_current.Type != TokenType.Identifier && _current.Type != TokenType.Meta)
				throw ParserError.ExpectedIdentifier.WithSpan(_current.Span).Render();
			var name = _current.Literal;
			Advance();

			var args = new List<(string, Expression)>();
			if (CheckOperator(Operator.LParen)) {
				Advance(); // consume '('
				if (!CheckOperator(Operator.RParen)) {
					args.Add(("", ExpressionParser.ParseExpression()));
					while (CheckOperator(Operator.Comma)) {
						Advance();
						args.Add(("", ExpressionParser.ParseExpression()));
					}
				}

				ExpectOperator(Operator.RParen);
			}

			annotations.Add(new TraitAnnotation(name, args, TokenSpan.Merge(start, Previous().Span)));
		}

		return annotations;
	}

	/// <summary>
	/// Parses the member visibility modifier from the current token stream.
	/// If no visibility modifier is explicitly specified, defaults to <see cref="Visibility.Private"/>.
	/// </summary>
	/// <returns>
	/// A <see cref="Visibility"/> value representing the member's visibility.
	/// </returns>
	private Visibility ParseMemberVisibility() {
		if (CheckKeyword(Keyword.Public)) {
			Advance();
			return Visibility.Public;
		}

		if (CheckKeyword(Keyword.Private)) {
			Advance();
			return Visibility.Private;
		}

		if (CheckKeyword(Keyword.Internal)) {
			Advance();
			return Visibility.Internal;
		}

		return Visibility.Private;
	}

	/// <summary>
	/// Parses a field declaration, including its annotations, visibility, optional modifiers,
	/// type, name, and an optional initializer. Ensures that the parsed declaration is syntactically correct
	/// and spans the correct token range.
	/// </summary>
	/// <param name="annotations">
	/// A list of <see cref="TraitAnnotation"/> objects that describe additional metadata applied to the field.
	/// </param>
	/// <param name="visibility">
	/// The visibility level of the field, represented as a <see cref="Visibility"/> enum.
	/// </param>
	/// <returns>
	/// A <see cref="FieldDeclaration"/> object containing metadata, syntax components,
	/// and token span for the parsed field declaration.
	/// </returns>
	private FieldDeclaration ParseFieldDecl(List<TraitAnnotation> annotations, Visibility visibility) {
		var start = _current.Span;

		FieldModifiers? modifier = null;
		if (CheckKeyword(Keyword.Atomic)) {
			modifier = FieldModifiers.Atomic;
			Advance();
		}

		var type = ParseTypeExpression();
		var name = ExpectIdentifier();

		Expression? initializer = null;
		if (ConsumeOp(Operator.Equal))
			initializer = ExpressionParser.ParseExpression();

		var end = ExpectSemiColon();
		return new FieldDeclaration(annotations, visibility, modifier, type, name, initializer, null, TokenSpan.Merge(start, end));
	}

	/// <summary>
	/// Parses a constant declaration, including its visibility, static modifier, type, name,
	/// and token span, from the current token stream.
	/// Validates the syntax of the constant declaration, ensuring presence of required keywords,
	/// type, and initializer pattern.
	/// </summary>
	/// <param name="visibility">The visibility modifier of the constant declaration (e.g., Public, Private).</param>
	/// <param name="isStatic">A boolean indicating whether the constant is declared as static.</param>
	/// <returns>
	/// A <see cref="ConstantDeclaration"/> object containing details of the parsed constant declaration,
	/// including its visibility, static modifier, type, name, and associated metadata.
	/// </returns>
	private ConstantDeclaration ParseConstDecl(Visibility visibility, bool isStatic) {
		var start = _current.Span;
		ExpectKeyword(Keyword.Const);

		var type = ParseTypeExpression();
		var name = ExpectIdentifier();
		ExpectOperator(Operator.Equal);

		// TODO: parse value expression; skip to ';' for now
		while (!CheckOperator(Operator.Semicolon) && !AtEof()) Advance();

		var end = ExpectSemiColon();
		return new ConstantDeclaration(visibility, isStatic, type, name, null, null, TokenSpan.Merge(start, end));
	}

	/// <summary>
	/// Parses a method declaration, including its annotations, visibility, modifiers, name, parameters, return type,
	/// optional "maybe" clause, and body, from the current token stream.
	/// Validates the syntax of the method declaration and ensures its proper format.
	/// </summary>
	/// <param name="annotations">
	/// A list of annotations applied to the method, representing additional metadata or traits.
	/// </param>
	/// <param name="visibility">
	/// The visibility modifier of the method, such as public, private, or internal.
	/// </param>
	/// <returns>
	/// A <see cref="MethodDeclaration"/> object containing the annotations, visibility, modifiers, name, parameters,
	/// return type, optional "maybe" clause, body, and token span of the parsed method declaration.
	/// </returns>
	/// <exception cref="ParserError">
	/// Thrown if required tokens, such as the function keyword ('func'), method name, parentheses, or semicolon, are missing
	/// or if the syntax of the method declaration is invalid.
	/// </exception>
	private MethodDeclaration ParseMethodDecl(List<TraitAnnotation> annotations, Visibility visibility, List<FunctionModifiers> modifiers) {
		var start = _current.Span;

		ExpectKeyword(Keyword.Func);
		var name = ExpectIdentifier();

		ExpectOperator(Operator.LParen);
		var parameters = ParseParameters();
		ExpectOperator(Operator.RParen);

		TypeExpression returnType;
		if (CheckOperator(Operator.Colon)) {
			Advance(); // consume ':'
			returnType = ParseTypeExpression();
		}
		else {
			returnType = new TypeExpression(new BaseType.Void(), false, null, _current.Span);
		}

		var maybeClause = new List<TypeExpression>();
		if (CheckKeyword(Keyword.Maybe)) {
			Advance(); // consume 'maybe'
			maybeClause.Add(ParseTypeExpression());
			while (ConsumeOp(Operator.Comma)) {
				maybeClause.Add(ParseTypeExpression());
			}
		}

		Block? body = null;
		if (CheckOperator(Operator.LBrace)) {
			body = ParseBlock();
		}
		else {
			ExpectSemiColon(); // abstract / prototype method
		}

		return new MethodDeclaration(annotations, visibility, modifiers, name, parameters, returnType, maybeClause, body, TokenSpan.Merge(start, Previous().Span));
	}

	/// <summary>
	/// Parses a fragment declaration, including its annotations, visibility, modifiers, name, parameters, return type, optional "maybe" clause,
	/// and body or prototype. Validates the fragment's structure and syntax within the current token stream.
	/// </summary>
	/// <param name="annotations">
	/// The list of trait annotations associated with the fragment declaration.
	/// </param>
	/// <param name="visibility">
	/// The visibility modifier of the fragment (e.g., public, private, or internal).
	/// </param>
	/// <param name="modifiers">
	/// The list of additional function modifiers applied to the fragment, such as const or prototype.
	/// </param>
	/// <returns>
	/// A <see cref="FragmentDeclaration"/> object representing the parsed fragment, containing all associated data including the name,
	/// parameters, return type, optional clauses, body, and token span.
	/// </returns>
	/// <exception cref="ParserError">
	/// Thrown if the fragment declaration contains invalid tokens, missing required components such as the identifier, or
	/// if any syntax rules are violated during parsing.
	/// </exception>
	private FragmentDeclaration ParseFragmentDecl(List<TraitAnnotation> annotations, Visibility visibility, List<FunctionModifiers> modifiers) {
		var start = _current.Span;

		ExpectKeyword(Keyword.Fragment);
		var name = ExpectIdentifier();

		ExpectOperator(Operator.LParen);
		var parameters = ParseParameters();
		ExpectOperator(Operator.RParen);

		TypeExpression returnType;
		if (CheckOperator(Operator.Colon)) {
			Advance(); // consume ':'
			returnType = ParseTypeExpression();
		}
		else {
			returnType = new TypeExpression(new BaseType.Void(), false, null, _current.Span);
		}

		var maybeClause = new List<TypeExpression>();
		if (CheckKeyword(Keyword.Maybe)) {
			Advance(); // consume 'maybe'
			maybeClause.Add(ParseTypeExpression());
			while (ConsumeOp(Operator.Comma)) {
				maybeClause.Add(ParseTypeExpression());
			}
		}

		Block? body = null;
		if (CheckOperator(Operator.LBrace)) {
			body = ParseBlock(); // TODO: replace with ParseBlock() when statement parsing is implemented
		}
		else {
			ExpectSemiColon(); // abstract / prototype method
		}

		return new FragmentDeclaration(annotations, visibility, modifiers, name, parameters, returnType, maybeClause, body, TokenSpan.Merge(start, Previous().Span));
	}

	/// <summary>
	/// Parses a list of function modifiers from the current token stream.
	/// Supports recognizing specific function-level modifiers such as `const` and `prototype`.
	/// Advances the token stream upon identifying valid modifiers.
	/// </summary>
	/// <returns>
	/// A list of <see cref="FunctionModifiers"/> representing the parsed function modifiers.
	/// </returns>
	private List<FunctionModifiers> ParseFunctionModifiers() {
		var modifiers = new List<FunctionModifiers>();
		while (true) {
			if (CheckKeyword(Keyword.Static) && !modifiers.Contains(FunctionModifiers.Static)) {
				modifiers.Add(FunctionModifiers.Static);
				Advance();
			}
			else if (CheckKeyword(Keyword.Const) && !modifiers.Contains(FunctionModifiers.Const)) {
				modifiers.Add(FunctionModifiers.Const);
				Advance();
			}
			else if (CheckKeyword(Keyword.Prototype) && !modifiers.Contains(FunctionModifiers.Prototype)) {
				modifiers.Add(FunctionModifiers.Prototype);
				Advance();
			}
			else break;
		}

		return modifiers;
	}

	/// <summary>
	/// Parses a constructor declaration, including its annotations, visibility, parameters,
	/// and body, from the current token stream.
	/// </summary>
	/// <param name="annotations">
	/// A list of <see cref="TraitAnnotation"/> objects representing traits or metadata
	/// applied to the constructor.
	/// </param>
	/// <param name="visibility">
	/// The <see cref="Visibility"/> modifier specifying the access level of the constructor.
	/// </param>
	/// <returns>
	/// A <see cref="ConstructorDeclaration"/> object that encapsulates the parsed annotations,
	/// visibility, parameters, body, and its corresponding token span.
	/// </returns>
	private ConstructorDeclaration ParseConstructorDecl(List<TraitAnnotation> annotations, Visibility visibility) {
		var start = _current.Span;
		var parameters = new List<Parameter>();

		// This is optional, as the primary parameters are handled at the head.
		if (ExpectOperator(Operator.LParen, false)) {
			parameters = ParseParameters();
			ExpectOperator(Operator.RParen);
		}

		var body = ParseBlock();

		return new ConstructorDeclaration(annotations, visibility, parameters, body, TokenSpan.Merge(start, Previous().Span));
	}

	private DestructorDeclaration ParseDestructorDecl(List<TraitAnnotation> annotations, Visibility visibility) {
		var start = _current.Span;
		var name = ExpectIdentifier();
		// Match the destructor name against the class CURRENTLY being parsed — for top-level
		// classes that's `_currentFileName`, for nested classes it's the explicit identifier.
		if (name != _currentClassName) throw ParserError.InvalidDestructorName.WithMessage($"Got {name}, expected {_currentClassName}").WithSpan(_current.Span).Render();

		// Optional empty parens: ~Name() {} is equivalent to ~Name {}
		if (CheckOperator(Operator.LParen)) {
			Advance(); // consume '('
			ExpectOperator(Operator.RParen);
		}

		var body = ParseBlock();
		return new DestructorDeclaration(visibility, body, TokenSpan.Merge(start, Previous().Span));
	}

	/// <summary>
	/// Parses a block of statements enclosed within curly braces ('{', '}'), ensuring proper syntax and structure.
	/// </summary>
	/// <returns>
	/// A <see cref="Block"/> object containing a list of parsed statements and the combined token span of the block.
	/// </returns>
	/// <exception cref="ParserError">
	/// Thrown if the opening or closing curly brace is missing or if a syntax error occurs within the block.
	/// </exception>
	internal Block ParseBlock() {
		var start = _current.Span;
		ExpectOperator(Operator.LBrace); // consume '{', _current = first token inside

		var statements = new List<Stmt>();
		while (!CheckOperator(Operator.RBrace) && !AtEof()) {
			statements.Add(ParseStatement());
		}

		ExpectOperator(Operator.RBrace);
		var end = Previous().Span;
		return new Block(statements, TokenSpan.Merge(start, end));
	}

	internal Stmt ParseStatement() => StatementParser.ParseStatement();

	/// <summary>
	/// Parses a list of parameters from the current token stream within a method, constructor,
	/// or function declaration, extracting their types, names, and default values, if present.
	/// </summary>
	/// <returns>
	/// A list of <see cref="Parameter"/> objects, where each parameter encapsulates its type,
	/// name, optional default expression, and token span in the source code.
	/// </returns>
	internal List<Parameter> ParseParameters() {
		// Called after '(' has been consumed; _current = first param type or ')'
		var parameters = new List<Parameter>();
		if (!CheckOperator(Operator.RParen)) {
			parameters.Add(ParseParameter());
			while (ConsumeOp(Operator.Comma)) {
				parameters.Add(ParseParameter());
			}
		}

		return parameters;
	}

	/// <summary>
	/// Parses a parameter from the current token stream, extracting its type, name, and token span.
	/// </summary>
	/// <returns>
	/// A <see cref="Parameter"/> object representing a function or method parameter,
	/// including its type, name, and associated token span in the source code.
	/// </returns>
	/// <exception cref="ParserError">
	/// Thrown if the token stream does not contain a valid parameter declaration or an expected token is missing.
	/// </exception>
	internal Parameter ParseParameter() {
		var start = _current.Span;
		var type = ParseTypeExpression();
		var name = ExpectIdentifier();
		// Default values require expression parsing; deferred until ParseExpression() is implemented.
		return new Parameter(type, name, null, TokenSpan.Merge(start, Previous().Span));
	}

	/// <summary>
	/// Parses a type expression from the current token stream, including support for arrays, tuples, nullable types,
	/// and ownership modifiers like transfer or mutable borrow.
	/// </summary>
	/// <returns>A parsed <see cref="TypeExpression"/> representing the type information in the token stream.</returns>
	/// <exception cref="ParserError">
	/// Thrown when the token stream does not contain a valid type expression or an expected token is missing.
	/// </exception>
	internal TypeExpression ParseTypeExpression() {
		var start = _current.Span;
		BaseType baseType;

		// Arrays with [] postfix
		if (CheckOperator(Operator.LBracket)) {
			// Array: [ElementType]
			Advance(); // consume '['
			var elementType = ParseTypeExpression();
			ExpectOperator(Operator.RBracket);
			baseType = new BaseType.Array(elementType);
		}
		else if (CheckOperator(Operator.LParen)) {
			// Tuple: (T1, T2, ...)
			Advance(); // consume '('
			var elements = new List<TypeExpression>();
			elements.Add(ParseTypeExpression());
			while (ConsumeOp(Operator.Comma)) {
				elements.Add(ParseTypeExpression());
			}

			ExpectOperator(Operator.RParen);
			baseType = new BaseType.Tuple(elements);
		}
		else if (CheckKeyword(Keyword.Void)) {
			Advance();
			baseType = new BaseType.Void();
		}
		else if (CheckKeyword(Keyword.Any)) {
			Advance();
			baseType = new BaseType.Any();
		}
		else if (_current.Type == TokenType.Identifier || _current.Type == TokenType.Keyword) {
			var typeName = _current.Lexeme;
			Advance();
			if (CheckOperator(Operator.Less)) {
				// Generic: Name<T1, T2>
				Advance(); // consume '<'
				var args = new List<TypeExpression>();
				args.Add(ParseTypeExpression());
				while (ConsumeOp(Operator.Comma)) {
					args.Add(ParseTypeExpression());
				}

				ExpectOperator(Operator.Greater);
				baseType = new BaseType.Generic(typeName, args);
			}
			else {
				baseType = new BaseType.Named(typeName);
			}
		}
		else {
			throw ParserError.ExpectedIdentifier.WithMessage($"expected type, got '{_current.Lexeme}'").WithSpan(_current.Span).Render();
		}

		// Postfix array syntax: type[]  (repeatable: type[][])
		while (CheckOperator(Operator.LBracket) && PeekAt(1).Operator == Operator.RBracket) {
			Advance(); // consume '['
			Advance(); // consume ']'
			baseType = new BaseType.Array(new TypeExpression(baseType, false, null, TokenSpan.Merge(start, Previous().Span)));
		}

		var nullable = false;
		if (CheckOperator(Operator.Question)) {
			nullable = true;
			Advance();
		}

		// Postfix ownership annotation: `Type!` transfers ownership to the callee, `Type&`
		// is a mutable borrow. Plain `Type` is the implicit (read-only) borrow. Postfix
		// position matches the user-visible spec; it's parsed last so the modifier sticks
		// to the fully-formed type (after array suffixes, after nullable).
		OwnershipModifier? ownership = null;
		if (CheckOperator(Operator.Not)) {
			ownership = OwnershipModifier.Transfer;
			Advance();
		}
		else if (CheckOperator(Operator.And)) {
			ownership = OwnershipModifier.MutBorrow;
			Advance();
		}

		return new TypeExpression(baseType, nullable, ownership, TokenSpan.Merge(start, Previous().Span));
	}

	/// <summary>
	/// Parses the visibility modifier of a declaration from the current token.
	/// </summary>
	/// <returns>The visibility modifier as a <see cref="Visibility"/> enum.</returns>
	/// <exception cref="ParserError">
	/// Thrown when the current token does not represent a valid visibility modifier.
	/// </exception>
	private Visibility ParseVisibility() {
		switch (_current.Keyword) {
			case Keyword.Public:
				Advance();
				return Visibility.Public;
			case Keyword.Private:
				// Top-level `private` is rejected: top-level decls live in a module and
				// `internal` (same-module access) is the most-restrictive sensible default.
				throw ParserError.InvalidVisibilityModifier.WithMessage($"top level declarations cannot be {Keywords.GetStringFromKeyword(Keyword.Private)}").WithSpan(_current.Span).Render();
			case Keyword.Internal:
				Advance();
				return Visibility.Internal;
			// No keyword token, or a keyword that starts the rest of the declaration —
			// visibility was simply omitted, default to Internal without consuming.
			case null:
			case Keyword.Class:
			case Keyword.Struct:
			case Keyword.Enum:
			case Keyword.Interface:
			case Keyword.Trait:
			case Keyword.Const:
			case Keyword.Abstract:
				return Visibility.Internal;
			default:
				throw ParserError.InvalidVisibilityModifier.WithMessage($"invalid visibility modifier '{_current.Lexeme}'").WithSpan(_current.Span).Render();
		}
	}

	/// <summary>
	/// Parses and collects top-level modifiers applied to a type declaration.
	/// Recognizes and validates modifiers such as "const" or "abstract" from the current token stream.
	/// </summary>
	/// <returns>
	/// A list of <see cref="ClassModifiers"/> representing the valid modifiers applied
	/// to the top-level type declaration.
	/// </returns>
	private List<ClassModifiers> ParseTopModifiers() {
		var modifiers = new List<ClassModifiers>();

		if (CheckKeyword(Keyword.Const)) {
			modifiers.Add(ClassModifiers.Const);
			Advance();
		}

		if (CheckKeyword(Keyword.Abstract)) {
			modifiers.Add(ClassModifiers.Abstract);
			Advance();
		}

		return modifiers;
	}

	/// <summary>
	/// Ensures that the current token is a semicolon, consuming it if valid.
	/// </summary>
	/// <returns>The span of the semicolon token if it is valid.</returns>
	/// <exception cref="ParserError">Thrown when the current token is not a semicolon.</exception>
	internal TokenSpan ExpectSemiColon() {
		if (_current.Operator != Operator.Semicolon)
			throw ParserError.ExpectedSemiColon.WithSpan(_current.Span).Render();
		var span = _current.Span;
		Advance(); // consume ';'
		return span;
	}

	/// <summary>
	/// Ensures that the current token is an identifier, consuming it if valid.
	/// </summary>
	/// <returns>The string value of the identifier if the current token is a valid identifier.</returns>
	/// <exception cref="ParserError">Thrown when the current token is not an identifier.</exception>
	internal string ExpectIdentifier() {
		if (_current.Type != TokenType.Identifier)
			throw ParserError.ExpectedIdentifier.WithSpan(_current.Span).WithMessage($"expected {nameof(TokenType.Identifier)}, got '{_current.Lexeme}'").Render();
		var name = _current.Literal;
		Advance();
		return name;
	}

	/// <summary>
	/// Validates and consumes the current token as an import identifier or wildcard.
	/// Supports identifiers and the '*' operator commonly used in imports.
	/// </summary>
	/// <returns>
	/// A string representing the literal value of the current token used as an import identifier.
	/// </returns>
	/// <exception cref="ParserError">
	/// Thrown when the current token is not a valid identifier or the '*' operator,
	/// providing detailed information about the error and its location within the token span.
	/// </exception>
	private string ExpectImportIdentifier() {
		if (_current.Type != TokenType.Identifier && _current.Operator != Operator.Star)
			throw ParserError.ExpectedIdentifier.WithSpan(_current.Span).WithMessage($"expected {nameof(TokenType.Identifier)}, got '{_current.Lexeme}'").Render();
		var name = _current.Literal;
		Advance();
		return name;
	}

	/// <summary>
	/// Ensures that the current token matches the specified operator, consuming it if valid.
	/// </summary>
	/// <param name="op">The expected operator to verify against the current token.</param>
	/// <returns>True if the current token matches the specified operator and is successfully consumed.</returns>
	/// <exception cref="ParserError">Thrown when the current token does not match the expected operator.</exception>
	internal bool ExpectOperator(Operator op, bool throwError = true) {
		if (_current.Operator != op) {
			if (throwError) {
				throw ParserError.ExpectedOperator.WithMessage($"expected '{Operators.GetStringFromOperator(op)}', got '{_current.Lexeme}'").WithSpan(_current.Span).Render();
			}

			return false;
		}

		Advance();
		return true;
	}

	/// <summary>
	/// Ensures that the current token matches the specified keyword, consuming it if valid.
	/// </summary>
	/// <param name="keyword">The expected keyword to verify against the current token.</param>
	/// <returns>True if the current token matches and is successfully consumed.</returns>
	/// <exception cref="ParserError">Thrown when the current token does not match the expected keyword.</exception>
	internal bool ExpectKeyword(Keyword keyword) {
		if (_current.Keyword != keyword)
			throw ParserError.ExpectedKeyword.WithMessage($"expected '{Keywords.GetStringFromKeyword(keyword)}', got {_current.Type.ToString().ToLower()} '{_current.Lexeme}'").Render();
		Advance();
		return true;
	}

	/// <summary>
	/// Attempts to consume the specified operator from the current token if it matches.
	/// </summary>
	/// <param name="op">The operator to be consumed if it matches the current token.</param>
	/// <returns>True if the specified operator was successfully consumed; otherwise, false.</returns>
	internal bool ConsumeOp(Operator op) {
		if (_current.Operator == op) {
			Advance();
			return true;
		}

		return false;
	}

	internal bool ConsumeKeyword(Keyword keyword) {
		if (_current.Keyword == keyword) {
			Advance();
			return true;
		}

		return false;
	}

	/// <summary>
	/// Checks if the current token matches the specified keyword.
	/// </summary>
	/// <param name="keyword">The keyword to be checked against the current token.</param>
	/// <returns>True if the current token matches the specified keyword; otherwise, false.</returns>
	internal bool CheckKeyword(Keyword keyword) => _current.Keyword == keyword;

	/// <summary>
	/// Checks if the current token matches the specified operator.
	/// </summary>
	/// <param name="op">The operator to check against the current token.</param>
	/// <returns>True if the current token matches the specified operator; otherwise, false.</returns>
	internal bool CheckOperator(Operator op) => _current.Operator == op;

	/// <summary>
	/// Advances the parser's cursor to the next token in the token stream.
	/// If the end of the stream is reached, the cursor remains at the last token.
	/// </summary>
	/// <returns>The token at the new cursor position after advancing.</returns>
	internal Token Advance() {
		if (_cursor < _tokens.Count - 1) _cursor++;
		return _current = _tokens[_cursor];
	}

	/// <summary>
	/// Retrieves the previous token in the token stream relative to the current cursor position.
	/// If the cursor is at the beginning of the stream, returns the first token.
	/// </summary>
	/// <returns>The previous token in the token stream, or the first token if at the beginning of the stream.</returns>
	internal Token Previous() {
		if (_cursor - 1 > 0) return _tokens[_cursor - 1];
		return _tokens.First();
	}

	/// <summary>
	/// Retrieves the next token from the token stream without advancing the cursor.
	/// If the end of the token list is reached, returns the last token.
	/// </summary>
	/// <returns>The next token in the token stream, or the last token if at the end of the stream.</returns>
	internal Token Peek() {
		return PeekAt(1);
	}

	/// <summary>
	/// Retrieves a token at the specified offset from the current cursor position in the token stream.
	/// If the offset exceeds the bounds of the token list, the last token in the stream is returned.
	/// </summary>
	/// <param name="offset">The number of positions to move forward from the current cursor to peek at a token.</param>
	/// <returns>The token located at the specified offset, or the last token if the offset exceeds the bounds of the token list.</returns>
	internal Token PeekAt(int offset) {
		var target = _cursor + offset;
		if (target < _tokens.Count) return _tokens[target];
		return _tokens.Last();
	}

	/// <summary>
	/// Determines whether the end of the token stream has been reached.
	/// </summary>
	/// <returns>
	/// True if the current token is of type <see cref="TokenType.Eof"/>, indicating the end of the stream;
	/// otherwise, false.
	/// </returns>
	internal bool AtEof() => _current.Type == TokenType.Eof;

	/// <summary>The current token under the cursor, exposed for sub-parsers.</summary>
	internal Token Current => _current;

	/// <summary>Returns the current cursor index so a sub-parser can restore it on failure.</summary>
	internal int SaveCursor() => _cursor;

	/// <summary>Restores the cursor to a previously saved position.</summary>
	internal void RestoreTo(int pos) {
		_cursor = pos;
		_current = _tokens[pos];
	}

	/// <summary>
	/// Attempts to parse a type expression without throwing on failure.
	/// Returns <c>null</c> if the tokens at the current position do not form a valid type,
	/// leaving the cursor wherever parsing stalled (caller must <see cref="RestoreTo"/> on null).
	/// </summary>
	internal TypeExpression? TryParseTypeExpression() {
		var start = _current.Span;
		BaseType baseType;

		if (CheckOperator(Operator.LBracket)) {
			Advance();
			var elem = TryParseTypeExpression();
			if (elem == null || !CheckOperator(Operator.RBracket)) return null;
			Advance();
			baseType = new BaseType.Array(elem.Value);
		}
		else if (CheckOperator(Operator.LParen)) {
			Advance();
			var first = TryParseTypeExpression();
			if (first == null) return null;
			var elems = new List<TypeExpression> { first.Value };
			while (ConsumeOp(Operator.Comma)) {
				var next = TryParseTypeExpression();
				if (next == null) return null;
				elems.Add(next.Value);
			}

			if (!CheckOperator(Operator.RParen)) return null;
			Advance();
			baseType = new BaseType.Tuple(elems);
		}
		else if (CheckKeyword(Keyword.Void)) {
			Advance();
			baseType = new BaseType.Void();
		}
		else if (CheckKeyword(Keyword.Any)) {
			Advance();
			baseType = new BaseType.Any();
		}
		else if (_current.Type == TokenType.Identifier || _current.Type == TokenType.Keyword) {
			var name = _current.Lexeme;
			Advance();
			if (CheckOperator(Operator.Less)) {
				Advance();
				var firstArg = TryParseTypeExpression();
				if (firstArg == null) return null;
				var args = new List<TypeExpression> { firstArg.Value };
				while (ConsumeOp(Operator.Comma)) {
					var nextArg = TryParseTypeExpression();
					if (nextArg == null) return null;
					args.Add(nextArg.Value);
				}

				if (!CheckOperator(Operator.Greater)) return null;
				Advance();
				baseType = new BaseType.Generic(name, args);
			}
			else {
				baseType = new BaseType.Named(name);
			}
		}
		else {
			return null;
		}

		// Postfix array syntax: type[]
		while (CheckOperator(Operator.LBracket) && PeekAt(1).Operator == Operator.RBracket) {
			Advance();
			Advance();
			baseType = new BaseType.Array(new TypeExpression(baseType, false, null, TokenSpan.Merge(start, Previous().Span)));
		}

		var nullable = false;
		if (CheckOperator(Operator.Question)) {
			nullable = true;
			Advance();
		}

		// Postfix ownership annotation, mirroring ParseTypeExpression. `Type!` = transfer,
		// `Type&` = mutable borrow, none = implicit borrow.
		OwnershipModifier? ownership = null;
		if (CheckOperator(Operator.Not)) {
			ownership = OwnershipModifier.Transfer;
			Advance();
		}
		else if (CheckOperator(Operator.And)) {
			ownership = OwnershipModifier.MutBorrow;
			Advance();
		}

		return new TypeExpression(baseType, nullable, ownership, TokenSpan.Merge(start, Previous().Span));
	}

	/// <summary>
	/// Ensures that the end of the file (EOF) has been reached in the token stream.
	/// Throws an exception if the current token is not of type <see cref="TokenType.Eof"/>.
	/// </summary>
	/// <exception cref="ParserError">
	/// Thrown when the EOF is not reached, indicating unexpected tokens are still present.
	/// Includes information about the unexpected token and its location in the source.
	/// </exception>
	private void ExpectEof() {
		if (!AtEof())
			throw ParserError.ExpectedEof.WithMessage($"unexpected token '{_current.Lexeme}' ({_current.Type.ToString().ToLower()})").WithSpan(_current.Span).Render();
	}
}