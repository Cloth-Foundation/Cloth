package loom.compiler.semantic;

import loom.compiler.ast.Expr;
import loom.compiler.ast.Stmt;
import loom.compiler.ast.declarations.*;
import loom.compiler.ast.expressions.*;
import loom.compiler.ast.statements.BlockStmt;
import loom.compiler.ast.statements.ExpressionStmt;
import loom.compiler.ast.statements.ReturnStmt;
import loom.compiler.ast.statements.ImportStmt;
import loom.compiler.ast.Parameter;
import loom.compiler.diagnostics.ErrorReporter;
import loom.compiler.parser.ScopeManager;
import loom.compiler.parser.TypeChecker;

import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.HashMap;

/**
 * Semantic analyzer that performs two-pass compilation:
 * 1. First pass: Collect all top-level declarations
 * 2. Second pass: Full semantic analysis with complete symbol table
 * 
 * This enables forward references and order-independent declarations.
 */
public class SemanticAnalyzer {

	private final ErrorReporter reporter;
	private final SymbolTable symbolTable;
	private final TypeChecker typeChecker;
	private final ScopeManager scopeManager;
	private final ImportResolver importResolver;
	private final DeclarationCollector declarationCollector;
	
	// Store class member symbols for forward references
	private final Map<String, Map<String, Symbol>> classMemberSymbols = new HashMap<>();

	
	public SemanticAnalyzer(ErrorReporter reporter) {
		this(reporter, ".");
	}
	
	public SemanticAnalyzer(ErrorReporter reporter, String basePath) {
		this.reporter = reporter;
		this.symbolTable = new SymbolTable(reporter);
		this.typeChecker = new TypeChecker(reporter, symbolTable);
		this.scopeManager = new ScopeManager();
		this.importResolver = new ImportResolver(reporter, basePath);
		this.declarationCollector = new DeclarationCollector(reporter, symbolTable);
	}
	
	/**
	 * Two-pass analysis: first collect declarations, then perform full analysis
	 */
	public void analyze(List<Stmt> statements) {
		// Pass 1: Collect all top-level declarations
		collectDeclarations(statements);
		
		// Pass 2: Full semantic analysis with complete symbol table
		performSemanticAnalysis(statements);
	}
	
	/**
	 * First pass: Collect all top-level declarations
	 */
	private void collectDeclarations(List<Stmt> statements) {
		declarationCollector.collectDeclarations(statements);
	}
	
	/**
	 * Second pass: Perform full semantic analysis
	 */
	private void performSemanticAnalysis(List<Stmt> statements) {
		for (Stmt stmt : statements) {
			analyzeStatement(stmt);
		}
	}
	
