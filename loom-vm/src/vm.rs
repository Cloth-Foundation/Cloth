use crate::bytecode::{BytecodeProgram, Instruction, Value, Function};
use crate::error::VmError;
use crate::memory::MemoryManager;
use std::collections::HashMap;
use tracing::{info, debug, error};

/// Virtual machine for executing Loom bytecode
pub struct LoomVM {
    /// Memory manager
    memory: MemoryManager,
    
    /// Program being executed
    program: Option<BytecodeProgram>,
    
    /// Stack for execution
    stack: Vec<Value>,
    
    /// Local variables (function scope)
    locals: HashMap<String, Value>,
    
    /// Global variables
    globals: HashMap<String, Value>,
    
    /// Call stack (for function calls)
    call_stack: Vec<CallFrame>,
    
    /// Current instruction pointer
    ip: usize,
    
    /// Current function being executed
    current_function: Option<String>,
    
    /// Enable debug mode
    debug_mode: bool,
}

/// Call frame for function execution
#[derive(Debug, Clone)]
struct CallFrame {
    /// Function name
    function_name: String,
    
    /// Return address
    return_address: usize,
    
    /// Local variables
    locals: HashMap<String, Value>,
    
    /// Stack base pointer
    stack_base: usize,
}

impl LoomVM {
    /// Create a new LoomVM instance
    pub fn new() -> Self {
        Self {
            memory: MemoryManager::new(),
            program: None,
            stack: Vec::new(),
            locals: HashMap::new(),
            globals: HashMap::new(),
            call_stack: Vec::new(),
            ip: 0,
            current_function: None,
            debug_mode: false,
        }
    }
    
    /// Load and execute a .rl file
    pub fn execute_file(&mut self, filename: &str) -> Result<(), VmError> {
        info!("Loading bytecode file: {}", filename);
        
        // Load the bytecode program
        let program = self.load_bytecode(filename)?;
        self.program = Some(program);
        
        // Execute the program
        self.execute_program()
    }
    
    /// Load bytecode from file
    fn load_bytecode(&self, filename: &str) -> Result<BytecodeProgram, VmError> {
        use std::fs::File;
        use std::io::Read;
        
        let mut file = File::open(filename)?;
        let mut data = Vec::new();
        file.read_to_end(&mut data)?;
        
        // Try to deserialize as bincode first, then JSON
        if let Ok(program) = bincode::deserialize(&data) {
            Ok(program)
        } else if let Ok(json_str) = String::from_utf8(data) {
            let program: BytecodeProgram = serde_json::from_str(&json_str)?;
            Ok(program)
        } else {
            Err(VmError::InvalidBytecode("Failed to parse bytecode file".to_string()))
        }
    }
    
    /// Execute the loaded program
    fn execute_program(&mut self) -> Result<(), VmError> {
        let program = self.program.as_ref()
            .ok_or_else(|| VmError::Runtime("No program loaded".to_string()))?
            .clone();
        
        info!("Starting execution of program: {}", program.name);
        
        // Initialize globals
        self.globals = program.globals.clone();
        
        // Find and execute main function
        let main_function = &program.main_function;
        if let Some(function) = program.functions.get(main_function) {
            self.execute_function(function)?;
        } else {
            return Err(VmError::UndefinedFunction(main_function.clone()));
        }
        
        info!("Program execution completed");
        Ok(())
    }
    
    /// Execute a function
    fn execute_function(&mut self, function: &Function) -> Result<Value, VmError> {
        info!("Executing function: {}", function.name);
        
        // Create call frame
        let call_frame = CallFrame {
            function_name: function.name.clone(),
            return_address: self.ip,
            locals: HashMap::new(),
            stack_base: self.stack.len(),
        };
        
        self.call_stack.push(call_frame);
        self.current_function = Some(function.name.clone());
        
        // Execute instructions
        let result = self.execute_instructions(&function.instructions)?;
        
        // Restore call frame
        if let Some(frame) = self.call_stack.pop() {
            self.ip = frame.return_address;
            self.locals = frame.locals;
        }
        
        self.current_function = None;
        Ok(result)
    }
    
    /// Execute a sequence of instructions
    fn execute_instructions(&mut self, instructions: &[Instruction]) -> Result<Value, VmError> {
        let mut result = Value::Null;
        
        for (i, instruction) in instructions.iter().enumerate() {
            self.ip = i;
            
            if self.debug_mode {
                debug!("Executing instruction {}: {:?}", i, instruction);
            }
            
            match self.execute_instruction(instruction) {
                Ok(value) => {
                    result = value;
                }
                Err(e) => {
                    error!("Error executing instruction {}: {:?} - {}", i, instruction, e);
                    return Err(e);
                }
            }
        }
        
        Ok(result)
    }
    
