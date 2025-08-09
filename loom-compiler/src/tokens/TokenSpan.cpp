#include "TokenSpan.hpp"
#include <sstream>

namespace lang {

bool TokenSpan::operator==(const TokenSpan &other) const noexcept {
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

} // namespace lang


