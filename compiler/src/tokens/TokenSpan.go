package tokens

import (
	"fmt"
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
	dirBase := filepath.Base(filepath.Dir(s.File))
	scope := name
	if dirBase != "." && dirBase != "" {
		scope = dirBase + "::" + name
	}
	return fmt.Sprintf("{\n\t\tscope: %s\n\t\tstart: [%d:%d]\n\t\tend: [%d:%d]\n\t}", scope, s.StartLine, s.StartColumn, s.EndLine, s.EndColumn)
}