	/**
	 * Analyze a single statement
	 */
	private void analyzeStatement(Stmt stmt) {
		if (stmt instanceof VarDecl) {
			analyzeVariableDeclaration((VarDecl) stmt);
		} else if (stmt instanceof FunctionDecl) {
			analyzeFunctionDeclaration((FunctionDecl) stmt);
		} else if (stmt instanceof ClassDecl) {
			analyzeClassDeclaration((ClassDecl) stmt);
		} else if (stmt instanceof EnumDecl) {
			analyzeEnumDeclaration((EnumDecl) stmt);
		} else if (stmt instanceof StructDecl) {
			analyzeStructDeclaration((StructDecl) stmt);
		} else if (stmt instanceof ReturnStmt) {
			analyzeReturnStatement((ReturnStmt) stmt);
		} else if (stmt instanceof BlockStmt) {
			analyzeBlockStatement((BlockStmt) stmt);
		} else if (stmt instanceof ImportStmt) {
			analyzeImportStatement((ImportStmt) stmt);
		} else if (stmt instanceof ExpressionStmt) {
			// Analyze expression statements for semantic errors
			analyzeExpression(((ExpressionStmt) stmt).expression);
		}
	}
	

	
	/**
	 * Analyze a variable declaration
	 */
	private void analyzeVariableDeclaration(VarDecl varDecl) {
		String name = varDecl.name;
		String declaredType = varDecl.type;
		ScopeManager.Scope accessLevel = varDecl.scope;
		
		// Handle nullable types
		if (varDecl.isNullable && declaredType != null) {
			declaredType = declaredType + "?";
		}
		
		// Analyze initializer if present
		if (varDecl.initializer != null) {
			String inferredType = typeChecker.inferType(varDecl.initializer);
			
			// If no type was declared, use the inferred type
			if (declaredType == null) {
				declaredType = inferredType;
			} else {
				// Validate that the initializer type matches the declared type
				if (!typeChecker.typesCompatible(inferredType, declaredType)) {
					symbolTable.reportTypeMismatch(declaredType, inferredType, varDecl.getSpan(), "variable initialization");
					return;
				}
			}
		}
		
		// Define the variable in the current scope (Pass 2)
		// Only define if not already defined locally (to avoid redefinition errors)
		if (!symbolTable.isDefinedLocally(name)) {
			// Determine symbol kind based on context
			Symbol.Kind kind = Symbol.Kind.VARIABLE;
			if (symbolTable.getScopeDepth() > 1) {
				// We're inside a class, so this is a field
				kind = Symbol.Kind.FIELD;
			}
			
			// Define the symbol with access control
			boolean success = symbolTable.defineSymbol(
				name, 
				kind, 
				declaredType, 
				!varDecl.isFinal, // variables are mutable by default, unless final
				varDecl.initializer, 
				0,
				accessLevel
			);
			
			if (!success) {
				symbolTable.reportSymbolRedefinition(name, varDecl.getSpan());
			}
		}
	}
	
	/**
	 * Analyze a function declaration
	 */
	private void analyzeFunctionDeclaration(FunctionDecl funcDecl) {
		String name = funcDecl.name;
		String returnType = funcDecl.returnType;
		ScopeManager.Scope accessLevel = funcDecl.scope;
		
		// Note: Function declaration was already collected in Pass 1
		// Here we just analyze the function body and validate the function structure
		
		// Analyze function body
		analyzeFunctionBody(funcDecl);
	}
	
	/**
	 * Analyze class declaration
	 */
	private void analyzeClassDeclaration(ClassDecl classDecl) {
		// Register inheritance relationship if there's a superclass
		if (classDecl.superclass != null && !classDecl.superclass.isEmpty()) {
			symbolTable.registerInheritance(classDecl.name, classDecl.superclass);
		}
		
		// Analyze class members
		analyzeClassMembers(classDecl);
	}
	
	/**
	 * Analyze an enum declaration
	 */
	private void analyzeEnumDeclaration(EnumDecl enumDecl) {
		String name = enumDecl.name;
		ScopeManager.Scope accessLevel = enumDecl.scope;
		
		// Note: Enum declaration was already collected in Pass 1
		// Here we just analyze the enum fields and validate the enum structure
		
		// Analyze enum fields
		for (VarDecl field : enumDecl.fields) {
			analyzeVariableDeclaration(field);
		}
	}
	
	/**
	 * Analyze a struct declaration
	 */
	private void analyzeStructDeclaration(StructDecl structDecl) {
		String name = structDecl.name;
		ScopeManager.Scope accessLevel = structDecl.scope;
		
		// Note: Struct declaration was already collected in Pass 1
		// Here we just analyze the struct fields and validate the struct structure
		
		// Store struct field information for type checking
		for (VarDecl field : structDecl.fields) {
			// Create qualified field name for symbol table
			String qualifiedFieldName = name + "." + field.name;
			
			// Define field symbol with struct's access level
			Symbol fieldSymbol = new Symbol(
				qualifiedFieldName,
				Symbol.Kind.FIELD,
				field.type,
				field.isFinal,
				null,  // no initial value
				-1,    // no address yet
				accessLevel
			);
			
			symbolTable.define(qualifiedFieldName, fieldSymbol);
		}
		
		// Analyze struct fields
		for (VarDecl field : structDecl.fields) {
			analyzeVariableDeclaration(field);
		}
	}
	
