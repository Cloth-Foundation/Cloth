package loom.compiler.ast.expressions;

import loom.compiler.ast.Expr;
import loom.compiler.ast.NodeVisitor;
import loom.compiler.token.TokenSpan;

import java.util.List;

public class ProjectedEnumExpr implements Expr {
    public final Expr enumVariant; // The enum variant (e.g., Config.DEBUG)
    public final List<ProjectedField> projectedFields; // List of fields to project
    public final TokenSpan span;

    public ProjectedEnumExpr(Expr enumVariant, List<ProjectedField> projectedFields, TokenSpan span) {
        this.enumVariant = enumVariant;
        this.projectedFields = projectedFields;
        this.span = span;
    }

    @Override
    public <T> T accept(NodeVisitor<T> visitor) {
        return visitor.visitProjectedEnumExpr(this);
    }

    @Override
    public TokenSpan getSpan() {
        return span;
    }

    public static class ProjectedField {
        public final String fieldName; // Original field name
        public final String alias; // Optional alias (can be null if no renaming)

        public ProjectedField(String fieldName, String alias) {
            this.fieldName = fieldName;
            this.alias = alias;
        }

        public ProjectedField(String fieldName) {
            this(fieldName, null);
        }

        public String getFinalName() {
            return alias != null ? alias : fieldName;
        }
    }
} 