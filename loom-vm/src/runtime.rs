use crate::bytecode::Value;
use crate::error::VmError;
use std::collections::HashMap;

/// Native function signature
pub type NativeFunction = fn(&[Value]) -> Result<Value, VmError>;

/// Runtime environment with native functions
pub struct Runtime {
    /// Native function registry
    native_functions: HashMap<String, NativeFunction>,
    
    /// Runtime configuration
    config: RuntimeConfig,
}

/// Runtime configuration
#[derive(Debug, Clone)]
pub struct RuntimeConfig {
    /// Enable debug mode
    pub debug_mode: bool,
    
    /// Enable profiling
    pub profiling_enabled: bool,
    
    /// Memory limit in bytes
    pub memory_limit: Option<usize>,
    
    /// Stack size limit
    pub stack_limit: Option<usize>,
}

impl Default for RuntimeConfig {
    fn default() -> Self {
        Self {
            debug_mode: false,
            profiling_enabled: false,
            memory_limit: Some(1024 * 1024 * 1024), // 1GB
            stack_limit: Some(1024 * 1024), // 1MB
        }
    }
}

impl Runtime {
    /// Create a new runtime environment
    pub fn new() -> Self {
        let mut runtime = Self {
            native_functions: HashMap::new(),
            config: RuntimeConfig::default(),
        };
        
        // Register standard library functions
        runtime.register_standard_library();
        
        runtime
    }
    
    /// Register the standard library functions
    fn register_standard_library(&mut self) {
        // IO functions
        self.register_native("print", Self::native_print);
        self.register_native("println", Self::native_println);
        self.register_native("printf", Self::native_printf);
        self.register_native("read_line", Self::native_read_line);
        
        // Math functions
        self.register_native("add", Self::native_add);
        self.register_native("subtract", Self::native_subtract);
        self.register_native("multiply", Self::native_multiply);
        self.register_native("divide", Self::native_divide);
        self.register_native("modulo", Self::native_modulo);
        self.register_native("abs", Self::native_abs);
        self.register_native("sqrt", Self::native_sqrt);
        self.register_native("pow", Self::native_pow);
        self.register_native("sin", Self::native_sin);
        self.register_native("cos", Self::native_cos);
        self.register_native("tan", Self::native_tan);
        
        // String functions
        self.register_native("length", Self::native_length);
        self.register_native("isEmpty", Self::native_is_empty);
        self.register_native("toUpperCase", Self::native_to_upper_case);
        self.register_native("toLowerCase", Self::native_to_lower_case);
        self.register_native("substring", Self::native_substring);
        self.register_native("indexOf", Self::native_index_of);
        self.register_native("replace", Self::native_replace);
        self.register_native("trim", Self::native_trim);
        
        // Array functions
        self.register_native("array_length", Self::native_array_length);
        self.register_native("array_push", Self::native_array_push);
        self.register_native("array_pop", Self::native_array_pop);
        self.register_native("array_insert", Self::native_array_insert);
        self.register_native("array_remove", Self::native_array_remove);
        
        // Type functions
        self.register_native("typeOf", Self::native_type_of);
        self.register_native("isNull", Self::native_is_null);
        self.register_native("isNumber", Self::native_is_number);
        self.register_native("isString", Self::native_is_string);
        self.register_native("isBool", Self::native_is_bool);
        self.register_native("isObject", Self::native_is_object);
        self.register_native("isArray", Self::native_is_array);
        
        // Utility functions
        self.register_native("random", Self::native_random);
        self.register_native("time", Self::native_time);
        self.register_native("sleep", Self::native_sleep);
    }
    
    /// Register a native function
    pub fn register_native(&mut self, name: &str, func: NativeFunction) {
        self.native_functions.insert(name.to_string(), func);
    }
    
    /// Call a native function
    pub fn call_native(&self, name: &str, args: &[Value]) -> Result<Value, VmError> {
        if let Some(func) = self.native_functions.get(name) {
            func(args)
        } else {
            Err(VmError::UndefinedFunction(format!("native:{}", name)))
        }
    }
    
    /// Get runtime configuration
    pub fn config(&self) -> &RuntimeConfig {
        &self.config
    }
    
    /// Set runtime configuration
    pub fn set_config(&mut self, config: RuntimeConfig) {
        self.config = config;
    }
    
