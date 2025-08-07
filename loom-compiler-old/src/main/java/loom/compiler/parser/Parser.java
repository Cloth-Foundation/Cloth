package loom.compiler.parser;

import loom.compiler.ast.Stmt;
import loom.compiler.diagnostics.ErrorReporter;
import loom.compiler.diagnostics.ParseError;
import loom.compiler.parser.objects.*;
import loom.compiler.parser.precedence.PrecedenceParser;
import loom.compiler.token.Keywords;
import loom.compiler.token.Token;
import loom.compiler.token.TokenType;

import java.util.ArrayList;
import java.util.List;

public class Parser {

	private final List<Token> tokens;
	private final ErrorReporter reporter;
	private int current = 0;

	private final ClassParser classParser;
	private final ConstructorParser constructorParser;
	private final ImportParser importParser;
	private final FunctionParser functionParser;
	private final DeclarationParser declarationParser;
	private final StatementParser statementParser;
	private final BlockParser blockParser;
	private final ParameterParser parameterParser;
	private final IfStatementParser ifStatementParser;
	private final WhileStatementParser whileStatementParser;
	private final ForStatementParser forStatementParser;
	private final DoWhileStatementParser doWhileStatementParser;
	private final BreakContinueParser breakContinueParser;
	private final EnumParser enumParser;
	private final ProjectedEnumParser projectedEnumParser;
	private final StructParser structParser;

	private final PrecedenceParser precedenceParser;

	private final ScopeManager scopeManager;

	public Parser (List<Token> tokens, ErrorReporter reporter) {
		this.tokens = tokens;
		this.reporter = reporter;
		this.classParser = new ClassParser(this);
		this.constructorParser = new ConstructorParser(this);
		this.importParser = new ImportParser(this);
		this.functionParser = new FunctionParser(this);
		this.declarationParser = new DeclarationParser(this);
		this.statementParser = new StatementParser(this);
		this.blockParser = new BlockParser(this);
		this.parameterParser = new ParameterParser(this);
		this.ifStatementParser = new IfStatementParser(this);
		this.whileStatementParser = new WhileStatementParser(this);
		this.forStatementParser = new ForStatementParser(this);
		this.doWhileStatementParser = new DoWhileStatementParser(this);
		this.breakContinueParser = new BreakContinueParser(this);
		this.enumParser = new EnumParser(this);
		this.projectedEnumParser = new ProjectedEnumParser(this);
		this.structParser = new StructParser(this);

		this.precedenceParser = new PrecedenceParser(this);

		this.scopeManager = new ScopeManager();
	}

	public List<Stmt> parse () {
		List<Stmt> statements = new ArrayList<>();
		while (!isAtEnd()) {
			int before = current;
			Stmt stmt = parseDeclaration();
			if (current == before) {
				//reportError(peek(), "Parser did not advance; skipping token to prevent infinite loop");
				advance();
			}
			if (stmt != null) {
				statements.add(stmt);
			}
		}
		return statements;
	}

	public Stmt parseDeclaration () {
		try {
			// Handle scope modifiers (PUBLIC, PRIVATE, PROTECTED)
			ScopeManager.Scope scope = ScopeManager.Scope.DEFAULT;
			if (match(TokenType.KEYWORD, Keywords.Keyword.PUBLIC)) {
				scope = ScopeManager.Scope.PUBLIC;
			} else if (match(TokenType.KEYWORD, Keywords.Keyword.PRIVATE)) {
				scope = ScopeManager.Scope.PRIVATE;
			} else if (match(TokenType.KEYWORD, Keywords.Keyword.PROTECTED)) {
				scope = ScopeManager.Scope.PROTECTED;
			}

			// Handle final modifier
			boolean isFinal = false;
			if (match(TokenType.KEYWORD, Keywords.Keyword.FINAL)) {
				isFinal = true;
			}

			// Parse declarations based on the next keyword
			if (match(TokenType.KEYWORD, Keywords.Keyword.IMPORT)) {
				return getImportParser().parseImport();
			} else if (match(TokenType.KEYWORD, Keywords.Keyword.FUNC)) {
				// Functions cannot be final
				if (isFinal) {
					reportError(peek(), "Functions cannot be final");
					return null;
				}
				return getFunctionParser().parseFunction(scope);
			} else if (match(TokenType.KEYWORD, Keywords.Keyword.CLASS)) {
				return getClassParser().parseClass(scope == ScopeManager.Scope.PUBLIC, scope, isFinal);
			} else if (match(TokenType.KEYWORD, Keywords.Keyword.ENUM)) {
				return getEnumParser().parseEnumDeclaration();
			} else if (match(TokenType.KEYWORD, Keywords.Keyword.STRUCT)) {
				return getStructParser().parseStructDeclaration();
			} else if (match(TokenType.KEYWORD, Keywords.Keyword.VAR)) {
				return getDeclarationParser().parseVariable(scope, isFinal);
			} else if (scope != ScopeManager.Scope.DEFAULT) {
				// If we have a scope modifier but no valid declaration keyword
				reportError(peek(), "Expected declaration (class, func, struct, var) after scope modifier");
				return null;
			}
			
			return getStatementParser().parseStatement();
		} catch (ParseError e) {
			synchronize();
			return null;
		}
	}