    /// Execute a single instruction
    fn execute_instruction(&mut self, instruction: &Instruction) -> Result<Value, VmError> {
        match instruction {
            // Stack operations
            Instruction::PUSH(value) => {
                self.stack.push(value.clone());
                Ok(Value::Null)
            }
            Instruction::POP => {
                self.stack.pop().ok_or(VmError::StackUnderflow)
            }
            Instruction::DUP => {
                let value = self.stack.last().cloned()
                    .ok_or(VmError::StackUnderflow)?;
                self.stack.push(value.clone());
                Ok(value)
            }
            Instruction::SWAP => {
                if self.stack.len() < 2 {
                    return Err(VmError::StackUnderflow);
                }
                let len = self.stack.len();
                self.stack.swap(len - 1, len - 2);
                Ok(Value::Null)
            }
            
            // Variable operations
            Instruction::LOAD_VAR(name) => {
                let value = self.get_variable(name)?;
                self.stack.push(value.clone());
                Ok(value)
            }
            Instruction::STORE_VAR(name) => {
                let value = self.stack.pop().ok_or(VmError::StackUnderflow)?;
                self.set_variable(name, value.clone())?;
                Ok(value)
            }
            Instruction::LOAD_CONST(index) => {
                let program = self.program.as_ref()
                    .ok_or_else(|| VmError::Runtime("No program loaded".to_string()))?;
                
                let value = program.constants.get(*index)
                    .ok_or_else(|| VmError::Runtime("Invalid constant index".to_string()))?;
                
                self.stack.push(value.clone());
                Ok(value.clone())
            }
            
            // Arithmetic operations
            Instruction::ADD => self.execute_binary_op(|a, b| Ok(a + b)),
            Instruction::SUB => self.execute_binary_op(|a, b| Ok(a - b)),
            Instruction::MUL => self.execute_binary_op(|a, b| Ok(a * b)),
            Instruction::DIV => self.execute_binary_op(|a, b| {
                if b == 0 { return Err(VmError::DivisionByZero); }
                Ok(a / b)
            }),
            Instruction::MOD => self.execute_binary_op(|a, b| {
                if b == 0 { return Err(VmError::DivisionByZero); }
                Ok(a % b)
            }),
            Instruction::NEG => {
                let value = self.stack.pop().ok_or(VmError::StackUnderflow)?;
                let negated = match value {
                    Value::Int(i) => Value::Int(-i),
                    Value::Float(f) => Value::Float(-f),
                    _ => return Err(VmError::TypeError("Cannot negate non-numeric value".to_string())),
                };
                self.stack.push(negated.clone());
                Ok(negated)
            }
            
            // Comparison operations
            Instruction::EQ => self.execute_comparison(|a, b| a == b),
            Instruction::NE => self.execute_comparison(|a, b| a != b),
            Instruction::LT => self.execute_comparison(|a, b| a < b),
            Instruction::LE => self.execute_comparison(|a, b| a <= b),
            Instruction::GT => self.execute_comparison(|a, b| a > b),
            Instruction::GE => self.execute_comparison(|a, b| a >= b),
            
            // Logical operations
            Instruction::AND => self.execute_logical_op(|a, b| a && b),
            Instruction::OR => self.execute_logical_op(|a, b| a || b),
            Instruction::NOT => {
                let value = self.stack.pop().ok_or(VmError::StackUnderflow)?;
                let result = match value {
                    Value::Bool(b) => Value::Bool(!b),
                    _ => return Err(VmError::TypeError("Cannot apply NOT to non-boolean value".to_string())),
                };
                self.stack.push(result.clone());
                Ok(result)
            }
            
            // Control flow
            Instruction::JMP(offset) => {
                self.ip = *offset;
                Ok(Value::Null)
            }
            Instruction::JMP_IF(offset) => {
                let condition = self.stack.pop().ok_or(VmError::StackUnderflow)?;
                if condition.is_truthy() {
                    self.ip = *offset;
                }
                Ok(Value::Null)
            }
            Instruction::JMP_IF_FALSE(offset) => {
                let condition = self.stack.pop().ok_or(VmError::StackUnderflow)?;
                if !condition.is_truthy() {
                    self.ip = *offset;
                }
                Ok(Value::Null)
            }
            
            // Function operations
            Instruction::CALL(name) => {
                self.call_function(name)
            }
            Instruction::CALL_NATIVE(name) => {
                self.call_native_function(name)
            }
            Instruction::RETURN => {
                let value = self.stack.pop().unwrap_or(Value::Null);
                Ok(value)
            }
            
            // Object operations
            Instruction::NEW(class_name) => {
                let object_id = self.memory.allocate_object(class_name.clone());
                let value = Value::Object(object_id);
                self.stack.push(value.clone());
                Ok(value)
            }
            Instruction::GET_FIELD(field_name) => {
                let object_value = self.stack.pop().ok_or(VmError::StackUnderflow)?;
                let field_value = self.get_object_field(&object_value, field_name)?;
                self.stack.push(field_value.clone());
                Ok(field_value)
            }
            Instruction::SET_FIELD(field_name) => {
                let value = self.stack.pop().ok_or(VmError::StackUnderflow)?;
                let object_value = self.stack.pop().ok_or(VmError::StackUnderflow)?;
                self.set_object_field(&object_value, field_name, value.clone())?;
                self.stack.push(value.clone());
                Ok(value)
            }
            
            // Array operations
            Instruction::NEW_ARRAY(size) => {
                let array_id = self.memory.allocate_array("any".to_string(), *size);
                let value = Value::Array(array_id);
                self.stack.push(value.clone());
                Ok(value)
            }
            Instruction::GET_ELEMENT => {
                let index_value = self.stack.pop().ok_or(VmError::StackUnderflow)?;
                let array_value = self.stack.pop().ok_or(VmError::StackUnderflow)?;
                let element = self.get_array_element(&array_value, &index_value)?;
                self.stack.push(element.clone());
                Ok(element)
            }
            Instruction::SET_ELEMENT => {
                let value = self.stack.pop().ok_or(VmError::StackUnderflow)?;
                let index_value = self.stack.pop().ok_or(VmError::StackUnderflow)?;
                let array_value = self.stack.pop().ok_or(VmError::StackUnderflow)?;
                self.set_array_element(&array_value, &index_value, value.clone())?;
                self.stack.push(value.clone());
                Ok(value)
            }
            Instruction::GET_LENGTH => {
                let array_value = self.stack.pop().ok_or(VmError::StackUnderflow)?;
                let length = self.get_array_length(&array_value)?;
                let value = Value::Int(length as i64);
                self.stack.push(value.clone());
                Ok(value)
            }
            
            // Special operations
            Instruction::HALT => {
                info!("VM halted");
                Ok(Value::Null)
            }
            Instruction::NOOP => {
                Ok(Value::Null)
            }
            
            // Debug operations
            Instruction::BREAKPOINT => {
                if self.debug_mode {
                    info!("Breakpoint hit at instruction {}", self.ip);
                }
                Ok(Value::Null)
            }
            Instruction::TRACE => {
                let value = self.stack.last().cloned().unwrap_or(Value::Null);
                info!("TRACE: {:?}", value);
                Ok(value)
            }
            
            // Unimplemented instructions
            _ => {
                Err(VmError::InvalidInstruction(self.ip, format!("Unimplemented instruction: {:?}", instruction)))
            }
        }
    }
    
