package loom.compiler.parser;

import loom.compiler.semantic.Symbol;
import loom.compiler.token.Keywords;

import java.util.HashMap;
import java.util.Map;
import java.util.Stack;

public class ScopeManager {

	public enum Scope {
		PUBLIC, PRIVATE, PROTECTED, DEFAULT
	}

	private final Stack<Map<String, Symbol>> scopes = new Stack<>();
	private final Stack<Scope> scopeStack = new Stack<>();

	public ScopeManager () {
		// Initialize with a global scope
		scopes.push(new HashMap<>());
		scopeStack.push(Scope.PUBLIC); // Global scope is public by default
	}

	public void enterScope () {
		scopes.push(new HashMap<>());
		scopeStack.push(Scope.DEFAULT);
	}

	public void enterScope (Scope scope) {
		scopes.push(new HashMap<>());
		scopeStack.push(scope);
	}

	public void exitScope () {
		if (scopes.size() > 1) {
			scopes.pop();
			scopeStack.pop();
		} else {
			throw new IllegalStateException("Cannot exit global scope");
		}
	}

	public void define (String name, Symbol symbol) {
		scopes.peek().put(name, symbol);
	}

	public void define (String name, Symbol.Kind kind, String type, boolean mutable, Object value, int address) {
		Symbol symbol = new Symbol(name, kind, type, mutable, value, address);
		define(name, symbol);
	}

	public Symbol resolve (String name) {
		for (int i = scopes.size() - 1; i >= 0; i--) {
			Map<String, Symbol> scope = scopes.get(i);
			if (scope.containsKey(name)) return scope.get(name);
		}

		return null; // Not found in any scope
	}

	public Scope getCurrentScope () {
		return scopeStack.peek();
	}

	public boolean isInGlobalScope () {
		return scopes.size() == 1;
	}

	public boolean isInClassScope () {
		// This is a simplified check - in a real implementation you'd track scope types
		return scopes.size() > 1;
	}

	public static Scope parseScopeModifier (Keywords.Keyword keyword) {
		return switch (keyword) {
			case PUBLIC -> Scope.PUBLIC;
			case PRIVATE -> Scope.PRIVATE;
			case PROTECTED -> Scope.PROTECTED;
			default -> Scope.DEFAULT;
		};
	}

	public boolean isAccessible (Symbol symbol, Scope accessLevel) {
		// Simple access control logic
		// In a real implementation, this would be more complex
		// considering inheritance, package boundaries, etc.
		return switch (accessLevel) {
			case PUBLIC -> true;
			case PRIVATE -> isInClassScope(); // Only accessible within the same class
			case PROTECTED -> isInClassScope(); // Simplified - in real implementation would check inheritance
			default -> true; // Default is public
		};
	}

	public int getScopeDepth () {
		return scopes.size();
	}

	public boolean isSymbolDefined (String name) {
		return resolve(name) != null;
	}

	public void printScopeInfo () {
		System.out.println("Current scope depth: " + getScopeDepth());
		System.out.println("Current scope level: " + getCurrentScope());
		System.out.println("In global scope: " + isInGlobalScope());
		System.out.println("In class scope: " + isInClassScope());
	}
}
