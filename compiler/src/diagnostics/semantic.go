package diagnostics

import (
	"compiler/src/semantic"
	"compiler/src/tokens"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

func RenderBindDiagnostics(diags []semantic.Diagnostic) {
	if len(diags) == 0 {
		return
	}
	for _, d := range diags {
		printSemantic("error", d.Message, d.Span, d.Hint)
	}
}

func RenderTypeResolveDiagnostics(diags []semantic.TypeDiag) {
	if len(diags) == 0 {
		return
	}
	for _, d := range diags {
		printSemantic("error", d.Message, d.Span, d.Hint)
	}
}

func RenderTypeCheckDiagnostics(diags []semantic.CheckDiag) {
	if len(diags) == 0 {
		return
	}
	for _, d := range diags {
		printSemantic("error", d.Message, d.Span, d.Hint)
	}
}

func printSemantic(level string, message string, sp tokens.TokenSpan, hint string) {
	// If no file info, print simple message
	if sp.File == "" {
		fmt.Printf("%s: %s\n", level, message)
		if strings.TrimSpace(hint) != "" {
			fmt.Println("help:", hint)
		}
		return
	}
	// Load file content to render caret view
	data, err := os.ReadFile(sp.File)
	if err != nil {
		// try relative to CWD
		if b, e2 := os.ReadFile(filepath.Clean(sp.File)); e2 == nil {
			data = b
		} else {
			fmt.Printf("%s: %s [%s:%d:%d]\n", level, message, sp.File, sp.StartLine, sp.StartColumn)
			if strings.TrimSpace(hint) != "" {
				fmt.Println("help:", hint)
			}
			return
		}
	}
	lines := splitLines(string(data))
	// heading
	fmt.Printf("%s%sx%s %s\n", vRed, vBold, vReset, message)
	link := fmt.Sprintf("[%s:%d:%d]", sp.File, sp.StartLine, sp.StartColumn)
	fmt.Printf("  %s%s%s%s\n", vBlue, vUnder, link, vReset)
	printSpanStyled(lines, sp, message)
	if strings.TrimSpace(hint) != "" {
		fmt.Printf("\n%shelp:%s %s\n\n", vBlue, vReset, hint)
	}
}
