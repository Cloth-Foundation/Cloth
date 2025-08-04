package loom.compiler.parser.precedence;

import loom.compiler.ast.Expr;
import loom.compiler.ast.expressions.*;
import loom.compiler.ast.expressions.IncrementExpr;
import loom.compiler.ast.expressions.DecrementExpr;
import loom.compiler.ast.expressions.TernaryExpr;
import loom.compiler.ast.expressions.CompoundAssignExpr;
import loom.compiler.parser.Parser;
import loom.compiler.token.Keywords;
import loom.compiler.token.Token;
import loom.compiler.token.TokenSpan;
import loom.compiler.token.TokenType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

public final class PrecedenceParser {

	private final Parser parser;

	public PrecedenceParser (Parser parser) {
		this.parser = parser;
	}

	public Expr parseExpression () {
		Expr expr = parseTernary();

		if (parser.match(TokenType.EQ)) {
			Token equals = parser.previous();
			Expr value = parseExpression();

			// Only allow assignment to variable or field access
			if (expr instanceof VariableExpr || expr instanceof GetExpr) {
				TokenSpan span = expr.getSpan().merge(value.getSpan());
				return new AssignExpr(expr, value, span);
			} else {
				parser.reportError(equals, "Invalid assignment target.");
			}
		}

		// Handle compound assignment operators
		if (parser.match(TokenType.PLUSEQ, TokenType.MINUSEQ, TokenType.STAREQ, TokenType.SLASHEQ, TokenType.MODULOEQ)) {
			Token operator = parser.previous();
			Expr value = parseExpression();

			// Only allow compound assignment to variable or field access
			if (expr instanceof VariableExpr || expr instanceof GetExpr) {
				TokenSpan span = expr.getSpan().merge(value.getSpan());
				return new CompoundAssignExpr(expr, operator.value, value, span);
			} else {
				parser.reportError(operator, "Invalid compound assignment target.");
			}
		}

		return expr;
	}
	
	private Expr parseTernary() {
		Expr expr = parseLogical();
		
		if (parser.match(TokenType.QUESTION)) {
			Token question = parser.previous();
			Expr trueExpr = parseExpression();
			parser.consume(TokenType.COLON, "Expected ':' in ternary operator");
			Expr falseExpr = parseTernary();
			
			TokenSpan span = expr.getSpan().merge(falseExpr.getSpan());
			return new TernaryExpr(expr, trueExpr, falseExpr, span);
		}
		
		return expr;
	}
	
	private Expr parseLogical() {
		Expr expr = parseEquality();

		while (parser.match(TokenType.AND, TokenType.OR)) {
			Token op = parser.previous();
			Expr right = parseEquality();
			expr = new BinaryExpr(expr, op.value, right, expr.getSpan().merge(right.getSpan()));
		}

		return expr;
	}

	private Expr parseEquality () {
		Expr expr = parseTerm();

		while (parser.match(TokenType.EQEQ, TokenType.BANGEQ, TokenType.GT, TokenType.GTEQ, TokenType.LT, TokenType.LTEQ)) {
			Token op = parser.previous();
			Expr right = parseTerm();
			expr = new BinaryExpr(expr, op.value, right, expr.getSpan().merge(right.getSpan()));
		}

		return expr;
	}

	private Expr parseTerm () {
		Expr expr = parseFactor();

		while (parser.match(TokenType.PLUS, TokenType.MINUS)) {
			Token op = parser.previous();
			Expr right = parseFactor();
			expr = new BinaryExpr(expr, op.value, right, expr.getSpan().merge(right.getSpan()));
		}

		return expr;
	}

	private Expr parseFactor () {
		Expr expr = parsePrimary();

		while (parser.match(TokenType.STAR, TokenType.SLASH, TokenType.MODULO)) {
			Token op = parser.previous();
			Expr right = parsePrimary();
			expr = new BinaryExpr(expr, op.value, right, expr.getSpan().merge(right.getSpan()));
		}

		return expr;
	}

