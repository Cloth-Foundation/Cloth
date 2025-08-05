use serde::{Deserialize, Serialize};
use std::collections::HashMap;

/// Represents a value in the LoomVM
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum Value {
    /// Integer values (i8, i16, i32, i64)
    Int(i64),
    
    /// Floating point values (f32, f64)
    Float(f64),
    
    /// Boolean values
    Bool(bool),
    
    /// String values
    String(String),
    
    /// Null value
    Null,
    
    /// Object reference (points to heap object)
    Object(ObjectId),
    
    /// Function reference
    Function(FunctionId),
    
    /// Array reference
    Array(ArrayId),
}

/// Unique identifier for objects in the heap
pub type ObjectId = u64;

/// Unique identifier for functions
pub type FunctionId = u64;

/// Unique identifier for arrays
pub type ArrayId = u64;

/// Bytecode instruction set
#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum Instruction {
    // Stack operations
    PUSH(Value),
    POP,
    DUP,
    SWAP,
    
    // Variable operations
    LOAD_VAR(String),
    STORE_VAR(String),
    LOAD_CONST(usize),  // Index into constant pool
    
    // Arithmetic operations
    ADD,
    SUB,
    MUL,
    DIV,
    MOD,
    NEG,
    
    // Comparison operations
    EQ,
    NE,
    LT,
    LE,
    GT,
    GE,
    
    // Logical operations
    AND,
    OR,
    NOT,
    
    // Control flow
    JMP(usize),      // Unconditional jump
    JMP_IF(usize),   // Conditional jump (if top of stack is true)
    JMP_IF_FALSE(usize), // Conditional jump (if top of stack is false)
    
    // Function operations
    CALL(String),     // Call function by name
    CALL_NATIVE(String), // Call native function
    RETURN,
    
    // Object operations
    NEW(String),      // Create new object of given class
    GET_FIELD(String), // Get object field
    SET_FIELD(String), // Set object field
    GET_METHOD(String), // Get method from object
    
    // Array operations
    NEW_ARRAY(usize), // Create array with given size
    GET_ELEMENT,      // Get array element
    SET_ELEMENT,      // Set array element
    GET_LENGTH,       // Get array length
    
    // Type operations
    CAST(String),     // Cast to type
    IS_NULL,
    IS_TYPE(String),
    
    // Memory operations
    ALLOC(usize),     // Allocate memory block
    FREE,             // Free memory block
    
    // Debug operations
    BREAKPOINT,
    TRACE,
    
    // Special operations
    HALT,
    NOOP,
}

/// Represents a compiled Loom program
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BytecodeProgram {
    /// Program name/identifier
    pub name: String,
    
    /// Version of the bytecode format
    pub version: u32,
    
    /// Constant pool (literals, strings, etc.)
    pub constants: Vec<Value>,
    
    /// Function definitions
    pub functions: HashMap<String, Function>,
    
    /// Class definitions
    pub classes: HashMap<String, Class>,
    
    /// Main entry point
    pub main_function: String,
    
    /// Global variables
    pub globals: HashMap<String, Value>,
}

/// Represents a function in bytecode
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Function {
    /// Function name
    pub name: String,
    
    /// Parameter names
    pub parameters: Vec<String>,
    
    /// Local variable names
    pub locals: Vec<String>,
    
    /// Instructions
    pub instructions: Vec<Instruction>,
    
    /// Return type
    pub return_type: Option<String>,
    
    /// Access level (public, private, protected)
    pub access_level: AccessLevel,
}

/// Represents a class in bytecode
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Class {
    /// Class name
    pub name: String,
    
    /// Parent class (if any)
    pub parent: Option<String>,
    
    /// Field definitions
    pub fields: HashMap<String, Field>,
    
    /// Method definitions
    pub methods: HashMap<String, Function>,
    
    /// Constructor
    pub constructor: Option<Function>,
    
    /// Access level
    pub access_level: AccessLevel,
}

/// Represents a class field
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Field {
    /// Field name
    pub name: String,
    
    /// Field type
    pub field_type: String,
    
    /// Access level
    pub access_level: AccessLevel,
    
    /// Is final/immutable
    pub is_final: bool,
    
    /// Default value
    pub default_value: Option<Value>,
}

/// Access level for functions, classes, and fields
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
pub enum AccessLevel {
    Public,
    Private,
    Protected,
}

impl Default for AccessLevel {
    fn default() -> Self {
        AccessLevel::Public
    }
}

/// Represents an object in the heap
#[derive(Debug, Clone)]
pub struct Object {
    /// Object ID
    pub id: ObjectId,
    
    /// Class name
    pub class_name: String,
    
    /// Field values
    pub fields: HashMap<String, Value>,
    
    /// Reference count (for garbage collection)
    pub ref_count: u32,
}

/// Represents an array in the heap
#[derive(Debug, Clone)]
pub struct Array {
    /// Array ID
    pub id: ArrayId,
    
    /// Element type
    pub element_type: String,
    
    /// Array elements
    pub elements: Vec<Value>,
    
    /// Reference count
    pub ref_count: u32,
}

impl Value {
    /// Get the type name of this value
    pub fn type_name(&self) -> &'static str {
        match self {
            Value::Int(_) => "int",
            Value::Float(_) => "float",
            Value::Bool(_) => "bool",
            Value::String(_) => "string",
            Value::Null => "null",
            Value::Object(_) => "object",
            Value::Function(_) => "function",
            Value::Array(_) => "array",
        }
    }
    
    /// Check if this value is truthy
    pub fn is_truthy(&self) -> bool {
        match self {
            Value::Bool(b) => *b,
            Value::Int(i) => *i != 0,
            Value::Float(f) => *f != 0.0,
            Value::String(s) => !s.is_empty(),
            Value::Null => false,
            Value::Object(_) => true,
            Value::Function(_) => true,
            Value::Array(_) => true,
        }
    }
    
    /// Convert to string representation
    pub fn to_string(&self) -> String {
        match self {
            Value::Int(i) => i.to_string(),
            Value::Float(f) => f.to_string(),
            Value::Bool(b) => b.to_string(),
            Value::String(s) => s.clone(),
            Value::Null => "null".to_string(),
            Value::Object(id) => format!("object#{}", id),
            Value::Function(id) => format!("function#{}", id),
            Value::Array(id) => format!("array#{}", id),
        }
    }
} 