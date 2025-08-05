# Loomc - OCaml Implementation of Loom Language Compiler

This is an OCaml rewrite of the Loom programming language compiler, originally implemented in Java.

## Project Structure

```
loomc/
├── lib/                    # Library modules
│   ├── token_types.ml     # Token type definitions
│   ├── keywords.ml        # Language keywords
│   ├── token_span.ml      # Source location spans
│   ├── token.ml           # Token representation
│   └── lexer.ml           # Lexical analyzer
├── bin/                    # Binary/executable
│   └── main.ml            # Main entry point
└── test/                   # Tests
```

## Features Implemented

### Lexer
- **Token Types**: Complete set of token types matching the original Java implementation
- **Keywords**: All Loom language keywords with type checking
- **Source Spans**: Accurate source location tracking with error reporting
- **String Literals**: Support for string literals with escape sequences
- **Number Literals**: Support for integer and floating-point numbers
- **Comments**: Single-line (`//`) and block (`/* */`) comments
- **Operators**: All arithmetic, logical, bitwise, and comparison operators
- **Identifiers**: Proper identifier recognition and keyword detection

### Token Types Supported
- **Literals**: `Identifier`, `Number`, `String`, `Null`
- **Keywords**: `Keyword` (with specific keyword detection)
- **Punctuation**: Parentheses, braces, brackets, commas, semicolons, etc.
- **Operators**: Arithmetic (`+`, `-`, `*`, `/`, `%`), logical (`&&`, `||`), bitwise (`&`, `|`, `^`, `~`, `<<`, `>>`, `>>>`), comparison (`==`, `!=`, `<`, `<=`, `>`, `>=`)
- **Assignment**: Compound assignment operators (`+=`, `-=`, `*=`, `/=`, `%=`)
- **Increment/Decrement**: `++`, `--`
- **Special**: `->` (arrow), `::` (double colon), `?` (question mark)

## Building and Running

```bash
# Build the project
dune build

# Run the lexer test
dune exec loomc

# Or run the binary directly
_build/default/bin/main.exe
```

## Example Output

The lexer correctly tokenizes Loom source code:

```ocaml
func main() {
  var x = 42 + 10;
  var y = "hello world";
  if (x > 50) {
    return x;
  } else {
    return y;
  }
}
```

Produces tokens with accurate source locations and proper classification of keywords, identifiers, literals, and operators.

## Design Principles

- **Clean Separation**: Library modules are separated from binary code
- **OCaml Idioms**: Uses standard OCaml patterns and conventions
- **Type Safety**: Leverages OCaml's type system for compile-time safety
- **Error Handling**: Proper error reporting with source location information
- **Maintainability**: Clean, readable code following OCaml best practices

## Next Steps

The lexer is now fully functional and ready for integration with:
1. **Parser**: Syntactic analysis
2. **Semantic Analyzer**: Type checking and symbol resolution
3. **Code Generator**: Target code generation
4. **Error Recovery**: Robust error handling and recovery

This provides a solid foundation for the complete Loom language compiler in OCaml. 