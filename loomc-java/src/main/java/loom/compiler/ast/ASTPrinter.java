package loom.compiler.ast;

import loom.compiler.ast.declarations.ClassDecl;
import loom.compiler.ast.declarations.ConstructorDecl;
import loom.compiler.ast.declarations.EnumDecl;
import loom.compiler.ast.declarations.FunctionDecl;
import loom.compiler.ast.declarations.StructDecl;
import loom.compiler.ast.declarations.VarDecl;
import loom.compiler.ast.expressions.*;
import loom.compiler.ast.expressions.IncrementExpr;
import loom.compiler.ast.expressions.DecrementExpr;
import loom.compiler.ast.expressions.ProjectedEnumExpr;
import loom.compiler.ast.expressions.TernaryExpr;
import loom.compiler.ast.expressions.CompoundAssignExpr;
import loom.compiler.ast.statements.BlockStmt;
import loom.compiler.ast.statements.BreakStmt;
import loom.compiler.ast.statements.ContinueStmt;
import loom.compiler.ast.statements.DoWhileStmt;
import loom.compiler.ast.statements.ExpressionStmt;
import loom.compiler.ast.statements.ForStmt;
import loom.compiler.ast.statements.IfStmt;
import loom.compiler.ast.statements.ImportStmt;
import loom.compiler.ast.statements.ReturnStmt;
import loom.compiler.ast.statements.WhileStmt;
import loom.compiler.parser.ScopeManager;

import java.util.Map;

public class ASTPrinter implements NodeVisitor<String> {

	private int indentLevel = 0;
	private static final String INDENT = "  ";

	private String indent() {
		return INDENT.repeat(indentLevel);
	}

	private String withIndent(String content) {
		return indent() + content;
	}

	private String visitChild(Node node) {
		indentLevel++;
		String result = node.accept(this);
		indentLevel--;
		return result;
	}

	private String formatScope(ScopeManager.Scope scope) {
		if (scope == ScopeManager.Scope.DEFAULT) return "";
		return " [" + scope.name().toLowerCase() + "]";
	}

	@Override
	public String visitLiteralExpr(LiteralExpr expr) {
		return withIndent("Literal: " + (expr.value == null ? "null" : expr.value.toString()));
	}

	@Override
	public String visitVariableExpr(VariableExpr expr) {
		return withIndent("Variable: " + expr.name);
	}

	@Override
	public String visitBinaryExpr(BinaryExpr expr) {
		StringBuilder sb = new StringBuilder();
		sb.append(withIndent("Binary Expression: " + expr.operator));
		sb.append("\n").append(visitChild(expr.left));
		sb.append("\n").append(visitChild(expr.right));
		return sb.toString();
	}

	@Override
	public String visitUnaryExpr(UnaryExpr expr) {
		StringBuilder sb = new StringBuilder();
		sb.append(withIndent("Unary Expression: " + expr.operator));
		sb.append("\n").append(visitChild(expr.right));
		return sb.toString();
	}

	@Override
	public String visitCallExpr(CallExpr expr) {
		StringBuilder sb = new StringBuilder();
		sb.append(withIndent("Function Call: "));
		sb.append("\n").append(visitChild(expr.callee));
		
		if (!expr.arguments.isEmpty()) {
			sb.append("\n").append(withIndent("Arguments:"));
			for (Expr arg : expr.arguments) {
				sb.append("\n").append(visitChild(arg));
			}
		}
		return sb.toString();
	}

	@Override
	public String visitNewExpr(NewExpr expr) {
		StringBuilder sb = new StringBuilder();
		sb.append(withIndent("New Object: " + expr.className));
		
		if (!expr.arguments.isEmpty()) {
			sb.append("\n").append(withIndent("Constructor Arguments:"));
			for (Expr arg : expr.arguments) {
				sb.append("\n").append(visitChild(arg));
			}
		}
		return sb.toString();
	}

	@Override
	public String visitMemberAccessExpr(MemberAccessExpr expr) {
		StringBuilder sb = new StringBuilder();
		sb.append(withIndent("Member Access: " + expr.member));
		sb.append("\n").append(visitChild(expr.object));
		return sb.toString();
	}

