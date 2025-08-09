cmake_minimum_required(VERSION 3.31)
project(loom_compiler)

set(CMAKE_CXX_STANDARD 23)

add_executable(loom_compiler

        ##############################
        ## SOURCE FILES
        ##############################

        src/main.cpp

        # LEXER
        src/lexer/Lexer.cpp

        # TOKEN
        src/tokens/Token.cpp
        src/tokens/TokenSpan.cpp

        ##############################
        ## HEADERS
        ##############################

        include/main.hpp

        # LEXER
        include/lexer/Lexer.hpp

        # TOKEN
        include/tokens/Token.hpp
        include/tokens/TokenSpan.hpp
)

include_directories(
        ${PROJECT_SOURCE_DIR}/include
        ${PROJECT_SOURCE_DIR}/include/tokens
        ${PROJECT_SOURCE_DIR}/include/lexer
)
