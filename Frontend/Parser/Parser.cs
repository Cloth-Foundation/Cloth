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
using FrontEnd.Utilities;

namespace FrontEnd.Parser;

using Lexer;
using Token;

public class Parser {

	private readonly List<Token> _tokens;
	private Token _current;
	private bool _moduleDeclared;
	private int _cursor;

	public Parser(Lexer lexer) {
		_tokens = lexer.LexAll();
		_current = _tokens.First();
		_moduleDeclared = false;
		_cursor = 0;
	}
	
	public CompilationUnit Parse() {
		var start = _current.Span;

		_current = _tokens.First();
		var module = ParseModuleDecl();
		var imports = ParseImports();
		var types = new List<TypeDeclaration>();

		ExpectEof();
		var end = _current.Span;
		return new CompilationUnit(module, imports, types, TokenSpan.Merge(start, end));
	}

	/// Parses a module declaration from the token stream and constructs a corresponding
	/// ModuleDeclaration object. This method ensures that the module declaration conforms
	/// to the expected syntax, starting with the `module` keyword followed by a valid
	/// module path and ending with a semicolon. If the module declaration is either
	/// missing or incorrect, a parser error is thrown. Additionally, it enforces that
	/// only a single module declaration is allowed per file.
	/// <returns>A ModuleDeclaration object representing the parsed module.</returns>
	private ModuleDeclaration ParseModuleDecl() {
		if (_moduleDeclared) throw ParserError.ModuleAlreadyDefined.WithSpan(_current.Span).Render();
		if (_current.Type != TokenType.Keyword || _current.Keyword != Keyword.Module) throw ParserError.ModuleExpected.WithMessage($"expected 'module', got {_current.Type.ToString().ToLower()} '{_current.Lexeme}'").Render();

		var startSpan = _current.Span;
		Advance(); // eat "module"
		var path = ParseModulePath();
		if (path.Count == 0) throw ParserError.ModulePathNotDefined.WithMessage("module path not defined").Render();
		var endSpan = ExpectSemiColon();

		_moduleDeclared = true;
		return new ModuleDeclaration(path, TokenSpan.Merge(startSpan, endSpan));
	}

	/// Parses a module path from the token stream and returns it as a list of strings.
	/// The method sequentially consumes identifiers and ensures they are separated
	/// by dots (`.`). If the token expected at any position is not an identifier or
	/// the sequence is improperly formatted, an appropriate parser error is thrown.
	/// This method ensures the structure of a module path is syntactically valid and
	/// terminates once the full path is parsed.
	/// <returns>A list of strings representing the parsed module path.</returns>
	private List<string> ParseModulePath() {
		var path = new List<string>();

		ExpectIdentifier();
		path.Add(_current.Literal);

		// Allow _src to be used as a module path, but only if it's not followed by a dot
		// This will be checked by the semantic analyzer later to ensure the file location
		// is in the source (default "src") folder.
		if (path[0].ToLower().Equals("_src")) {
			if (Peek().Operator == Operator.Dot) throw ParserError.ModuleSrcInvalid.WithMessage("'_src' is only valid as its own identifier").WithSpan(_current.Span).Render();
			return path;
		}

		while (Peek().Operator == Operator.Dot) {
			Advance(); // consume the dot
			Advance(); // move to the next identifier
			ExpectIdentifier();
			path.Add(_current.Literal);
		}

		return path;
	}

	/// Parses a sequence of import declarations from the token stream. This method iterates through the
	/// tokens, identifying and processing all consecutive `import` statements using the `ParseImport` method
	/// until a non-import token is encountered. It collects the parsed import declarations into a list,
	/// ensuring their syntax is valid and conforms to the expected structure of import statements.
	/// <returns>A list of ImportDeclaration objects representing the parsed imports.</returns>
	private List<ImportDeclaration> ParseImports() {
		var imports = new List<ImportDeclaration>();
		while (CheckKeyword(Keyword.Import)) {
			imports.Add(ParseImport());
		}

		return imports;
	}