	@Override
	public String visitGetExpr(GetExpr expr) {
		StringBuilder sb = new StringBuilder();
		sb.append(withIndent("Field Access: " + expr.field));
		sb.append("\n").append(visitChild(expr.object));
		return sb.toString();
	}

	@Override
	public String visitAssignExpr(AssignExpr expr) {
		StringBuilder sb = new StringBuilder();
		sb.append(withIndent("Assignment:"));
		sb.append("\n").append(visitChild(expr.target));
		sb.append("\n").append(visitChild(expr.value));
		return sb.toString();
	}

	@Override
	public String visitVarDecl(VarDecl stmt) {
		StringBuilder sb = new StringBuilder();
		String finalStatus = stmt.isFinal ? " [final]" : "";
		String nullableStatus = stmt.isNullable ? " [nullable]" : "";
		sb.append(withIndent("Variable Declaration" + formatScope(stmt.scope) + finalStatus + nullableStatus + ": " + stmt.name));
		if (stmt.type != null) {
			String typeDisplay = stmt.type;
			if (stmt.isNullable) {
				typeDisplay = stmt.type + "?";
			}
			sb.append(" -> " + typeDisplay);
		}
		
		if (stmt.initializer != null) {
			sb.append("\n").append(withIndent("Initializer:"));
			sb.append("\n").append(visitChild(stmt.initializer));
		}
		return sb.toString();
	}

	@Override
	public String visitFunctionDecl(FunctionDecl stmt) {
		StringBuilder sb = new StringBuilder();
		sb.append(withIndent("Function Declaration" + formatScope(stmt.scope) + ": " + stmt.name));
		
		// Parameters
		if (stmt.parameters.isEmpty()) {
			sb.append("()");
		} else {
			sb.append("\n").append(withIndent("Parameters:"));
			for (Parameter param : stmt.parameters) {
				sb.append("\n").append(withIndent("- " + param.name + ": " + param.type));
			}
		}
		
		// Return type
		if (stmt.returnType != null) {
			sb.append(" -> " + stmt.returnType);
		}
		
		// Body
		if (!stmt.body.isEmpty()) {
			sb.append("\n").append(withIndent("Body:"));
			for (Stmt bodyStmt : stmt.body) {
				sb.append("\n").append(visitChild(bodyStmt));
			}
		}
		return sb.toString();
	}

	@Override
	public String visitConstructorDecl(ConstructorDecl stmt) {
		StringBuilder sb = new StringBuilder();
		sb.append(withIndent("Constructor Declaration:"));
		
		// Parameters
		if (stmt.parameters.isEmpty()) {
			sb.append("()");
		} else {
			sb.append("\n").append(withIndent("Parameters:"));
			for (Parameter param : stmt.parameters) {
				sb.append("\n").append(withIndent("- " + param.name + ": " + param.type));
			}
		}
		
		// Body
		if (!stmt.body.isEmpty()) {
			sb.append("\n").append(withIndent("Body:"));
			for (Stmt bodyStmt : stmt.body) {
				sb.append("\n").append(visitChild(bodyStmt));
			}
		}
		return sb.toString();
	}

	@Override
	public String visitClassDecl(ClassDecl stmt) {
		StringBuilder sb = new StringBuilder();
		String finalStatus = stmt.isFinal ? " [final]" : "";
		sb.append(withIndent("Class Declaration" + formatScope(stmt.scope) + finalStatus + ": " + stmt.name));
		
		if (stmt.superclass != null) {
			sb.append(" extends " + stmt.superclass);
		}
		
		if (!stmt.members.isEmpty()) {
			sb.append("\n").append(withIndent("Members:"));
			for (Stmt member : stmt.members) {
				sb.append("\n").append(visitChild(member));
			}
		}
		return sb.toString();
	}

