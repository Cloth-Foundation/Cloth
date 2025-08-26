//
// Created by wylan on 8/26/2025.
//

#ifndef LOOMC_VM_H
#define LOOMC_VM_H

#include "../chunk/chunk.h"
#include "../value/value.h"

#define STACK_MAX 256

typedef struct {
    Chunk *chunk;
    uint8_t *ip;
    Value *stack;
    int stackCount;
    int stackCapacity;
} VM;

typedef enum  {
    INTERPRET_OK,
    INTERPRET_COMPILE_ERROR,
    INTERPRET_RUNTIME_ERROR
} InterpretResult;

void initVM();
void freeVM();
InterpretResult interpret(Chunk *chunk);
void push(Value value);
Value pop();

#endif //LOOMC_VM_H