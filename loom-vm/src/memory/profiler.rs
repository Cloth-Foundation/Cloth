use std::collections::HashMap;
use std::time::{Instant, Duration};

/// Memory allocation event
#[derive(Debug, Clone)]
pub struct AllocationEvent {
    /// Object ID
    pub id: u64,
    
    /// Allocation size in bytes
    pub size: usize,
    
    /// Allocation type
    pub allocation_type: String,
    
    /// Timestamp
    pub timestamp: Instant,
    
    /// Stack trace (if available)
    pub stack_trace: Option<Vec<String>>,
}

/// Memory deallocation event
#[derive(Debug, Clone)]
pub struct DeallocationEvent {
    /// Object ID
    pub id: u64,
    
    /// Deallocation type
    pub deallocation_type: String,
    
    /// Timestamp
    pub timestamp: Instant,
    
    /// Lifetime duration
    pub lifetime: Duration,
}

/// Garbage collection event
#[derive(Debug, Clone)]
pub struct GCEvent {
    /// Number of objects collected
    pub objects_collected: u64,
    
    /// Bytes freed
    pub bytes_freed: u64,
    
    /// GC duration
    pub duration: Duration,
    
    /// Timestamp
    pub timestamp: Instant,
}

/// Memory usage statistics
#[derive(Debug, Clone)]
pub struct MemoryStats {
    /// Total allocations
    pub total_allocations: u64,
    
    /// Total deallocations
    pub total_deallocations: u64,
    
    /// Current memory usage in bytes
    pub current_usage: usize,
    
    /// Peak memory usage in bytes
    pub peak_usage: usize,
    
    /// Total bytes allocated
    pub total_bytes_allocated: u64,
    
    /// Total bytes freed
    pub total_bytes_freed: u64,
    
    /// Average allocation size
    pub avg_allocation_size: f64,
    
    /// Average object lifetime
    pub avg_lifetime: Duration,
    
    /// Number of garbage collections
    pub gc_count: u64,
    
    /// Total GC time
    pub total_gc_time: Duration,
}

/// Memory profiler for tracking allocation patterns
pub struct MemoryProfiler {
    /// Allocation events
    allocations: Vec<AllocationEvent>,
    
    /// Deallocation events
    deallocations: Vec<DeallocationEvent>,
    
    /// GC events
    gc_events: Vec<GCEvent>,
    
    /// Current memory usage
    current_usage: usize,
    
    /// Peak memory usage
    peak_usage: usize,
    
    /// Total bytes allocated
    total_bytes_allocated: u64,
    
    /// Total bytes freed
    total_bytes_freed: u64,
    
    /// Number of GC events
    gc_count: u64,
    
    /// Total GC time
    total_gc_time: Duration,
    
    /// Total allocations
    total_allocations: u64,
    
    /// Total deallocations
    total_deallocations: u64,
    
    /// Allocation type statistics
    allocation_types: HashMap<String, u64>,
    
    /// Size distribution
    size_distribution: HashMap<usize, u64>,
    
    /// Enable profiling
    enabled: bool,
}

impl MemoryProfiler {
    pub fn new() -> Self {
        Self {
            allocations: Vec::new(),
            deallocations: Vec::new(),
            gc_events: Vec::new(),
            current_usage: 0,
            peak_usage: 0,
            total_bytes_allocated: 0,
            total_bytes_freed: 0,
            gc_count: 0,
            total_gc_time: Duration::ZERO,
            total_allocations: 0,
            total_deallocations: 0,
            allocation_types: HashMap::new(),
            size_distribution: HashMap::new(),
            enabled: true,
        }
    }
    
    /// Record an allocation event
    pub fn record_allocation(&mut self, id: u64, allocation_type: &str) {
        if !self.enabled {
            return;
        }
        
        let event = AllocationEvent {
            id,
            size: 0, // Will be set by caller
            allocation_type: allocation_type.to_string(),
            timestamp: Instant::now(),
            stack_trace: None, // Could be implemented with backtrace crate
        };
        
        self.allocations.push(event);
        *self.allocation_types.entry(allocation_type.to_string()).or_insert(0) += 1;
        self.total_allocations += 1;
    }
    
    /// Record allocation with size
    pub fn record_allocation_with_size(&mut self, id: u64, allocation_type: &str, size: usize) {
        if !self.enabled {
            return;
        }
        
        self.current_usage += size;
        self.total_bytes_allocated += size as u64;
        
        if self.current_usage > self.peak_usage {
            self.peak_usage = self.current_usage;
        }
        
        // Record size distribution
        let size_bucket = self.get_size_bucket(size);
        *self.size_distribution.entry(size_bucket).or_insert(0) += 1;
        
        let event = AllocationEvent {
            id,
            size,
            allocation_type: allocation_type.to_string(),
            timestamp: Instant::now(),
            stack_trace: None,
        };
        
        self.allocations.push(event);
        *self.allocation_types.entry(allocation_type.to_string()).or_insert(0) += 1;
        self.total_allocations += 1;
    }
    
    /// Record a deallocation event
    pub fn record_deallocation(&mut self, id: u64, deallocation_type: &str) {
        if !self.enabled {
            return;
        }
        
        // Find the corresponding allocation
        let allocation_time = self.allocations
            .iter()
            .find(|event| event.id == id)
            .map(|event| event.timestamp);
        
        let lifetime = allocation_time
            .map(|alloc_time| Instant::now().duration_since(alloc_time))
            .unwrap_or(Duration::ZERO);
        
        let event = DeallocationEvent {
            id,
            deallocation_type: deallocation_type.to_string(),
            timestamp: Instant::now(),
            lifetime,
        };
        
        self.deallocations.push(event);
        self.total_deallocations += 1;
    }
    