	@Override
	public String visitEnumDecl(EnumDecl stmt) {
		StringBuilder sb = new StringBuilder();
		sb.append(withIndent("Enum Declaration" + formatScope(stmt.scope) + ": " + stmt.name));
		
		if (!stmt.constants.isEmpty()) {
			sb.append("\n").append(withIndent("Constants:"));
			for (EnumDecl.EnumConstant constant : stmt.constants) {
				sb.append("\n").append(withIndent("- " + constant.name));
				if (!constant.arguments.isEmpty()) {
					sb.append("(");
					for (int i = 0; i < constant.arguments.size(); i++) {
						if (i > 0) sb.append(", ");
						sb.append(visitChild(constant.arguments.get(i)));
					}
					sb.append(")");
				}
			}
		}
		
		if (!stmt.fields.isEmpty()) {
			sb.append("\n").append(withIndent("Fields:"));
			for (VarDecl field : stmt.fields) {
				sb.append("\n").append(visitChild(field));
			}
		}
		
		if (stmt.constructor != null) {
			sb.append("\n").append(visitChild(stmt.constructor));
		}
		
		return sb.toString();
	}

	@Override
	public String visitStructDecl(StructDecl stmt) {
		StringBuilder sb = new StringBuilder();
		sb.append(withIndent("Struct Declaration" + formatScope(stmt.scope) + ": " + stmt.name));
		
		if (!stmt.fields.isEmpty()) {
			sb.append("\n").append(withIndent("Fields:"));
			for (VarDecl field : stmt.fields) {
				sb.append("\n").append(visitChild(field));
			}
		}
		
		return sb.toString();
	}

	@Override
	public String visitReturnStmt(ReturnStmt stmt) {
		StringBuilder sb = new StringBuilder();
		sb.append(withIndent("Return Statement"));
		if (stmt.value != null) {
			sb.append("\n").append(visitChild(stmt.value));
		}
		return sb.toString();
	}

	@Override
	public String visitExpressionStmt(ExpressionStmt stmt) {
		StringBuilder sb = new StringBuilder();
		sb.append(withIndent("Expression Statement:"));
		sb.append("\n").append(visitChild(stmt.expression));
		return sb.toString();
	}

	@Override
	public String visitBlockStmt(BlockStmt stmt) {
		StringBuilder sb = new StringBuilder();
		sb.append(withIndent("Block:"));
		for (Stmt statement : stmt.statements) {
			sb.append("\n").append(visitChild(statement));
		}
		return sb.toString();
	}

	@Override
	public String visitImportStmt(ImportStmt stmt) {
		StringBuilder sb = new StringBuilder();
		sb.append(withIndent("Import: " + String.join("::", stmt.path)));
		if (!stmt.symbols.isEmpty()) {
			sb.append(" {").append(String.join(", ", stmt.symbols)).append("}");
		}
		return sb.toString();
	}

	@Override
	public String visitIfStmt(IfStmt stmt) {
		StringBuilder sb = new StringBuilder();
		sb.append(withIndent("If Statement:"));
		sb.append("\n").append(withIndent("Condition:"));
		sb.append("\n").append(visitChild(stmt.condition));
		sb.append("\n").append(withIndent("Then Branch:"));
		for (Stmt thenStmt : stmt.thenBranch) {
			sb.append("\n").append(visitChild(thenStmt));
		}
		if (stmt.elseBranch != null) {
			sb.append("\n").append(withIndent("Else Branch:"));
			for (Stmt elseStmt : stmt.elseBranch) {
				sb.append("\n").append(visitChild(elseStmt));
			}
		}
		return sb.toString();
	}

	@Override
	public String visitWhileStmt(WhileStmt stmt) {
		StringBuilder sb = new StringBuilder();
		sb.append(withIndent("While Loop:"));
		sb.append("\n").append(withIndent("Condition:"));
		sb.append("\n").append(visitChild(stmt.condition));
		sb.append("\n").append(withIndent("Body:"));
		for (Stmt bodyStmt : stmt.body) {
			sb.append("\n").append(visitChild(bodyStmt));
		}
		return sb.toString();
	}

