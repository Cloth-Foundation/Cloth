package arc

import "sync"

type ARCInner struct {
	mu        sync.Mutex
	strong    int64
	weak      int64
	value     any
	finalizer func(any)
}

type StrongPtr struct{ inner *ARCInner }

type WeakPtr struct{ inner *ARCInner }

func NewStrong(v any) StrongPtr {
	in := &ARCInner{strong: 1, weak: 0, value: v}
	return StrongPtr{inner: in}
}

func (p StrongPtr) Clone() StrongPtr {
	if p.inner == nil {
		return StrongPtr{}
	}
	p.inner.mu.Lock()
	p.inner.strong++
	p.inner.mu.Unlock()
	return p
}

func (p *StrongPtr) Release() {
	if p == nil || p.inner == nil {
		return
	}
	in := p.inner
	p.inner = nil
	decrStrong(in)
}

func decrStrong(in *ARCInner) {
	in.mu.Lock()
	in.strong--
	zeroStrong := in.strong == 0
	val := in.value
	fin := in.finalizer
	if zeroStrong {
		in.value = nil
	}
	weak := in.weak
	in.mu.Unlock()
	if zeroStrong {
		if fin != nil {
			fin(val)
		}
		if weak == 0 {
			// allow GC
		}
	}
}

func (p StrongPtr) Downgrade() WeakPtr {
	if p.inner == nil {
		return WeakPtr{}
	}
	p.inner.mu.Lock()
	p.inner.weak++
	p.inner.mu.Unlock()
	return WeakPtr{inner: p.inner}
}

func (p WeakPtr) Upgrade() (StrongPtr, bool) {
	if p.inner == nil {
		return StrongPtr{}, false
	}
	p.inner.mu.Lock()
	if p.inner.strong == 0 {
		p.inner.mu.Unlock()
		return StrongPtr{}, false
	}
	p.inner.strong++
	p.inner.mu.Unlock()
	return StrongPtr{inner: p.inner}, true
}

func (p *WeakPtr) Release() {
	if p == nil || p.inner == nil {
		return
	}
	in := p.inner
	p.inner = nil
	in.mu.Lock()
	in.weak--
	zero := in.weak == 0 && in.strong == 0
	in.mu.Unlock()
	if zero {
		// allow GC
	}
}

func (p StrongPtr) Get() any {
	if p.inner == nil {
		return nil
	}
	p.inner.mu.Lock()
	v := p.inner.value
	p.inner.mu.Unlock()
	return v
}

func (p StrongPtr) SetFinalizer(fn func(any)) {
	if p.inner == nil {
		return
	}
	p.inner.mu.Lock()
	p.inner.finalizer = fn
	p.inner.mu.Unlock()
}

func Retain(ptr StrongPtr) StrongPtr { return ptr.Clone() }
func Release(ptr *StrongPtr)         { ptr.Release() }
func NewObject(v any) StrongPtr      { return NewStrong(v) }

func TryGet(x any) (any, bool) {
	if sp, ok := x.(StrongPtr); ok {
		return sp.Get(), true
	}
	return x, false
}
