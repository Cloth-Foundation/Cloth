package loom.compiler.semantic;

import loom.compiler.ast.Stmt;
import loom.compiler.ast.declarations.*;
import loom.compiler.ast.statements.ImportStmt;
import loom.compiler.diagnostics.ErrorReporter;
import loom.compiler.parser.ScopeManager;
import loom.compiler.token.TokenSpan;

import java.util.List;

/**
 * First pass collector that gathers all top-level declarations
 * to build a complete symbol table before semantic analysis.
 * This enables forward references and order-independent declarations.
 */
public class DeclarationCollector {
    
    private final ErrorReporter reporter;
    private final SymbolTable symbolTable;
    
    public DeclarationCollector(ErrorReporter reporter, SymbolTable symbolTable) {
        this.reporter = reporter;
        this.symbolTable = symbolTable;
    }
    
    /**
     * Collect all top-level declarations from the AST
     */
    public void collectDeclarations(List<Stmt> statements) {
        for (Stmt stmt : statements) {
            collectDeclaration(stmt);
        }
    }
    
    /**
     * Collect a single declaration
     */
    private void collectDeclaration(Stmt stmt) {
        if (stmt instanceof FunctionDecl) {
            collectFunctionDeclaration((FunctionDecl) stmt);
        } else if (stmt instanceof ClassDecl) {
            collectClassDeclaration((ClassDecl) stmt);
        } else if (stmt instanceof EnumDecl) {
            collectEnumDeclaration((EnumDecl) stmt);
        } else if (stmt instanceof StructDecl) {
            collectStructDeclaration((StructDecl) stmt);
        } else if (stmt instanceof VarDecl) {
            collectVariableDeclaration((VarDecl) stmt);
        } else if (stmt instanceof ImportStmt) {
            // Imports are handled separately, but we can track them
            collectImportDeclaration((ImportStmt) stmt);
        }
        // Note: We don't collect block statements, expression statements, etc.
        // as they are not top-level declarations
    }
    
    /**
     * Collect function declaration
     */
    private void collectFunctionDeclaration(FunctionDecl funcDecl) {
        String name = funcDecl.name;
        String returnType = funcDecl.returnType != null ? funcDecl.returnType : "void";
        ScopeManager.Scope accessLevel = funcDecl.scope;
        
        // Check for redefinition
        if (symbolTable.isDefinedLocally(name)) {
            symbolTable.reportSymbolRedefinition(name, funcDecl.getSpan());
            return;
        }
        
        // Create function symbol
        Symbol functionSymbol = new Symbol(
            name,
            Symbol.Kind.FUNCTION,
            returnType,
            false, // functions are not mutable
            null,  // no initial value
            -1,    // no address yet
            accessLevel
        );
        
        symbolTable.define(name, functionSymbol);
    }
    
    	/**
	 * Collect class declaration
	 */
	private void collectClassDeclaration(ClassDecl classDecl) {
		String name = classDecl.name;
		String superclass = classDecl.superclass;
		ScopeManager.Scope accessLevel = classDecl.scope;
		
		// Check for redefinition
		if (symbolTable.isDefinedLocally(name)) {
			symbolTable.reportSymbolRedefinition(name, classDecl.getSpan());
			return;
		}
		
		// Create class symbol
		Symbol classSymbol = new Symbol(
			name,
			Symbol.Kind.CLASS,
			"class",
			false, // classes are not mutable
			null,  // no initial value
			-1,    // no address yet
			accessLevel
		);
		
		symbolTable.define(name, classSymbol);
		
		// Collect class members for forward references within the class
		collectClassMembers(classDecl);
	}
	
