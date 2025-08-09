//
// Created by Wylan Shoemaker on 8/6/25.
//

#include "Token.hpp"
#include <sstream>
#include <iomanip>
#include <utility>
#include <type_traits>

namespace lang {
    // --- Token ---

    Token::Token(const TokenType type,
                 const std::string_view lexeme,
                 TokenSpan span,
                 TokenValue value,
                 TokenCategory category)
        : type_(type),
          text_(lexeme),
          span_(std::move(span)),
          value_(std::move(value)),
          category_(category) {
        // Auto-classify when not explicitly provided (except truly invalid tokens)
        if (category_ == TokenCategory::Error && type_ != TokenType::Invalid) {
            category_ = classifyTokenType(type_);
        }
    }

    TokenType Token::type() const noexcept {
        return type_;
    }

    const std::string &Token::text() const noexcept {
        return text_;
    }

    const TokenSpan &Token::span() const noexcept {
        return span_;
    }

    TokenCategory Token::category() const noexcept {
        return category_;
    }

    const TokenValue &Token::value() const noexcept {
        return value_;
    }

    bool Token::hasValue() const noexcept {
        return !std::holds_alternative<std::monostate>(value_);
    }

    bool Token::is(TokenType t) const noexcept {
        return type_ == t;
    }

    bool Token::isCategory(TokenCategory c) const noexcept {
        return category_ == c;
    }

    std::string Token::to_string() const {
        std::ostringstream oss;
        oss << "Token(type:" << tokenTypeName(type_)
                << ", text:\"" << text_ << "\""
                << ", span:" << span_.to_string()
                << ", category:" << tokenCategoryName(category_);

        std::visit([&oss]<typename T>(T &&val) {
            using Decayed = std::decay_t<T>;
            if constexpr (std::is_same_v<Decayed, std::monostate>) {
                oss << ", value: none";
            } else if constexpr (std::is_same_v<Decayed, std::string>) {
                oss << ", value: \"" << val << "\"";
            } else if constexpr (std::is_same_v<Decayed, int64_t>) {
                oss << ", value: " << val;
            } else if constexpr (std::is_same_v<Decayed, double>) {
                oss << ", value: " << std::fixed << std::setprecision(6) << val;
            } else if constexpr (std::is_same_v<Decayed, bool>) {
                oss << ", value: " << (val ? "true" : "false");
            } else if constexpr (std::is_same_v<Decayed, NumericLiteral>) {
                oss << ", value: NumericLiteral{digits=\"" << val.digits
                    << "\", base=" << val.base
                    << ", isFloat=" << (val.isFloat ? "true" : "false")
                    << ", suffix=\"" << val.suffix << "\"}";
            }
        }, value_);

        oss << ")";
        return oss.str();
    }

    bool Token::operator==(const Token &other) const noexcept {
        return type_ == other.type_ &&
               text_ == other.text_ &&
               span_ == other.span_ &&
               value_ == other.value_ &&
               category_ == other.category_;
    }

    bool Token::operator!=(const Token &other) const noexcept {
        return !(*this == other);
    }
} // namespace lang

// --- Hash support for unordered_map / unordered_set ---

namespace std {
    std::size_t hash<lang::Token>::operator()(const lang::Token &tok) const noexcept {
        // 64-bit FNV-1a for stable ordering across runs; fold to size_t
        constexpr uint64_t FNV_OFFSET = 1469598103934665603ull;
        constexpr uint64_t FNV_PRIME = 1099511628211ull;

        auto mix64 = [&](uint64_t h, uint64_t v) noexcept -> uint64_t {
            h ^= v;
            h *= FNV_PRIME;
            return h;
        };

        uint64_t h = FNV_OFFSET;

        // type
        h = mix64(h, static_cast<uint64_t>(tok.type()));

        // category
        h = mix64(h, static_cast<uint64_t>(tok.category()));

        // text
        const std::string &s = tok.text();
        for (unsigned char c: s) {
            h = mix64(h, c);
        }

        // span
        const auto &sp = tok.span();
        // include file path to reduce collisions
        for (unsigned char c: sp.file) {
            h = mix64(h, c);
        }
        h = mix64(h, sp.startLine);
        h = mix64(h, sp.startColumn);
        h = mix64(h, sp.endLine);
        h = mix64(h, sp.endColumn);

        if constexpr (sizeof(std::size_t) >= sizeof(uint64_t)) {
            return h;
        } else {
            return static_cast<std::size_t>(
                (h >> (8 * sizeof(std::size_t))) ^ (h & ((1ull << (8 * sizeof(std::size_t))) - 1))
            );
        }
    }
}

// --- Token names and classification ---