	public ClassParser getClassParser () {
		return classParser;
	}

	public ConstructorParser getConstructorParser () {
		return constructorParser;
	}

	public ImportParser getImportParser () {
		return importParser;
	}

	public FunctionParser getFunctionParser () {
		return functionParser;
	}

	public DeclarationParser getDeclarationParser () {
		return declarationParser;
	}

	public StatementParser getStatementParser () {
		return statementParser;
	}

	public BlockParser getBlockParser () {
		return blockParser;
	}

	public ParameterParser getParameterParser () {
		return parameterParser;
	}

	public IfStatementParser getIfStatementParser () {
		return ifStatementParser;
	}

	public WhileStatementParser getWhileStatementParser () {
		return whileStatementParser;
	}

	public ForStatementParser getLoopStatementParser() {
		return forStatementParser;
	}

	public DoWhileStatementParser getDoWhileStatementParser () {
		return doWhileStatementParser;
	}

	public BreakContinueParser getBreakContinueParser () {
		return breakContinueParser;
	}

	public EnumParser getEnumParser () {
		return enumParser;
	}

	public ProjectedEnumParser getProjectedEnumParser () {
		return projectedEnumParser;
	}

	public StructParser getStructParser () {
		return structParser;
	}

	public PrecedenceParser getPrecedenceParser () {
		return precedenceParser;
	}

	public ScopeManager getScopeManager () {
		return scopeManager;
	}

	public ErrorReporter getErrorReporter () {
		return reporter;
	}

	public boolean match (TokenType type) {
		if (check(type)) {
			advance();
			return true;
		}
		return false;
	}

	public boolean match (TokenType type, String expectedText) {
		if (check(type) && peek().value.equals(expectedText)) {
			advance();
			return true;
		}
		return false;
	}

	public boolean match (TokenType type, Keywords.Keyword keyword) {
		return match(type, keyword.getName());
	}

	public boolean match (TokenType... types) {
		for (TokenType type : types) {
			if (check(type)) {
				advance();
				return true;
			}
		}
		return false;
	}

	public boolean check (TokenType type) {
		return !isAtEnd() && peek().type == type;
	}

	public Token consume (TokenType type, String message) {
		if (check(type)) return advance();
		reportError(peek(), message);
		throw error(); // halt current rule
	}

	public Token advance () {
		if (!isAtEnd()) current++;
		return previous();
	}

	public Token previous () {
		return tokens.get(current - 1);
	}

	public Token peek () {
		if (current >= tokens.size()) {
			// Return EOF token but ensure we don't go beyond the list
			return tokens.get(Math.min(current, tokens.size() - 1));
		}
		return tokens.get(current);
	}

	public Token peek (int offset) {
		int index = current + offset;
		if (index >= tokens.size()) {
			// Return EOF token but ensure we don't go beyond the list
			return tokens.get(Math.min(index, tokens.size() - 1));
		}
		return tokens.get(index);
	}

	public boolean isAtEnd () {
		return peek().type == TokenType.EOF;
	}

	public void reportError (Token token, String message) {
		reporter.reportError(token.getSpan(), message, token.value);
	}

	public void synchronize () {
		while (!isAtEnd()) {
			if (previous().type == TokenType.SEMICOLON) return;

			if (check(TokenType.KEYWORD)) {
				String keyword = peek().value;
				if (keyword.equals(Keywords.Keyword.CLASS.getName()) ||
						keyword.equals(Keywords.Keyword.FUNC.getName()) ||
						keyword.equals(Keywords.Keyword.VAR.getName()) ||
						keyword.equals(Keywords.Keyword.IMPORT.getName()) ||
						keyword.equals(Keywords.Keyword.RETURN.getName()) ||
						keyword.equals(Keywords.Keyword.PUBLIC.getName())) {
					return;
				}
			}

			if (check(TokenType.RBRACE)) return; // recover before ending a block
			advance();
		}
	}

	public ParseError error () {
		return new ParseError();
	}

	public ParseError error (Token token, String message) {
		return new ParseError(token.getSpan(), message, token.value);
	}
}