    // Standard Library Functions
    
    /// Print function
    fn native_print(args: &[Value]) -> Result<Value, VmError> {
        if args.is_empty() {
            return Ok(Value::Null);
        }
        
        for arg in args {
            print!("{}", arg.to_string());
        }
        
        Ok(Value::Null)
    }
    
    /// Println function
    fn native_println(args: &[Value]) -> Result<Value, VmError> {
        if args.is_empty() {
            println!();
            return Ok(Value::Null);
        }
        
        for arg in args {
            print!("{}", arg.to_string());
        }
        println!();
        
        Ok(Value::Null)
    }
    
    /// Printf function (simple implementation)
    fn native_printf(args: &[Value]) -> Result<Value, VmError> {
        if args.is_empty() {
            return Ok(Value::Null);
        }
        
        let format_str = args[0].to_string();
        let mut result = format_str;
        
        for arg in args.iter().skip(1) {
            result = result.replacen("{}", &arg.to_string(), 1);
        }
        
        print!("{}", result);
        Ok(Value::Null)
    }
    
    /// Read line function
    fn native_read_line(_args: &[Value]) -> Result<Value, VmError> {
        use std::io::{self, Write};
        
        io::stdout().flush().map_err(|e| VmError::Runtime(e.to_string()))?;
        
        let mut input = String::new();
        io::stdin().read_line(&mut input).map_err(|e| VmError::Runtime(e.to_string()))?;
        
        // Remove trailing newline
        input = input.trim_end_matches('\n').to_string();
        
        Ok(Value::String(input))
    }
    
    /// Add function
    fn native_add(args: &[Value]) -> Result<Value, VmError> {
        if args.len() != 2 {
            return Err(VmError::Runtime("add requires exactly 2 arguments".to_string()));
        }
        
        match (&args[0], &args[1]) {
            (Value::Int(a), Value::Int(b)) => Ok(Value::Int(a + b)),
            (Value::Float(a), Value::Float(b)) => Ok(Value::Float(a + b)),
            (Value::Int(a), Value::Float(b)) => Ok(Value::Float(*a as f64 + b)),
            (Value::Float(a), Value::Int(b)) => Ok(Value::Float(a + *b as f64)),
            (Value::String(a), Value::String(b)) => Ok(Value::String(a.clone() + b)),
            _ => Err(VmError::TypeError("Invalid operands for addition".to_string())),
        }
    }
    
    /// Subtract function
    fn native_subtract(args: &[Value]) -> Result<Value, VmError> {
        if args.len() != 2 {
            return Err(VmError::Runtime("subtract requires exactly 2 arguments".to_string()));
        }
        
        match (&args[0], &args[1]) {
            (Value::Int(a), Value::Int(b)) => Ok(Value::Int(a - b)),
            (Value::Float(a), Value::Float(b)) => Ok(Value::Float(a - b)),
            (Value::Int(a), Value::Float(b)) => Ok(Value::Float(*a as f64 - b)),
            (Value::Float(a), Value::Int(b)) => Ok(Value::Float(a - *b as f64)),
            _ => Err(VmError::TypeError("Invalid operands for subtraction".to_string())),
        }
    }
    
    /// Multiply function
    fn native_multiply(args: &[Value]) -> Result<Value, VmError> {
        if args.len() != 2 {
            return Err(VmError::Runtime("multiply requires exactly 2 arguments".to_string()));
        }
        
        match (&args[0], &args[1]) {
            (Value::Int(a), Value::Int(b)) => Ok(Value::Int(a * b)),
            (Value::Float(a), Value::Float(b)) => Ok(Value::Float(a * b)),
            (Value::Int(a), Value::Float(b)) => Ok(Value::Float(*a as f64 * b)),
            (Value::Float(a), Value::Int(b)) => Ok(Value::Float(a * *b as f64)),
            _ => Err(VmError::TypeError("Invalid operands for multiplication".to_string())),
        }
    }
    
