#ifndef TOKEN_SPAN_HPP
#define TOKEN_SPAN_HPP

#pragma once

#include <string>

namespace loom {

// Location and range of a token in source
struct TokenSpan {
    std::string file;
    std::size_t startLine;
    std::size_t startColumn;
    std::size_t endLine;
    std::size_t endColumn;

    bool operator==(const TokenSpan &other) const noexcept;

    [[nodiscard]] std::string to_string() const;
};

} // namespace lang

#endif // TOKEN_SPAN_HPP


