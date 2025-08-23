package tokens

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// TokenSpan mirrors loom::TokenSpan
type TokenSpan struct {
	File        string
	StartLine   uint64
	StartColumn uint64
	EndLine     uint64
	EndColumn   uint64
}

func (s TokenSpan) String() string {
	base := filepath.Base(s.File)
	name := strings.TrimSuffix(base, filepath.Ext(base))
	dir := filepath.Dir(s.File)
	if dir == "." || dir == "" {
		return fmt.Sprintf("{\n\t\tscope: %s\n\t\tstart: [%d:%d]\n\t\tend: [%d:%d]\n\t}", name, s.StartLine, s.StartColumn, s.EndLine, s.EndColumn)
	}
	// Prefer path relative to current working directory when possible
	if filepath.IsAbs(dir) {
		if wd, err := os.Getwd(); err == nil {
			if rel, err := filepath.Rel(wd, dir); err == nil {
				dir = rel
			}
		}
	}
	dir = filepath.ToSlash(filepath.Clean(dir))
	dir = strings.TrimPrefix(dir, "./")
	dirDots := strings.ReplaceAll(dir, "/", ".")
	scope := dirDots + "::" + name
	return fmt.Sprintf("{\n\t\tscope: %s\n\t\tstart: [%d:%d]\n\t\tend: [%d:%d]\n\t}", scope, s.StartLine, s.StartColumn, s.EndLine, s.EndColumn)
}