    /// Execute binary arithmetic operation
    fn execute_binary_op<F>(&mut self, op: F) -> Result<Value, VmError>
    where
        F: FnOnce(i64, i64) -> Result<i64, VmError>,
    {
        let b = self.stack.pop().ok_or(VmError::StackUnderflow)?;
        let a = self.stack.pop().ok_or(VmError::StackUnderflow)?;
        
        let result = match (a, b) {
            (Value::Int(a), Value::Int(b)) => {
                let result = op(a, b)?;
                Value::Int(result)
            }
            (Value::Float(a), Value::Float(b)) => {
                let result = op(a as i64, b as i64)?;
                Value::Float(result as f64)
            }
            (Value::Int(a), Value::Float(b)) => {
                let result = op(a, b as i64)?;
                Value::Float(result as f64)
            }
            (Value::Float(a), Value::Int(b)) => {
                let result = op(a as i64, b)?;
                Value::Float(result as f64)
            }
            _ => return Err(VmError::TypeError("Invalid operands for arithmetic operation".to_string())),
        };
        
        self.stack.push(result.clone());
        Ok(result)
    }
    
    /// Execute comparison operation
    fn execute_comparison<F>(&mut self, op: F) -> Result<Value, VmError>
    where
        F: FnOnce(i64, i64) -> bool,
    {
        let b = self.stack.pop().ok_or(VmError::StackUnderflow)?;
        let a = self.stack.pop().ok_or(VmError::StackUnderflow)?;
        
        let result = match (a, b) {
            (Value::Int(a), Value::Int(b)) => Value::Bool(op(a, b)),
            (Value::Float(a), Value::Float(b)) => Value::Bool(op(a as i64, b as i64)),
            (Value::String(a), Value::String(b)) => Value::Bool(op(a.len() as i64, b.len() as i64)),
            _ => return Err(VmError::TypeError("Invalid operands for comparison".to_string())),
        };
        
        self.stack.push(result.clone());
        Ok(result)
    }
    
