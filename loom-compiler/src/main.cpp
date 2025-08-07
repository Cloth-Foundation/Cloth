#include "main.hpp"
#include "Token.hpp"
#include <iostream>

int main() {

    using namespace lang;

    TokenSpan span = {.file = "test.lm", .startLine = 1, .startColumn = 1, .endLine = 1, .endColumn = 8};

    Token token(TokenType::Number, "123", span, static_cast<int64_t>(123), TokenCategory::Literal);

    printf("%s\n", token.to_string().c_str());

    return 0;
}
