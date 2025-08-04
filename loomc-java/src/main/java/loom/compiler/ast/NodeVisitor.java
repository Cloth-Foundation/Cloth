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
import loom.compiler.ast.expressions.StructExpr;
import loom.compiler.ast.expressions.TernaryExpr;
import loom.compiler.ast.expressions.CompoundAssignExpr;
import loom.compiler.ast.statements.BlockStmt;
import loom.compiler.ast.statements.ExpressionStmt;
import loom.compiler.ast.statements.IfStmt;
import loom.compiler.ast.statements.BreakStmt;
import loom.compiler.ast.statements.ContinueStmt;
import loom.compiler.ast.statements.DoWhileStmt;
import loom.compiler.ast.statements.ForStmt;
import loom.compiler.ast.statements.ImportStmt;
import loom.compiler.ast.statements.ReturnStmt;
import loom.compiler.ast.statements.WhileStmt;

public interface NodeVisitor<T> {

	T visitLiteralExpr (LiteralExpr expr);

	T visitBinaryExpr (BinaryExpr expr);

	T visitVariableExpr (VariableExpr expr);

	T visitUnaryExpr (UnaryExpr expr);

	T visitCallExpr (CallExpr expr);

	T visitNewExpr (NewExpr expr);

	T visitMemberAccessExpr (MemberAccessExpr expr);

	T visitGetExpr (GetExpr expr);

	T visitAssignExpr (AssignExpr expr);

	T visitFunctionDecl (FunctionDecl decl);

	T visitVarDecl (VarDecl decl);

	T visitClassDecl (ClassDecl decl);

	T visitEnumDecl (EnumDecl decl);

	T visitStructDecl (StructDecl decl);

	T visitConstructorDecl (ConstructorDecl decl);

	T visitReturnStmt (ReturnStmt stmt);

	T visitExpressionStmt (ExpressionStmt stmt);

	T visitBlockStmt (BlockStmt stmt);

	T visitImportStmt (ImportStmt stmt);

	T visitIfStmt (IfStmt stmt);

	T visitWhileStmt (WhileStmt stmt);

	T visitForStmt (ForStmt stmt);

	T visitDoWhileStmt (DoWhileStmt stmt);

	T visitBreakStmt (BreakStmt stmt);

	T visitContinueStmt (ContinueStmt stmt);
	
	T visitIncrementExpr (IncrementExpr expr);
	T visitDecrementExpr (DecrementExpr expr);
	T visitProjectedEnumExpr (ProjectedEnumExpr expr);
	T visitTernaryExpr (TernaryExpr expr);
	T visitCompoundAssignExpr (CompoundAssignExpr expr);
	T visitStructExpr (StructExpr expr);
	// Add more as needed

}
