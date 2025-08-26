//
// Created by wylan on 8/25/2025.
//

#ifndef LOOMC_DEBUG_H
#define LOOMC_DEBUG_H

#include "../chunk/chunk.h"

void disassembleChunk(Chunk* chunk, const char* name);
int disassembleInstruction(Chunk* chunk, int offset);

#endif //LOOMC_DEBUG_H