	/**
	 * Collect class members for forward references
	 */
	private void collectClassMembers(ClassDecl classDecl) {
		// Store class member symbols with class prefix to avoid conflicts
		// This allows multiple classes to have members with the same name
		for (Stmt stmt : classDecl.members) {
			if (stmt instanceof FunctionDecl) {
				FunctionDecl funcDecl = (FunctionDecl) stmt;
				// Store method symbol with class prefix to avoid conflicts
				String qualifiedName = classDecl.name + "." + funcDecl.name;
				Symbol methodSymbol = new Symbol(
					qualifiedName,
					Symbol.Kind.METHOD,
					funcDecl.returnType != null ? funcDecl.returnType : "void",
					false, // methods are not mutable
					null,
					-1,
					funcDecl.scope
				);
				symbolTable.define(qualifiedName, methodSymbol);
			} else if (stmt instanceof VarDecl) {
				VarDecl varDecl = (VarDecl) stmt;
				// Store field symbol with class prefix to avoid conflicts
				String qualifiedName = classDecl.name + "." + varDecl.name;
				Symbol fieldSymbol = new Symbol(
					qualifiedName,
					Symbol.Kind.FIELD,
					varDecl.type,
					!varDecl.isFinal,
					varDecl.initializer,
					-1,
					varDecl.scope
				);
				symbolTable.define(qualifiedName, fieldSymbol);
			}
			// Note: We don't collect constructors as they're not callable by name
		}
	}
    
    /**
     * Collect enum declaration
     */
    private void collectEnumDeclaration(EnumDecl enumDecl) {
        String name = enumDecl.name;
        ScopeManager.Scope accessLevel = enumDecl.scope;
        
        // Check for redefinition
        if (symbolTable.isDefinedLocally(name)) {
            symbolTable.reportSymbolRedefinition(name, enumDecl.getSpan());
            return;
        }
        
        // Create enum symbol
        Symbol enumSymbol = new Symbol(
            name,
            Symbol.Kind.ENUM,
            "enum",
            false, // enums are not mutable
            null,  // no initial value
            -1,    // no address yet
            accessLevel
        );
        
        symbolTable.define(name, enumSymbol);
        
        // Note: Enum variants will be collected during the second pass
    }
    
    /**
     * Collect struct declaration
     */
    private void collectStructDeclaration(StructDecl structDecl) {
        String name = structDecl.name;
        ScopeManager.Scope accessLevel = structDecl.scope;
        
        // Check for redefinition
        if (symbolTable.isDefinedLocally(name)) {
            symbolTable.reportSymbolRedefinition(name, structDecl.getSpan());
            return;
        }
        
        // Create struct symbol
        Symbol structSymbol = new Symbol(
            name,
            Symbol.Kind.STRUCT,
            "struct",
            false, // structs are not mutable (they're value types)
            null,  // no initial value
            -1,    // no address yet
            accessLevel
        );
        
        symbolTable.define(name, structSymbol);
        
        // Note: Struct fields will be collected during the second pass
    }
    
    /**
     * Collect variable declaration
     */
    private void collectVariableDeclaration(VarDecl varDecl) {
        String name = varDecl.name;
        String declaredType = varDecl.type;
        ScopeManager.Scope accessLevel = varDecl.scope;
        
        // Check for redefinition
        if (symbolTable.isDefinedLocally(name)) {
            symbolTable.reportSymbolRedefinition(name, varDecl.getSpan());
            return;
        }
        
        // Handle nullable types
        if (varDecl.isNullable && declaredType != null) {
            declaredType = declaredType + "?";
        }
        
        // Create variable symbol
        Symbol varSymbol = new Symbol(
            name,
            Symbol.Kind.VARIABLE,
            declaredType,
            !varDecl.isFinal, // mutable unless final
            varDecl.initializer, // initial value if present
            -1, // no address yet
            accessLevel
        );
        
        symbolTable.define(name, varSymbol);
    }
    
    	/**
	 * Collect constructor declaration
	 */
	private void collectConstructorDeclaration(ConstructorDecl constructorDecl) {
		// Constructors don't need to be collected as symbols since they're not callable by name
		// They're automatically called when creating new instances
		// Just analyze the parameters and body for type checking
	}
	
	/**
	 * Collect import declaration (for tracking purposes)
	 */
	private void collectImportDeclaration(ImportStmt importStmt) {
		// Imports are handled by ImportResolver, but we can track them here
		// if needed for future enhancements
	}
} 