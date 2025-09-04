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

	// Build candidate paths
	dir := filepath.Join(append([]string{l.Root}, modulePath...)...)
	candidates := []string{
		filepath.Join(dir, "Main.co"),
	}
	if len(modulePath) > 0 {
		candidates = append(candidates, filepath.Join(dir, fmt.Sprintf("%s.co", modulePath[len(modulePath)-1])))
		candidates = append(candidates, filepath.Join(append([]string{l.Root}, append(modulePath[:len(modulePath)-1], modulePath[len(modulePath)-1]+".co")...)...))
	}

	var data []byte
	var entry string
	var readErr error
	for _, path := range candidates {
		if b, err := os.ReadFile(path); err == nil {
			data = b
			entry = path
			readErr = nil
			break
		} else {
			readErr = err
		}
	}
	if data == nil {
		return nil, fmt.Errorf("load module %s: %v (tried: %s)", strings.Join(modulePath, "."), readErr, strings.Join(candidates, ", "))
	}
	lx := lexer.New(string(data), entry)
	p := parser.New(lx)
	file, _ := p.ParseFile()
	l.cache[k] = file
	return file, nil
}
