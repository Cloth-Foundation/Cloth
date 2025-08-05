package loom.compiler.semantic;

import loom.compiler.diagnostics.ErrorReporter;
import loom.compiler.parser.ScopeManager;
import loom.compiler.token.TokenSpan;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Stack;

/**
 * Comprehensive symbol table that supports nested scopes, scope management, and type checking.
 * Combines basic symbol table functionality with enhanced features.
 */
public class SymbolTable {

	private final ErrorReporter reporter;
	private final Stack<ScopeTable> scopes = new Stack<>();
	private final Map<String, Symbol> globalSymbols = new HashMap<>();

	// Track class inheritance relationships for protected access checking
	private final Map<String, String> classInheritance = new HashMap<>(); // child -> parent

	/**
	 * Internal scope table for nested scopes
	 */
	private static class ScopeTable {
		private final ScopeTable parent;
		private final Map<String, Symbol> symbols = new HashMap<>();
		private final String className; // Track which class this scope belongs to

		public ScopeTable () {
			this.parent = null;
			this.className = null;
		}

		public ScopeTable (ScopeTable parent) {
			this.parent = parent;
			this.className = null;
		}

		public ScopeTable (ScopeTable parent, String className) {
			this.parent = parent;
			this.className = className;
		}

		/**
		 * Define a new symbol in the current scope.
		 * Returns false if the symbol already exists in this scope.
		 */
		public boolean define (String name, Symbol symbol) {
			if (symbols.containsKey(name)) return false;
			symbols.put(name, symbol);
			return true;
		}

		/**
		 * Look up a symbol in the current or parent scopes.
		 */
		public Optional<Symbol> resolve (String name) {
			Symbol symbol = symbols.get(name);
			if (symbol != null) return Optional.of(symbol);
			if (parent != null) return parent.resolve(name);
			return Optional.empty();
		}

		/**
		 * Check if the symbol exists only in the current scope.
		 */
		public boolean isDefinedLocally (String name) {
			return symbols.containsKey(name);
		}

		public Map<String, Symbol> getAll () {
			return Map.copyOf(symbols);
		}

		public String getClassName () {
			return className;
		}
	}

	/**
	 * Constructor for basic symbol table (backward compatibility)
	 */
	public SymbolTable () {
		this.reporter = null;
		// Start with global scope
		enterScope();
	}

	/**
	 * Constructor for enhanced symbol table with error reporting
	 */
	public SymbolTable (ErrorReporter reporter) {
		this.reporter = reporter;
		// Start with global scope
		enterScope();
	}

	/**
	 * Enter a new scope
	 */
	public void enterScope () {
		ScopeTable parent = scopes.isEmpty() ? null : scopes.peek();
		scopes.push(new ScopeTable(parent));
	}

	/**
	 * Enter a new class scope
	 */
	public void enterClassScope (String className) {
		ScopeTable parent = scopes.isEmpty() ? null : scopes.peek();
		scopes.push(new ScopeTable(parent, className));
	}

	/**
	 * Exit the current scope
	 */
	public void exitScope () {
		if (!scopes.isEmpty()) {
			scopes.pop();
		}
	}

	/**
	 * Register a class inheritance relationship
	 */
	public void registerInheritance (String childClass, String parentClass) {
		classInheritance.put(childClass, parentClass);
	}

	/**
	 * Get the parent class of a given class
	 */
	public String getParentClass (String className) {
		return classInheritance.get(className);
	}

	/**
	 * Check if a class inherits from another class (directly or indirectly)
	 */
	private boolean isDerivedFrom (String derivedClass, String baseClass) {
		if (derivedClass == null || baseClass == null) {
			return false;
		}

		if (derivedClass.equals(baseClass)) {
			return true; // Same class
		}

		String currentParent = classInheritance.get(derivedClass);
		while (currentParent != null) {
			if (currentParent.equals(baseClass)) {
				return true; // Found inheritance relationship
			}
			currentParent = classInheritance.get(currentParent);
		}

		return false; // No inheritance relationship found
	}

	/**
	 * Get the current class name from the scope stack
	 */
	private String getCurrentClassName () {
		for (int i = scopes.size() - 1; i >= 0; i--) {
			String className = scopes.get(i).getClassName();
			if (className != null) {
				return className;
			}
		}
		return null;
	}

	/**
	 * Get the class name where a symbol is defined
	 */
	private String getSymbolClassName (String name) {
		for (int i = scopes.size() - 1; i >= 0; i--) {
			ScopeTable scope = scopes.get(i);
			if (scope.symbols.containsKey(name)) {
				return scope.getClassName();
			}
		}
		return null;
	}

	/**
	 * Define a symbol in the current scope
	 */
	public boolean define (String name, Symbol symbol) {
		if (scopes.isEmpty()) {
			globalSymbols.put(name, symbol);
			return true;
		}
		return scopes.peek().define(name, symbol);
	}

	/**
	 * Define a symbol with basic parameters
	 */
	public boolean defineSymbol (String name, Symbol.Kind kind, String type, boolean mutable, Object value, int address) {
		Symbol symbol = new Symbol(name, kind, type, mutable, value, address, ScopeManager.Scope.DEFAULT);
		return define(name, symbol);
	}

