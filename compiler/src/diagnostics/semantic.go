package diagnostics

import (
	"compiler/src/semantic"
	"fmt"
)

func RenderBindDiagnostics(diags []semantic.Diagnostic) {
	if len(diags) == 0 {
		return
	}
	for _, d := range diags {
		if d.Span.File == "" {
			fmt.Println("error:", d.Message)
			continue
		}
		fmt.Printf("%s:%d:%d: error: %s\n", d.Span.File, d.Span.StartLine, d.Span.StartColumn, d.Message)
		if d.Hint != "" {
			fmt.Println("help:", d.Hint)
		}
	}
}

func RenderTypeResolveDiagnostics(diags []semantic.TypeDiag) {
	if len(diags) == 0 {
		return
	}
	fmt.Println("Type resolution errors:")
	for _, d := range diags {
		fmt.Println(" -", d.Message)
	}
}

func RenderTypeCheckDiagnostics(diags []semantic.CheckDiag) {
	if len(diags) == 0 {
		return
	}
	fmt.Println("Type checking errors:")
	for _, d := range diags {
		fmt.Println(" -", d.Message)
	}
}
