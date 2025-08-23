package gc

// Traceable marks heap objects that can be traced by the GC.
type Traceable interface {
	Trace(gc *GC)
}

// GC is a placeholder for a future garbage collector implementation.
type GC struct{}

func New() *GC { return &GC{} }

// Collect performs a collection cycle (no-op for now).
func (g *GC) Collect() {}
