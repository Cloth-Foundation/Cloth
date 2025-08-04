package loom.compiler.ast.expressions;

import loom.compiler.ast.Expr;
import loom.compiler.ast.NodeVisitor;
import loom.compiler.token.TokenSpan;

public class DecrementExpr implements Expr {
    public final Expr operand;
    public final boolean isPrefix; // true for --x, false for x--
    public final TokenSpan span;

    public DecrementExpr(Expr operand, boolean isPrefix, TokenSpan span) {
        this.operand = operand;
        this.isPrefix = isPrefix;
        this.span = span;
    }

    @Override
    public <T> T accept(NodeVisitor<T> visitor) {
        return visitor.visitDecrementExpr(this);
    }

    @Override
    public TokenSpan getSpan() {
        return span;
    }
} 