    /// Record deallocation with size
    pub fn record_deallocation_with_size(&mut self, id: u64, deallocation_type: &str, size: usize) {
        if !self.enabled {
            return;
        }
        
        self.current_usage = self.current_usage.saturating_sub(size);
        self.total_bytes_freed += size as u64;
        
        self.record_deallocation(id, deallocation_type);
    }
    
    /// Record a garbage collection event
    pub fn record_gc(&mut self) {
        if !self.enabled {
            return;
        }
        
        let start_time = Instant::now();
        
        // Simulate GC (in real implementation, this would be called after actual GC)
        let objects_collected = self.allocations.len() as u64 - self.deallocations.len() as u64;
        let bytes_freed = self.current_usage;
        
        let duration = Instant::now().duration_since(start_time);
        
        let event = GCEvent {
            objects_collected,
            bytes_freed: bytes_freed as u64,
            duration,
            timestamp: Instant::now(),
        };
        
        self.gc_events.push(event);
        self.gc_count += 1;
        self.total_gc_time += duration;
    }
    
    /// Get memory statistics
    pub fn get_stats(&self) -> MemoryStats {
        let avg_allocation_size = if self.total_allocations > 0 {
            self.total_bytes_allocated as f64 / self.total_allocations as f64
        } else {
            0.0
        };
        
        let avg_lifetime = if self.total_deallocations > 0 {
            let total_lifetime: Duration = self.deallocations
                .iter()
                .map(|event| event.lifetime)
                .sum();
            total_lifetime / self.total_deallocations as u32
        } else {
            Duration::ZERO
        };
        
        MemoryStats {
            total_allocations: self.total_allocations,
            total_deallocations: self.total_deallocations,
            current_usage: self.current_usage,
            peak_usage: self.peak_usage,
            total_bytes_allocated: self.total_bytes_allocated,
            total_bytes_freed: self.total_bytes_freed,
            avg_allocation_size,
            avg_lifetime,
            gc_count: self.gc_count,
            total_gc_time: self.total_gc_time,
        }
    }
    
    /// Get allocation type distribution
    pub fn get_allocation_types(&self) -> &HashMap<String, u64> {
        &self.allocation_types
    }
    
    /// Get size distribution
    pub fn get_size_distribution(&self) -> &HashMap<usize, u64> {
        &self.size_distribution
    }
    
    /// Enable or disable profiling
    pub fn set_enabled(&mut self, enabled: bool) {
        self.enabled = enabled;
    }
    
    /// Clear all profiling data
    pub fn clear(&mut self) {
        self.allocations.clear();
        self.deallocations.clear();
        self.gc_events.clear();
        self.allocation_types.clear();
        self.size_distribution.clear();
        self.current_usage = 0;
        self.peak_usage = 0;
        self.total_bytes_allocated = 0;
        self.total_bytes_freed = 0;
        self.gc_count = 0;
        self.total_gc_time = Duration::ZERO;
        self.total_allocations = 0;
        self.total_deallocations = 0;
    }
    
    /// Get size bucket for distribution tracking
    fn get_size_bucket(&self, size: usize) -> usize {
        match size {
            0..=64 => 64,
            65..=256 => 256,
            257..=1024 => 1024,
            1025..=4096 => 4096,
            4097..=16384 => 16384,
            _ => 65536, // 64KB+
        }
    }
    
    /// Generate a memory usage report
    pub fn generate_report(&self) -> String {
        let stats = self.get_stats();
        
        format!(
            "Memory Profiler Report\n\
             =====================\n\
             Total Allocations: {}\n\
             Total Deallocations: {}\n\
             Current Usage: {} bytes\n\
             Peak Usage: {} bytes\n\
             Total Bytes Allocated: {}\n\
             Total Bytes Freed: {}\n\
             Average Allocation Size: {:.2} bytes\n\
             Average Lifetime: {:?}\n\
             GC Count: {}\n\
             Total GC Time: {:?}\n\
             \n\
             Allocation Types:\n{}\n\
             \n\
             Size Distribution:\n{}",
            stats.total_allocations,
            stats.total_deallocations,
            stats.current_usage,
            stats.peak_usage,
            stats.total_bytes_allocated,
            stats.total_bytes_freed,
            stats.avg_allocation_size,
            stats.avg_lifetime,
            stats.gc_count,
            stats.total_gc_time,
            self.format_allocation_types(),
            self.format_size_distribution(),
        )
    }
    
    /// Format allocation types for report
    fn format_allocation_types(&self) -> String {
        let mut result = String::new();
        for (allocation_type, count) in &self.allocation_types {
            result.push_str(&format!("  {}: {}\n", allocation_type, count));
        }
        result
    }
    
    /// Format size distribution for report
    fn format_size_distribution(&self) -> String {
        let mut result = String::new();
        let mut sizes: Vec<_> = self.size_distribution.iter().collect();
        sizes.sort_by_key(|(size, _)| **size);
        
        for (size, count) in sizes {
            result.push_str(&format!("  {} bytes: {}\n", size, count));
        }
        result
    }
} 