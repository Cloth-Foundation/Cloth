#include "Lexer.hpp"

#include <cctype>
#include <unordered_map>
#include <cstdint>
#include <cstdlib>

namespace loom {

namespace {
    // UTF-8 decoding with minimal validation; errors yield U+FFFD and consume 1 byte
    inline bool isContinuation(unsigned char b) { return (b & 0xC0u) == 0x80u; }

    struct DecodedCp {
        char32_t codepoint;
        std::size_t lengthBytes;
    };

    DecodedCp decodeUtf8At(std::string_view s, std::size_t i) {
        if (i >= s.size()) return {U'\0', 0};
        const unsigned char b0 = static_cast<unsigned char>(s[i]);
        if (b0 < 0x80u) return {static_cast<char32_t>(b0), 1};
        if ((b0 & 0xE0u) == 0xC0u) {
            if (i + 1 >= s.size()) return {static_cast<char32_t>(0xFFFD), 1};
            const unsigned char b1 = static_cast<unsigned char>(s[i + 1]);
            if (!isContinuation(b1)) return {static_cast<char32_t>(0xFFFD), 1};
            const char32_t cp = ((b0 & 0x1Fu) << 6) | (b1 & 0x3Fu);
            if (cp < 0x80) return {static_cast<char32_t>(0xFFFD), 1}; // overlong
            return {cp, 2};
        }
        if ((b0 & 0xF0u) == 0xE0u) {
            if (i + 2 >= s.size()) return {static_cast<char32_t>(0xFFFD), 1};
            const unsigned char b1 = static_cast<unsigned char>(s[i + 1]);
            const unsigned char b2 = static_cast<unsigned char>(s[i + 2]);
            if (!isContinuation(b1) || !isContinuation(b2)) return {static_cast<char32_t>(0xFFFD), 1};
            const char32_t cp = ((b0 & 0x0Fu) << 12) | ((b1 & 0x3Fu) << 6) | (b2 & 0x3Fu);
            if (cp < 0x800 || (cp >= 0xD800 && cp <= 0xDFFF)) return {static_cast<char32_t>(0xFFFD), 1}; // overlong or surrogate
            return {cp, 3};
        }
        if ((b0 & 0xF8u) == 0xF0u) {
            if (i + 3 >= s.size()) return {static_cast<char32_t>(0xFFFD), 1};
            const unsigned char b1 = static_cast<unsigned char>(s[i + 1]);
            const unsigned char b2 = static_cast<unsigned char>(s[i + 2]);
            const unsigned char b3 = static_cast<unsigned char>(s[i + 3]);
            if (!isContinuation(b1) || !isContinuation(b2) || !isContinuation(b3)) return {static_cast<char32_t>(0xFFFD), 1};
            const char32_t cp = ((b0 & 0x07u) << 18) | ((b1 & 0x3Fu) << 12) | ((b2 & 0x3Fu) << 6) | (b3 & 0x3Fu);
            if (cp < 0x10000 || cp > 0x10FFFF) return {static_cast<char32_t>(0xFFFD), 1}; // overlong or out of range
            return {cp, 4};
        }
        return {static_cast<char32_t>(0xFFFD), 1};
    }

    inline bool isAsciiLetter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    inline bool isIdentifierStartCp(char32_t cp) {
        if (cp < 0x80) return isAsciiLetter(static_cast<char>(cp)) || cp == U'_' || cp == U'$';
        // Allow any non-ASCII code point as a start for now to support symbols like π.
        return true;
    }

    inline bool isIdentifierPartCp(char32_t cp) {
        if (cp < 0x80) return std::isalnum(static_cast<unsigned char>(cp)) || cp == U'_' || cp == U'$';
        // Allow any non-ASCII code point as a continue
        return true;
    }

    inline int digitValue(int ch) {
        if (ch >= '0' && ch <= '9') return ch - '0';
        if (ch >= 'a' && ch <= 'f') return 10 + (ch - 'a');
        if (ch >= 'A' && ch <= 'F') return 10 + (ch - 'A');
        return -1;
    }

    inline bool isHexDigit(int ch) {
        return std::isxdigit(static_cast<unsigned char>(ch)) != 0;
    }

    inline bool isBinDigit(int ch) { return ch == '0' || ch == '1'; }

