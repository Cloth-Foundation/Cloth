//
// Created by wylan on 8/6/25.
//

#include "Token.hpp"
#include <sstream>
#include <iomanip>
#include <utility>

namespace lang {

// --- TokenSpan ---

bool TokenSpan::operator==(const TokenSpan& other) const {
    return file == other.file &&
           startLine == other.startLine &&
           startColumn == other.startColumn &&
           endLine == other.endLine &&
           endColumn == other.endColumn;
}

std::string TokenSpan::to_string() const {
    std::ostringstream oss;
    oss << file << ":" << startLine << ":" << startColumn
        << "-" << endLine << ":" << endColumn;
    return oss.str();
}

// --- Token ---

Token::Token(const TokenType type,
             const std::string_view lexeme,
             TokenSpan  span,
             TokenValue value,
             TokenCategory category)
    : type_(type),
      text_(lexeme),
      span_(std::move(span)),
      value_(std::move(value)),
      category_(category)
{}

TokenType Token::type() const {
    return type_;
}

const std::string& Token::text() const {
    return text_;
}

const TokenSpan& Token::span() const {
    return span_;
}

TokenCategory Token::category() const {
    return category_;
}

const TokenValue& Token::value() const {
    return value_;
}

bool Token::is(TokenType t) const {
    return type_ == t;
}

bool Token::isCategory(TokenCategory c) const {
    return category_ == c;
}

std::string Token::to_string() const {
    std::ostringstream oss;
    oss << "Token(" << static_cast<int>(type_) << ", \"" << text_ << "\", " << span_.to_string();

    std::visit([&oss]<typename visitor>(visitor&& val) {
        using T = std::decay_t<visitor>;
        if constexpr (std::is_same_v<T, std::monostate>) {
            oss << ", value: none";
        } else if constexpr (std::is_same_v<T, std::string>) {
            oss << ", value: \"" << val << "\"";
        } else if constexpr (std::is_same_v<T, int64_t>) {
            oss << ", value: " << val;
        } else if constexpr (std::is_same_v<T, double>) {
            oss << ", value: " << std::fixed << std::setprecision(6) << val;
        } else if constexpr (std::is_same_v<T, bool>) {
            oss << ", value: " << (val ? "true" : "false");
        }
    }, value_);

    oss << ")";
    return oss.str();
}

bool Token::operator==(const Token& other) const {
    return type_ == other.type_ &&
           text_ == other.text_ &&
           span_ == other.span_ &&
           value_ == other.value_ &&
           category_ == other.category_;
}

bool Token::operator!=(const Token& other) const {
    return !(*this == other);
}

} // namespace lang

// --- Hash support for unordered_map / unordered_set ---

namespace std {
    std::size_t hash<lang::Token>::operator()(const lang::Token& tok) const noexcept {
        std::hash<std::string> h;
        return h(tok.text()) ^ static_cast<std::size_t>(tok.type());
    }
}
