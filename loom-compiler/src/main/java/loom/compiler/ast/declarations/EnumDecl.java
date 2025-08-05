package loom.compiler.ast.declarations;

import loom.compiler.ast.Expr;
import loom.compiler.ast.NodeVisitor;
import loom.compiler.ast.Stmt;
import loom.compiler.parser.ScopeManager;
import loom.compiler.token.TokenSpan;

import java.util.List;

public class EnumDecl implements Stmt {
    public final String name;
    public final List<EnumConstant> constants;
    public final List<VarDecl> fields; // Enum fields/member variables
    public final ConstructorDecl constructor; // Optional constructor for enum constants with parameters
    public final ScopeManager.Scope scope;
    public final TokenSpan span;

    public EnumDecl(String name, List<EnumConstant> constants, List<VarDecl> fields, ConstructorDecl constructor, ScopeManager.Scope scope, TokenSpan span) {
        this.name = name;
        this.constants = constants;
        this.fields = fields;
        this.constructor = constructor;
        this.scope = scope;
        this.span = span;
    }

    @Override
    public <T> T accept(NodeVisitor<T> visitor) {
        return visitor.visitEnumDecl(this);
    }

    @Override
    public TokenSpan getSpan() {
        return span;
    }

    public static class EnumConstant {
        public final String name;
        public final List<Expr> arguments; // For enum constants with parameters (now supports expressions)
        public final TokenSpan span;

        public EnumConstant(String name, List<Expr> arguments, TokenSpan span) {
            this.name = name;
            this.arguments = arguments;
            this.span = span;
        }

        public EnumConstant(String name, TokenSpan span) {
            this(name, List.of(), span);
        }
    }
} 