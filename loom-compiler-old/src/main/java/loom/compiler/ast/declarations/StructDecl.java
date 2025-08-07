package loom.compiler.ast.declarations;

import loom.compiler.ast.NodeVisitor;
import loom.compiler.ast.Stmt;
import loom.compiler.parser.ScopeManager;
import loom.compiler.token.TokenSpan;

import java.util.List;

public class StructDecl implements Stmt {
    public final String name;
    public final List<VarDecl> fields;
    public final TokenSpan span;
    public final ScopeManager.Scope scope;

    public StructDecl(String name, List<VarDecl> fields, TokenSpan span, ScopeManager.Scope scope) {
        this.name = name;
        this.fields = fields;
        this.span = span;
        this.scope = scope;
        setFieldScopes();
    }

    // Ensure all fields inherit the struct's access modifier
    private void setFieldScopes() {
        for (int i = 0; i < fields.size(); i++) {
            VarDecl oldField = fields.get(i);
            // Create a new VarDecl with the struct's scope
            VarDecl newField = new VarDecl(
                oldField.name,
                oldField.type,
                oldField.initializer,
                oldField.span,
                this.scope,
                oldField.isFinal,
                oldField.isNullable
            );
            fields.set(i, newField);
        }
    }

    @Override
    public <T> T accept(NodeVisitor<T> visitor) {
        return visitor.visitStructDecl(this);
    }

    @Override
    public TokenSpan getSpan() {
        return span;
    }
} 