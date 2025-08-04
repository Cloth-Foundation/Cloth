# Loom Programming Language Reference

## Table of Contents
1. [Overview](#overview)
2. [Language Features](#language-features)
3. [Lexical Structure](#lexical-structure)
4. [Data Types](#data-types)
5. [Variables and Declarations](#variables-and-declarations)
6. [Expressions](#expressions)
7. [Statements](#statements)
8. [Functions](#functions)
9. [Classes](#classes)
10. [Structs](#structs)
11. [Enums](#enums)
12. [Access Control](#access-control)
13. [Import System](#import-system)
14. [Two-Pass Compilation](#two-pass-compilation)
15. [Standard Library](#standard-library)
16. [Examples](#examples)

## Overview

Loom is a statically-typed programming language designed for simplicity, performance, and expressiveness. It features a two-pass compilation system that enables forward declarations, making the order of declarations irrelevant.

### Key Design Principles
- **Static Typing**: All types are checked at compile time
- **Value Semantics**: Structs are copied by value, classes by reference
- **Two-Pass Compilation**: Forward declarations supported
- **Memory Safety**: No null pointer exceptions, explicit nullable types
- **Performance**: Stack allocation for value types, efficient memory management

## Language Features

### Features
- [ ] Static type system with type inference
- [ ] Two-pass compilation with forward declarations
- [ ] Classes with inheritance and methods
- [ ] Structs with value semantics
- [ ] Enums with variants and constructors
- [ ] Functions with parameters and return types
- [ ] Access control (public, private, protected)
- [ ] Import system for modular code
- [ ] Standard library modules
- [ ] Type widening for numeric types
- [ ] Nullable types with explicit syntax
- [ ] Pattern matching for enums
- [ ] Generics/templates
- [ ] Error handling system
- [ ] Code generation pipeline
- [ ] Advanced standard library

## Lexical Structure

### Keywords
```
// Access modifiers
pub, priv, prot

// Type declarations
class, struct, enum, func, var, let, fin

// Control flow
if, else, while, for, do, break, continue, return

// Object-oriented
self, new, extends

// Import system
import

// Type system
i8, i16, i32, i64, f32, f64, bool, string, void, char, byte, null, true, false

// Other keywords
as, async, atmoic, await, self
```

### Operators
```
// Arithmetic
+, -, *, /, %

// Comparison
==, !=, <, <=, >, >=

// Logical
&&, ||, !

// Assignment
=, +=, -=, *=, /=, %=

// Increment/Decrement
++, --

// Member access
.
```

### Literals
```loom
// Integer literals (auto-sized)
42        // i8
1000      // i16
100000    // i32
1000000000 // i64

// Floating point
3.14      // f64
3.14f     // f32

// Boolean
true, false

// String
"Hello, World!"

// Char
'a'        // Single character

// Byte
b'\xFF'   // Byte literal

// Null
null
```

## Data Types

### Primitive Types
| Type     | Size     | Range             | Description           |
|----------|----------|-------------------|-----------------------|
| `i8`     | 1 byte   | -128 to 127       | 8-bit signed integer  |
| `i16`    | 2 bytes  | -32,768 to 32,767 | 16-bit signed integer |
| `i32`    | 4 bytes  | -2^31 to 2^31-1   | 32-bit signed integer |
| `i64`    | 8 bytes  | -2^63 to 2^63-1   | 64-bit signed integer |
| `f32`    | 4 bytes  | IEEE 754          | 32-bit floating point |
| `f64`    | 8 bytes  | IEEE 754          | 64-bit floating point |
| `bool`   | 1 byte   | true/false        | Boolean               |
| `string` | Variable | UTF-8             | String                |
| `void`   | 0 bytes  | N/A               | No value              |
| `char`   | 1 byte   | Unicode character | Single character      |
| `byte`   | 1 byte   | 0 to 255          | Byte (unsigned)       |
| `null`   | 0 bytes  | N/A               | Null value            |

### Nullable Types
```loom
string?    // Nullable string
i32?       // Nullable integer
Point2D?   // Nullable struct
```

### Type Inference
```loom
var x = 42;           // Inferred as i8
var y = 1000;         // Inferred as i16
var z = 3.14;         // Inferred as f64
var flag = true;      // Inferred as bool
var text = "Hello";   // Inferred as string
```

### Type Widening
```loom
var small: i8 = 100;
var large: i32 = small;  // Automatic widening from i8 to i32

var narrow: i8 = 127;
var wide: i64 = narrow;  // Automatic widening from i8 to i64
```

## Variables and Declarations

### Variable Declaration
```loom
// Explicit type
var name: string = "Alice";
var age: i32 = 25;
var height: f64 = 1.75;

// Type inference
var x = 42;
var y = 3.14;
var flag = true;

// Mutable vs Immutable
var mutable: i32 = 10;      // Can be changed
fin var immutable: i32 = 20;    // Cannot be changed
```

### Access Modifiers
```loom
pub var publicVar: i32 = 10;      // Public access
priv var privateVar: i32 = 20;    // Private access
prot var protectedVar: i32 = 30;  // Protected access
```

## Expressions

### Arithmetic Expressions
```loom
var a = 10 + 5;      // Addition
var b = 20 - 3;      // Subtraction
var c = 4 * 6;       // Multiplication
var d = 15 / 3;      // Division
var e = 17 % 5;      // Modulo
```

### Comparison Expressions
```loom
var equal = a == b;
var notEqual = a != b;
var less = a < b;
var lessEqual = a <= b;
var greater = a > b;
var greaterEqual = a >= b;
```

### Logical Expressions
```loom
var and = a && b;
var or = a || b;
var not = !a;
```

### Assignment Expressions
```loom
var x = 10;
x = 20;              // Simple assignment
x += 5;              // Compound assignment
x -= 3;
x *= 2;
x /= 4;
x %= 7;
```

### Increment/Decrement
```loom
var x = 10;
x++;                 // Post-increment
++x;                 // Pre-increment
x--;                 // Post-decrement
--x;                 // Pre-decrement
```

### Member Access
```loom
point.x              // Struct field access
person.name          // Class field access
object.method()      // Method call
```

### Ternary Expression
```loom
var result = condition ? value1 : value2;
var max = a > b ? a : b;
```

## Statements

### Expression Statement
```loom
x = 10;
print("Hello");
object.method();
```

### Variable Declaration Statement
```loom
var x: i32 = 10;
var y = 20;
```

### Block Statement
```loom
{
    var x = 10;
    var y = 20;
    print(x + y);
}
```

### If Statement
```loom
if (condition) {
    // code
}

if (condition) {
    // code
} else {
    // code
}

if (condition1) {
    // code
} else if (condition2) {
    // code
} else {
    // code
}
```

### While Statement
```loom
while (condition) {
    // code
}
```

### Do-While Statement
```loom
do {
    // code
} while (condition);
```

### For Statement
```loom
for (var i = 0; i < 10; i++) {
    print(i);
}
```

### Break Statement
```loom
while (true) {
    if (condition) {
        break;
    }
}
```

### Continue Statement
```loom
for (var i = 0; i < 10; i++) {
    if (i % 2 == 0) {
        continue;
    }
    print(i);
}
```

### Return Statement
```loom
return;              // Return void
return value;        // Return value
```

## Functions

### Function Declaration
```loom
func add(a: i32, b: i32) -> i32 {
    return a + b;
}

func greet(name: string) -> string {
    return "Hello, " + name;
}

func printMessage(message: string) {
    print(message);
}
```

### Function Parameters
```loom
// Required parameters
func sum(a: i32, b: i32, c: i32) -> i32 {
    return a + b + c;
}

// Function call
var result = sum(1, 2, 3);
```

### Function Overloading
```loom
func add(a: i32, b: i32) -> i32 {
    return a + b;
}

func add(a: f64, b: f64) -> f64 {
    return a + b;
}
```

### Access Modifiers on Functions
```loom
pub func publicFunction() { }
priv func privateFunction() { }
prot func protectedFunction() { }
```

## Classes

### Class Declaration
```loom
class Person {
    var name: string;
    var age: i32;
    
    constructor(name: string, age: i32) {
        self.name = name;
        self.age = age;
    }
    
    func getInfo() -> string {
        return self.name + " (" + self.age + ")";
    }
}
```

### Class Inheritance
```loom
class Animal {
    var name: string;
    
    constructor(name: string) {
        self.name = name;
    }
    
    func speak() -> string {
        return "Some sound";
    }
}

class Dog -> Animal {
    var breed: string;
    
    constructor(name: string, breed: string) {
        self.name = name;
        self.breed = breed;
    }
    
    func speak() -> string {
        return "Woof!";
    }
}
```

### Object Instantiation
```loom
var person = new Person("Alice", 25);
var dog = new Dog("Buddy", "Golden Retriever");
```

### Access Control in Classes
```loom
class Example {
    pub var publicField: i32;      // Accessible from anywhere
    priv var privateField: i32;    // Accessible only within class
    prot var protectedField: i32;  // Accessible within class and derived classes
    
    pub func publicMethod() { }
    priv func privateMethod() { }
    prot func protectedMethod() { }
}
```

### Constructor
```loom
class Point {
    var x: f64;
    var y: f64;
    
    constructor(x: f64, y: f64) {
        self.x = x;
        self.y = y;
    }
}
```

## Structs

### Struct Declaration
```loom
struct Point2D {
    x -> f64;
    y -> f64;
}

struct Color {
    red -> i8;
    green -> i8;
    blue -> i8;
    alpha -> i8;
}
```

### Struct Instantiation
```loom
var point = Point2D { x: 10.5, y: 20.3 };
var color = Color { red: 255, green: 128, blue: 64, alpha: 255 };
```

### Struct Field Access
```loom
var x = point.x;
var y = point.y;
point.x = 15.0;
point.y = 25.0;
```

### Value Semantics
```loom
var point1 = Point2D { x: 10.0, y: 20.0 };
var point2 = point1;  // Creates a COPY, not a reference

point2.x = 30.0;      // Only affects point2
// point1.x is still 10.0
```

### Nested Structs
```loom
struct Rectangle {
    topLeft -> Point2D;
    bottomRight -> Point2D;
    color -> Color;
}

var rect = Rectangle {
    topLeft: Point2D { x: 0.0, y: 0.0 },
    bottomRight: Point2D { x: 100.0, y: 100.0 },
    color: Color { red: 255, green: 255, blue: 255, alpha: 255 }
};

var x = rect.topLeft.x;
var red = rect.color.red;
```

### Access Modifiers on Structs
```loom
pub struct PublicStruct {
    x -> i32;  // Inherits public access
}

priv struct PrivateStruct {
    x -> i32;  // Inherits private access
}
```

## Enums

### Simple Enum
```loom
enum Direction {
    NORTH,
    SOUTH,
    EAST,
    WEST
}
```

### Enum with Values
```loom
enum HttpStatus {
    OK(200, "OK"),
    NOT_FOUND(404, "Not Found"),
    INTERNAL_ERROR(500, "Internal Server Error")
}
```

### Enum with Constructor
```loom
enum Color {
    RED(255, 0, 0),
    GREEN(0, 255, 0),
    BLUE(0, 0, 255);
    
    var red: i8;
    var green: i8;
    var blue: i8;
    
    constructor(red: i8, green: i8, blue: i8) {
        self.red = red;
        self.green = green;
        self.blue = blue;
    }
}
```

### Enum Projection
```loom
enum Result<T> {
    OK(T),
    ERROR(string)
}

var result = Result.OK(42);
var value = result.OK;  // Projection syntax
```

### Enum Pattern Matching (Planned)
```loom
// Future feature
match (result) {
    OK(value) => print("Success: " + value),
    ERROR(message) => print("Error: " + message)
}
```

## Access Control

### Access Levels
| Level | Description | Access |
|-------|-------------|--------|
| `pub` | Public | Accessible from anywhere |
| `priv` | Private | Accessible only within same scope |
| `prot` | Protected | Accessible within same scope and derived classes |

### Class Access Control
```loom
class Example {
    pub var publicField: i32;      // Accessible from anywhere
    priv var privateField: i32;    // Accessible only within class
    prot var protectedField: i32;  // Accessible within class and derived classes
    
    pub func publicMethod() { }
    priv func privateMethod() { }
    prot func protectedMethod() { }
}
```

### Struct Access Control
```loom
pub struct PublicStruct {
    x -> i32;  // Inherits public access
}

priv struct PrivateStruct {
    x -> i32;  // Inherits private access
}
```

### Function Access Control
```loom
pub func publicFunction() { }
priv func privateFunction() { }
prot func protectedFunction() { }
```

## Import System

### Basic Import
```loom
import std::io;
```

### Selective Import
```loom
import std::io::{print, println, printf};
```

### Import with Alias
```loom
import std::math as math;
```

### Standard Library Modules
```loom
import std::io;      // Input/output functions
import std::math;    // Mathematical functions
import std::strings; // String manipulation
```

### Available Standard Library Functions
```loom
// std::io
print(message: string)
println(message: string)
printf(format: string, ...args)

// std::math
add(a: f64, b: f64) -> f64
subtract(a: f64, b: f64) -> f64
multiply(a: f64, b: f64) -> f64
divide(a: f64, b: f64) -> f64

// std::strings
length(str: string) -> i32
isEmpty(str: string) -> bool
toUpperCase(str: string) -> string
toLowerCase(str: string) -> string
```

## Two-Pass Compilation

### Overview
Loom uses a two-pass compilation system that enables forward declarations, making the order of functions, classes, and other declarations irrelevant.

### Pass 1: Declaration Collection
- Collects all top-level declarations (functions, classes, structs, enums)
- Builds symbol table with forward references
- Validates basic syntax and structure

### Pass 2: Semantic Analysis
- Performs full semantic analysis using complete symbol table
- Type checking and validation
- Access control verification
- Import resolution

### Benefits
- **Forward Declarations**: Use functions before they're defined
- **Flexible Order**: Declare classes and functions in any order
- **Better Organization**: Group related functionality together
- **Reduced Dependencies**: No need to worry about declaration order

### Example
```loom
// This works because of two-pass compilation
func main() {
    var result = calculate(10, 20);
    print(result);
}

func calculate(a: i32, b: i32) -> i32 {
    return a + b;
}
```

## Standard Library

### IO Module (`std::io`)
```loom
import std::io::{print, println, printf};

// Basic output
print("Hello, World!");
println("With newline");

// Formatted output
printf("Value: %d", 42);
```

### Math Module (`std::math`)
```loom
import std::math::{add, subtract, multiply, divide};

var result = add(10.5, 20.3);
var product = multiply(5.0, 3.0);
```

### Strings Module (`std::strings`)
```loom
import std::strings::{length, isEmpty, toUpperCase, toLowerCase};

var len = length("Hello");
var empty = isEmpty("");
var upper = toUpperCase("hello");
var lower = toLowerCase("WORLD");
```

## Examples

### Complete Program
```loom
import std::io::{print, println};

struct Point2D {
    x -> f64;
    y -> f64;
}

class Person {
    var name: string;
    var age: i32;
    
    constructor(name: string, age: i32) {
        self.name = name;
        self.age = age;
    }
    
    func getInfo() -> string {
        return self.name + " (" + self.age + ")";
    }
}

enum Direction {
    NORTH,
    SOUTH,
    EAST,
    WEST
}

func calculateDistance(p1: Point2D, p2: Point2D) -> f64 {
    var dx = p2.x - p1.x;
    var dy = p2.y - p1.y;
    return dx * dx + dy * dy;
}

func main() {
    var point1 = Point2D { x: 0.0, y: 0.0 };
    var point2 = Point2D { x: 3.0, y: 4.0 };
    
    var person = new Person("Alice", 25);
    
    var distance = calculateDistance(point1, point2);
    var info = person.getInfo();
    
    println("Distance: " + distance);
    println("Person: " + info);
}
```

### Class Inheritance Example
```loom
class Animal {
    var name: string;
    
    constructor(name: string) {
        self.name = name;
    }
    
    func speak() -> string {
        return "Some sound";
    }
}

class Dog -> Animal {
    var breed: string;
    
    constructor(name: string, breed: string) {
        self.name = name;
        self.breed = breed;
    }
    
    func speak() -> string {
        return "Woof!";
    }
    
    func getInfo() -> string {
        return self.name + " (" + self.breed + ")";
    }
}

func main() {
    var dog = new Dog("Buddy", "Golden Retriever");
    println(dog.speak());      // "Woof!"
    println(dog.getInfo());    // "Buddy (Golden Retriever)"
}
```

### Struct Value Semantics Example
```loom
struct Color {
    red -> i8;
    green -> i8;
    blue -> i8;
    alpha -> i8;
}

func main() {
    var color1 = Color { red: 255, green: 128, blue: 64, alpha: 255 };
    var color2 = color1;  // Copy by value
    
    color2.red = 128;     // Only affects color2
    
    println("Color1: " + color1.red);  // Still 255
    println("Color2: " + color2.red);  // Now 128
}
```

### Enum with Constructor Example
```loom
enum HttpStatus {
    OK(200, "OK"),
    NOT_FOUND(404, "Not Found"),
    INTERNAL_ERROR(500, "Internal Server Error");
    
    var code: i32;
    var message: string;
    
    constructor(code: i32, message: string) {
        self.code = code;
        self.message = message;
    }
}

func getStatusInfo(status: HttpStatus) -> string {
    return status.code + ": " + status.message;
}

func main() {
    var status = HttpStatus.OK;
    println(getStatusInfo(status));  // "200: OK"
}
```

## Language Comparison

| Feature | Loom | Java | C++ | Rust |
|---------|------|------|-----|------|
| Static Typing | ✅ | ✅ | ✅ | ✅ |
| Type Inference | ✅ | ✅ | ✅ | ✅ |
| Classes | ✅ | ✅ | ✅ | ✅ |
| Structs | ✅ | ❌ | ✅ | ✅ |
| Enums | ✅ | ✅ | ✅ | ✅ |
| Value Semantics | ✅ | ❌ | ✅ | ✅ |
| Two-Pass Compilation | ✅ | ✅ | ❌ | ❌ |
| Forward Declarations | ✅ | ✅ | ✅ | ❌ |
| Nullable Types | ✅ | ✅ | ❌ | ✅ |
| Access Control | ✅ | ✅ | ✅ | ✅ |

## Best Practices

### Naming Conventions
- **Classes**: PascalCase (`Person`, `HttpClient`)
- **Structs**: PascalCase (`Point2D`, `Color`)
- **Enums**: PascalCase (`Direction`, `HttpStatus`)
- **Functions**: camelCase (`calculateDistance`, `getUserInfo`)
- **Variables**: camelCase (`userName`, `maxValue`)
- **Constants**: UPPER_SNAKE_CASE (`MAX_SIZE`, `DEFAULT_TIMEOUT`)

### Code Organization
```loom
// 1. Imports
import std::io::{print, println};

// 2. Struct declarations
struct Point2D {
    x -> f64;
    y -> f64;
}

// 3. Enum declarations
enum Direction {
    NORTH, SOUTH, EAST, WEST
}

// 4. Class declarations
class Person {
    // class members
}

// 5. Function declarations
func main() {
    // main function
}
```

### Error Handling
```loom
// Use nullable types for optional values
var email: string? = null;

// Check for null before using
if (email != null) {
    print("Email: " + email);
} else {
    print("No email provided");
}
```

### Performance Considerations
- Use structs for small, frequently-copied data
- Use classes for large objects or when you need methods
- Prefer stack allocation (structs) over heap allocation (classes)
- Use appropriate integer types (i8, i16, i32, i64) for memory efficiency

## Future Enhancements

### Planned Features
1. **Pattern Matching**: Advanced enum matching with destructuring
2. **Generics/Templates**: Type-parameterized functions and types
3. **Error Handling**: Built-in error handling system
4. **Code Generation**: Actual machine code generation
5. **Advanced Standard Library**: More comprehensive library modules
6. **Package Management**: Dependency management system
7. **IDE Support**: Language server and development tools

---