	/**
	 * Analyze a return statement
	 */
	private void analyzeReturnStatement(ReturnStmt returnStmt) {
		// This will be handled by the function body analysis
		// For now, just validate the expression if present
		if (returnStmt.value != null) {
			typeChecker.inferType(returnStmt.value);
		}
	}
	
	/**
	 * Analyze a block statement
	 */
	private void analyzeBlockStatement(BlockStmt blockStmt) {
		// Enter new scope for block
		symbolTable.enterScope();
		
		// Analyze all statements in the block
		for (Stmt stmt : blockStmt.statements) {
			analyzeStatement(stmt);
		}
		
		// Exit scope
		symbolTable.exitScope();
	}
	
	/**
	 * Analyze an import statement
	 */
	private void analyzeImportStatement(ImportStmt importStmt) {
		try {
			boolean success = importResolver.resolveImport(importStmt, symbolTable);
			if (!success) {
				// Import failed, but don't halt compilation - just report the error
				reporter.reportError(importStmt.getSpan(), 
					"Failed to resolve import: " + String.join("::", importStmt.path), 
					String.join("::", importStmt.path));
			}
		} catch (Exception e) {
			// Catch any exceptions during import resolution to prevent hanging
			reporter.reportError(importStmt.getSpan(), 
				"Error during import resolution: " + e.getMessage(), 
				String.join("::", importStmt.path));
		}
	}
	
	/**
	 * Analyze function body with proper scope management
	 */
	private void analyzeFunctionBody(FunctionDecl funcDecl) {
		// Enter function scope
		symbolTable.enterScope();
		
		// Add parameters to scope
		for (Parameter param : funcDecl.parameters) {
			symbolTable.defineSymbol(
				param.name,
				Symbol.Kind.VARIABLE,
				param.type.toString(),
				false, // parameters are immutable
				null,
				0
			);
		}
		
		// Analyze function body
		for (Stmt stmt : funcDecl.body) {
			analyzeStatement(stmt);
		}
		
		// Validate return statements
		validateFunctionReturns(funcDecl);
		
		// Exit function scope
		symbolTable.exitScope();
	}
	
	/**
	 * Analyze class members
	 */
	private void analyzeClassMembers(ClassDecl classDecl) {
		// Enter class scope with class name
		symbolTable.enterClassScope(classDecl.name);
		
		// Add 'self' symbol for class methods
		symbolTable.defineSymbol(
			"self",
			Symbol.Kind.VARIABLE,
			classDecl.name, // type is the class name
			false, // self is immutable
			null,
			0,
			ScopeManager.Scope.DEFAULT
		);
		
		// Get or create class member symbols map
		Map<String, Symbol> memberSymbols = classMemberSymbols.computeIfAbsent(
			classDecl.name, k -> new HashMap<>()
		);
		
		// Add all class members to the symbol table (using unqualified names in class scope)
		for (Stmt stmt : classDecl.members) {
			if (stmt instanceof FunctionDecl) {
				FunctionDecl funcDecl = (FunctionDecl) stmt;
				Symbol methodSymbol = new Symbol(
					funcDecl.name,
					Symbol.Kind.METHOD,
					funcDecl.returnType != null ? funcDecl.returnType : "void",
					false, // methods are not mutable
					null,
					0,
					funcDecl.scope
				);
				symbolTable.define(funcDecl.name, methodSymbol);
				memberSymbols.put(funcDecl.name, methodSymbol);
			} else if (stmt instanceof VarDecl) {
				VarDecl varDecl = (VarDecl) stmt;
				Symbol fieldSymbol = new Symbol(
					varDecl.name,
					Symbol.Kind.FIELD,
					varDecl.type,
					!varDecl.isFinal,
					varDecl.initializer,
					0,
					varDecl.scope
				);
				symbolTable.define(varDecl.name, fieldSymbol);
				memberSymbols.put(varDecl.name, fieldSymbol);
			}
		}
		
		// Analyze all members
		for (Stmt stmt : classDecl.members) {
			analyzeStatement(stmt);
		}
		
		// Exit class scope
		symbolTable.exitScope();
	}
	
