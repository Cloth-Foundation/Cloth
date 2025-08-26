cmake_minimum_required(VERSION 4.0)
project(loomc)

set(CMAKE_CXX_STANDARD 23)

add_executable(loomc
        src/main.cpp
        include/common.h
        include/chunk/chunk.h
        src/chunk/chunk.cpp
        include/memory/memory.h
        src/memory/memory.cpp
        include/debug/debug.h
        src/debug/debug.cpp
        include/value/value.h
        src/value/value.cpp)