    /// Divide function
    fn native_divide(args: &[Value]) -> Result<Value, VmError> {
        if args.len() != 2 {
            return Err(VmError::Runtime("divide requires exactly 2 arguments".to_string()));
        }
        
        match (&args[0], &args[1]) {
            (Value::Int(a), Value::Int(b)) => {
                if *b == 0 {
                    return Err(VmError::DivisionByZero);
                }
                Ok(Value::Int(a / b))
            }
            (Value::Float(a), Value::Float(b)) => {
                if *b == 0.0 {
                    return Err(VmError::DivisionByZero);
                }
                Ok(Value::Float(a / b))
            }
            (Value::Int(a), Value::Float(b)) => {
                if *b == 0.0 {
                    return Err(VmError::DivisionByZero);
                }
                Ok(Value::Float(*a as f64 / b))
            }
            (Value::Float(a), Value::Int(b)) => {
                if *b == 0 {
                    return Err(VmError::DivisionByZero);
                }
                Ok(Value::Float(a / *b as f64))
            }
            _ => Err(VmError::TypeError("Invalid operands for division".to_string())),
        }
    }
    
    /// Modulo function
    fn native_modulo(args: &[Value]) -> Result<Value, VmError> {
        if args.len() != 2 {
            return Err(VmError::Runtime("modulo requires exactly 2 arguments".to_string()));
        }
        
        match (&args[0], &args[1]) {
            (Value::Int(a), Value::Int(b)) => {
                if *b == 0 {
                    return Err(VmError::DivisionByZero);
                }
                Ok(Value::Int(a % b))
            }
            (Value::Float(a), Value::Float(b)) => {
                if *b == 0.0 {
                    return Err(VmError::DivisionByZero);
                }
                Ok(Value::Float(a % b))
            }
            _ => Err(VmError::TypeError("Invalid operands for modulo".to_string())),
        }
    }
    
    /// Absolute value function
    fn native_abs(args: &[Value]) -> Result<Value, VmError> {
        if args.len() != 1 {
            return Err(VmError::Runtime("abs requires exactly 1 argument".to_string()));
        }
        
        match &args[0] {
            Value::Int(a) => Ok(Value::Int(a.abs())),
            Value::Float(a) => Ok(Value::Float(a.abs())),
            _ => Err(VmError::TypeError("Invalid operand for abs".to_string())),
        }
    }
    
    /// Square root function
    fn native_sqrt(args: &[Value]) -> Result<Value, VmError> {
        if args.len() != 1 {
            return Err(VmError::Runtime("sqrt requires exactly 1 argument".to_string()));
        }
        
        match &args[0] {
            Value::Int(a) => {
                if *a < 0 {
                    return Err(VmError::Runtime("Cannot take square root of negative number".to_string()));
                }
                Ok(Value::Float((*a as f64).sqrt()))
            }
            Value::Float(a) => {
                if *a < 0.0 {
                    return Err(VmError::Runtime("Cannot take square root of negative number".to_string()));
                }
                Ok(Value::Float(a.sqrt()))
            }
            _ => Err(VmError::TypeError("Invalid operand for sqrt".to_string())),
        }
    }
    
    /// Power function
    fn native_pow(args: &[Value]) -> Result<Value, VmError> {
        if args.len() != 2 {
            return Err(VmError::Runtime("pow requires exactly 2 arguments".to_string()));
        }
        
        match (&args[0], &args[1]) {
            (Value::Int(a), Value::Int(b)) => Ok(Value::Float((*a as f64).powf(*b as f64))),
            (Value::Float(a), Value::Float(b)) => Ok(Value::Float(a.powf(*b))),
            (Value::Int(a), Value::Float(b)) => Ok(Value::Float((*a as f64).powf(*b))),
            (Value::Float(a), Value::Int(b)) => Ok(Value::Float(a.powi(*b as i32))),
            _ => Err(VmError::TypeError("Invalid operands for pow".to_string())),
        }
    }
    
    /// Trigonometric functions
    fn native_sin(args: &[Value]) -> Result<Value, VmError> {
        if args.len() != 1 {
            return Err(VmError::Runtime("sin requires exactly 1 argument".to_string()));
        }
        
        match &args[0] {
            Value::Int(a) => Ok(Value::Float((*a as f64).sin())),
            Value::Float(a) => Ok(Value::Float(a.sin())),
            _ => Err(VmError::TypeError("Invalid operand for sin".to_string())),
        }
    }
    
    fn native_cos(args: &[Value]) -> Result<Value, VmError> {
        if args.len() != 1 {
            return Err(VmError::Runtime("cos requires exactly 1 argument".to_string()));
        }
        
        match &args[0] {
            Value::Int(a) => Ok(Value::Float((*a as f64).cos())),
            Value::Float(a) => Ok(Value::Float(a.cos())),
            _ => Err(VmError::TypeError("Invalid operand for cos".to_string())),
        }
    }
    