	private Expr parsePrimary () {
		Token token = parser.peek();

		// Handle prefix increment/decrement
		if (parser.match(TokenType.PLUSPLUS)) {
			Expr operand = parsePrimary();
			return new IncrementExpr(operand, true, token.getSpan().merge(operand.getSpan()));
		}

		if (parser.match(TokenType.MINUSMINUS)) {
			Expr operand = parsePrimary();
			return new DecrementExpr(operand, true, token.getSpan().merge(operand.getSpan()));
		}

		Expr expr;
		if (parser.match(TokenType.IDENTIFIER)) {
			expr = new VariableExpr(token.value, token.getSpan());
			return parseCallOrAccess(expr);
		}

		if (parser.match(TokenType.KEYWORD, Keywords.Keyword.SELF)) {
			expr = new VariableExpr("self", token.getSpan());

			if (parser.match(TokenType.DOT)) {
				Token field = parser.consume(TokenType.IDENTIFIER, "Expected field after 'self.'");
				expr = new GetExpr(expr, field.value, field.getSpan());
			}

			return parseCallOrAccess(expr);
		}

		if (parser.match(TokenType.STRING)) {
			return new LiteralExpr(token.value, token.getSpan());
		}

		if (parser.match(TokenType.NUMBER)) {
			// Parse the number value
			Object numberValue = parseNumberValue(token.value);
			return new LiteralExpr(numberValue, token.getSpan());
		}

		if (parser.match(TokenType.KEYWORD, Keywords.Keyword.TRUE)) {
			return new LiteralExpr(true, token.getSpan());
		}

		if (parser.match(TokenType.KEYWORD, Keywords.Keyword.FALSE)) {
			return new LiteralExpr(false, token.getSpan());
		}

		if (parser.match(TokenType.KEYWORD, Keywords.Keyword.NULL)) {
			return new LiteralExpr(null, token.getSpan());
		}

		if (parser.match(TokenType.KEYWORD, Keywords.Keyword.NEW)) {
			Token classToken = parser.consume(TokenType.IDENTIFIER, "Expected class name after 'new'");
			parser.consume(TokenType.LPAREN, "Expected '(' after class name");

			List<Expr> args = new ArrayList<>();
			if (!parser.check(TokenType.RPAREN)) {
				do {
					args.add(parseExpression());
				} while (parser.match(TokenType.COMMA));
			}

			Token right_paren = parser.consume(TokenType.RPAREN, "Expected ')' after constructor arguments");
			TokenSpan span = token.getSpan().merge(right_paren.getSpan());
			return new NewExpr(classToken.value, args, span);
		}

		if (parser.match(TokenType.LPAREN)) {
			expr = parseExpression();
			parser.consume(TokenType.RPAREN, "Expected ')' after expression");
			return parseCallOrAccess(expr);
		}

		throw parser.error(token, "Expected expression");
	}

	private Expr parseStructInstantiation(Token typeToken) {
		parser.consume(TokenType.LBRACE, "Expected '{' after struct type name");
		Map<String, Expr> fieldValues = new HashMap<>();
		while (!parser.check(TokenType.RBRACE) && !parser.isAtEnd()) {
			Token fieldName = parser.consume(TokenType.IDENTIFIER, "Expected field name in struct initializer");
			parser.consume(TokenType.COLON, "Expected ':' after field name");
			Expr value = parseExpression();
			fieldValues.put(fieldName.value, value);
			if (!parser.match(TokenType.COMMA)) {
				break;
			}
		}
		parser.consume(TokenType.RBRACE, "Expected '}' after struct initializer");
		return new StructExpr(typeToken.value, fieldValues, typeToken.getSpan().merge(parser.previous().getSpan()));
	}

	private Expr parseCallOrAccess (Expr expr) {
		while (true) {
			if (parser.match(TokenType.LBRACE)) {
				// Struct instantiation as postfix
				Map<String, Expr> fieldValues = new HashMap<>();
				while (!parser.check(TokenType.RBRACE) && !parser.isAtEnd()) {
					Token fieldName = parser.consume(TokenType.IDENTIFIER, "Expected field name in struct initializer");
					parser.consume(TokenType.COLON, "Expected ':' after field name");
					Expr value = parseExpression();
					fieldValues.put(fieldName.value, value);
					if (!parser.match(TokenType.COMMA)) {
						break;
					}
				}
				parser.consume(TokenType.RBRACE, "Expected '}' after struct initializer");
				
				// Get the struct name from the VariableExpr
				String structName = null;
				if (expr instanceof VariableExpr) {
					structName = ((VariableExpr)expr).name;
				} else {
					parser.reportError(parser.peek(), "Expected struct type name before '{'");
					return expr;
				}
				
				return new StructExpr(structName, fieldValues, expr.getSpan().merge(parser.previous().getSpan()));
			}
			if (parser.match(TokenType.DOT)) {
				// Check if this is a projection (.()
				if (parser.check(TokenType.LPAREN)) {
					// This is a projection, not a field access
					parser.advance(); // consume the (
					expr = parser.getProjectedEnumParser().parseProjectedEnum(expr);
					if (expr == null) {
						// If projection parsing failed, fall back to field access
						Token method = parser.consume(TokenType.IDENTIFIER, "Expected method name after '::'");
						expr = new GetExpr(expr, method.value, method.getSpan());
					}
				} else {
					Token method = parser.consume(TokenType.IDENTIFIER, "Expected method name after '::'");
					expr = new GetExpr(expr, method.value, method.getSpan());
				}
			} else if (parser.match(TokenType.LPAREN)) {
				List<Expr> args = new ArrayList<>();
				if (!parser.check(TokenType.RPAREN)) {
					do {
						args.add(parseExpression());
					} while (parser.match(TokenType.COMMA));
				}
				Token end = parser.consume(TokenType.RPAREN, "Expected ')' after arguments");
				expr = new CallExpr(expr, args, expr.getSpan().merge(end.getSpan()));
			} else if (parser.match(TokenType.PLUSPLUS)) {
				// Postfix increment
				expr = new IncrementExpr(expr, false, expr.getSpan().merge(parser.previous().getSpan()));
			} else if (parser.match(TokenType.MINUSMINUS)) {
				// Postfix decrement
				expr = new DecrementExpr(expr, false, expr.getSpan().merge(parser.previous().getSpan()));
			} else {
				break;
			}
		}
		return expr;
	}

	private Object parseNumberValue (String value) {
		try {
			// Try to parse as integer first
			if (!value.contains(".")) {
				return Integer.parseInt(value);
			} else {
				// Parse as double
				return Double.parseDouble(value);
			}
		} catch (NumberFormatException e) {
			// If parsing fails, return the string value
			return value;
		}
	}

}
