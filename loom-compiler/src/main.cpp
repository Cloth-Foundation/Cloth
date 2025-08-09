#include "main.hpp"
#include "Token.hpp"
#include "Lexer.hpp"
#include <iostream>
#include <fstream>
#include <sstream>
#include <clocale>
#if defined(_WIN32)
#  include <windows.h>
#endif

int main(int argc, char** argv) {
    using namespace lang;

#if defined(_WIN32)
    // Ensure Windows console uses UTF-8 for correct display of Unicode identifiers like π
    SetConsoleOutputCP(CP_UTF8);
    SetConsoleCP(CP_UTF8);
#endif
    std::setlocale(LC_ALL, ".UTF-8");

    std::string filePath;
    if (argc > 1) {
        filePath = argv[1];
    } else {
        // Default sample if no file provided
        filePath = "example/SyntaxDefinitions.lm";
    }

    std::ifstream in(filePath, std::ios::in | std::ios::binary);
    if (!in) {
        std::cerr << "Failed to open file: " << filePath << "\n";
        return 1;
    }
    std::ostringstream buffer;
    buffer << in.rdbuf();
    std::string source = buffer.str();

    Lexer lexer(std::move(source), filePath);
    while (true) {
        Token tok = lexer.next();
        std::cout << tok.to_string() << '\n';
        if (tok.is(TokenType::EndOfFile)) break;
    }

    return 0;
}