    fn native_tan(args: &[Value]) -> Result<Value, VmError> {
        if args.len() != 1 {
            return Err(VmError::Runtime("tan requires exactly 1 argument".to_string()));
        }
        
        match &args[0] {
            Value::Int(a) => Ok(Value::Float((*a as f64).tan())),
            Value::Float(a) => Ok(Value::Float(a.tan())),
            _ => Err(VmError::TypeError("Invalid operand for tan".to_string())),
        }
    }
    
    /// String functions
    fn native_length(args: &[Value]) -> Result<Value, VmError> {
        if args.len() != 1 {
            return Err(VmError::Runtime("length requires exactly 1 argument".to_string()));
        }
        
        match &args[0] {
            Value::String(s) => Ok(Value::Int(s.len() as i64)),
            Value::Array(_) => Ok(Value::Int(0)), // TODO: Implement array length
            _ => Err(VmError::TypeError("Invalid operand for length".to_string())),
        }
    }
    
    fn native_is_empty(args: &[Value]) -> Result<Value, VmError> {
        if args.len() != 1 {
            return Err(VmError::Runtime("isEmpty requires exactly 1 argument".to_string()));
        }
        
        match &args[0] {
            Value::String(s) => Ok(Value::Bool(s.is_empty())),
            Value::Array(_) => Ok(Value::Bool(true)), // TODO: Implement array empty check
            _ => Err(VmError::TypeError("Invalid operand for isEmpty".to_string())),
        }
    }
    
    fn native_to_upper_case(args: &[Value]) -> Result<Value, VmError> {
        if args.len() != 1 {
            return Err(VmError::Runtime("toUpperCase requires exactly 1 argument".to_string()));
        }
        
        match &args[0] {
            Value::String(s) => Ok(Value::String(s.to_uppercase())),
            _ => Err(VmError::TypeError("Invalid operand for toUpperCase".to_string())),
        }
    }
    
    fn native_to_lower_case(args: &[Value]) -> Result<Value, VmError> {
        if args.len() != 1 {
            return Err(VmError::Runtime("toLowerCase requires exactly 1 argument".to_string()));
        }
        
        match &args[0] {
            Value::String(s) => Ok(Value::String(s.to_lowercase())),
            _ => Err(VmError::TypeError("Invalid operand for toLowerCase".to_string())),
        }
    }
    
    fn native_substring(args: &[Value]) -> Result<Value, VmError> {
        if args.len() != 3 {
            return Err(VmError::Runtime("substring requires exactly 3 arguments".to_string()));
        }
        
        match (&args[0], &args[1], &args[2]) {
            (Value::String(s), Value::Int(start), Value::Int(end)) => {
                let start = *start as usize;
                let end = *end as usize;
                
                if start > s.len() || end > s.len() || start > end {
                    return Err(VmError::Runtime("Invalid substring indices".to_string()));
                }
                
                Ok(Value::String(s[start..end].to_string()))
            }
            _ => Err(VmError::TypeError("Invalid operands for substring".to_string())),
        }
    }
    
    fn native_index_of(args: &[Value]) -> Result<Value, VmError> {
        if args.len() != 2 {
            return Err(VmError::Runtime("indexOf requires exactly 2 arguments".to_string()));
        }
        
        match (&args[0], &args[1]) {
            (Value::String(s), Value::String(sub)) => {
                if let Some(index) = s.find(sub) {
                    Ok(Value::Int(index as i64))
                } else {
                    Ok(Value::Int(-1))
                }
            }
            _ => Err(VmError::TypeError("Invalid operands for indexOf".to_string())),
        }
    }
    
    fn native_replace(args: &[Value]) -> Result<Value, VmError> {
        if args.len() != 3 {
            return Err(VmError::Runtime("replace requires exactly 3 arguments".to_string()));
        }
        
        match (&args[0], &args[1], &args[2]) {
            (Value::String(s), Value::String(from), Value::String(to)) => {
                Ok(Value::String(s.replace(from, to)))
            }
            _ => Err(VmError::TypeError("Invalid operands for replace".to_string())),
        }
    }
    
