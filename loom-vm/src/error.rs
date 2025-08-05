use thiserror::Error;

#[derive(Error, Debug)]
pub enum VmError {
    #[error("IO error: {0}")]
    Io(#[from] std::io::Error),
    
    #[error("Invalid bytecode format: {0}")]
    InvalidBytecode(String),
    
    #[error("Runtime error: {0}")]
    Runtime(String),
    
    #[error("Type error: {0}")]
    TypeError(String),
    
    #[error("Memory error: {0}")]
    MemoryError(String),
    
    #[error("Stack overflow")]
    StackOverflow,
    
    #[error("Stack underflow")]
    StackUnderflow,
    
    #[error("Undefined variable: {0}")]
    UndefinedVariable(String),
    
    #[error("Undefined function: {0}")]
    UndefinedFunction(String),
    
    #[error("Division by zero")]
    DivisionByZero,
    
    #[error("Invalid instruction at offset {0}: {1}")]
    InvalidInstruction(usize, String),
    
    #[error("Serialization error: {0}")]
    Serialization(String),
    
    #[error("Deserialization error: {0}")]
    Deserialization(String),
}

impl From<bincode::Error> for VmError {
    fn from(err: bincode::Error) -> Self {
        VmError::Serialization(err.to_string())
    }
}

impl From<serde_json::Error> for VmError {
    fn from(err: serde_json::Error) -> Self {
        VmError::Serialization(err.to_string())
    }
} 