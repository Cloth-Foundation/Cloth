use std::sync::Arc;
use std::sync::Weak;
use std::collections::HashMap;
use parking_lot::RwLock;

/// Usage pattern for adaptive smart pointers
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum UsagePattern {
    /// Shared ownership (multiple references)
    Shared,
    
    /// Unique ownership (single reference)
    Unique,
    
    /// Temporary/borrowed reference
    Temporary,
    
    /// Weak reference (doesn't prevent cleanup)
    Weak,
}

/// Statistics about pointer usage
#[derive(Debug, Clone)]
pub struct PointerStats {
    /// Number of times accessed
    pub access_count: u64,
    
    /// Number of times modified
    pub modification_count: u64,
    
    /// Number of references
    pub reference_count: u32,
    
    /// Last access time
    pub last_access: std::time::Instant,
    
    /// Usage pattern
    pub pattern: UsagePattern,
}

/// Adaptive smart pointer that switches strategies based on usage
#[derive(Debug)]
pub enum SmartPointer<T> {
    /// Reference counted (shared ownership)
    Shared(Arc<RwLock<T>>),
    
    /// Weak reference (doesn't prevent cleanup)
    Weak(Weak<RwLock<T>>),
    
    /// Unique ownership (moved, not copied)
    Unique(Box<T>),
    
    /// Borrowed reference (lifetime limited)
    Borrowed(*const T),
}

impl<T> SmartPointer<T> {
    /// Create a new shared smart pointer
    pub fn new_shared(value: T) -> Self {
        SmartPointer::Shared(Arc::new(RwLock::new(value)))
    }
    
    /// Create a new unique smart pointer
    pub fn new_unique(value: T) -> Self {
        SmartPointer::Unique(Box::new(value))
    }
    
    /// Create a weak reference
    pub fn new_weak(shared: &Arc<RwLock<T>>) -> Self {
        SmartPointer::Weak(Arc::downgrade(shared))
    }
    
    /// Adapt the pointer based on usage pattern
    pub fn adapt(&mut self, pattern: UsagePattern) -> Result<(), String> {
        match pattern {
            UsagePattern::Unique => {
                if let SmartPointer::Shared(_) = self {
                    return Err("Cannot convert shared to unique: not implemented".to_string());
                }
            }
            UsagePattern::Shared => {
                if let SmartPointer::Unique(_) = self {
                    return Err("Cannot convert unique to shared: not implemented".to_string());
                }
            }
            UsagePattern::Weak => {
                if let SmartPointer::Shared(arc) = self {
                    *self = SmartPointer::Weak(Arc::downgrade(arc));
                }
            }
            UsagePattern::Temporary => {
                // No conversion needed
            }
        }
        Ok(())
    }
    
    /// Check if the pointer is valid
    pub fn is_valid(&self) -> bool {
        match self {
            SmartPointer::Shared(_) => true,
            SmartPointer::Weak(weak) => weak.upgrade().is_some(),
            SmartPointer::Unique(_) => true,
            SmartPointer::Borrowed(_) => true, // Assume valid
        }
    }
    
    /// Get the reference count (for shared pointers)
    pub fn ref_count(&self) -> Option<u32> {
        match self {
            SmartPointer::Shared(arc) => Some(Arc::strong_count(arc) as u32),
            SmartPointer::Weak(weak) => weak.upgrade().map(|arc| Arc::strong_count(&arc) as u32),
            _ => None,
        }
    }
    
    /// Get a reference to the value
    pub fn as_ref(&self) -> Option<&T> {
        match self {
            SmartPointer::Shared(_) => {
                // For now, return None to avoid lifetime issues
                // In a real implementation, you'd need to handle this properly
                None
            }
            SmartPointer::Weak(weak) => {
                weak.upgrade().map(|_arc| {
                    // For now, return None to avoid lifetime issues
                    None
                }).flatten()
            }
            SmartPointer::Unique(boxed) => Some(boxed),
            SmartPointer::Borrowed(ptr) => {
                // SAFETY: This is unsafe, but we assume the pointer is valid
                unsafe { Some(&**ptr) }
            }
        }
    }
    
    /// Get a mutable reference to the value
    pub fn as_mut(&mut self) -> Option<&mut T> {
        match self {
            SmartPointer::Shared(_) => {
                // For now, return None to avoid lifetime issues
                // In a real implementation, you'd need to handle this properly
                None
            }
            SmartPointer::Weak(_) => None, // Weak references can't be mutable
            SmartPointer::Unique(boxed) => Some(boxed),
            SmartPointer::Borrowed(_) => None, // Borrowed references can't be mutable
        }
    }
}

/// Registry for tracking smart pointer usage
pub struct SmartPointerRegistry {
    /// Statistics for each pointer
    stats: HashMap<u64, PointerStats>,
    
    /// Next pointer ID
    next_id: u64,
}

impl SmartPointerRegistry {
    pub fn new() -> Self {
        Self {
            stats: HashMap::new(),
            next_id: 1,
        }
    }
    
    /// Register a new pointer
    pub fn register(&mut self, pattern: UsagePattern) -> u64 {
        let id = self.next_id;
        self.next_id += 1;
        
        self.stats.insert(id, PointerStats {
            access_count: 0,
            modification_count: 0,
            reference_count: 1,
            last_access: std::time::Instant::now(),
            pattern,
        });
        
        id
    }
    
    /// Record an access
    pub fn record_access(&mut self, id: u64) {
        if let Some(stats) = self.stats.get_mut(&id) {
            stats.access_count += 1;
            stats.last_access = std::time::Instant::now();
        }
    }
    
    /// Record a modification
    pub fn record_modification(&mut self, id: u64) {
        if let Some(stats) = self.stats.get_mut(&id) {
            stats.modification_count += 1;
            stats.last_access = std::time::Instant::now();
        }
    }
    
    /// Update reference count
    pub fn update_ref_count(&mut self, id: u64, count: u32) {
        if let Some(stats) = self.stats.get_mut(&id) {
            stats.reference_count = count;
        }
    }
    
    /// Get usage pattern recommendation
    pub fn recommend_pattern(&self, id: u64) -> Option<UsagePattern> {
        if let Some(stats) = self.stats.get(&id) {
            if stats.reference_count > 1 {
                Some(UsagePattern::Shared)
            } else if stats.access_count > 100 {
                Some(UsagePattern::Unique)
            } else if stats.modification_count == 0 {
                Some(UsagePattern::Weak)
            } else {
                Some(UsagePattern::Temporary)
            }
        } else {
            None
        }
    }
    
    /// Get statistics for a pointer
    pub fn get_stats(&self, id: u64) -> Option<&PointerStats> {
        self.stats.get(&id)
    }
    
    /// Clean up unused statistics
    pub fn cleanup(&mut self) {
        let now = std::time::Instant::now();
        self.stats.retain(|_, stats| {
            now.duration_since(stats.last_access).as_secs() < 300 // 5 minutes
        });
    }
} 