	/**
	 * Validate that function returns match the declared return type
	 */
	private void validateFunctionReturns(FunctionDecl funcDecl) {
		String returnType = funcDecl.returnType;
		
		if (returnType == null || returnType.equals("void")) {
			// Void function - should not return values
			validateVoidFunctionReturns(funcDecl);
		} else {
			// Non-void function - must return values
			validateNonVoidFunctionReturns(funcDecl);
		}
	}
	
	/**
	 * Validate void function returns
	 */
	private void validateVoidFunctionReturns(FunctionDecl funcDecl) {
		for (Stmt stmt : funcDecl.body) {
			if (stmt instanceof ReturnStmt) {
				ReturnStmt returnStmt = (ReturnStmt) stmt;
				if (returnStmt.value != null) {
					reporter.reportError(
						returnStmt.getSpan(),
						"Void function '" + funcDecl.name + "' should not return a value",
						returnStmt.value.toString()
					);
				}
			}
		}
	}
	
	/**
	 * Validate non-void function returns
	 */
	private void validateNonVoidFunctionReturns(FunctionDecl funcDecl) {
		boolean hasReturnWithValue = false;
		
		for (Stmt stmt : funcDecl.body) {
			if (stmt instanceof ReturnStmt) {
				ReturnStmt returnStmt = (ReturnStmt) stmt;
				
				if (returnStmt.value != null) {
					hasReturnWithValue = true;
					// Validate return type
					typeChecker.validateReturnType(returnStmt.value, funcDecl.returnType, returnStmt.getSpan(), funcDecl.name);
				} else {
					reporter.reportError(
						returnStmt.getSpan(),
						"Function '" + funcDecl.name + "' with return type '" + funcDecl.returnType + "' must return a value",
						"return;"
					);
				}
			}
		}
		
		if (!hasReturnWithValue) {
			reporter.reportError(
				funcDecl.getSpan(),
				"Function '" + funcDecl.name + "' with return type '" + funcDecl.returnType + "' must return a value",
				funcDecl.name
			);
		}
	}
	
	/**
	 * Analyze an expression for type checking and semantic errors
	 */
	public String analyzeExpression(Expr expr) {
		// Check for assignment to final variables
		if (expr instanceof AssignExpr) {
			analyzeAssignmentExpression((AssignExpr) expr);
		}
		
		return typeChecker.inferType(expr);
	}
	
	/**
	 * Analyze assignment expressions to check for final variable reassignment
	 */
	private void analyzeAssignmentExpression(AssignExpr expr) {
		// Check if the target is a variable
		if (expr.target instanceof VariableExpr) {
			String varName = ((VariableExpr) expr.target).name;
			
			// Look up the variable in the symbol table
			java.util.Optional<Symbol> symbolOpt = symbolTable.resolve(varName);
			if (symbolOpt.isPresent()) {
				Symbol symbol = symbolOpt.get();
				
				// Check if the variable is final (immutable)
				if (!symbol.isMutable()) {
					reporter.reportError(expr.getSpan(), 
						"Cannot assign to final variable '" + varName + "'", 
						varName);
				}
			}
		}
	}
	
	/**
	 * Get the symbol table for debugging
	 */
	public SymbolTable getSymbolTable() {
		return symbolTable;
	}
	
	/**
	 * Get the type checker
	 */
	public TypeChecker getTypeChecker() {
		return typeChecker;
	}
} 