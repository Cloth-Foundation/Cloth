package loom.compiler.parser.objects;

import loom.compiler.ast.Expr;
import loom.compiler.ast.Stmt;
import loom.compiler.ast.statements.BlockStmt;
import loom.compiler.ast.statements.ExpressionStmt;
import loom.compiler.ast.statements.ReturnStmt;
import loom.compiler.diagnostics.ParseError;
import loom.compiler.parser.Parser;
import loom.compiler.token.Keywords;
import loom.compiler.token.Token;
import loom.compiler.token.TokenSpan;
import loom.compiler.token.TokenType;

public class StatementParser {

	private final Parser parser;

	public StatementParser(Parser parser) {
		this.parser = parser;
	}

	public Stmt parseStatement () {
		try {
			if (parser.match(TokenType.LBRACE)) {
				return new BlockStmt(parser.getBlockParser().parseBlock(), parser.previous().getSpan());
			}

			if (parser.match(TokenType.KEYWORD, Keywords.Keyword.RETURN)) {
				Token returnToken = parser.previous();
				Expr value = null;
				if (!parser.check(TokenType.SEMICOLON)) {
					value = parser.getPrecedenceParser().parseExpression();
				}
				Token semicolon = parser.consume(TokenType.SEMICOLON, "Expected ';' after return");
				TokenSpan span = returnToken.getSpan().merge(semicolon.getSpan());
				return new ReturnStmt(value, span);
			}

			// Control flow statements
			if (parser.match(TokenType.KEYWORD, Keywords.Keyword.IF)) {
				return parser.getIfStatementParser().parseIfStatement();
			}

			if (parser.match(TokenType.KEYWORD, Keywords.Keyword.WHILE)) {
				return parser.getWhileStatementParser().parseWhileStatement();
			}

			if (parser.match(TokenType.KEYWORD, Keywords.Keyword.FOR)) {
				return parser.getForStatementParser().parseForStatement();
			}

			if (parser.match(TokenType.KEYWORD, Keywords.Keyword.DO)) {
				return parser.getDoWhileStatementParser().parseDoWhileStatement();
			}

			// Break and continue statements
			Stmt breakContinue = parser.getBreakContinueParser().parseBreakOrContinue();
			if (breakContinue != null) {
				return breakContinue;
			}

			// Expression statement fallback
			Expr expr = parser.getPrecedenceParser().parseExpression();
			Token semi = parser.consume(TokenType.SEMICOLON, "Expected ';' after expression");
			TokenSpan span = expr.getSpan().merge(semi.getSpan());
			return new ExpressionStmt(expr, span);
		} catch (ParseError e) {
			parser.synchronize();
			return null;
		}
	}

}
