cmake_minimum_required(VERSION 3.31)
project(loom_compiler)

set(CMAKE_CXX_STANDARD 23)

add_executable(loom_compiler src/main.cpp
        include/main.hpp
        src/tokens/Token.cpp
        include/token/Token.hpp)

include_directories(${PROJECT_SOURCE_DIR}/include
        ${PROJECT_SOURCE_DIR}/include/token)