	/// Parses an import declaration from the token stream and constructs a corresponding
	/// ImportDeclaration object. This method ensures the import declaration adheres to the expected
	/// syntax, starting with the `import` keyword followed by a valid module path. Optionally, it supports
	/// selective imports specified using the `::{}` syntax. If the import declaration is invalid or
	/// incomplete, a parser error is thrown. The method also enforces the proper use of delimiters
	/// such as dots in module paths and a terminating semicolon.
	/// <returns>An ImportDeclaration object representing the parsed import declaration.</returns>
	private ImportDeclaration ParseImport() {
		var start = _current.Span;

		ExpectKeyword(Keyword.Import);
		Advance(); // consume 'import'

		var path = new List<string>();
		ExpectIdentifier();
		path.Add(_current.Literal);

		// Collect dotted path segments, stopping before :: or ;
		while (Peek().Operator == Operator.Dot && PeekAt(2).Type == TokenType.Identifier) {
			Advance(); // consume '.'
			Advance(); // move to next identifier
			ExpectIdentifier();
			path.Add(_current.Literal);
		}

		// Optional selective import: ::{ x, y as z }
		ImportDeclaration.ImportItems items;
		if (Peek().Operator == Operator.ColonColon) {
			Advance(); // consume '::'
			Advance(); // move to '{'
			ExpectOperator(Operator.LBrace);
			Advance(); // move to first entry

			var entries = new List<ImportEntry>();
			do {
				var entryStart = _current.Span;
				ExpectIdentifier();
				var name = _current.Literal;

				string? alias = null;
				if (Peek().Keyword == Keyword.As) {
					Advance(); // consume 'as'
					Advance(); // move to alias identifier
					ExpectIdentifier();
					alias = _current.Literal;
				}

				var entryEnd = _current.Span;
				entries.Add(new ImportEntry(name, alias, TokenSpan.Merge(entryStart, entryEnd)));

				if (Peek().Operator != Operator.Comma) break;
				Advance(); // consume ','
				Advance(); // move to next entry
			} while (true);

			Advance(); // move to '}'
			ExpectOperator(Operator.RBrace);
			items = new ImportDeclaration.ImportItems.Selective(entries);
		} else {
			items = new ImportDeclaration.ImportItems.Module();
		}

		var end = ExpectSemiColon();

		return new ImportDeclaration(path, items, TokenSpan.Merge(start, end));
	}

	/// Ensures the current token is of type `Operator` and specifically a semicolon (`;`).
	/// If the current token is not a semicolon, a `ParserError.ExpectedSemiColon` exception
	/// is thrown. This method is used to enforce that a semicolon is required at the
	/// current position during parsing to maintain syntax correctness.
	private TokenSpan ExpectSemiColon() {
		if (Advance().Operator != Operator.Semicolon) throw ParserError.ExpectedSemiColon.WithSpan(_current.Span).Render();
		var span = _current.Span;
		Advance(); // move past ';' so _current points at the next statement
		return span;
	}

	/// Ensures the current token is of type `Identifier`. If the current token
	/// is not an identifier, a `ParserError.ExpectedIdentifier` exception is
	/// thrown with the span of the invalid token.
	/// This method is used to enforce that an identifier is expected at the
	/// current position during parsing.
	private void ExpectIdentifier() {
		if (_current.Type != TokenType.Identifier)
			throw ParserError.ExpectedIdentifier.WithSpan(_current.Span).Render();
	}

	/// Verifies that the current token matches the expected operator and throws a parser error if it does not.
	/// This method ensures the syntactic correctness of the parsed input by comparing the current token's
	/// operator to the provided one. If the operators do not match, it raises an error with a descriptive
	/// message and the span of the token that caused the mismatch.
	/// <param name="op">The expected operator to match against the current token.</param>
	private void ExpectOperator(Operator op) {
		if (_current.Operator != op)
			throw ParserError.ExpectedOperator
				.WithMessage($"expected '{Operators.GetLexemeFromOperator(op)}', got '{_current.Lexeme}'")
				.WithSpan(_current.Span)
				.Render();
	}