	@Override
	public String visitForStmt(ForStmt stmt) {
		StringBuilder sb = new StringBuilder();
		sb.append(withIndent("For Loop:"));
		if (stmt.initializer != null) {
			sb.append("\n").append(withIndent("Initializer:"));
			sb.append("\n").append(visitChild(stmt.initializer));
		}
		if (stmt.condition != null) {
			sb.append("\n").append(withIndent("Condition:"));
			sb.append("\n").append(visitChild(stmt.condition));
		}
		if (stmt.increment != null) {
			sb.append("\n").append(withIndent("Increment:"));
			sb.append("\n").append(visitChild(stmt.increment));
		}
		sb.append("\n").append(withIndent("Body:"));
		for (Stmt bodyStmt : stmt.body) {
			sb.append("\n").append(visitChild(bodyStmt));
		}
		return sb.toString();
	}

	@Override
	public String visitDoWhileStmt(DoWhileStmt stmt) {
		StringBuilder sb = new StringBuilder();
		sb.append(withIndent("Do-While Loop:"));
		sb.append("\n").append(withIndent("Body:"));
		for (Stmt bodyStmt : stmt.body) {
			sb.append("\n").append(visitChild(bodyStmt));
		}
		sb.append("\n").append(withIndent("Condition:"));
		sb.append("\n").append(visitChild(stmt.condition));
		return sb.toString();
	}

	@Override
	public String visitBreakStmt(BreakStmt stmt) {
		return withIndent("Break Statement");
	}

	@Override
	public String visitContinueStmt(ContinueStmt stmt) {
		return withIndent("Continue Statement");
	}

	@Override
	public String visitIncrementExpr(IncrementExpr expr) {
		StringBuilder sb = new StringBuilder();
		sb.append(withIndent("Increment Expression: " + (expr.isPrefix ? "prefix" : "postfix")));
		sb.append("\n").append(visitChild(expr.operand));
		return sb.toString();
	}

	@Override
	public String visitDecrementExpr(DecrementExpr expr) {
		StringBuilder sb = new StringBuilder();
		sb.append(withIndent("Decrement Expression: " + (expr.isPrefix ? "prefix" : "postfix")));
		sb.append("\n").append(visitChild(expr.operand));
		return sb.toString();
	}

	@Override
	public String visitProjectedEnumExpr(ProjectedEnumExpr expr) {
		StringBuilder sb = new StringBuilder();
		sb.append(withIndent("Projected Enum Expression:"));
		sb.append("\n").append(visitChild(expr.enumVariant));
		sb.append("\n").append(withIndent("Projected Fields:"));
		for (ProjectedEnumExpr.ProjectedField field : expr.projectedFields) {
			String fieldDisplay = field.fieldName;
			if (field.alias != null) {
				fieldDisplay += " as " + field.alias;
			}
			sb.append("\n").append(withIndent("- " + fieldDisplay));
		}
		return sb.toString();
	}

	@Override
	public String visitTernaryExpr(TernaryExpr expr) {
		StringBuilder sb = new StringBuilder();
		sb.append(withIndent("Ternary Expression:"));
		sb.append("\n").append(withIndent("Condition:"));
		sb.append("\n").append(visitChild(expr.condition));
		sb.append("\n").append(withIndent("True Branch:"));
		sb.append("\n").append(visitChild(expr.trueExpr));
		sb.append("\n").append(withIndent("False Branch:"));
		sb.append("\n").append(visitChild(expr.falseExpr));
		return sb.toString();
	}

	@Override
	public String visitCompoundAssignExpr(CompoundAssignExpr expr) {
		StringBuilder sb = new StringBuilder();
		sb.append(withIndent("Compound Assignment: " + expr.operator));
		sb.append("\n").append(visitChild(expr.target));
		sb.append("\n").append(visitChild(expr.value));
		return sb.toString();
	}

	@Override
	public String visitStructExpr(StructExpr expr) {
		StringBuilder sb = new StringBuilder();
		sb.append(withIndent("Struct Instantiation: " + expr.structName));
		
		if (!expr.fieldValues.isEmpty()) {
			sb.append("\n").append(withIndent("Fields:"));
			for (Map.Entry<String, Expr> entry : expr.fieldValues.entrySet()) {
				sb.append("\n").append(withIndent(entry.getKey() + ": "));
				sb.append(visitChild(entry.getValue()));
			}
		}
		
		return sb.toString();
	}
}
