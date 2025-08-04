package loom.compiler.semantic;

import loom.compiler.ast.Expr;
import loom.compiler.ast.Stmt;
import loom.compiler.ast.expressions.LiteralExpr;
import loom.compiler.ast.declarations.ClassDecl;
import loom.compiler.ast.declarations.FunctionDecl;
import loom.compiler.ast.declarations.VarDecl;
import loom.compiler.ast.statements.ImportStmt;
import loom.compiler.diagnostics.ErrorReporter;
import loom.compiler.lexer.Lexer;
import loom.compiler.parser.Parser;
import loom.compiler.parser.ScopeManager;
import loom.compiler.token.Token;
import loom.compiler.token.TokenSpan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Handles import resolution and module loading.
 */
public class ImportResolver {
	
	private final ErrorReporter reporter;
	private final Map<String, Module> loadedModules = new HashMap<>();
	private final String basePath;
	
	public ImportResolver(ErrorReporter reporter, String basePath) {
		this.reporter = reporter;
		this.basePath = basePath;
	}
	
	/**
	 * Resolves an import statement and adds imported symbols to the symbol table.
	 */
	public boolean resolveImport(ImportStmt importStmt, SymbolTable symbolTable) {
		String modulePath = String.join("::", importStmt.path);
		
		try {
			// Load the module if not already loaded
			Module module = loadModule(modulePath, importStmt.getSpan());
			if (module == null) {
				return false;
			}
			
			// Import the requested symbols
			for (String symbolName : importStmt.symbols) {
				Optional<Symbol> symbol = module.getSymbol(symbolName);
				if (symbol.isPresent()) {
					// Check access control - only public symbols can be imported
					if (symbol.get().accessLevel() == ScopeManager.Scope.PUBLIC) {
						// Import the symbol into the current scope
						Symbol importedSymbol = symbol.get();
						boolean success = symbolTable.defineSymbol(
							importedSymbol.name(),
							importedSymbol.kind(),
							importedSymbol.type(),
							importedSymbol.isMutable(),
							importedSymbol.value(),
							importedSymbol.address(),
							importedSymbol.accessLevel()
						);
						
						if (!success) {
							reporter.reportError(
								importStmt.getSpan(),
								"Symbol '" + symbolName + "' conflicts with existing symbol",
								symbolName
							);
							return false;
						}
					} else {
						reporter.reportError(
							importStmt.getSpan(),
							"Cannot import private/protected symbol '" + symbolName + "' from module '" + modulePath + "'",
							symbolName
						);
						return false;
					}
				} else {
					reporter.reportError(
						importStmt.getSpan(),
						"Symbol '" + symbolName + "' not found in module '" + modulePath + "'",
						symbolName
					);
					return false;
				}
			}
			
			return true;
		} catch (Exception e) {
			reporter.reportError(
				importStmt.getSpan(),
				"Unexpected error during import resolution: " + e.getMessage(),
				modulePath
			);
			return false;
		}
	}
	
	/**
	 * Loads a module from the file system.
	 */
	private Module loadModule(String modulePath, TokenSpan span) {
		// Check if already loaded
		if (loadedModules.containsKey(modulePath)) {
			return loadedModules.get(modulePath);
		}
		
		// Convert module path to file path
		Path filePath = resolveModulePath(modulePath);
		if (filePath == null) {
			reporter.reportError(span, "Module '" + modulePath + "' not found", modulePath);
			return null;
		}
		
		// Check if file exists
		if (!Files.exists(filePath)) {
			reporter.reportError(span, "Module file not found: " + filePath, modulePath);
			return null;
		}
		
		// Read and parse the module file
		String source;
		try {
			source = Files.readString(filePath);
		} catch (IOException e) {
			reporter.reportError(span, "Failed to read module file: " + e.getMessage(), modulePath);
			return null;
		} catch (Exception e) {
			reporter.reportError(span, "Unexpected error reading module file: " + e.getMessage(), modulePath);
			return null;
		}
		
		// Parse the module with timeout protection
		Module module = parseModule(source, modulePath, span);
		if (module != null) {
			loadedModules.put(modulePath, module);
		}
		
		return module;
	}
	
	/**
	 * Converts a module path to a file system path.
	 */
	private Path resolveModulePath(String modulePath) {
		// Handle standard library modules
		if (modulePath.startsWith("std::")) {
			String stdPath = modulePath.substring(5); // Remove "std::"
			return Paths.get(basePath, "std", stdPath + ".lm");
		}
		
		// Handle relative modules
		String[] parts = modulePath.split("::");
		String fileName = parts[parts.length - 1] + ".lm";
		
		// Try multiple possible locations for the module
		Path[] possiblePaths = {
			// Try in base path
			Paths.get(basePath, fileName),
			// Try in examples directory
			Paths.get(basePath, "examples", fileName),
			// Try in current directory
			Paths.get(fileName)
		};
		
		// Check each possible path
		for (Path path : possiblePaths) {
			if (Files.exists(path)) {
				return path;
			}
		}
		
		// If not found in any location, return the first path for error reporting
		return possiblePaths[0];
	}
	
