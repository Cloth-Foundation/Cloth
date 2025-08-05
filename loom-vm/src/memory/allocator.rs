use std::collections::HashMap;

/// Memory allocation strategy
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum AllocationStrategy {
    /// Stack allocation (for small, short-lived objects)
    Stack,
    
    /// Pool allocation (for medium-sized objects)
    Pool,
    
    /// Heap allocation (for large objects)
    Heap,
    
    /// Arena allocation (for objects with similar lifetimes)
    Arena,
}

/// Memory pool for efficient allocation
pub struct MemoryPool {
    /// Pool size in bytes
    size: usize,
    
    /// Available blocks
    free_blocks: Vec<usize>,
    
    /// Memory buffer
    buffer: Vec<u8>,
    
    /// Block size
    block_size: usize,
    
    /// Number of blocks
    num_blocks: usize,
}

impl MemoryPool {
    pub fn new(size: usize, block_size: usize) -> Self {
        let num_blocks = size / block_size;
        let mut free_blocks: Vec<usize> = (0..num_blocks).collect();
        free_blocks.reverse(); // Pop from end for better performance
        
        Self {
            size,
            free_blocks,
            buffer: vec![0; size],
            block_size,
            num_blocks,
        }
    }
    
    /// Allocate a block from the pool
    pub fn allocate(&mut self) -> Option<*mut u8> {
        self.free_blocks.pop().map(|block_index| {
            let offset = block_index * self.block_size;
            &mut self.buffer[offset] as *mut u8
        })
    }
    
    /// Free a block back to the pool
    pub fn free(&mut self, ptr: *mut u8) {
        let buffer_start = self.buffer.as_mut_ptr();
        let offset = unsafe { ptr.offset_from(buffer_start) } as usize;
        let block_index = offset / self.block_size;
        
        if block_index < self.num_blocks {
            self.free_blocks.push(block_index);
        }
    }
    
    /// Get pool statistics
    pub fn stats(&self) -> PoolStats {
        PoolStats {
            total_blocks: self.num_blocks,
            free_blocks: self.free_blocks.len(),
            used_blocks: self.num_blocks - self.free_blocks.len(),
            block_size: self.block_size,
            total_size: self.size,
        }
    }
}

/// Statistics for a memory pool
#[derive(Debug, Clone)]
pub struct PoolStats {
    pub total_blocks: usize,
    pub free_blocks: usize,
    pub used_blocks: usize,
    pub block_size: usize,
    pub total_size: usize,
}

/// Arena allocator for objects with similar lifetimes
pub struct ArenaAllocator {
    /// Current arena
    current_arena: Vec<u8>,
    
    /// Arena size
    arena_size: usize,
    
    /// Current position in arena
    current_pos: usize,
    
    /// List of completed arenas
    completed_arenas: Vec<Vec<u8>>,
}

impl ArenaAllocator {
    pub fn new(arena_size: usize) -> Self {
        Self {
            current_arena: Vec::with_capacity(arena_size),
            arena_size,
            current_pos: 0,
            completed_arenas: Vec::new(),
        }
    }
    
    /// Allocate memory from the arena
    pub fn allocate(&mut self, size: usize) -> *mut u8 {
        // Ensure alignment
        let aligned_size = (size + 7) & !7; // 8-byte alignment
        
        if self.current_pos + aligned_size > self.current_arena.len() {
            // Need a new arena
            self.completed_arenas.push(std::mem::take(&mut self.current_arena));
            self.current_arena = vec![0; self.arena_size];
            self.current_pos = 0;
        }
        
        let ptr = &mut self.current_arena[self.current_pos] as *mut u8;
        self.current_pos += aligned_size;
        ptr
    }
    
    /// Reset the arena (free all allocations)
    pub fn reset(&mut self) {
        self.current_arena.clear();
        self.completed_arenas.clear();
        self.current_pos = 0;
    }
}

/// Adaptive allocator that switches strategies based on allocation patterns
pub struct AdaptiveAllocator {
    /// Small object pool (0-64 bytes)
    small_pool: MemoryPool,
    
    /// Medium object pool (64-1024 bytes)
    medium_pool: MemoryPool,
    
    /// Arena allocator for temporary objects
    arena: ArenaAllocator,
    
    /// Allocation statistics
    stats: AllocationStats,
    
    /// Strategy recommendations
    strategy_cache: HashMap<usize, AllocationStrategy>,
}

/// Statistics about allocations
#[derive(Debug, Clone)]
pub struct AllocationStats {
    /// Total allocations
    pub total_allocations: u64,
    
    /// Total bytes allocated
    pub total_bytes: u64,
    
    /// Allocations by strategy
    pub strategy_counts: HashMap<AllocationStrategy, u64>,
    
    /// Average allocation size
    pub avg_size: f64,
    
    /// Peak memory usage
    pub peak_usage: usize,
}

