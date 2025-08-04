package loom.compiler.ast.statements;

import loom.compiler.ast.NodeVisitor;
import loom.compiler.ast.Stmt;
import loom.compiler.token.TokenSpan;

import java.util.List;

public class ImportStmt implements Stmt {
	public final List<String> path;
	public final List<String> symbols;
	public final TokenSpan span;

	public ImportStmt(List<String> path, List<String> symbols, TokenSpan span) {
		this.path = path;
		this.symbols = symbols;
		this.span = span;
	}

	@Override
	public <T> T accept(NodeVisitor<T> visitor) {
		return visitor.visitImportStmt(this);
	}

	@Override
	public TokenSpan getSpan() {
		return span;
	}
}
