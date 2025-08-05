pub mod smart_pointer;
pub mod allocator;
pub mod profiler;

use crate::bytecode::{Value, Object, Array, ObjectId, ArrayId};
use crate::error::VmError;
use std::collections::HashMap;
use std::sync::Arc;
use parking_lot::RwLock;

/// Memory manager for the LoomVM
pub struct MemoryManager {
    /// Object heap
    objects: Arc<RwLock<HashMap<ObjectId, Object>>>,
    
    /// Array heap
    arrays: Arc<RwLock<HashMap<ArrayId, Array>>>,
    
    /// Next object ID
    next_object_id: ObjectId,
    
    /// Next array ID
    next_array_id: ArrayId,
    
    /// Memory profiler
    profiler: profiler::MemoryProfiler,
    
    /// Adaptive allocator
    allocator: allocator::AdaptiveAllocator,
}

impl MemoryManager {
    pub fn new() -> Self {
        Self {
            objects: Arc::new(RwLock::new(HashMap::new())),
            arrays: Arc::new(RwLock::new(HashMap::new())),
            next_object_id: 1,
            next_array_id: 1,
            profiler: profiler::MemoryProfiler::new(),
            allocator: allocator::AdaptiveAllocator::new(),
        }
    }
    
    /// Allocate a new object
    pub fn allocate_object(&mut self, class_name: String) -> ObjectId {
        let id = self.next_object_id;
        self.next_object_id += 1;
        
        let object = Object {
            id,
            class_name,
            fields: HashMap::new(),
            ref_count: 1,
        };
        
        self.objects.write().insert(id, object);
        self.profiler.record_allocation(id, "object");
        
        id
    }
    
    /// Allocate a new array
    pub fn allocate_array(&mut self, element_type: String, size: usize) -> ArrayId {
        let id = self.next_array_id;
        self.next_array_id += 1;
        
        let array = Array {
            id,
            element_type,
            elements: vec![Value::Null; size],
            ref_count: 1,
        };
        
        self.arrays.write().insert(id, array);
        self.profiler.record_allocation(id, "array");
        
        id
    }
    
    /// Get an object by ID
    pub fn get_object(&self, id: ObjectId) -> Option<Object> {
        self.objects.read().get(&id).cloned()
    }
    
    /// Get an array by ID
    pub fn get_array(&self, id: ArrayId) -> Option<Array> {
        self.arrays.read().get(&id).cloned()
    }
    
    /// Update an object
    pub fn update_object(&self, object: Object) -> Result<(), VmError> {
        let mut objects = self.objects.write();
        if objects.contains_key(&object.id) {
            objects.insert(object.id, object);
            Ok(())
        } else {
            Err(VmError::Runtime("Object not found".to_string()))
        }
    }
    
    /// Update an array
    pub fn update_array(&self, array: Array) -> Result<(), VmError> {
        let mut arrays = self.arrays.write();
        if arrays.contains_key(&array.id) {
            arrays.insert(array.id, array);
            Ok(())
        } else {
            Err(VmError::Runtime("Array not found".to_string()))
        }
    }
    
    /// Increment reference count
    pub fn increment_ref(&self, value: &Value) -> Result<(), VmError> {
        match value {
            Value::Object(id) => {
                let mut objects = self.objects.write();
                if let Some(obj) = objects.get_mut(id) {
                    obj.ref_count += 1;
                }
            }
            Value::Array(id) => {
                let mut arrays = self.arrays.write();
                if let Some(arr) = arrays.get_mut(id) {
                    arr.ref_count += 1;
                }
            }
            _ => {}
        }
        Ok(())
    }
    
    /// Decrement reference count and free if zero
    pub fn decrement_ref(&mut self, value: &Value) -> Result<(), VmError> {
        match value {
            Value::Object(id) => {
                let mut objects = self.objects.write();
                if let Some(obj) = objects.get_mut(id) {
                    obj.ref_count -= 1;
                    if obj.ref_count == 0 {
                        objects.remove(id);
                        self.profiler.record_deallocation(*id, "object");
                    }
                }
            }
            Value::Array(id) => {
                let mut arrays = self.arrays.write();
                if let Some(arr) = arrays.get_mut(id) {
                    arr.ref_count -= 1;
                    if arr.ref_count == 0 {
                        arrays.remove(id);
                        self.profiler.record_deallocation(*id, "array");
                    }
                }
            }
            _ => {}
        }
        Ok(())
    }
    
    /// Get memory statistics
    pub fn get_stats(&self) -> profiler::MemoryStats {
        self.profiler.get_stats()
    }
    
    /// Run garbage collection
    pub fn gc(&mut self) -> Result<(), VmError> {
        // Simple reference counting GC
        // In the future, this could be more sophisticated
        self.profiler.record_gc();
        Ok(())
    }
} 