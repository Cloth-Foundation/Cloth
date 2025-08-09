//
// Created by wylan on 8/6/25.
//

#ifndef TOKEN_H
#define TOKEN_H

#pragma once

#include <string>
#include <string_view>
#include <variant>
#include <optional>
#include <memory>
#include <functional>
#include <cstdint>
#include "TokenSpan.hpp"

// Forward declarations for interop
namespace ast {
    class Node; // if needed
}

namespace lang {
    enum class TokenType : std::uint16_t {
        // Literals
        Char,
        False,
        Identifier,
        Null,
        Number,
        String,
        True,

        // Keywords
        As,
        Atomic, // for atomic references
        Bit, // for booleans and bitsets (or as a bitfield)
        Bool, // boolean
        Break,
        Builder, // like a constructor
        Case,
        Class,
        Const,
        Continue,
        Default,
        Do,
        Elif,
        Else,
        Enum,
        Fin, // final
        For,
        Func,
        If,
        Import,
        In,
        Internal,
        Let,
        Loop,
        Mod, // module
        New,
        Priv,
        Prot,
        Pub,
        Ret, // return
        Rev, // reverse
        Self,
        Step,
        Struct,
        Super,
        Switch,
        This,
        Var,
        While,

        // Built-in Types
        Byte,
        f16,
        f32,
        f64,
        i8,
        i16,
        i32,
        i64,
        u8,
        u16,
        u32,
        u64,

        // Future Type Support (optional, leave commented if unimplemented)
        // f8,
        // f128,
        // i128,
        // u128,
        // isize,
        // usize,

        // Operators
        And, // &&
        Arrow, // ->
        DoubleEqual, // ==
        Equal, // =
        Greater, // >
        GreaterEqual, // >=
        Less, // <
        LessEqual, // <=
        Minus, // -
        Not, // !
        NotEqual, // !=
        Or, // ||
        Percent, // %
        Plus, // +
        Range, // ..
        Range_Inclusive, // ..=
        Slash, // /
        Star, // *

        // Symbols
        Colon, // :
        Comma, // ,
        DoubleColon, // ::
        Dot, // .
        LBrace, // {
        LBracket, // [
        LParen, // (
        Question, // ?
        RBrace, // }
        RBracket, // ]
        RParen, // )
        Semicolon, // ;

        // Special
        EndOfFile,
        Invalid
    };

    /// Category of a token
    enum class TokenCategory : std::uint8_t {
        Literal,
        Keyword,
        Operator,
        Punctuation,
        Identifier,
        Whitespace,
        Comment,
        Error,
        Eof
    };

    // TokenSpan moved to its own header

    // Structured numeric literal metadata preserved from lexing
    struct NumericLiteral {
        std::string digits;   // underscores removed; for floats contains '.'
        int base;             // 2, 10, 16
        bool isFloat;         // true if contains a fractional part/exponent
        std::string suffix;   // e.g., i32, f64, ""

        bool operator==(const NumericLiteral& other) const = default;
    };

    /// Token value types
    using TokenValue = std::variant<
        std::monostate, // for tokens with no value (punctuation, keywords, etc.)
        std::string, // identifiers, strings, chars
        int64_t, // integer literals (direct)
        double, // float literals (direct)
        bool, // true/false
        NumericLiteral // structured literal (preferred for numerics)
    >;

    /// Token object
    class Token {
    public:
        Token(TokenType type,
              std::string_view lexeme,
              TokenSpan span,
              TokenValue value = {},
              TokenCategory category = TokenCategory::Error);

        // Accessors
        [[nodiscard]] TokenType type() const noexcept;

        [[nodiscard]] const std::string &text() const noexcept;

        [[nodiscard]] const TokenSpan &span() const noexcept;

        [[nodiscard]] TokenCategory category() const noexcept;

        [[nodiscard]] const TokenValue &value() const noexcept;

        [[nodiscard]] bool hasValue() const noexcept; // value is not monostate

        // Utility
        [[nodiscard]] bool is(TokenType t) const noexcept;

        [[nodiscard]] bool isCategory(TokenCategory c) const noexcept;

        [[nodiscard]] std::string to_string() const;

        // Comparisons
        bool operator==(const Token &other) const noexcept;

        bool operator!=(const Token &other) const noexcept;

    private:
        TokenType type_;
        std::string text_; // Lexeme
        TokenSpan span_;
        TokenValue value_;
        TokenCategory category_;
    };

    // Classification helpers and names
    [[nodiscard]] TokenCategory classifyTokenType(TokenType type) noexcept;
    [[nodiscard]] std::string_view tokenTypeName(TokenType type) noexcept;
    [[nodiscard]] std::string_view tokenCategoryName(TokenCategory category) noexcept;
} // namespace lang

namespace std {
    template<>
    struct hash<lang::Token> {
        std::size_t operator()(const lang::Token &tok) const noexcept;
    };
}

#endif //TOKEN_H