	/**
	 * Parses a module file and returns a Module object.
	 */
	private Module parseModule(String source, String modulePath, TokenSpan span) {
		try {
			// Create a separate error reporter for this module parsing
			ErrorReporter moduleReporter = new ErrorReporter();
			
			// Create a lexer and parser for the module source
			Lexer lexer = new Lexer(source, modulePath, moduleReporter);
			List<Token> tokens = lexer.tokenize();
			
			// Check for lexing errors
			if (moduleReporter.hasErrors()) {
				reporter.reportError(span, "Failed to tokenize module '" + modulePath + "'", modulePath);
				return null;
			}
			
			// Create a parser for the module
			Parser parser = new Parser(tokens, moduleReporter);
			List<Stmt> statements = parser.parse();
			
			// Check for parsing errors
			if (moduleReporter.hasErrors()) {
				reporter.reportError(span, "Failed to parse module '" + modulePath + "'", modulePath);
				return null;
			}
			
			// Create a module to hold the symbols
			Module module = new Module(modulePath);
			
			// Extract symbols from the parsed statements
			for (Stmt stmt : statements) {
				try {
					extractSymbolsFromStatement(stmt, module);
				} catch (Exception e) {
					// Log the error but continue processing other statements
					reporter.reportError(span, "Error extracting symbols from statement in module '" + modulePath + "': " + e.getMessage(), modulePath);
				}
			}
			
			return module;
		} catch (Exception e) {
			reporter.reportError(span, "Failed to parse module '" + modulePath + "': " + e.getMessage(), modulePath);
			return null;
		}
	}
	
	/**
	 * Extracts symbols from a statement and adds them to the module.
	 */
	private void extractSymbolsFromStatement(Stmt stmt, Module module) {
		if (stmt instanceof FunctionDecl) {
			FunctionDecl funcDecl = (FunctionDecl) stmt;
			// Only add public functions to the module
			if (funcDecl.scope == ScopeManager.Scope.PUBLIC) {
				Symbol symbol = new Symbol(
					funcDecl.name,
					Symbol.Kind.FUNCTION,
					funcDecl.returnType != null ? funcDecl.returnType.toString() : "void",
					false, // functions are not mutable
					null, // no value
					0, // no address
					funcDecl.scope
				);
				module.addSymbol(symbol);
			}
		} else if (stmt instanceof VarDecl) {
			VarDecl varDecl = (VarDecl) stmt;
			// Only add public variables to the module
			if (varDecl.scope == ScopeManager.Scope.PUBLIC) {
				Symbol symbol = new Symbol(
					varDecl.name,
					Symbol.Kind.VARIABLE,
					varDecl.type != null ? varDecl.type.toString() : "unknown",
					false, // variables are not mutable by default
					varDecl.initializer != null ? extractLiteralValue(varDecl.initializer) : null,
					0, // no address
					varDecl.scope
				);
				module.addSymbol(symbol);
			}
		} else if (stmt instanceof ClassDecl) {
			ClassDecl classDecl = (ClassDecl) stmt;
			// Only add public classes to the module
			if (classDecl.scope == ScopeManager.Scope.PUBLIC) {
				Symbol symbol = new Symbol(
					classDecl.name,
					Symbol.Kind.CLASS,
					classDecl.name, // class type is the class name
					false, // classes are not mutable
					null, // no value
					0, // no address
					classDecl.scope
				);
				module.addSymbol(symbol);
			}
		}
	}
	
	/**
	 * Extracts the literal value from an expression for variable initialization.
	 */
	private Object extractLiteralValue(Expr expr) {
		if (expr instanceof LiteralExpr) {
			return ((LiteralExpr) expr).value;
		}
		// For other expression types, we can't extract a simple value
		return null;
	}
	
	/**
	 * Represents a loaded module with its symbols.
	 */
	public static class Module {
		private final String name;
		private final Map<String, Symbol> symbols = new HashMap<>();
		
		public Module(String name) {
			this.name = name;
		}
		
		public void addSymbol(Symbol symbol) {
			symbols.put(symbol.name(), symbol);
		}
		
		public Optional<Symbol> getSymbol(String name) {
			return Optional.ofNullable(symbols.get(name));
		}
		
		public String getName() {
			return name;
		}
		
		@Override
		public String toString() {
			return "Module(" + name + ")";
		}
	}
} 