package semantic

import (
	"compiler/src/ast"
	"compiler/src/lexer"
	"compiler/src/parser"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// FSLoader loads modules from the filesystem based on a root directory.
// A module path like ["tests","first"] resolves to <root>/tests/first/Main.lm by convention.
type FSLoader struct {
	Root     string
	cache    map[string]*ast.File
	visiting map[string]bool
}

func (l *FSLoader) key(parts []string) string { return strings.Join(parts, ".") }

func (l *FSLoader) Load(modulePath []string) (*ast.File, error) {
	if l.cache == nil {
		l.cache = map[string]*ast.File{}
	}
	if l.visiting == nil {
		l.visiting = map[string]bool{}
	}
	k := l.key(modulePath)
	if f, ok := l.cache[k]; ok {
		return f, nil
	}
	if l.visiting[k] {
		return nil, fmt.Errorf("import cycle detected at %s", k)
	}
	l.visiting[k] = true
	defer delete(l.visiting, k)

	// Convention: module path maps to directory; load 'Main.lm'
	dir := filepath.Join(append([]string{l.Root}, modulePath...)...)
	entry := filepath.Join(dir, "Main.lm")
	data, err := os.ReadFile(entry)
	if err != nil {
		// fallback: try a file named by last segment + .lm
		if len(modulePath) > 0 {
			alt := filepath.Join(dir, fmt.Sprintf("%s.lm", modulePath[len(modulePath)-1]))
			if b, e2 := os.ReadFile(alt); e2 == nil {
				data = b
				entry = alt
			} else {
				return nil, fmt.Errorf("load module %s: %w", strings.Join(modulePath, "."), err)
			}
		} else {
			return nil, fmt.Errorf("load module %v: %w", modulePath, err)
		}
	}
	lx := lexer.New(string(data), entry)
	p := parser.New(lx)
	file, _ := p.ParseFile()
	l.cache[k] = file
	return file, nil
}
