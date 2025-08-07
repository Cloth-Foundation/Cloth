package loom.compiler.ast.expressions;

import loom.compiler.ast.NodeVisitor;
import loom.compiler.ast.Expr;
import loom.compiler.token.TokenSpan;

import java.util.Map;

public class StructExpr implements Expr {
    public final String structName;
    public final Map<String, Expr> fieldValues;
    public final TokenSpan span;

    public StructExpr(String structName, Map<String, Expr> fieldValues, TokenSpan span) {
        this.structName = structName;
        this.fieldValues = fieldValues;
        this.span = span;
    }

    @Override
    public <T> T accept(NodeVisitor<T> visitor) {
        return visitor.visitStructExpr(this);
    }

    @Override
    public TokenSpan getSpan() {
        return span;
    }
} 