    /// Execute logical operation
    fn execute_logical_op<F>(&mut self, op: F) -> Result<Value, VmError>
    where
        F: FnOnce(bool, bool) -> bool,
    {
        let b = self.stack.pop().ok_or(VmError::StackUnderflow)?;
        let a = self.stack.pop().ok_or(VmError::StackUnderflow)?;
        
        let result = match (a, b) {
            (Value::Bool(a), Value::Bool(b)) => Value::Bool(op(a, b)),
            _ => return Err(VmError::TypeError("Invalid operands for logical operation".to_string())),
        };
        
        self.stack.push(result.clone());
        Ok(result)
    }
    
    /// Get a variable (local or global)
    fn get_variable(&self, name: &str) -> Result<Value, VmError> {
        // Check locals first
        if let Some(value) = self.locals.get(name) {
            return Ok(value.clone());
        }
        
        // Check globals
        if let Some(value) = self.globals.get(name) {
            return Ok(value.clone());
        }
        
        Err(VmError::UndefinedVariable(name.to_string()))
    }
    
    /// Set a variable (local or global)
    fn set_variable(&mut self, name: &str, value: Value) -> Result<(), VmError> {
        // Set in locals (function scope)
        self.locals.insert(name.to_string(), value);
        Ok(())
    }
    
    /// Call a function
    fn call_function(&mut self, name: &str) -> Result<Value, VmError> {
        let program = self.program.as_ref()
            .ok_or_else(|| VmError::Runtime("No program loaded".to_string()))?
            .clone();
        
        if let Some(function) = program.functions.get(name) {
            self.execute_function(function)
        } else {
            Err(VmError::UndefinedFunction(name.to_string()))
        }
    }
    
    /// Call a native function
    fn call_native_function(&mut self, name: &str) -> Result<Value, VmError> {
        match name {
            "print" => {
                let value = self.stack.pop().ok_or(VmError::StackUnderflow)?;
                println!("{}", value.to_string());
                Ok(Value::Null)
            }
            "println" => {
                let value = self.stack.pop().ok_or(VmError::StackUnderflow)?;
                println!("{}", value.to_string());
                Ok(Value::Null)
            }
            _ => Err(VmError::UndefinedFunction(format!("native:{}", name))),
        }
    }
    
    /// Get object field
    fn get_object_field(&self, object_value: &Value, field_name: &str) -> Result<Value, VmError> {
        if let Value::Object(id) = object_value {
            if let Some(object) = self.memory.get_object(*id) {
                if let Some(value) = object.fields.get(field_name) {
                    return Ok(value.clone());
                }
            }
        }
        Err(VmError::Runtime(format!("Field '{}' not found", field_name)))
    }
    
    /// Set object field
    fn set_object_field(&mut self, object_value: &Value, field_name: &str, value: Value) -> Result<(), VmError> {
        if let Value::Object(id) = object_value {
            if let Some(mut object) = self.memory.get_object(*id) {
                object.fields.insert(field_name.to_string(), value);
                self.memory.update_object(object)?;
                return Ok(());
            }
        }
        Err(VmError::Runtime(format!("Cannot set field '{}'", field_name)))
    }
    
    /// Get array element
    fn get_array_element(&self, array_value: &Value, index_value: &Value) -> Result<Value, VmError> {
        if let (Value::Array(id), Value::Int(index)) = (array_value, index_value) {
            if let Some(array) = self.memory.get_array(*id) {
                if let Some(element) = array.elements.get(*index as usize) {
                    return Ok(element.clone());
                }
            }
        }
        Err(VmError::Runtime("Invalid array access".to_string()))
    }
    
    /// Set array element
    fn set_array_element(&mut self, array_value: &Value, index_value: &Value, value: Value) -> Result<(), VmError> {
        if let (Value::Array(id), Value::Int(index)) = (array_value, index_value) {
            if let Some(mut array) = self.memory.get_array(*id) {
                if let Some(element) = array.elements.get_mut(*index as usize) {
                    *element = value;
                    self.memory.update_array(array)?;
                    return Ok(());
                }
            }
        }
        Err(VmError::Runtime("Invalid array access".to_string()))
    }
    
    /// Get array length
    fn get_array_length(&self, array_value: &Value) -> Result<usize, VmError> {
        if let Value::Array(id) = array_value {
            if let Some(array) = self.memory.get_array(*id) {
                return Ok(array.elements.len());
            }
        }
        Err(VmError::Runtime("Invalid array".to_string()))
    }
    
    /// Enable debug mode
    pub fn set_debug_mode(&mut self, enabled: bool) {
        self.debug_mode = enabled;
    }
    
    /// Get memory statistics
    pub fn get_memory_stats(&self) -> crate::memory::profiler::MemoryStats {
        self.memory.get_stats()
    }
} 