    fn native_trim(args: &[Value]) -> Result<Value, VmError> {
        if args.len() != 1 {
            return Err(VmError::Runtime("trim requires exactly 1 argument".to_string()));
        }
        
        match &args[0] {
            Value::String(s) => Ok(Value::String(s.trim().to_string())),
            _ => Err(VmError::TypeError("Invalid operand for trim".to_string())),
        }
    }
    
    /// Array functions (placeholder implementations)
    fn native_array_length(_args: &[Value]) -> Result<Value, VmError> {
        Ok(Value::Int(0)) // TODO: Implement
    }
    
    fn native_array_push(_args: &[Value]) -> Result<Value, VmError> {
        Ok(Value::Null) // TODO: Implement
    }
    
    fn native_array_pop(_args: &[Value]) -> Result<Value, VmError> {
        Ok(Value::Null) // TODO: Implement
    }
    
    fn native_array_insert(_args: &[Value]) -> Result<Value, VmError> {
        Ok(Value::Null) // TODO: Implement
    }
    
    fn native_array_remove(_args: &[Value]) -> Result<Value, VmError> {
        Ok(Value::Null) // TODO: Implement
    }
    
    /// Type functions
    fn native_type_of(args: &[Value]) -> Result<Value, VmError> {
        if args.len() != 1 {
            return Err(VmError::Runtime("typeOf requires exactly 1 argument".to_string()));
        }
        
        Ok(Value::String(args[0].type_name().to_string()))
    }
    
    fn native_is_null(args: &[Value]) -> Result<Value, VmError> {
        if args.len() != 1 {
            return Err(VmError::Runtime("isNull requires exactly 1 argument".to_string()));
        }
        
        Ok(Value::Bool(matches!(args[0], Value::Null)))
    }
    
    fn native_is_number(args: &[Value]) -> Result<Value, VmError> {
        if args.len() != 1 {
            return Err(VmError::Runtime("isNumber requires exactly 1 argument".to_string()));
        }
        
        Ok(Value::Bool(matches!(args[0], Value::Int(_) | Value::Float(_))))
    }
    
    fn native_is_string(args: &[Value]) -> Result<Value, VmError> {
        if args.len() != 1 {
            return Err(VmError::Runtime("isString requires exactly 1 argument".to_string()));
        }
        
        Ok(Value::Bool(matches!(args[0], Value::String(_))))
    }
    
    fn native_is_bool(args: &[Value]) -> Result<Value, VmError> {
        if args.len() != 1 {
            return Err(VmError::Runtime("isBool requires exactly 1 argument".to_string()));
        }
        
        Ok(Value::Bool(matches!(args[0], Value::Bool(_))))
    }
    
    fn native_is_object(args: &[Value]) -> Result<Value, VmError> {
        if args.len() != 1 {
            return Err(VmError::Runtime("isObject requires exactly 1 argument".to_string()));
        }
        
        Ok(Value::Bool(matches!(args[0], Value::Object(_))))
    }
    
    fn native_is_array(args: &[Value]) -> Result<Value, VmError> {
        if args.len() != 1 {
            return Err(VmError::Runtime("isArray requires exactly 1 argument".to_string()));
        }
        
        Ok(Value::Bool(matches!(args[0], Value::Array(_))))
    }
    
    /// Utility functions
    fn native_random(_args: &[Value]) -> Result<Value, VmError> {
        use rand::Rng;
        let mut rng = rand::thread_rng();
        Ok(Value::Float(rng.gen()))
    }
    
    fn native_time(_args: &[Value]) -> Result<Value, VmError> {
        use std::time::{SystemTime, UNIX_EPOCH};
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_err(|e| VmError::Runtime(e.to_string()))?;
        Ok(Value::Int(now.as_secs() as i64))
    }
    
    fn native_sleep(args: &[Value]) -> Result<Value, VmError> {
        if args.len() != 1 {
            return Err(VmError::Runtime("sleep requires exactly 1 argument".to_string()));
        }
        
        let seconds = match &args[0] {
            Value::Int(s) => *s as u64,
            Value::Float(s) => *s as u64,
            _ => return Err(VmError::TypeError("Invalid operand for sleep".to_string())),
        };
        
        std::thread::sleep(std::time::Duration::from_secs(seconds));
        Ok(Value::Null)
    }
} 