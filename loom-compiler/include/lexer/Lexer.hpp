#ifndef LEXER_HPP
#define LEXER_HPP

#pragma once

#include <string>
#include <string_view>
#include <vector>
#include <optional>

#include "Token.hpp"

namespace loom {
    class Lexer {
    public:
        explicit Lexer(std::string source, std::string fileName = "<memory>");

        [[nodiscard]] bool eof() const noexcept;

        // Returns the next token without consuming it
        [[nodiscard]] const Token &peek();

        // Consumes and returns the next token
        Token next();

        // Tokenize the entire input
        [[nodiscard]] std::vector<Token> tokenizeAll();

    private:
        // Core scanning
        Token scanToken();

        // Helpers
        [[nodiscard]] bool isAtEnd() const noexcept;

        [[nodiscard]] char current() const noexcept;

        [[nodiscard]] char lookahead(std::size_t n = 1) const noexcept;

        char advance();

        bool match(char expected);

        void skipWhitespaceAndComments();

        // Scanners
        Token scanIdentifierOrKeyword();

        Token scanNumber();

        Token scanString();

        Token scanCharLiteral();

        Token scanOperatorOrPunctuation();

        // Builders
        Token makeToken(TokenType type, std::string_view lexeme, TokenValue value = {});

        Token makeTokenFromRange(TokenType type,
                                 std::size_t startPos,
                                 std::size_t startLine,
                                 std::size_t startCol,
                                 TokenValue value = {});

        Token makeInvalidToken(std::string_view message,
                               std::size_t startPos,
                               std::size_t startLine,
                               std::size_t startCol);

        // Keyword recognition
        static std::optional<TokenType> lookupKeyword(std::string_view text) noexcept;

        std::string source_;
        std::string fileName_;
        std::size_t pos_{0};
        std::size_t line_{1};
        std::size_t column_{1};

        // single-token lookahead cache
        std::optional<Token> lookaheadToken_;
    };
} // namespace lang

#endif // LEXER_HPP