	/**
	 * Define a symbol with access level
	 */
	public boolean defineSymbol (String name, Symbol.Kind kind, String type, boolean mutable, Object value, int address, ScopeManager.Scope accessLevel) {
		Symbol symbol = new Symbol(name, kind, type, mutable, value, address, accessLevel);
		return define(name, symbol);
	}

	/**
	 * Resolve a symbol in the current scope or parent scopes
	 */
	public Optional<Symbol> resolve (String name) {
		if (scopes.isEmpty()) {
			return Optional.ofNullable(globalSymbols.get(name));
		}
		return scopes.peek().resolve(name);
	}

	/**
	 * Resolve a symbol (basic version)
	 */
	public Optional<Symbol> resolveSymbol (String name) {
		return resolve(name);
	}

	/**
	 * Check if a symbol is defined in the current scope
	 */
	public boolean isDefinedLocally (String name) {
		if (scopes.isEmpty()) {
			return globalSymbols.containsKey(name);
		}
		return scopes.peek().isDefinedLocally(name);
	}

	/**
	 * Get the type of a symbol
	 */
	public Optional<String> getSymbolType (String name) {
		Optional<Symbol> symbol = resolve(name);
		return symbol.map(Symbol::type);
	}

	/**
	 * Get the kind of a symbol
	 */
	public Optional<Symbol.Kind> getSymbolKind (String name) {
		Optional<Symbol> symbol = resolve(name);
		return symbol.map(Symbol::kind);
	}

	/**
	 * Check if a symbol is mutable
	 */
	public boolean isSymbolMutable (String name) {
		Optional<Symbol> symbol = resolve(name);
		return symbol.map(Symbol::isMutable).orElse(false);
	}

	/**
	 * Check if a symbol can be accessed from the current scope
	 */
	public boolean canAccessSymbol (String name, ScopeManager.Scope currentScope) {
		Optional<Symbol> symbol = resolve(name);
		if (!symbol.isPresent()) {
			return false;
		}

		Symbol sym = symbol.get();
		ScopeManager.Scope symbolAccess = sym.accessLevel();

		// Public symbols are always accessible
		if (symbolAccess == ScopeManager.Scope.PUBLIC) {
			return true;
		}

		// Private symbols are only accessible from the same scope
		if (symbolAccess == ScopeManager.Scope.PRIVATE) {
			return currentScope == symbolAccess;
		}

		// Protected symbols are accessible from the same scope and derived classes
		if (symbolAccess == ScopeManager.Scope.PROTECTED) {
			// Check if we're in the same scope
			if (currentScope == symbolAccess) {
				return true;
			}

			// Check inheritance relationship
			String currentClass = getCurrentClassName();
			String symbolClass = getSymbolClassName(name);

			if (currentClass != null && symbolClass != null) {
				// Allow access if current class is derived from symbol's class
				return isDerivedFrom(currentClass, symbolClass);
			}

			return false;
		}

		// Default scope - accessible from same scope
		return currentScope == symbolAccess;
	}

	/**
	 * Report undefined symbol error
	 */
	public void reportUndefinedSymbol (String name, TokenSpan span) {
		if (reporter != null) {
			reporter.reportError(span, "Undefined symbol '" + name + "'", name);
		}
	}

	/**
	 * Report symbol redefinition error
	 */
	public void reportSymbolRedefinition (String name, TokenSpan span) {
		if (reporter != null) {
			reporter.reportError(span, "Symbol '" + name + "' is already defined in this scope", name);
		}
	}

	/**
	 * Report type mismatch error
	 */
	public void reportTypeMismatch (String expectedType, String actualType, TokenSpan span, String context) {
		if (reporter != null) {
			reporter.reportError(span,
					"Type mismatch in " + context + ": expected '" + expectedType + "' but got '" + actualType + "'",
					context);
		}
	}

	/**
	 * Get all symbols in the current scope
	 */
	public Map<String, Symbol> getCurrentScopeSymbols () {
		if (scopes.isEmpty()) {
			return new HashMap<>(globalSymbols);
		}
		return scopes.peek().getAll();
	}

	/**
	 * Get all symbols (basic version)
	 */
	public Map<String, Symbol> getAll () {
		return getCurrentScopeSymbols();
	}

	/**
	 * Get the current scope depth
	 */
	public int getScopeDepth () {
		return scopes.size();
	}

	/**
	 * Check if we're in the global scope
	 */
	public boolean isInGlobalScope () {
		return scopes.size() <= 1;
	}

	/**
	 * Get a string representation of the symbol table for debugging
	 */
	@Override
	public String toString () {
		StringBuilder sb = new StringBuilder();
		sb.append("Symbol Table (depth: ").append(getScopeDepth()).append(")\n");

		// Global symbols
		if (!globalSymbols.isEmpty()) {
			sb.append("Global symbols:\n");
			for (Symbol symbol : globalSymbols.values()) {
				sb.append("  ").append(symbol.toString()).append("\n");
			}
		}

		// Current scope symbols
		if (!scopes.isEmpty()) {
			Map<String, Symbol> currentSymbols = scopes.peek().getAll();
			if (!currentSymbols.isEmpty()) {
				sb.append("Current scope symbols:\n");
				for (Symbol symbol : currentSymbols.values()) {
					sb.append("  ").append(symbol.toString()).append("\n");
				}
			}
		}

		return sb.toString();
	}
}
