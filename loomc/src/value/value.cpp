//
// Created by wylan on 8/25/2025.
//

#include <stdio.h>

#include "../../include/memory/memory.h"
#include "../../include/value/value.h"

void initValueArray(ValueArray *array) {
    array->values = NULL;
    array->capactity = 0;
    array->count = 0;
}

void writeValueArray(ValueArray *array, Value value) {
    if (array->capactity < array->count + 1) {
        int oldCapacity = array->capactity;
        array->capactity = GROW_CAPACITY(oldCapacity);
        array->values = GROW_ARRAY(Value, array->values, oldCapacity, array->capactity);
    }

    array->values[array->count] = value;
    array->count++;
}

void freeValueArray(ValueArray *array) {
    FREE_ARRAY(Value, array->values, array->capactity);
    initValueArray(array);
}

void printValue(Value value) {
    printf("%g", value);
}