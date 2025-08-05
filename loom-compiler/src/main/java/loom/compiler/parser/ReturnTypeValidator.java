package loom.compiler.parser;

import loom.compiler.ast.Stmt;
import loom.compiler.ast.statements.ReturnStmt;
import loom.compiler.diagnostics.ErrorReporter;
import loom.compiler.token.Token;

import java.util.List;

public class ReturnTypeValidator {
	
	private final ErrorReporter reporter;
	
	public ReturnTypeValidator(ErrorReporter reporter) {
		this.reporter = reporter;
	}
	
	/**
	 * Validates that a function's return statements match its declared return type
	 */
	public void validateFunctionReturnType(String functionName, String returnType, List<Stmt> body, Token functionToken) {
		if (returnType == null || returnType.equals("void")) {
			// Void function - should not return values
			validateVoidFunction(functionName, body, functionToken);
		} else {
			// Non-void function - must return values
			validateNonVoidFunction(functionName, returnType, body, functionToken);
		}
	}
	
	private void validateVoidFunction(String functionName, List<Stmt> body, Token functionToken) {
		for (Stmt stmt : body) {
			if (stmt instanceof ReturnStmt) {
				ReturnStmt returnStmt = (ReturnStmt) stmt;
				if (returnStmt.value != null) {
					reporter.reportError(returnStmt.getSpan(), "Void function '" + functionName + "' should not return a value", returnStmt.value.toString());
				}
			}
		}
	}
	
	private void validateNonVoidFunction(String functionName, String returnType, List<Stmt> body, Token functionToken) {
		boolean hasReturn = false;
		boolean hasReturnWithValue = false;
		TypeChecker typeChecker = new TypeChecker(reporter);
		
		for (Stmt stmt : body) {
			if (stmt instanceof ReturnStmt returnStmt) {
				hasReturn = true;
				
				if (returnStmt.value != null) {
					hasReturnWithValue = true;
					// Validate that the return expression type matches the function's return type
					typeChecker.validateReturnType(returnStmt.value, returnType, returnStmt.getSpan(), functionName);
				} else {
					reporter.reportError(
						returnStmt.getSpan(),
						"Function '" + functionName + "' with return type '" + returnType + "' must return a value",
						"return;"
					);
				}
			}
		}
		
		if (!hasReturnWithValue) {
			reporter.reportError(
				functionToken.getSpan(),
				"Function '" + functionName + "' with return type '" + returnType + "' must return a value",
				functionName
			);
		}
	}
} 