	/// Ensures that the current token matches the specified keyword. If the current
	/// token's keyword does not match the provided keyword, a parser error is thrown
	/// with a detailed error message indicating the expected keyword and the actual
	/// token encountered. When the keywords match, the method returns true.
	/// <param name="keyword">The expected keyword to compare against the current token.</param>
	/// <returns>True if the current token's keyword matches the specified keyword; otherwise, an exception is thrown.</returns>
	private bool ExpectKeyword(Keyword keyword) {
		if (_current.Keyword != keyword) throw ParserError.ExpectedKeyword.WithMessage($"expected '{Keywords.GetKeywordString(keyword)}', got {_current.Type.ToString().ToLower()} '{Keywords.GetKeywordString(_current.Keyword ?? null)}'").Render();
		return _current.Keyword == keyword;
	}

	/// Checks if the current token matches the specified keyword.
	/// This method compares the `Keyword` property of the current token
	/// with the specified keyword to determine if they are equal.
	/// <param name="keyword">The keyword to compare against the current token.</param>
	/// <returns>True if the current token's keyword matches the specified keyword; otherwise, false.</returns>
	private bool CheckKeyword(Keyword keyword) {
		return _current.Keyword == keyword;
	}

	/// Advances the cursor to the next token in the sequence and updates
	/// the current token. If the cursor is already at the last token, it
	/// remains unchanged, returning the current token without advancing further.
	/// This method ensures progression through the token list during parsing.
	/// <returns>
	/// The token at the new cursor position, or the current token if the end
	/// of the list has been reached.
	/// </returns>
	private Token Advance() {
		if (_cursor < _tokens.Count - 1) _cursor++;
		return _current = _tokens[_cursor];
	}

	/// Retrieves the token immediately preceding the current cursor position.
	/// If the cursor is at the start of the token list, the first token is returned instead.
	/// This method allows backward traversal without changing the cursor position.
	/// <returns>
	/// The token immediately before the current cursor position, or the first token if
	/// the cursor is already at the beginning of the list.
	/// </returns>
	private Token Previous() {
		if (_cursor - 1 > 0) {
			return _tokens[_cursor - 1];
		}

		return _tokens.First();
	}

	/// Retrieves the next token in the sequence without advancing the cursor.
	/// This method allows inspecting the token that comes directly after the
	/// current position in the token list. If there is no token after the
	/// current one, the last token in the list is returned.
	/// <returns>
	/// The next token in the sequence, or the last token if the end of the list
	/// has been reached.
	/// </returns>
	private Token Peek() {
		if (_cursor + 1 < _tokens.Count)
			return _tokens[_cursor + 1];
		return _tokens.Last();
	}

	/// Retrieves a token from the token stream at the specified offset from the current cursor position.
	/// This method allows looking ahead or behind in the token stream without modifying the current cursor position.
	/// If the offset points beyond the bounds of the token list, it returns the last token in the list.
	/// <param name="offset">The positional offset from the current cursor. A positive value looks ahead,
	/// and a negative value looks behind in the token stream.</param>
	/// <returns>The token located at the specified offset. If the offset exceeds the bounds of the token list,
	/// the last token in the stream is returned.</returns>
	private Token PeekAt(int offset) {
		var target = _cursor + offset;
		if (target < _tokens.Count) return _tokens[target];
		return _tokens.Last();
	}

	/// Determines whether the parser has reached the end of the token stream.
	/// This method checks if the current token's type is `TokenType.Eof`,
	/// signaling that no further tokens are available to process.
	/// <returns>True if the current token is of type `Eof`; otherwise, false.</returns>
	private bool AtEof() {
		return _current.Type == TokenType.Eof;
	}

	/// Ensures that the end of the token stream has been reached. This method verifies
	/// that no additional tokens exist beyond the expected end and throws a parser
	/// error if any extra tokens are encountered. If the current token is not of type
	/// `Eof`, the method retrieves the next token and uses it to construct a detailed
	/// error message, aiding in debugging malformed input.
	/// <exception cref="ParserError">
	/// Thrown when the token stream does not end as expected, containing diagnostic
	/// information about the unexpected token.
	/// </exception>
	private void ExpectEof() {
		if (!AtEof())
			throw ParserError.ExpectedEof
				.WithMessage($"unexpected token '{_current.Lexeme}' ({_current.Type.ToString().ToLower()})")
				.WithSpan(_current.Span)
				.Render();
	}

}