#include "main.hpp"
#include "Token.hpp"
#include "Lexer.hpp"
#include <iostream>
#include <fstream>
#include <sstream>
#include <clocale>
#if defined(_WIN32)
  #include <windows.h>
#endif

void checkEnvironment() {
#if defined(_WIN32)
    // Windows: switch console I/O to UTF-8 and enable ANSI escapes
    SetConsoleOutputCP(CP_UTF8);
    SetConsoleCP(CP_UTF8);

    HANDLE hOut = GetStdHandle(STD_OUTPUT_HANDLE);
    if (hOut != INVALID_HANDLE_VALUE) {
        DWORD mode = 0;
        if (GetConsoleMode(hOut, &mode)) {
            mode |= ENABLE_PROCESSED_OUTPUT | ENABLE_VIRTUAL_TERMINAL_PROCESSING;
            SetConsoleMode(hOut, mode);
        }
    }
#else
    // macOS/Linux: honor user's locale; if unset, fall back to UTF-8
    if (!std::setlocale(LC_ALL, "")) {
        std::setlocale(LC_ALL, "en_US.UTF-8");
    }
#endif
}

int main(int argc, char** argv) {
    using namespace loom;
    checkEnvironment();

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