    inline bool isOctDigit(int ch) { return ch >= '0' && ch <= '7'; }
}

Lexer::Lexer(std::string source, std::string fileName)
    : source_(std::move(source)), fileName_(std::move(fileName)) {
    // Skip UTF-8 BOM if present
    if (source_.size() >= 3 &&
        static_cast<unsigned char>(source_[0]) == 0xEF &&
        static_cast<unsigned char>(source_[1]) == 0xBB &&
        static_cast<unsigned char>(source_[2]) == 0xBF) {
        pos_ = 3;
    }
}

bool Lexer::eof() const noexcept { return isAtEnd(); }

const Token &Lexer::peek() {
    if (!lookaheadToken_.has_value()) {
        lookaheadToken_ = scanToken();
    }
    return *lookaheadToken_;
}

Token Lexer::next() {
    if (lookaheadToken_.has_value()) {
        Token t = *lookaheadToken_;
        lookaheadToken_.reset();
        return t;
    }
    return scanToken();
}

std::vector<Token> Lexer::tokenizeAll() {
    std::vector<Token> tokens;
    while (!isAtEnd()) {
        tokens.push_back(scanToken());
    }
    // always end with EOF
    TokenSpan span{fileName_, line_, column_, line_, column_};
    tokens.emplace_back(TokenType::EndOfFile, std::string_view{}, span, TokenValue{}, TokenCategory::Eof);
    return tokens;
}

bool Lexer::isAtEnd() const noexcept { return pos_ >= source_.size(); }

char Lexer::current() const noexcept {
    if (isAtEnd()) return '\0';
    return source_[pos_];
}

char Lexer::lookahead(std::size_t n) const noexcept {
    if (pos_ + n >= source_.size()) return '\0';
    return source_[pos_ + n];
}

char Lexer::advance() {
    char c = current();
    if (c == '\n') {
        ++line_;
        column_ = 1;
    } else {
        ++column_;
    }
    ++pos_;
    return c;
}

bool Lexer::match(char expected) {
    if (isAtEnd() || source_[pos_] != expected) return false;
    advance();
    return true;
}

void Lexer::skipWhitespaceAndComments() {
    for (;;) {
        char c = current();
        switch (c) {
            case ' ': case '\r': case '\t': case '\n':
                advance();
                break;
            case '#':
                if (lookahead() == '|') {
                    // multi-line comment starting with #|
                    advance(); // '#'
                    advance(); // '|'
                    while (!isAtEnd()) {
                        if (current() == '#' && lookahead() == '|') {
                            advance(); // '#'
                            advance(); // '|'
                            break;
                        }
                        advance();
                    }
                } else {
                    // single-line comment starting with #
                    while (!isAtEnd() && current() != '\n') advance();
                }
                break;
            default:
                return;
        }
    }
}

Token Lexer::scanToken() {
    skipWhitespaceAndComments();
    if (isAtEnd()) {
        TokenSpan span{fileName_, line_, column_, line_, column_};
        return Token(TokenType::EndOfFile, std::string_view{}, span, TokenValue{}, TokenCategory::Eof);
    }

    const std::size_t startPos = pos_;
    const std::size_t startLine = line_;
    const std::size_t startCol = column_;

    // Unicode-aware identifier scanning
    {
        const auto first = decodeUtf8At(source_, pos_);
        if (first.lengthBytes > 0 && isIdentifierStartCp(first.codepoint)) {
            // consume first
            pos_ += first.lengthBytes;
            ++column_;
            // consume rest
            for (;;) {
                const auto next = decodeUtf8At(source_, pos_);
                if (next.lengthBytes == 0 || !isIdentifierPartCp(next.codepoint)) break;
                pos_ += next.lengthBytes;
                ++column_;
            }
            std::string_view text(&source_[startPos], pos_ - startPos);
            if (auto kw = lookupKeyword(text)) {
                return makeTokenFromRange(*kw, startPos, startLine, startCol);
            }
            return makeTokenFromRange(TokenType::Identifier, startPos, startLine, startCol, std::string(text));
        }
    }

    // Number scanning (no pre-advance)
    if (std::isdigit(static_cast<unsigned char>(current()))) {
        return scanNumber();
    }

    char c = advance();

    switch (c) {
        case '\'':
            return scanCharLiteral();
        case '"':
            return scanString();
        default:
            // operators and punctuation handling, including multi-char
            --pos_; // un-advance to let the helper read from start
            // fix column since we rolled back pos_
            --column_;
            return scanOperatorOrPunctuation();
    }
}

Token Lexer::scanIdentifierOrKeyword() {
    const std::size_t startPos = pos_;
    const std::size_t startLine = line_;
    const std::size_t startCol = column_;
    // Unicode-aware walk
    for (;;) {
        const auto d = decodeUtf8At(source_, pos_);
        if (d.lengthBytes == 0 || !isIdentifierPartCp(d.codepoint)) break;
        pos_ += d.lengthBytes;
        ++column_;
    }
    std::string_view text(&source_[startPos], pos_ - startPos);
    if (auto kw = lookupKeyword(text)) {
        return makeToken(*kw, text);
    }
    return makeToken(TokenType::Identifier, text, std::string(text));
}

Token Lexer::scanNumber() {
    const std::size_t startPos = pos_;
    const std::size_t startLine = line_;
    const std::size_t startCol = column_;

    auto consumeUnderscoredDigits = [&](auto isValidDigit) {
        while (true) {
            char ch = current();
            if (ch == '_') { advance(); continue; }
            if (!isValidDigit(ch)) break;
            advance();
        }
    };

    auto slice = [&](std::size_t from, std::size_t to) -> std::string_view {
        return std::string_view(&source_[from], to - from);
    };

    auto stripUnderscores = [&](std::string_view sv) -> std::string {
        std::string out;
        out.reserve(sv.size());
        for (char ch : sv) if (ch != '_') out.push_back(ch);
        return out;
    };

    // Check for base prefixes 0x / 0b / 0o
    if (current() == '0' && (lookahead() == 'x' || lookahead() == 'X' || lookahead() == 'b' || lookahead() == 'B' || lookahead() == 'o' || lookahead() == 'O')) {
        char baseCh = lookahead();
        advance(); // '0'
        advance(); // base char
        const std::size_t digitsStart = pos_;
        if (baseCh == 'x' || baseCh == 'X') {
            consumeUnderscoredDigits([](char ch){ return isHexDigit(ch); });
        } else if (baseCh == 'b' || baseCh == 'B') {
            consumeUnderscoredDigits([](char ch){ return isBinDigit(ch); });
        } else { // 'o' or 'O'
            consumeUnderscoredDigits([](char ch){ return isOctDigit(ch); });
        }
        const std::size_t digitsEnd = pos_;
        // optional type suffix (letters/digits)
        while (std::isalnum(static_cast<unsigned char>(current()))) advance();

        std::string_view fullLexeme = slice(startPos, pos_);
        std::string digitsClean = stripUnderscores(slice(digitsStart, digitsEnd));

        // Parse value manually to avoid non-portable bases
        uint64_t acc = 0;
        int base = (baseCh == 'x' || baseCh == 'X') ? 16 : (baseCh == 'b' || baseCh == 'B') ? 2 : 8;
        for (char ch : digitsClean) {
            int dv = digitValue(ch);
            if (dv < 0 || dv >= base) break;
            acc = acc * static_cast<uint64_t>(base) + static_cast<uint64_t>(dv);
        }
        // Preserve structured literal (suffix retained in lexeme)
        NumericLiteral lit{digitsClean, base, false, std::string(slice(digitsEnd, pos_))};
        return makeTokenFromRange(TokenType::Number, startPos, startLine, startCol, std::move(lit));
    }

    // Decimal or float with underscores
    // Integer part
    consumeUnderscoredDigits([](char ch){ return std::isdigit(static_cast<unsigned char>(ch)) != 0; });
    bool isFloat = false;
    std::size_t numericEnd = pos_;
    if (current() == '.' && std::isdigit(static_cast<unsigned char>(lookahead()))) {
        isFloat = true;
        advance(); // '.'
        consumeUnderscoredDigits([](char ch){ return std::isdigit(static_cast<unsigned char>(ch)) != 0; });
        numericEnd = pos_;
    }
    // Optional suffix (e.g., i32, f64)
    while (std::isalnum(static_cast<unsigned char>(current()))) advance();

    std::string_view fullLexeme = slice(startPos, pos_);
    std::string numericClean = stripUnderscores(slice(startPos, numericEnd));

    if (isFloat) {
        NumericLiteral lit{numericClean, 10, true, std::string(slice(numericEnd, pos_))};
        return makeTokenFromRange(TokenType::Number, startPos, startLine, startCol, std::move(lit));
    }
    NumericLiteral lit{numericClean, 10, false, std::string(slice(numericEnd, pos_))};
    return makeTokenFromRange(TokenType::Number, startPos, startLine, startCol, std::move(lit));
}

Token Lexer::scanString() {
    const std::size_t startPos = pos_ - 1; // include opening quote
    const std::size_t startLine = line_;
    const std::size_t startCol = column_ - 1;
    std::string value;
    while (!isAtEnd()) {
        char c = advance();
        if (c == '"') break;
        if (c == '\\') {
            char e = advance();
            switch (e) {
                case 'n': value.push_back('\n'); break;
                case 't': value.push_back('\t'); break;
                case 'r': value.push_back('\r'); break;
                case '\\': value.push_back('\\'); break;
                case '"': value.push_back('"'); break;
                default: value.push_back(e); break;
            }
        } else {
            value.push_back(c);
        }
    }
    return makeTokenFromRange(TokenType::String, startPos, startLine, startCol, std::string(value));
}

Token Lexer::scanCharLiteral() {
    const std::size_t startPos = pos_ - 1; // include opening quote
    const std::size_t startLine = line_;
    const std::size_t startCol = column_ - 1;
    char c = advance();
    if (c == '\\') { // escape
        char e = advance();
        switch (e) {
            case 'n': c = '\n'; break;
            case 't': c = '\t'; break;
            case 'r': c = '\r'; break;
            case '\\': c = '\\'; break;
            case '\'': c = '\''; break;
            default: /* keep as is */ c = e; break;
        }
    }
    if (current() != '\'') {
        // unterminated or too long char literal
        return makeInvalidToken("unterminated char", startPos, startLine, startCol);
    }
    advance(); // closing quote
    return makeTokenFromRange(TokenType::Char, startPos, startLine, startCol, std::string(1, c));
}

Token Lexer::scanOperatorOrPunctuation() {
    const std::size_t startPos = pos_;
    const std::size_t startLine = line_;
    const std::size_t startCol = column_;

    char c = advance();
    auto two = [&](char next, TokenType twoType, TokenType oneType) -> Token {
        if (current() == next) {
            advance();
            return makeTokenFromRange(twoType, startPos, startLine, startCol);
        }
        return makeTokenFromRange(oneType, startPos, startLine, startCol);
    };

    switch (c) {
        case '+': return makeTokenFromRange(TokenType::Plus, startPos, startLine, startCol);
        case '-':
            if (current() == '>') { advance(); return makeTokenFromRange(TokenType::Arrow, startPos, startLine, startCol); }
            return makeTokenFromRange(TokenType::Minus, startPos, startLine, startCol);
        case '*': return makeTokenFromRange(TokenType::Star, startPos, startLine, startCol);
        case '/': return makeTokenFromRange(TokenType::Slash, startPos, startLine, startCol);
        case '%': return makeTokenFromRange(TokenType::Percent, startPos, startLine, startCol);
        case '!': return two('=', TokenType::NotEqual, TokenType::Not);
        case '=': return two('=', TokenType::DoubleEqual, TokenType::Equal);
        case '<': return (current() == '=') ? (advance(), makeTokenFromRange(TokenType::LessEqual, startPos, startLine, startCol))
                                            : makeTokenFromRange(TokenType::Less, startPos, startLine, startCol);
        case '>': return (current() == '=') ? (advance(), makeTokenFromRange(TokenType::GreaterEqual, startPos, startLine, startCol))
                                            : makeTokenFromRange(TokenType::Greater, startPos, startLine, startCol);
        case '&':
            if (current() == '&') { advance(); return makeTokenFromRange(TokenType::And, startPos, startLine, startCol); }
            break;
        case '|':
            if (current() == '|') { advance(); return makeTokenFromRange(TokenType::Or, startPos, startLine, startCol); }
            break;
        case '.':
            if (current() == '.') {
                advance();
                if (current() == '=') { advance(); return makeTokenFromRange(TokenType::Range_Inclusive, startPos, startLine, startCol); }
                return makeTokenFromRange(TokenType::Range, startPos, startLine, startCol);
            }
            return makeTokenFromRange(TokenType::Dot, startPos, startLine, startCol);
        case ':':
            if (current() == ':') { advance(); return makeTokenFromRange(TokenType::DoubleColon, startPos, startLine, startCol); }
            return makeTokenFromRange(TokenType::Colon, startPos, startLine, startCol);
        case ';': return makeTokenFromRange(TokenType::Semicolon, startPos, startLine, startCol);
        case ',': return makeTokenFromRange(TokenType::Comma, startPos, startLine, startCol);
        case '?': return makeTokenFromRange(TokenType::Question, startPos, startLine, startCol);
        case '(': return makeTokenFromRange(TokenType::LParen, startPos, startLine, startCol);
        case ')': return makeTokenFromRange(TokenType::RParen, startPos, startLine, startCol);
        case '[': return makeTokenFromRange(TokenType::LBracket, startPos, startLine, startCol);
        case ']': return makeTokenFromRange(TokenType::RBracket, startPos, startLine, startCol);
        case '{': return makeTokenFromRange(TokenType::LBrace, startPos, startLine, startCol);
        case '}': return makeTokenFromRange(TokenType::RBrace, startPos, startLine, startCol);
        default:
            break;
    }

    // unknown byte
    return makeInvalidToken("unexpected character", startPos, startLine, startCol);
}

Token Lexer::makeToken(TokenType type, std::string_view lexeme, TokenValue value) {
    TokenSpan span{fileName_, line_, column_, line_, column_};
    return Token(type, lexeme, span, std::move(value));
}

Token Lexer::makeTokenFromRange(TokenType type,
                               std::size_t startPos,
                               std::size_t startLine,
                               std::size_t startCol,
                               TokenValue value) {
    std::string_view lexeme(&source_[startPos], pos_ - startPos);
    TokenSpan span{fileName_, startLine, startCol, line_, column_};
    return Token(type, lexeme, span, std::move(value));
}

Token Lexer::makeInvalidToken(std::string_view message,
                              std::size_t startPos,
                              std::size_t startLine,
                              std::size_t startCol) {
    std::string_view lexeme(&source_[startPos], pos_ - startPos);
    TokenSpan span{fileName_, startLine, startCol, line_, column_};
    return Token(TokenType::Invalid, lexeme, span, std::string(message), TokenCategory::Error);
}

std::optional<TokenType> Lexer::lookupKeyword(std::string_view text) noexcept {
    // Map selected keywords and builtins from TokenType
    static const std::unordered_map<std::string_view, TokenType> k = {
        {"as", TokenType::As},
        {"atomic", TokenType::Atomic},
        {"bit", TokenType::Bit},
        {"bool", TokenType::Bool},
        {"break", TokenType::Break},
        {"builder", TokenType::Builder},
        {"case", TokenType::Case},
        {"class", TokenType::Class},
        {"const", TokenType::Const},
        {"continue", TokenType::Continue},
        {"default", TokenType::Default},
        {"do", TokenType::Do},
        {"elif", TokenType::Elif},
        {"else", TokenType::Else},
        {"enum", TokenType::Enum},
        {"fin", TokenType::Fin},
        {"for", TokenType::For},
        {"func", TokenType::Func},
        {"if", TokenType::If},
        {"import", TokenType::Import},
        {"in", TokenType::In},
        {"internal", TokenType::Internal},
        {"let", TokenType::Let},
        {"loop", TokenType::Loop},
        {"mod", TokenType::Mod},
        {"new", TokenType::New},
        {"priv", TokenType::Priv},
        {"prot", TokenType::Prot},
        {"pub", TokenType::Pub},
        {"ret", TokenType::Ret},
        {"rev", TokenType::Rev},
        {"self", TokenType::Self},
        {"step", TokenType::Step},
        {"struct", TokenType::Struct},
        {"super", TokenType::Super},
        {"switch", TokenType::Switch},
        {"this", TokenType::This},
        {"var", TokenType::Var},
        {"while", TokenType::While},
        // builtins
        {"byte", TokenType::Byte},
        {"f16", TokenType::f16},
        {"f32", TokenType::f32},
        {"f64", TokenType::f64},
        {"i8", TokenType::i8},
        {"i16", TokenType::i16},
        {"i32", TokenType::i32},
        {"i64", TokenType::i64},
        {"u8", TokenType::u8},
        {"u16", TokenType::u16},
        {"u32", TokenType::u32},
        {"u64", TokenType::u64},
        // literals
        {"true", TokenType::True},
        {"false", TokenType::False},
        {"null", TokenType::Null},
    };
    auto it = k.find(text);
    if (it == k.end()) return std::nullopt;
    return it->second;
}

} // namespace lang


