use clap::Parser;
use tracing::{info, error};
use tracing_subscriber;

mod bytecode;
mod vm;
mod memory;
mod runtime;
mod error;

use vm::LoomVM;
use error::VmError;

#[derive(Parser)]
#[command(name = "loom-vm")]
#[command(about = "Virtual Machine for the Loom Programming Language")]
struct Cli {
    /// Input .rl file to execute
    #[arg(value_name = "FILE")]
    file: String,
    
    /// Enable debug output
    #[arg(short, long)]
    debug: bool,
    
    /// Enable verbose logging
    #[arg(short, long)]
    verbose: bool,
}

fn main() -> Result<(), VmError> {
    let cli = Cli::parse();
    
    // Initialize logging
    let level = if cli.verbose {
        tracing::Level::DEBUG
    } else {
        tracing::Level::INFO
    };
    
    tracing_subscriber::fmt()
        .with_max_level(level)
        .init();
    
    info!("LoomVM starting...");
    
    // Load and execute the .rl file
    let mut vm = LoomVM::new();
    let result = vm.execute_file(&cli.file);
    
    match result {
        Ok(_) => {
            info!("Execution completed successfully");
            Ok(())
        }
        Err(e) => {
            error!("Execution failed: {}", e);
            Err(e)
        }
    }
}