impl AdaptiveAllocator {
    pub fn new() -> Self {
        Self {
            small_pool: MemoryPool::new(1024 * 1024, 64), // 1MB, 64-byte blocks
            medium_pool: MemoryPool::new(4 * 1024 * 1024, 1024), // 4MB, 1KB blocks
            arena: ArenaAllocator::new(1024 * 1024), // 1MB arena
            stats: AllocationStats {
                total_allocations: 0,
                total_bytes: 0,
                strategy_counts: HashMap::new(),
                avg_size: 0.0,
                peak_usage: 0,
            },
            strategy_cache: HashMap::new(),
        }
    }
    
    /// Allocate memory using adaptive strategy
    pub fn allocate(&mut self, size: usize) -> *mut u8 {
        let strategy = self.recommend_strategy(size);
        let ptr = self.allocate_with_strategy(size, strategy);
        
        // Update statistics
        self.stats.total_allocations += 1;
        self.stats.total_bytes += size as u64;
        *self.stats.strategy_counts.entry(strategy).or_insert(0) += 1;
        self.stats.avg_size = self.stats.total_bytes as f64 / self.stats.total_allocations as f64;
        
        ptr
    }
    
    /// Allocate using a specific strategy
    pub fn allocate_with_strategy(&mut self, size: usize, strategy: AllocationStrategy) -> *mut u8 {
        match strategy {
            AllocationStrategy::Stack => {
                // For now, use arena for stack-like allocations
                self.arena.allocate(size)
            }
            AllocationStrategy::Pool => {
                if size <= 64 {
                    self.small_pool.allocate().unwrap_or_else(|| {
                        // Fallback to heap if pool is full
                        self.heap_allocate(size)
                    })
                } else {
                    self.medium_pool.allocate().unwrap_or_else(|| {
                        self.heap_allocate(size)
                    })
                }
            }
            AllocationStrategy::Heap => {
                self.heap_allocate(size)
            }
            AllocationStrategy::Arena => {
                self.arena.allocate(size)
            }
        }
    }
    
    /// Recommend allocation strategy based on size and usage patterns
    pub fn recommend_strategy(&mut self, size: usize) -> AllocationStrategy {
        // Check cache first
        if let Some(&strategy) = self.strategy_cache.get(&size) {
            return strategy;
        }
        
        let strategy = match size {
            0..=64 => {
                // Small objects: use pool for efficiency
                AllocationStrategy::Pool
            }
            65..=1024 => {
                // Medium objects: use pool or arena
                if self.stats.total_allocations > 1000 {
                    AllocationStrategy::Arena
                } else {
                    AllocationStrategy::Pool
                }
            }
            _ => {
                // Large objects: use heap
                AllocationStrategy::Heap
            }
        };
        
        // Cache the recommendation
        self.strategy_cache.insert(size, strategy);
        strategy
    }
    
    /// Heap allocation (fallback)
    fn heap_allocate(&self, size: usize) -> *mut u8 {
        let layout = std::alloc::Layout::from_size_align(size, 8).unwrap();
        unsafe { std::alloc::alloc(layout) }
    }
    
    /// Free memory
    pub fn free(&mut self, ptr: *mut u8, size: usize) {
        // For now, we'll use a simple approach
        // In a real implementation, you'd track which pool/arena the pointer came from
        
        // Try to free from pools first
        if size <= 64 {
            self.small_pool.free(ptr);
        } else if size <= 1024 {
            self.medium_pool.free(ptr);
        } else {
            // Heap allocation - free using standard allocator
            let layout = std::alloc::Layout::from_size_align(size, 8).unwrap();
            unsafe { std::alloc::dealloc(ptr, layout) };
        }
    }
    
    /// Get allocation statistics
    pub fn stats(&self) -> &AllocationStats {
        &self.stats
    }
    
    /// Get pool statistics
    pub fn pool_stats(&self) -> (PoolStats, PoolStats) {
        (self.small_pool.stats(), self.medium_pool.stats())
    }
    
    /// Reset arena (free all arena allocations)
    pub fn reset_arena(&mut self) {
        self.arena.reset();
    }
    
    /// Adapt allocation strategy based on usage patterns
    pub fn adapt(&mut self) {
        // Clear strategy cache to force re-evaluation
        self.strategy_cache.clear();
        
        // Adjust pool sizes based on usage
        let (small_stats, medium_stats) = self.pool_stats();
        
        if small_stats.used_blocks > small_stats.total_blocks * 3 / 4 {
            // Small pool is heavily used - consider expanding
            tracing::warn!("Small pool usage high: {}/{}", small_stats.used_blocks, small_stats.total_blocks);
        }
        
        if medium_stats.used_blocks > medium_stats.total_blocks * 3 / 4 {
            // Medium pool is heavily used - consider expanding
            tracing::warn!("Medium pool usage high: {}/{}", medium_stats.used_blocks, medium_stats.total_blocks);
        }
    }
} 