package gc

// Traceable marks heap objects that can be traced by the GC.
type Traceable interface {
	Trace(gc *GC)
}

type RootSet interface {
	Iterate(func(obj Traceable))
}

// GC is a placeholder for a future garbage collector implementation.
type GC struct {
	roots []RootSet
}

func New() *GC { return &GC{} }

func (g *GC) AddRoots(rs RootSet) { g.roots = append(g.roots, rs) }

// Collect performs a collection cycle (no-op for now).
func (g *GC) Collect() {
	// mark phase (stub)
	for _, rs := range g.roots {
		rs.Iterate(func(obj Traceable) { obj.Trace(g) })
	}
	// sweep phase (stub)
}
