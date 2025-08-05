# LoomVM

A high-performance virtual machine for the Loom programming language, written in Rust.

## Features

- **High Performance**: Written in Rust for maximum performance and memory safety
- **Adaptive Memory Management**: Smart pointer system that adapts based on usage patterns
- **Comprehensive Standard Library**: Built-in functions for I/O, math, strings, and more
- **Memory Profiling**: Built-in memory usage tracking and optimization
- **Cross-Platform**: Runs on Windows, macOS, and Linux
- **Extensible**: Easy to add new native functions and features

## Architecture

### Core Components

- **Bytecode Interpreter**: Executes `.rl` bytecode files
- **Memory Manager**: Handles object allocation and garbage collection
- **Smart Pointer System**: Adaptive memory management with usage-based optimization
- **Runtime Environment**: Standard library and native function support
- **Error Handling**: Comprehensive error reporting and debugging

### Memory Management

The LoomVM features an advanced memory management system:

- **Adaptive Allocator**: Switches between stack, pool, heap, and arena allocation strategies
- **Smart Pointers**: Automatically adapts between shared, unique, weak, and borrowed references
- **Reference Counting**: Automatic memory cleanup with zero-cost abstractions
- **Memory Profiling**: Tracks allocation patterns for optimization

### Bytecode Format

LoomVM executes `.rl` files containing:

- **Constant Pool**: Literals, strings, and other constants
- **Function Definitions**: Compiled function bytecode
- **Class Definitions**: Object structure and method information
- **Global Variables**: Program-wide variable storage

## Installation

### Prerequisites

- Rust 1.70 or later
- Cargo (comes with Rust)

### Building

```bash
# Clone the repository
git clone https://github.com/loom-lang/loom.git
cd loom/loom-vm

# Build the project
cargo build --release

# Run tests
cargo test

# Run benchmarks
cargo bench
```

### Usage

```bash
# Execute a .rl file
./target/release/loom-vm program.rl

# Enable debug mode
./target/release/loom-vm --debug program.rl

# Enable verbose logging
./target/release/loom-vm --verbose program.rl
```

## Development

### Project Structure

```
loom-vm/
├── src/
│   ├── main.rs          # CLI entry point
│   ├── bytecode.rs      # Bytecode definitions and serialization
│   ├── vm.rs            # Virtual machine implementation
│   ├── error.rs         # Error handling
│   ├── memory/          # Memory management system
│   │   ├── mod.rs       # Memory manager
│   │   ├── smart_pointer.rs  # Adaptive smart pointers
│   │   ├── allocator.rs # Adaptive allocator
│   │   └── profiler.rs  # Memory profiling
│   └── runtime.rs       # Runtime environment and standard library
├── Cargo.toml           # Dependencies and build configuration
└── README.md           # This file
```

### Adding New Features

#### Native Functions

To add a new native function:

1. Add the function to `src/runtime.rs`:
```rust
fn native_my_function(args: &[Value]) -> Result<Value, VmError> {
    // Implementation here
    Ok(Value::Null)
}
```

2. Register it in the `register_standard_library` method:
```rust
self.register_native("my_function", Self::native_my_function);
```

#### New Instructions

To add new bytecode instructions:

1. Add the instruction to `src/bytecode.rs`:
```rust
pub enum Instruction {
    // ... existing instructions
    MY_NEW_INSTRUCTION(String),
}
```

2. Implement execution in `src/vm.rs`:
```rust
Instruction::MY_NEW_INSTRUCTION(param) => {
    // Implementation here
    Ok(Value::Null)
}
```

### Testing

```bash
# Run all tests
cargo test

# Run specific test
cargo test test_name

# Run with output
cargo test -- --nocapture
```

### Benchmarking

```bash
# Run benchmarks
cargo bench

# Run specific benchmark
cargo bench benchmark_name
```

## Performance

LoomVM is designed for high performance:

- **Zero-Cost Abstractions**: Smart pointers have no runtime overhead
- **Memory Safety**: Rust's ownership system prevents memory bugs
- **Efficient Allocation**: Adaptive allocator optimizes for different usage patterns
- **Fast Execution**: Optimized bytecode interpreter

### Memory Management

The adaptive memory management system automatically:

- **Tracks Usage Patterns**: Monitors how objects are accessed and modified
- **Switches Strategies**: Adapts between shared, unique, weak, and borrowed references
- **Optimizes Allocation**: Chooses the best allocation strategy (stack, pool, heap, arena)
- **Profiles Performance**: Tracks memory usage for optimization

### Example Usage Patterns

```rust
// Shared ownership (multiple references)
let shared = SmartPointer::new_shared(data);

// Unique ownership (single reference)
let unique = SmartPointer::new_unique(data);

// Weak reference (doesn't prevent cleanup)
let weak = SmartPointer::new_weak(&shared);

// Borrowed reference (lifetime limited)
let borrowed = SmartPointer::Borrowed(&data);
```

## Standard Library

LoomVM includes a comprehensive standard library:

### I/O Functions
- `print(message)` - Print without newline
- `println(message)` - Print with newline
- `printf(format, ...args)` - Formatted printing
- `read_line()` - Read input from user

### Math Functions
- `add(a, b)` - Addition
- `subtract(a, b)` - Subtraction
- `multiply(a, b)` - Multiplication
- `divide(a, b)` - Division
- `modulo(a, b)` - Modulo
- `abs(x)` - Absolute value
- `sqrt(x)` - Square root
- `pow(x, y)` - Power
- `sin(x)`, `cos(x)`, `tan(x)` - Trigonometric functions

### String Functions
- `length(str)` - String length
- `isEmpty(str)` - Check if empty
- `toUpperCase(str)` - Convert to uppercase
- `toLowerCase(str)` - Convert to lowercase
- `substring(str, start, end)` - Extract substring
- `indexOf(str, sub)` - Find substring
- `replace(str, from, to)` - Replace substring
- `trim(str)` - Remove whitespace

### Type Functions
- `typeOf(value)` - Get type name
- `isNull(value)` - Check if null
- `isNumber(value)` - Check if number
- `isString(value)` - Check if string
- `isBool(value)` - Check if boolean
- `isObject(value)` - Check if object
- `isArray(value)` - Check if array

### Utility Functions
- `random()` - Random number (0-1)
- `time()` - Current timestamp
- `sleep(seconds)` - Sleep for seconds

## Error Handling

LoomVM provides comprehensive error handling:

- **Compile-time Errors**: Invalid bytecode format
- **Runtime Errors**: Undefined variables, type mismatches
- **Memory Errors**: Allocation failures, stack overflow
- **System Errors**: I/O failures, resource limits

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests for new functionality
5. Run the test suite
6. Submit a pull request

### Code Style

- Follow Rust conventions
- Use meaningful variable names
- Add documentation for public APIs
- Include error handling
- Write tests for new features

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Roadmap

- [ ] JIT compilation for hot code paths
- [ ] Parallel execution support
- [ ] Advanced garbage collection
- [ ] Debugger and profiling tools
- [ ] WebAssembly backend
- [ ] IDE integration
- [ ] Package management system

## Acknowledgments

- Built with Rust for performance and safety
- Inspired by modern VM designs
- Thanks to the Rust community for excellent tooling 