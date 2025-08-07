package loom.compiler.parser.objects;

import loom.compiler.ast.Expr;
import loom.compiler.ast.Stmt;
import loom.compiler.ast.declarations.StructDecl;
import loom.compiler.ast.declarations.VarDecl;
import loom.compiler.parser.Parser;
import loom.compiler.parser.ScopeManager;
import loom.compiler.token.Keywords;
import loom.compiler.token.Token;
import loom.compiler.token.TokenSpan;
import loom.compiler.token.TokenType;

import java.util.ArrayList;
import java.util.List;

public class StructParser {

    private final Parser parser;

    public StructParser(Parser parser) {
        this.parser = parser;
    }

    public StructDecl parseStructDeclaration() {
        Token start = parser.peek();

        // Parse struct name
        Token nameToken = parser.consume(TokenType.IDENTIFIER, "Expected struct name after 'struct'");

        // Parse opening brace
        parser.consume(TokenType.LBRACE, "Expected '{' after struct name");

        // Parse struct fields (no 'var' keyword needed)
        List<VarDecl> fields = new ArrayList<>();
        while (!parser.check(TokenType.RBRACE) && !parser.isAtEnd()) {
            // Parse field declaration without 'var' keyword
            VarDecl field = parseStructField();
            fields.add(field);
        }

        // Parse closing brace
        parser.consume(TokenType.RBRACE, "Expected '}' after struct fields");

        TokenSpan span = start.getSpan().merge(parser.previous().getSpan());
        // Use the struct's access modifier (default to public for now)
        ScopeManager.Scope structScope = ScopeManager.Scope.DEFAULT;
        StructDecl structDecl = new StructDecl(nameToken.value, fields, span, structScope);

        // Register the struct declaration in the symbol table
        parser.getScopeManager().define(nameToken.value, loom.compiler.semantic.Symbol.Kind.STRUCT, null, false, structDecl, 0);

        return structDecl;
    }

    private VarDecl parseStructField() {
        // No 'var' keyword needed for struct fields
        
        Token name = parser.consume(TokenType.IDENTIFIER, "Expected field name");

        // Parse type
        String type = null;
        boolean isNullable = false;
        if (parser.match(TokenType.ARROW)) {
            Token typeToken = null;
            if (parser.peek().isOfType(TokenType.KEYWORD)) {
                typeToken = parser.consume(TokenType.KEYWORD, "Expected type name after '->'");
            } else if (parser.peek().isOfType(TokenType.IDENTIFIER)) {
                typeToken = parser.consume(TokenType.IDENTIFIER, "Expected type name after '->'");
            }

            type = typeToken.value;
            
            // Check for nullable modifier
            if (parser.match(TokenType.QUESTION)) {
                isNullable = true;
            }
        }

        // Struct fields don't have initializers
        Expr initializer = null;

        // Validation: must have type
        if (type == null) {
            parser.reportError(name, "Struct field must have a type.");
        }

        // Consume optional semicolon after field
        parser.match(TokenType.SEMICOLON);

        TokenSpan span = name.getSpan();
        return new VarDecl(name.value, type, initializer, span, ScopeManager.Scope.DEFAULT, false, isNullable);
    }
} 