namespace lang {
    TokenCategory classifyTokenType(TokenType type) noexcept {
        using TT = TokenType;
        switch (type) {
            // Literals
            case TT::Char:
            case TT::False:
            case TT::Null:
            case TT::Number:
            case TT::String:
            case TT::True:
                return TokenCategory::Literal;
            // Keywords
            case TT::As:
            case TT::Atomic:
            case TT::Bit:
            case TT::Bool:
            case TT::Break:
            case TT::Builder:
            case TT::Case:
            case TT::Class:
            case TT::Const:
            case TT::Continue:
            case TT::Default:
            case TT::Do:
            case TT::Elif:
            case TT::Else:
            case TT::Enum:
            case TT::Fin:
            case TT::For:
            case TT::Func:
            case TT::If:
            case TT::Import:
            case TT::In:
            case TT::Internal:
            case TT::Let:
            case TT::Loop:
            case TT::Mod:
            case TT::New:
            case TT::Priv:
            case TT::Prot:
            case TT::Pub:
            case TT::Ret:
            case TT::Rev:
            case TT::Self:
            case TT::Step:
            case TT::Struct:
            case TT::Super:
            case TT::Switch:
            case TT::This:
            case TT::Var:
            case TT::While:
                return TokenCategory::Keyword;
            // Built-in types are considered keywords or identifiers depending on language design; choose Keyword here.
            case TT::Byte:
            case TT::f16:
            case TT::f32:
            case TT::f64:
            case TT::i8:
            case TT::i16:
            case TT::i32:
            case TT::i64:
            case TT::u8:
            case TT::u16:
            case TT::u32:
            case TT::u64:
                return TokenCategory::Keyword;
            // Operators
            case TT::And:
            case TT::Arrow:
            case TT::DoubleEqual:
            case TT::Equal:
            case TT::Greater:
            case TT::GreaterEqual:
            case TT::Less:
            case TT::LessEqual:
            case TT::Minus:
            case TT::Not:
            case TT::NotEqual:
            case TT::Or:
            case TT::Percent:
            case TT::Plus:
            case TT::Range:
            case TT::Range_Inclusive:
            case TT::Slash:
            case TT::Star:
                return TokenCategory::Operator;
            // Punctuation
            case TT::Colon:
            case TT::Comma:
            case TT::DoubleColon:
            case TT::Dot:
            case TT::LBrace:
            case TT::LBracket:
            case TT::LParen:
            case TT::Question:
            case TT::RBrace:
            case TT::RBracket:
            case TT::RParen:
            case TT::Semicolon:
                return TokenCategory::Punctuation;
            // Identifier token kind stands alone
            case TT::Identifier:
                return TokenCategory::Identifier;
            // Special
            case TT::EndOfFile:
                return TokenCategory::Eof;
            case TT::Invalid:
                return TokenCategory::Error;
        }
        return TokenCategory::Error;
    }

    std::string_view tokenTypeName(TokenType type) noexcept {
        switch (type) {
#define CASE_NAME(x) case TokenType::x: return #x;
            CASE_NAME(Char)
            CASE_NAME(False)
            CASE_NAME(Identifier)
            CASE_NAME(Null)
            CASE_NAME(Number)
            CASE_NAME(String)
            CASE_NAME(True)
            CASE_NAME(As)
            CASE_NAME(Atomic)
            CASE_NAME(Bit)
            CASE_NAME(Bool)
            CASE_NAME(Break)
            CASE_NAME(Builder)
            CASE_NAME(Case)
            CASE_NAME(Class)
            CASE_NAME(Const)
            CASE_NAME(Continue)
            CASE_NAME(Default)
            CASE_NAME(Do)
            CASE_NAME(Elif)
            CASE_NAME(Else)
            CASE_NAME(Enum)
            CASE_NAME(Fin)
            CASE_NAME(For)
            CASE_NAME(Func)
            CASE_NAME(If)
            CASE_NAME(Import)
            CASE_NAME(In)
            CASE_NAME(Internal)
            CASE_NAME(Let)
            CASE_NAME(Loop)
            CASE_NAME(Mod)
            CASE_NAME(New)
            CASE_NAME(Priv)
            CASE_NAME(Prot)
            CASE_NAME(Pub)
            CASE_NAME(Ret)
            CASE_NAME(Rev)
            CASE_NAME(Self)
            CASE_NAME(Step)
            CASE_NAME(Struct)
            CASE_NAME(Super)
            CASE_NAME(Switch)
            CASE_NAME(This)
            CASE_NAME(Var)
            CASE_NAME(While)
            CASE_NAME(Byte)
            CASE_NAME(f16)
            CASE_NAME(f32)
            CASE_NAME(f64)
            CASE_NAME(i8)
            CASE_NAME(i16)
            CASE_NAME(i32)
            CASE_NAME(i64)
            CASE_NAME(u8)
            CASE_NAME(u16)
            CASE_NAME(u32)
            CASE_NAME(u64)
            CASE_NAME(And)
            CASE_NAME(Arrow)
            CASE_NAME(DoubleEqual)
            CASE_NAME(Equal)
            CASE_NAME(Greater)
            CASE_NAME(GreaterEqual)
            CASE_NAME(Less)
            CASE_NAME(LessEqual)
            CASE_NAME(Minus)
            CASE_NAME(Not)
            CASE_NAME(NotEqual)
            CASE_NAME(Or)
            CASE_NAME(Percent)
            CASE_NAME(Plus)
            CASE_NAME(Range)
            CASE_NAME(Range_Inclusive)
            CASE_NAME(Slash)
            CASE_NAME(Star)
            CASE_NAME(Colon)
            CASE_NAME(Comma)
            CASE_NAME(DoubleColon)
            CASE_NAME(Dot)
            CASE_NAME(LBrace)
            CASE_NAME(LBracket)
            CASE_NAME(LParen)
            CASE_NAME(Question)
            CASE_NAME(RBrace)
            CASE_NAME(RBracket)
            CASE_NAME(RParen)
            CASE_NAME(Semicolon)
            CASE_NAME(EndOfFile)
            CASE_NAME(Invalid)
#undef CASE_NAME
        }
        return "Unknown";
    }

    std::string_view tokenCategoryName(TokenCategory category) noexcept {
        switch (category) {
            case TokenCategory::Literal: return "Literal";
            case TokenCategory::Keyword: return "Keyword";
            case TokenCategory::Operator: return "Operator";
            case TokenCategory::Punctuation: return "Punctuation";
            case TokenCategory::Identifier: return "Identifier";
            case TokenCategory::Whitespace: return "Whitespace";
            case TokenCategory::Comment: return "Comment";
            case TokenCategory::Error: return "Error";
            case TokenCategory::Eof: return "Eof";
        }
        return "Unknown";
    }
} // namespace lang
