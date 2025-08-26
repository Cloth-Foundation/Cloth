//
// Created by wylan on 8/25/2025.
//

#ifndef LOOMC_CHUNK_H
#define LOOMC_CHUNK_H

#include "../common.h"
#include "../value/value.h"

typedef enum {
    OP_CONSTANT,
    OP_CONSTANT_LONG,
    OP_RETURN
} OpCode;

typedef struct {
    int offset;
    int line;
} LineStart;

typedef struct {
    int count;
    int capacity;
    uint8_t *code;
    ValueArray constants;
    int lineCount;
    int lineCapacity;
    LineStart *lines;
} Chunk;

void initChunk(Chunk *chunk);

void freeChunk(Chunk *chunk);

void writeChunk(Chunk *chunk, uint8_t byte, int line);

int addConstant(Chunk *chunk, Value value);

int getLine(Chunk *chunk, int instruction);

void writeConstant(Chunk *chunk, Value value, int line);

#endif //LOOMC_CHUNK_H
